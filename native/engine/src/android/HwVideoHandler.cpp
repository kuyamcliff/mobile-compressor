// Hardware pipeline:
//
//   FFmpeg demux -> (Annex B conversion) -> MediaCodec decoder
//     -> Surface of an AImageReader (AIMAGE_FORMAT_PRIVATE, GPU-sampled)
//     -> AHardwareBuffer -> EGLImage -> GL (rotate / crop / scale / flip / gray)
//     -> MediaCodec encoder input Surface -> encoded packets -> FFmpeg muxer
//
// No frame is copied through the CPU. Timing: decoder output timestamps are
// microseconds of the source stream; the renderer stamps each submitted frame
// with eglPresentationTimeANDROID so the encoder output keeps them. Constant
// frame-rate conversion uses a hold-one-frame scheme equivalent to FFmpeg's
// fps filter with round=near: output slot t_k shows the latest decoded frame
// with pts <= t_k + interval/2.
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>

#include <chrono>
#include <condition_variable>
#include <mutex>

#include "android/GlRenderer.h"
#include "android/MediaCodecUtil.h"
#include "core/Log.h"
#include "pipeline/DtsGenerator.h"
#include "pipeline/Transcoder.h"
#include "probe/Probe.h"

namespace vc {

namespace {
constexpr const char* TAG = "HwVideo";
constexpr int64_t kTimeoutUs = 10000;
constexpr int kImageWaitMs = 2500;
constexpr int kEosWaitMs = 30000;

struct ImageReaderDeleter {
  void operator()(AImageReader* r) const {
    if (r) AImageReader_delete(r);
  }
};
struct ImageDeleter {
  void operator()(AImage* i) const {
    if (i) AImage_delete(i);
  }
};
using ImageReaderPtr = std::unique_ptr<AImageReader, ImageReaderDeleter>;
using ImagePtr = std::unique_ptr<AImage, ImageDeleter>;

class HwVideoHandler : public StreamHandler {
 public:
  HwVideoHandler(TranscodeContext& ctx, AVStream* in, int outIdx)
      : ctx_(ctx), in_(in), outIdx_(outIdx), plan_(*ctx.plan->video), adapter_(in) {
    const VideoPlan& v = plan_;
    srcW_ = in->codecpar->width;
    srcH_ = in->codecpar->height;
    geom_.srcWidth = srcW_;
    geom_.srcHeight = srcH_;
    geom_.rotation = (v.autorotate ? streamRotation(in) : 0) + v.filters.rotate;
    geom_.hflip = v.filters.hflip;
    geom_.vflip = v.filters.vflip;
    geom_.cropLeft = v.crop.left;
    geom_.cropTop = v.crop.top;
    geom_.cropRight = v.crop.right;
    geom_.cropBottom = v.crop.bottom;
    int rot = ((geom_.rotation % 360) + 360) % 360;
    int ow = (rot == 90 || rot == 270) ? srcH_ : srcW_;
    int oh = (rot == 90 || rot == 270) ? srcW_ : srcH_;
    int cw = ow - v.crop.left - v.crop.right, ch = oh - v.crop.top - v.crop.bottom;
    supersample_ = cw > 1.6 * v.width || ch > 1.6 * v.height;
    double srcFps = in->avg_frame_rate.num > 0 ? av_q2d(in->avg_frame_rate) : 30.0;
    if (v.fpsMode == FpsMode::Cfr ||
        (v.fpsMode == FpsMode::PeakLimit && srcFps > static_cast<double>(v.fpsNum) / v.fpsDen + 0.01)) {
      cfr_ = true;
      intervalUs_ = static_cast<int64_t>(1e6 * v.fpsDen / v.fpsNum);
    }
    double outFps = cfr_ ? static_cast<double>(v.fpsNum) / v.fpsDen : srcFps;
    pkt_ = makePacket();

    // Encoder with Surface input.
    encoder_.reset(AMediaCodec_createCodecByName(v.encoder.c_str()));
    if (!encoder_) {
      throwError(ErrorCategory::HardwareCodecFailure, "hw_encoder_create",
                 "The hardware encoder " + v.encoder + " could not be created on this device.", "",
                 {"Use software encoding"});
    }
    MediaFormatPtr ef = buildEncoderFormat(v, v.width, v.height, outFps, mc::COLOR_FormatSurface);
    media_status_t st = AMediaCodec_configure(encoder_.get(), ef.get(), nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    if (st != AMEDIA_OK) throwMediaError(st, "hw_encoder_configure", "surface input", &v, v.width, v.height);
    st = AMediaCodec_createInputSurface(encoder_.get(), &encoderWindow_);
    if (st != AMEDIA_OK || !encoderWindow_) throwMediaError(st, "hw_encoder_surface", "input surface", &v, v.width, v.height);
    st = AMediaCodec_start(encoder_.get());
    if (st != AMEDIA_OK) throwMediaError(st, "hw_encoder_start", "starting", &v, v.width, v.height);
    gl_.init(encoderWindow_, v.width, v.height);

    // ImageReader the decoder renders into.
    AImageReader* reader = nullptr;
    media_status_t rs = AImageReader_newWithUsage(srcW_, srcH_, AIMAGE_FORMAT_PRIVATE,
                                                  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 5, &reader);
    if (rs != AMEDIA_OK || !reader) throwMediaError(rs, "hw_image_reader", "decoder output surface", &v, srcW_, srcH_);
    reader_.reset(reader);
    listener_.context = this;
    listener_.onImageAvailable = [](void* c, AImageReader*) {
      auto* self = static_cast<HwVideoHandler*>(c);
      std::lock_guard<std::mutex> l(self->imgMu_);
      ++self->imagesAvailable_;
      self->imgCv_.notify_all();
    };
    AImageReader_setImageListener(reader_.get(), &listener_);
    ANativeWindow* decoderWindow = nullptr;
    AImageReader_getWindow(reader_.get(), &decoderWindow);

    // Decoder.
    if (!v.hwDecoder.empty()) decoder_.reset(AMediaCodec_createCodecByName(v.hwDecoder.c_str()));
    if (!decoder_) decoder_.reset(AMediaCodec_createDecoderByType(adapter_.mime().c_str()));
    if (!decoder_) {
      throwError(ErrorCategory::HardwareCodecFailure, "hw_decoder_create", "No hardware decoder is available for this video.",
                 "", {"Use software encoding"});
    }
    MediaFormatPtr df(AMediaFormat_new());
    AMediaFormat_setString(df.get(), AMEDIAFORMAT_KEY_MIME, adapter_.mime().c_str());
    AMediaFormat_setInt32(df.get(), AMEDIAFORMAT_KEY_WIDTH, srcW_);
    AMediaFormat_setInt32(df.get(), AMEDIAFORMAT_KEY_HEIGHT, srcH_);
    AMediaFormat_setInt32(df.get(), "priority", 1);
    adapter_.applyCsd(df.get());
    st = AMediaCodec_configure(decoder_.get(), df.get(), decoderWindow, nullptr, 0);
    if (st != AMEDIA_OK) throwMediaError(st, "hw_decoder_configure", "decoder", &v, srcW_, srcH_);
    st = AMediaCodec_start(decoder_.get());
    if (st != AMEDIA_OK) throwMediaError(st, "hw_decoder_start", "decoder", &v, srcW_, srcH_);
    VC_LOGI(TAG, "hardware pipeline %dx%d -> %dx%d rot=%d cfr=%d ss=%d", srcW_, srcH_, v.width, v.height, geom_.rotation,
            cfr_ ? 1 : 0, supersample_ ? 1 : 0);
  }

  ~HwVideoHandler() override {
    // Order matters: stop producers before their consumers.
    held_.reset();
    decoder_.reset();
    reader_.reset();
    gl_.release();
    encoder_.reset();
    if (encoderWindow_) ANativeWindow_release(encoderWindow_);
  }

  void onPacket(AVPacket* pkt) override {
    if (end_) return;
    int64_t absUs = toUs(pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts, in_->time_base);
    if (!adapter_.convert(pkt)) {
      ctx_.noteDecodeError("video");
      return;
    }
    for (int attempt = 0;; ++attempt) {
      ssize_t idx = AMediaCodec_dequeueInputBuffer(decoder_.get(), kTimeoutUs);
      if (idx >= 0) {
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(decoder_.get(), idx, &cap);
        if (!buf || cap < static_cast<size_t>(pkt->size)) {
          throwError(ErrorCategory::HardwareCodecFailure, "hw_decoder_input", "A video packet is larger than the hardware decoder buffer.");
        }
        memcpy(buf, pkt->data, pkt->size);
        uint32_t flags = (pkt->flags & AV_PKT_FLAG_KEY) ? mc::BUFFER_FLAG_KEY_FRAME : 0;
        AMediaCodec_queueInputBuffer(decoder_.get(), idx, 0, pkt->size, absUs == AV_NOPTS_VALUE ? 0 : absUs, flags);
        break;
      }
      drainDecoder(0);
      drainEncoder(0);
      if (attempt > 3000) throwError(ErrorCategory::HardwareCodecFailure, "hw_decoder_input", "The hardware decoder stalled.");
    }
    drainDecoder(0);
    drainEncoder(0);
  }

  void finish() override {
    // Signal end of input to the decoder.
    for (int attempt = 0; attempt < 3000; ++attempt) {
      ssize_t idx = AMediaCodec_dequeueInputBuffer(decoder_.get(), kTimeoutUs);
      if (idx >= 0) {
        AMediaCodec_queueInputBuffer(decoder_.get(), idx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        break;
      }
      drainDecoder(0);
      drainEncoder(0);
    }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kEosWaitMs);
    while (!decoderEos_ && !end_) {
      if (std::chrono::steady_clock::now() > deadline) {
        throwError(ErrorCategory::HardwareCodecFailure, "hw_decoder_flush", "The hardware decoder did not finish.");
      }
      drainDecoder(kTimeoutUs);
      drainEncoder(0);
    }
    flushHeld();
    if (framesSubmitted_ == 0) {
      throwError(ErrorCategory::InvalidInput, "hw_decode", "No video frames could be decoded from the selected range.");
    }
    AMediaCodec_signalEndOfInputStream(encoder_.get());
    deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kEosWaitMs);
    while (!encoderEos_) {
      if (std::chrono::steady_clock::now() > deadline) {
        throwError(ErrorCategory::HardwareCodecFailure, "hw_encoder_flush", "The hardware encoder did not finish.");
      }
      drainEncoder(kTimeoutUs);
    }
    dts_.markEndOfStream();
    emitPackets();
  }

  bool reachedEnd() const override { return end_; }
  std::string encoderDescription() const override { return plan_.encoder + " (hardware, surface)"; }

 private:
  void drainDecoder(int64_t timeoutUs) {
    for (;;) {
      AMediaCodecBufferInfo bi{};
      ssize_t idx = AMediaCodec_dequeueOutputBuffer(decoder_.get(), &bi, timeoutUs);
      if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return;
      if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
      if (idx < 0) throwMediaError(static_cast<media_status_t>(idx), "hw_decoder_output", "decoding", &plan_, srcW_, srcH_);
      bool eos = bi.flags & mc::BUFFER_FLAG_END_OF_STREAM;
      int64_t absUs = bi.presentationTimeUs;
      bool render = bi.size > 0 && !ctx_.beforeStart(absUs) && !ctx_.afterEnd(absUs) && !end_;
      if (bi.size > 0 && ctx_.afterEnd(absUs)) end_ = true;
      AMediaCodec_releaseOutputBuffer(decoder_.get(), idx, render);
      if (render) onFrameRendered(absUs - ctx_.baseUs);
      if (eos) {
        decoderEos_ = true;
        return;
      }
      timeoutUs = 0;
    }
  }

  ImagePtr waitImage() {
    std::unique_lock<std::mutex> l(imgMu_);
    if (!imgCv_.wait_for(l, std::chrono::milliseconds(kImageWaitMs), [&] { return imagesAvailable_ > 0; })) {
      throwError(ErrorCategory::HardwareCodecFailure, "hw_render", "Decoded frames stopped arriving from the hardware decoder.");
    }
    --imagesAvailable_;
    l.unlock();
    AImage* img = nullptr;
    media_status_t st = AImageReader_acquireNextImage(reader_.get(), &img);
    if (st != AMEDIA_OK || !img) throwMediaError(st, "hw_render", "acquiring a decoded frame", &plan_, srcW_, srcH_);
    return ImagePtr(img);
  }

  void onFrameRendered(int64_t ptsUs) {
    ImagePtr img = waitImage();
    if (ctx_.progress) ctx_.progress->onMediaTime(ptsUs);
    if (!cfr_) {
      submit(img.get(), ptsUs);
      return;
    }
    // CFR: emit slots decided by this frame's arrival using the held frame.
    if (held_) {
      while (nextSlotUs_ + intervalUs_ / 2 < ptsUs) {
        submit(held_.get(), nextSlotUs_);
        nextSlotUs_ += intervalUs_;
      }
    } else {
      // First frame: start the output timeline at the first frame time.
      nextSlotUs_ = std::max<int64_t>(0, ptsUs);
    }
    held_ = std::move(img);
    heldPtsUs_ = ptsUs;
    if (lastSrcPtsUs_ != INT64_MIN && ptsUs > lastSrcPtsUs_) srcFrameDurUs_ = ptsUs - lastSrcPtsUs_;
    lastSrcPtsUs_ = ptsUs;
  }

  void flushHeld() {
    if (!cfr_ || !held_) return;
    int64_t endUs = heldPtsUs_ + std::max<int64_t>(srcFrameDurUs_, 1);
    bool any = false;
    while (nextSlotUs_ + intervalUs_ / 2 < endUs || !any) {
      submit(held_.get(), nextSlotUs_);
      nextSlotUs_ += intervalUs_;
      any = true;
    }
    held_.reset();
  }

  void submit(AImage* img, int64_t ptsUs) {
    if (lastSubmitUs_ != INT64_MIN && ptsUs <= lastSubmitUs_) return;
    AHardwareBuffer* hb = nullptr;
    if (AImage_getHardwareBuffer(img, &hb) != AMEDIA_OK || !hb) {
      throwError(ErrorCategory::HardwareCodecFailure, "hw_render", "The decoder output cannot be accessed by the GPU.");
    }
    AImageCropRect crop{0, 0, srcW_, srcH_};
    AImage_getCropRect(img, &crop);
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hb, &desc);
    int bw = static_cast<int>(desc.width), bh = static_cast<int>(desc.height);
    TexTransform t = computeTransform(geom_, crop.left, crop.top, crop.right - crop.left, crop.bottom - crop.top,
                                      bw > 0 ? bw : srcW_, bh > 0 ? bh : srcH_);
    gl_.drawAndPresent(hb, t, plan_.filters.grayscale, supersample_, ptsUs * 1000);
    lastSubmitUs_ = ptsUs;
    ++framesSubmitted_;
    drainEncoder(0);
  }

  void drainEncoder(int64_t timeoutUs) {
    for (;;) {
      AMediaCodecBufferInfo bi{};
      ssize_t idx = AMediaCodec_dequeueOutputBuffer(encoder_.get(), &bi, timeoutUs);
      if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return;
      if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        if (AMediaFormat* of = AMediaCodec_getOutputFormat(encoder_.get())) {
          if (csd_.empty()) {
            for (const char* key : {"csd-0", "csd-1", "csd-2"}) {
              void* data = nullptr;
              size_t size = 0;
              if (AMediaFormat_getBuffer(of, key, &data, &size) && data && size) {
                csd_.insert(csd_.end(), static_cast<uint8_t*>(data), static_cast<uint8_t*>(data) + size);
              }
            }
          }
          AMediaFormat_delete(of);
        }
        continue;
      }
      if (idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
      if (idx < 0) throwMediaError(static_cast<media_status_t>(idx), "hw_encoder_output", "encoding", &plan_, plan_.width, plan_.height);
      size_t cap = 0;
      uint8_t* buf = AMediaCodec_getOutputBuffer(encoder_.get(), idx, &cap);
      if (bi.flags & mc::BUFFER_FLAG_CODEC_CONFIG) {
        if (buf && bi.size > 0) csd_.assign(buf + bi.offset, buf + bi.offset + bi.size);
      } else if (buf && bi.size > 0) {
        if (!paramsSent_) sendParams();
        PacketPtr p = makePacket();
        checkAv(av_new_packet(p.get(), bi.size), "hw_encoder_output", "allocating a packet");
        memcpy(p->data, buf + bi.offset, bi.size);
        p->pts = bi.presentationTimeUs;
        if (bi.flags & mc::BUFFER_FLAG_KEY_FRAME) p->flags |= AV_PKT_FLAG_KEY;
        dts_.push(std::move(p));
        emitPackets();
      }
      AMediaCodec_releaseOutputBuffer(encoder_.get(), idx, false);
      if (bi.flags & mc::BUFFER_FLAG_END_OF_STREAM) {
        encoderEos_ = true;
        return;
      }
      timeoutUs = 0;
    }
  }

  void sendParams() {
    paramsSent_ = true;
    if (!ctx_.muxer) return;
    AVStream* os = ctx_.muxer->stream(outIdx_);
    AVCodecParameters* par = os->codecpar;
    par->codec_type = AVMEDIA_TYPE_VIDEO;
    par->codec_id = codecForMime(plan_.mime);
    par->width = plan_.width;
    par->height = plan_.height;
    par->format = AV_PIX_FMT_YUV420P;
    par->color_primaries = AVCOL_PRI_BT709;
    par->color_trc = AVCOL_TRC_BT709;
    par->color_space = AVCOL_SPC_BT709;
    par->color_range = AVCOL_RANGE_MPEG;
    par->sample_aspect_ratio = AVRational{1, 1};
    if (!csd_.empty()) {
      par->extradata = static_cast<uint8_t*>(av_mallocz(csd_.size() + AV_INPUT_BUFFER_PADDING_SIZE));
      if (!par->extradata) throw std::bad_alloc();
      memcpy(par->extradata, csd_.data(), csd_.size());
      par->extradata_size = static_cast<int>(csd_.size());
    }
    os->time_base = AVRational{1, 1000000};
    if (cfr_) os->avg_frame_rate = AVRational{plan_.fpsNum, plan_.fpsDen};
    prepareOutputCodecpar(ctx_.plan->container.format, os);
    ctx_.muxer->markReady(outIdx_);
  }

  void emitPackets() {
    while (PacketPtr p = dts_.pop()) {
      if (!ctx_.muxer) continue;
      if (ctx_.progress) ctx_.progress->onEncodedVideoFrame(p->pts);
      ctx_.muxer->write(outIdx_, p.get(), AVRational{1, 1000000});
      if (ctx_.progress) ctx_.progress->setOutputBytes(ctx_.muxer->bytesWritten());
    }
  }

  TranscodeContext& ctx_;
  AVStream* in_;
  int outIdx_;
  VideoPlan plan_;
  DecoderInputAdapter adapter_;
  int srcW_ = 0, srcH_ = 0;
  GeometryParams geom_;
  bool supersample_ = false;
  bool cfr_ = false;
  int64_t intervalUs_ = 0;
  PacketPtr pkt_;

  MediaCodecPtr encoder_;
  ANativeWindow* encoderWindow_ = nullptr;
  GlRenderer gl_;
  ImageReaderPtr reader_;
  AImageReader_ImageListener listener_{};
  MediaCodecPtr decoder_;

  std::mutex imgMu_;
  std::condition_variable imgCv_;
  int imagesAvailable_ = 0;

  ImagePtr held_;
  int64_t heldPtsUs_ = 0;
  int64_t nextSlotUs_ = 0;
  int64_t lastSrcPtsUs_ = INT64_MIN;
  int64_t srcFrameDurUs_ = 0;
  int64_t lastSubmitUs_ = INT64_MIN;
  int64_t framesSubmitted_ = 0;

  std::vector<uint8_t> csd_;
  DtsGenerator dts_;
  bool paramsSent_ = false;
  bool decoderEos_ = false;
  bool encoderEos_ = false;
  bool end_ = false;
};
}  // namespace

std::unique_ptr<StreamHandler> createHardwareVideoHandler(TranscodeContext& ctx, AVStream* in, int outIdx) {
  if (ctx.pass != 0) {
    throwError(ErrorCategory::InvalidConfiguration, "pipeline", "Hardware encoders do not support two-pass encoding.");
  }
  return std::make_unique<HwVideoHandler>(ctx, in, outIdx);
}

}  // namespace vc
