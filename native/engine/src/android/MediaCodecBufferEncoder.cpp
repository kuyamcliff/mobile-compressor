// Hybrid pipeline encoder: frames decoded and filtered by FFmpeg (any source
// codec, any libavfilter chain incl. denoise / tone-mapping / subtitle burn-in)
// are copied into MediaCodec input buffers of a hardware encoder.
//
// Buffer layout: NV12 (COLOR_FormatYUV420SemiPlanar) or I420
// (COLOR_FormatYUV420Planar), using the stride / slice-height the codec reports
// in its input format (falling back to width / height).
#include <media/NdkMediaCodec.h>

#include <chrono>
#include <cstring>
#include <thread>

#include "android/MediaCodecUtil.h"
#include "core/Log.h"
#include "pipeline/DtsGenerator.h"
#include "pipeline/Transcoder.h"

namespace vc {

namespace {
constexpr const char* TAG = "MCBufferEncoder";
constexpr int64_t kDequeueTimeoutUs = 10000;
constexpr int kMaxEosWaitMs = 20000;

class MediaCodecBufferEncoder : public VideoEncoderBackend {
 public:
  explicit MediaCodecBufferEncoder(EncoderSetupInfo info) : info_(std::move(info)) {}

  void open(const AVFrame* first, AVRational frameTb, AVRational frameRate) override {
    const VideoPlan& v = *info_.plan;
    width_ = first->width;
    height_ = first->height;
    frameTb_ = frameTb;
    colorFormat_ = v.hwColorFormat == mc::COLOR_FormatYUV420Planar ? mc::COLOR_FormatYUV420Planar
                                                                    : mc::COLOR_FormatYUV420SemiPlanar;
    codec_.reset(AMediaCodec_createCodecByName(v.encoder.c_str()));
    if (!codec_) {
      throwError(ErrorCategory::HardwareCodecFailure, "hw_encoder_create",
                 "The hardware encoder " + v.encoder + " could not be created on this device.", "",
                 {"Use software encoding"});
    }
    double fps = frameRate.num > 0 && frameRate.den > 0 ? av_q2d(frameRate) : 30.0;
    MediaFormatPtr fmt = buildEncoderFormat(v, width_, height_, fps, colorFormat_);
    media_status_t st = AMediaCodec_configure(codec_.get(), fmt.get(), nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    if (st != AMEDIA_OK) throwMediaError(st, "hw_encoder_configure", "buffer input", &v, width_, height_);
    st = AMediaCodec_start(codec_.get());
    if (st != AMEDIA_OK) throwMediaError(st, "hw_encoder_start", "buffer input", &v, width_, height_);
    stride_ = width_;
    sliceHeight_ = height_;
    if (AMediaFormat* in = AMediaCodec_getInputFormat(codec_.get())) {
      int32_t s = 0, h = 0;
      if (AMediaFormat_getInt32(in, "stride", &s) && s >= width_) stride_ = s;
      if (AMediaFormat_getInt32(in, "slice-height", &h) && h >= height_) sliceHeight_ = h;
      AMediaFormat_delete(in);
    }
    VC_LOGI(TAG, "opened %s %dx%d stride=%d slice=%d color=%d", v.encoder.c_str(), width_, height_, stride_, sliceHeight_,
            colorFormat_);
  }

  bool isOpen() const override { return codec_ != nullptr; }
  std::string describe() const override { return info_.plan->encoder + " (hardware, buffer input)"; }

  void encode(const AVFrame* frame) override {
    if (!codec_) return;
    if (frame) {
      queueFrame(frame);
      drain(0);
      return;
    }
    // End of stream.
    for (;;) {
      ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_.get(), kDequeueTimeoutUs);
      if (idx >= 0) {
        AMediaCodec_queueInputBuffer(codec_.get(), idx, 0, 0, lastPtsUs_ + 1, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        break;
      }
      drain(0);
    }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kMaxEosWaitMs);
    while (!eos_) {
      if (std::chrono::steady_clock::now() > deadline) {
        throwError(ErrorCategory::HardwareCodecFailure, "hw_encoder_flush", "The hardware encoder did not finish.");
      }
      drain(kDequeueTimeoutUs);
    }
    dts_.markEndOfStream();
    emitReady();
  }

 private:
  void queueFrame(const AVFrame* f) {
    int64_t ptsUs = av_rescale_q(f->pts, frameTb_, AVRational{1, 1000000});
    if (lastPtsUs_ != INT64_MIN && ptsUs <= lastPtsUs_) return;  // duplicate tick
    for (int attempt = 0;; ++attempt) {
      ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_.get(), kDequeueTimeoutUs);
      if (idx >= 0) {
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(codec_.get(), idx, &cap);
        size_t need = static_cast<size_t>(stride_) * sliceHeight_ * 3 / 2;
        if (!buf || cap < need) {
          throwError(ErrorCategory::HardwareCodecFailure, "hw_encoder_input", "Hardware encoder input buffer is too small.",
                     "capacity=" + std::to_string(cap) + " need=" + std::to_string(need));
        }
        copyPlanes(f, buf);
        media_status_t st = AMediaCodec_queueInputBuffer(codec_.get(), idx, 0, need, ptsUs, 0);
        if (st != AMEDIA_OK) throwMediaError(st, "hw_encoder_input", "queueing a frame", info_.plan, width_, height_);
        lastPtsUs_ = ptsUs;
        return;
      }
      drain(0);
      if (attempt > 2000) {
        throwError(ErrorCategory::HardwareCodecFailure, "hw_encoder_input", "The hardware encoder stopped accepting frames.");
      }
    }
  }

  void copyPlanes(const AVFrame* f, uint8_t* dst) {
    for (int y = 0; y < height_; ++y) {
      memcpy(dst + static_cast<size_t>(y) * stride_, f->data[0] + static_cast<ptrdiff_t>(y) * f->linesize[0], width_);
    }
    uint8_t* chroma = dst + static_cast<size_t>(stride_) * sliceHeight_;
    if (colorFormat_ == mc::COLOR_FormatYUV420SemiPlanar) {
      for (int y = 0; y < height_ / 2; ++y) {
        memcpy(chroma + static_cast<size_t>(y) * stride_, f->data[1] + static_cast<ptrdiff_t>(y) * f->linesize[1], width_);
      }
    } else {
      int cs = stride_ / 2;
      uint8_t* v = chroma + static_cast<size_t>(cs) * (sliceHeight_ / 2);
      for (int y = 0; y < height_ / 2; ++y) {
        memcpy(chroma + static_cast<size_t>(y) * cs, f->data[1] + static_cast<ptrdiff_t>(y) * f->linesize[1], width_ / 2);
        memcpy(v + static_cast<size_t>(y) * cs, f->data[2] + static_cast<ptrdiff_t>(y) * f->linesize[2], width_ / 2);
      }
    }
  }

  void drain(int64_t timeoutUs) {
    for (;;) {
      AMediaCodecBufferInfo bi{};
      ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec_.get(), &bi, timeoutUs);
      if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return;
      if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        if (AMediaFormat* of = AMediaCodec_getOutputFormat(codec_.get())) {
          if (csd_.empty()) {
            for (const char* key : {"csd-0", "csd-1", "csd-2"}) {
              void* data = nullptr;
              size_t size = 0;
              if (AMediaFormat_getBuffer(of, key, &data, &size) && data && size) {
                const auto* b = static_cast<const uint8_t*>(data);
                csd_.insert(csd_.end(), b, b + size);
              }
            }
          }
          AMediaFormat_delete(of);
        }
        continue;
      }
      if (idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
      if (idx < 0) throwMediaError(static_cast<media_status_t>(idx), "hw_encoder_output", "reading output", info_.plan, width_, height_);
      size_t cap = 0;
      uint8_t* buf = AMediaCodec_getOutputBuffer(codec_.get(), idx, &cap);
      if (bi.flags & mc::BUFFER_FLAG_CODEC_CONFIG) {
        if (buf && bi.size > 0) csd_.assign(buf + bi.offset, buf + bi.offset + bi.size);
      } else if (buf && bi.size > 0) {
        if (!paramsSent_) sendParams();
        PacketPtr pkt = makePacket();
        checkAv(av_new_packet(pkt.get(), bi.size), "hw_encoder_output", "allocating a packet");
        memcpy(pkt->data, buf + bi.offset, bi.size);
        pkt->pts = bi.presentationTimeUs;
        if (bi.flags & mc::BUFFER_FLAG_KEY_FRAME) pkt->flags |= AV_PKT_FLAG_KEY;
        dts_.push(std::move(pkt));
        emitReady();
      }
      AMediaCodec_releaseOutputBuffer(codec_.get(), idx, false);
      if (bi.flags & mc::BUFFER_FLAG_END_OF_STREAM) {
        eos_ = true;
        return;
      }
    }
  }

  void sendParams() {
    paramsSent_ = true;
    if (!info_.onParams) return;
    AVCodecParameters* par = avcodec_parameters_alloc();
    if (!par) throw std::bad_alloc();
    par->codec_type = AVMEDIA_TYPE_VIDEO;
    par->codec_id = codecForMime(info_.plan->mime);
    par->width = width_;
    par->height = height_;
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
    info_.onParams(par, AVRational{1, 1000000});
    avcodec_parameters_free(&par);
  }

  void emitReady() {
    while (PacketPtr p = dts_.pop()) {
      if (info_.onPacket) info_.onPacket(p.get(), AVRational{1, 1000000});
    }
  }

  EncoderSetupInfo info_;
  MediaCodecPtr codec_;
  int width_ = 0, height_ = 0, stride_ = 0, sliceHeight_ = 0;
  int32_t colorFormat_ = mc::COLOR_FormatYUV420SemiPlanar;
  AVRational frameTb_{1, 1000000};
  int64_t lastPtsUs_ = INT64_MIN;
  std::vector<uint8_t> csd_;
  DtsGenerator dts_;
  bool paramsSent_ = false;
  bool eos_ = false;
};
}  // namespace

std::unique_ptr<VideoEncoderBackend> createMediaCodecBufferEncoder(EncoderSetupInfo info) {
  if (info.pass != 0) {
    throwError(ErrorCategory::InvalidConfiguration, "pipeline", "Hardware encoders do not support two-pass encoding.");
  }
  return std::make_unique<MediaCodecBufferEncoder>(std::move(info));
}

bool hardwarePipelineAvailable() { return true; }

}  // namespace vc
