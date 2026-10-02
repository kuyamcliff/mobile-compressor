#include "pipeline/VideoEncoder.h"

#include "core/Errors.h"
#include "core/Log.h"
#include "pipeline/EncoderSetup.h"

extern "C" {
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/pixdesc.h>
}

namespace vc {

namespace {
constexpr const char* TAG = "VideoEncoder";

// Copies HDR static metadata from the source stream to the encoder so that
// encoders that can signal it (SEI / OBU metadata) and muxers (mdcv/clli boxes,
// Matroska colour elements) preserve it.
void copyHdrSideData(AVCodecContext* ctx, const AVStream* src) {
  if (!src) return;
  const AVCodecParameters* p = src->codecpar;
  for (AVPacketSideDataType t : {AV_PKT_DATA_MASTERING_DISPLAY_METADATA, AV_PKT_DATA_CONTENT_LIGHT_LEVEL}) {
    const AVPacketSideData* sd = av_packet_side_data_get(p->coded_side_data, p->nb_coded_side_data, t);
    if (!sd) continue;
    AVFrameSideDataType ft = t == AV_PKT_DATA_MASTERING_DISPLAY_METADATA ? AV_FRAME_DATA_MASTERING_DISPLAY_METADATA
                                                                         : AV_FRAME_DATA_CONTENT_LIGHT_LEVEL;
    AVFrameSideData* fsd =
        av_frame_side_data_new(&ctx->decoded_side_data, &ctx->nb_decoded_side_data, ft, sd->size, AV_FRAME_SIDE_DATA_FLAG_UNIQUE);
    if (fsd) memcpy(fsd->data, sd->data, sd->size);
  }
}
}  // namespace

std::string encoderPixFmtFor(const VideoPlan& v) {
  if (v.pipeline == Pipeline::Hybrid) return v.hwColorFormat == 19 ? "yuv420p" : "nv12";
  if (!v.pixFmt.empty()) return v.pixFmt;
  return v.bitDepth > 8 ? "yuv420p10le" : "yuv420p";
}

FfVideoEncoder::FfVideoEncoder(EncoderSetupInfo info) : info_(std::move(info)) {
  pkt_ = makePacket();
  scratch_ = makeFrame();
}

std::string FfVideoEncoder::describe() const { return info_.plan ? info_.plan->encoder : "?"; }

void FfVideoEncoder::open(const AVFrame* first, AVRational frameTb, AVRational frameRate) {
  const VideoPlan& v = *info_.plan;
  codec_ = avcodec_find_encoder_by_name(v.encoder.c_str());
  if (!codec_) {
    throwError(ErrorCategory::UnsupportedCodec, "video_encoder", "The " + v.encoder + " encoder is not available in this build.");
  }
  ctx_.reset(avcodec_alloc_context3(codec_));
  if (!ctx_) throw std::bad_alloc();
  AVCodecContext* c = ctx_.get();
  c->width = first->width;
  c->height = first->height;
  c->pix_fmt = static_cast<AVPixelFormat>(first->format);
  pixFmt_ = c->pix_fmt;
  c->sample_aspect_ratio = AVRational{1, 1};
  if (v.encoder == "libkvazaar" && (c->width % 8 || c->height % 8)) {
    throwError(ErrorCategory::InvalidConfiguration, "video_encoder",
               "Kvazaar HEVC requires dimensions that are multiples of 8.");
  }

  // Time base: keep the filter output's (VFR-preserving) unless the codec
  // cannot represent it (MPEG-4 Part 2 limits the denominator to 16 bits).
  AVRational tb = frameTb;
  if (v.encoder == "mpeg4" && tb.den > 65535) {
    if (frameRate.num > 0 && frameRate.den > 0 && frameRate.num <= 65535) {
      tb = av_inv_q(frameRate);
    } else {
      av_reduce(&tb.num, &tb.den, tb.num, tb.den, 65535);
    }
  }
  c->time_base = tb;
  frameTb_ = frameTb;
  if (frameRate.num > 0 && frameRate.den > 0) c->framerate = frameRate;

  c->color_primaries = first->color_primaries;
  c->color_trc = first->color_trc;
  c->colorspace = first->colorspace;
  c->color_range = first->color_range != AVCOL_RANGE_UNSPECIFIED ? first->color_range : AVCOL_RANGE_MPEG;
  c->chroma_sample_location = first->chroma_location;
  c->field_order = AV_FIELD_PROGRESSIVE;
  if (info_.globalHeader) c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
  if (info_.pass == 1) c->flags |= AV_CODEC_FLAG_PASS1;
  if (info_.pass == 2) {
    c->flags |= AV_CODEC_FLAG_PASS2;
    // stats_in is owned by the context (freed with it).
    c->stats_in = av_strdup(info_.passStatsIn.c_str());
    if (!c->stats_in) throw std::bad_alloc();
  }
  bool hdrOut = v.hdrMode != "tonemap" && (first->color_trc == AVCOL_TRC_SMPTE2084 || first->color_trc == AVCOL_TRC_ARIB_STD_B67);
  if (hdrOut) copyHdrSideData(c, info_.source);

  Dict opts;
  configureSoftwareVideoEncoder(c, v, frameRate, opts);
  openEncoder(c, codec_, opts, v.encoder + " encoder (" + std::to_string(c->width) + "x" + std::to_string(c->height) + ", " +
                                   av_get_pix_fmt_name(c->pix_fmt) + ")");
  VC_LOGI(TAG, "opened %s %dx%d %s tb=%d/%d pass=%d", v.encoder.c_str(), c->width, c->height,
          av_get_pix_fmt_name(c->pix_fmt), c->time_base.num, c->time_base.den, info_.pass);

  if (info_.onParams && info_.pass != 1) {
    AVCodecParameters* par = avcodec_parameters_alloc();
    if (!par) throw std::bad_alloc();
    avcodec_parameters_from_context(par, c);
    if (hdrOut && info_.source) {
      const AVCodecParameters* sp = info_.source->codecpar;
      for (AVPacketSideDataType t : {AV_PKT_DATA_MASTERING_DISPLAY_METADATA, AV_PKT_DATA_CONTENT_LIGHT_LEVEL}) {
        const AVPacketSideData* sd = av_packet_side_data_get(sp->coded_side_data, sp->nb_coded_side_data, t);
        if (!sd) continue;
        AVPacketSideData* nd = av_packet_side_data_new(&par->coded_side_data, &par->nb_coded_side_data, t, sd->size, 0);
        if (nd) memcpy(nd->data, sd->data, sd->size);
      }
    }
    info_.onParams(par, c->time_base);
    avcodec_parameters_free(&par);
  }
}

void FfVideoEncoder::encode(const AVFrame* frame) {
  AVCodecContext* c = ctx_.get();
  if (!c) return;
  if (frame) {
    av_frame_unref(scratch_.get());
    checkAv(av_frame_ref(scratch_.get(), frame), "video_encode", "referencing a frame");
    AVFrame* f = scratch_.get();
    f->pts = av_rescale_q(frame->pts, frameTb_, c->time_base);
    if (lastPts_ != AV_NOPTS_VALUE && f->pts <= lastPts_) {
      // Two source frames collapsed onto one encoder tick: drop the duplicate.
      return;
    }
    lastPts_ = f->pts;
    f->pict_type = AV_PICTURE_TYPE_NONE;
    f->duration = 0;
    int ret = avcodec_send_frame(c, f);
    if (ret < 0) throw EngineException(fromAvError(ret, "video_encode", v_name()));
  } else {
    int ret = avcodec_send_frame(c, nullptr);
    if (ret < 0 && ret != AVERROR_EOF) throw EngineException(fromAvError(ret, "video_encode", "flushing the encoder"));
  }
  drain();
  if (!frame && info_.pass == 1 && c->stats_out) stats_ = c->stats_out;
}

std::string FfVideoEncoder::v_name() const { return info_.plan->encoder + " encoder"; }

void FfVideoEncoder::drain() {
  AVCodecContext* c = ctx_.get();
  for (;;) {
    int ret = avcodec_receive_packet(c, pkt_.get());
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return;
    if (ret < 0) throw EngineException(fromAvError(ret, "video_encode", v_name()));
    if (info_.pass == 1) {
      av_packet_unref(pkt_.get());
      continue;
    }
    if (info_.onPacket) info_.onPacket(pkt_.get(), c->time_base);
    av_packet_unref(pkt_.get());
  }
}

}  // namespace vc
