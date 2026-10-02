#include "android/MediaCodecUtil.h"

#include <cmath>

#include "core/Log.h"

namespace vc {

namespace {
constexpr const char* TAG = "MediaCodecUtil";

std::string statusName(media_status_t st) {
  switch (st) {
    case AMEDIA_OK: return "OK";
    case AMEDIACODEC_ERROR_INSUFFICIENT_RESOURCE: return "insufficient codec resources";
    case AMEDIACODEC_ERROR_RECLAIMED: return "codec reclaimed by the system";
    case AMEDIA_ERROR_MALFORMED: return "malformed data";
    case AMEDIA_ERROR_UNSUPPORTED: return "unsupported configuration";
    case AMEDIA_ERROR_INVALID_OBJECT: return "invalid codec object";
    case AMEDIA_ERROR_INVALID_PARAMETER: return "invalid parameter";
    case AMEDIA_ERROR_INVALID_OPERATION: return "invalid operation";
    case AMEDIA_ERROR_IO: return "I/O error";
    default: return "error " + std::to_string(static_cast<int>(st));
  }
}
}  // namespace

std::string mimeForCodec(AVCodecID id) {
  switch (id) {
    case AV_CODEC_ID_H264: return "video/avc";
    case AV_CODEC_ID_HEVC: return "video/hevc";
    case AV_CODEC_ID_VP8: return "video/x-vnd.on2.vp8";
    case AV_CODEC_ID_VP9: return "video/x-vnd.on2.vp9";
    case AV_CODEC_ID_AV1: return "video/av01";
    case AV_CODEC_ID_MPEG4: return "video/mp4v-es";
    case AV_CODEC_ID_H263: return "video/3gpp";
    case AV_CODEC_ID_MPEG2VIDEO: return "video/mpeg2";
    default: return "";
  }
}

AVCodecID codecForMime(const std::string& mime) {
  if (mime == "video/avc") return AV_CODEC_ID_H264;
  if (mime == "video/hevc") return AV_CODEC_ID_HEVC;
  if (mime == "video/x-vnd.on2.vp8") return AV_CODEC_ID_VP8;
  if (mime == "video/x-vnd.on2.vp9") return AV_CODEC_ID_VP9;
  if (mime == "video/av01") return AV_CODEC_ID_AV1;
  if (mime == "video/mp4v-es") return AV_CODEC_ID_MPEG4;
  return AV_CODEC_ID_NONE;
}

std::vector<std::pair<const uint8_t*, size_t>> splitAnnexB(const uint8_t* data, size_t size) {
  std::vector<std::pair<const uint8_t*, size_t>> out;
  auto findStart = [&](size_t from) -> size_t {
    for (size_t i = from; i + 3 <= size; ++i) {
      if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (i + 4 <= size && data[i + 2] == 0 && data[i + 3] == 1))) {
        return i;
      }
    }
    return size;
  };
  size_t pos = findStart(0);
  while (pos < size) {
    size_t sc = data[pos + 2] == 1 ? 3 : 4;
    size_t next = findStart(pos + sc);
    out.emplace_back(data + pos, next - pos);
    pos = next;
  }
  return out;
}

DecoderInputAdapter::DecoderInputAdapter(const AVStream* st) {
  const AVCodecParameters* p = st->codecpar;
  mime_ = mimeForCodec(p->codec_id);
  if (mime_.empty()) throwError(ErrorCategory::UnsupportedCodec, "hw_decoder", "No hardware decoder mapping for this codec.");
  tmp_ = makePacket();
  const char* bsfName = nullptr;
  bool lengthPrefixed = p->extradata_size > 0 && p->extradata[0] == 1;
  if (p->codec_id == AV_CODEC_ID_H264 && lengthPrefixed) bsfName = "h264_mp4toannexb";
  if (p->codec_id == AV_CODEC_ID_HEVC && lengthPrefixed) bsfName = "hevc_mp4toannexb";
  const uint8_t* extra = p->extradata;
  int extraSize = p->extradata_size;
  if (bsfName) {
    const AVBitStreamFilter* f = av_bsf_get_by_name(bsfName);
    if (!f) throwError(ErrorCategory::Internal, "hw_decoder", std::string("Missing bitstream filter ") + bsfName);
    AVBSFContext* ctx = nullptr;
    checkAv(av_bsf_alloc(f, &ctx), "hw_decoder", "allocating a bitstream filter");
    bsf_.reset(ctx);
    checkAv(avcodec_parameters_copy(ctx->par_in, p), "hw_decoder", "configuring a bitstream filter");
    ctx->time_base_in = st->time_base;
    checkAv(av_bsf_init(ctx), "hw_decoder", "initialising a bitstream filter");
    extra = ctx->par_out->extradata;
    extraSize = ctx->par_out->extradata_size;
  }
  if (p->codec_id == AV_CODEC_ID_H264 && extra && extraSize > 0 && extra[0] == 0) {
    // csd-0 = SPS NAL units, csd-1 = PPS NAL units (Annex B).
    for (auto [nal, len] : splitAnnexB(extra, extraSize)) {
      size_t sc = nal[2] == 1 ? 3 : 4;
      if (len <= sc) continue;
      int type = nal[sc] & 0x1f;
      auto& dst = type == 7 ? csd0_ : type == 8 ? csd1_ : csd0_;
      dst.insert(dst.end(), nal, nal + len);
    }
  } else if (p->codec_id == AV_CODEC_ID_HEVC && extra && extraSize > 0 && extra[0] == 0) {
    csd0_.assign(extra, extra + extraSize);  // VPS+SPS+PPS
  } else if (extra && extraSize > 0 && (p->codec_id == AV_CODEC_ID_AV1 || p->codec_id == AV_CODEC_ID_MPEG4 ||
                                         p->codec_id == AV_CODEC_ID_MPEG2VIDEO)) {
    csd0_.assign(extra, extra + extraSize);
  }
}

void DecoderInputAdapter::applyCsd(AMediaFormat* fmt) const {
  if (!csd0_.empty()) AMediaFormat_setBuffer(fmt, "csd-0", csd0_.data(), csd0_.size());
  if (!csd1_.empty()) AMediaFormat_setBuffer(fmt, "csd-1", csd1_.data(), csd1_.size());
}

bool DecoderInputAdapter::convert(AVPacket* pkt) {
  if (!bsf_) return true;
  int ret = av_bsf_send_packet(bsf_.get(), pkt);
  if (ret < 0) return false;
  ret = av_bsf_receive_packet(bsf_.get(), tmp_.get());
  if (ret < 0) return false;
  av_packet_unref(pkt);
  av_packet_move_ref(pkt, tmp_.get());
  // mp4toannexb is strictly one-in/one-out; drain defensively.
  while (av_bsf_receive_packet(bsf_.get(), tmp_.get()) == 0) av_packet_unref(tmp_.get());
  return true;
}

MediaFormatPtr buildEncoderFormat(const VideoPlan& v, int width, int height, double fps, int32_t colorFormat) {
  MediaFormatPtr f(AMediaFormat_new());
  AMediaFormat* m = f.get();
  AMediaFormat_setString(m, AMEDIAFORMAT_KEY_MIME, v.mime.c_str());
  AMediaFormat_setInt32(m, AMEDIAFORMAT_KEY_WIDTH, width);
  AMediaFormat_setInt32(m, AMEDIAFORMAT_KEY_HEIGHT, height);
  AMediaFormat_setInt32(m, AMEDIAFORMAT_KEY_COLOR_FORMAT, colorFormat);
  if (fps <= 0) fps = 30;
  AMediaFormat_setFloat(m, AMEDIAFORMAT_KEY_FRAME_RATE, static_cast<float>(fps));
  float iframe = v.keyIntSeconds > 0 ? static_cast<float>(v.keyIntSeconds) : 2.0f;
  AMediaFormat_setFloat(m, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, iframe);
  AMediaFormat_setInt32(m, "priority", 1);  // non-realtime: transcoding, not capture

  int mode = v.hwBitrateMode;
  if (v.rc.mode == RateMode::Cq && v.hwQuality >= 0) {
    mode = 0;  // BITRATE_MODE_CQ
    AMediaFormat_setInt32(m, "quality", v.hwQuality);
  }
  AMediaFormat_setInt32(m, "bitrate-mode", mode);
  if (mode != 0) {
    int kbps = v.rc.bitrateKbps > 0 ? v.rc.bitrateKbps : 4000;
    AMediaFormat_setInt32(m, AMEDIAFORMAT_KEY_BIT_RATE, kbps * 1000);
    if (v.rc.maxBitrateKbps > 0) AMediaFormat_setInt32(m, "max-bitrate", v.rc.maxBitrateKbps * 1000);
  }
  if (v.hwProfile > 0) AMediaFormat_setInt32(m, "profile", v.hwProfile);
  if (v.hwLevel > 0) AMediaFormat_setInt32(m, "level", v.hwLevel);
  if (v.bFrames >= 0) AMediaFormat_setInt32(m, "max-bframes", v.bFrames);
  if (v.hwQpMin >= 0) AMediaFormat_setInt32(m, "video-qp-min", v.hwQpMin);
  if (v.hwQpMax >= 0) AMediaFormat_setInt32(m, "video-qp-max", v.hwQpMax);
  // SDR BT.709 limited range output (the hardware path is SDR-only; HDR jobs
  // are planned onto the software path).
  AMediaFormat_setInt32(m, "color-standard", mc::COLOR_STANDARD_BT709);
  AMediaFormat_setInt32(m, "color-range", mc::COLOR_RANGE_LIMITED);
  AMediaFormat_setInt32(m, "color-transfer", mc::COLOR_TRANSFER_SDR_VIDEO);
  for (const auto& [k, val] : v.options) {
    // Expert MediaFormat keys: integers only (validated by the Kotlin planner).
    char* end = nullptr;
    long iv = std::strtol(val.c_str(), &end, 10);
    if (end && *end == '\0') AMediaFormat_setInt32(m, k.c_str(), static_cast<int32_t>(iv));
  }
  return f;
}

void throwMediaError(media_status_t st, const std::string& stage, const std::string& what, const VideoPlan* v, int width,
                     int height) {
  EngineError e;
  e.category = ErrorCategory::HardwareCodecFailure;
  e.code = static_cast<int>(st);
  e.stage = stage;
  e.detail = "MediaCodec: " + statusName(st);
  e.message = "Hardware encoding failed: the device codec rejected this configuration (" + what + ").";
  if (st == AMEDIACODEC_ERROR_INSUFFICIENT_RESOURCE || st == AMEDIACODEC_ERROR_RECLAIMED) {
    e.message = "Hardware codec resources are busy (another app or job is using them).";
    e.suggestions = {"Wait for other jobs to finish", "Use software encoding"};
  } else {
    if (v) {
      e.context = {v->codec, std::to_string(width) + "x" + std::to_string(height), std::to_string(v->bitDepth) + "-bit",
                   v->encoder};
    }
    e.suggestions = {"Choose 8-bit output", "Lower the resolution or frame rate", "Use software encoding"};
  }
  VC_LOGE(TAG, "%s failed: %s", stage.c_str(), statusName(st).c_str());
  throw EngineException(e);
}

}  // namespace vc
