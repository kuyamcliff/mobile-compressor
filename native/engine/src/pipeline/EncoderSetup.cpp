#include "pipeline/EncoderSetup.h"

#include <cmath>

#include "core/Errors.h"
#include "core/Log.h"
#include "core/Util.h"

extern "C" {
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
}

namespace vc {

namespace {
constexpr const char* TAG = "EncoderSetup";

int keyIntFrames(const VideoPlan& v, AVRational frameRate) {
  if (v.keyIntSeconds <= 0) return -1;
  double fps = frameRate.num > 0 && frameRate.den > 0 ? av_q2d(frameRate) : 30.0;
  return std::max(1, static_cast<int>(std::lround(v.keyIntSeconds * fps)));
}

void applyVbv(AVCodecContext* ctx, const RateControl& rc) {
  if (rc.maxBitrateKbps > 0) ctx->rc_max_rate = static_cast<int64_t>(rc.maxBitrateKbps) * 1000;
  if (rc.minBitrateKbps > 0) ctx->rc_min_rate = static_cast<int64_t>(rc.minBitrateKbps) * 1000;
  if (rc.bufferKbits > 0) ctx->rc_buffer_size = rc.bufferKbits * 1000;
  else if (rc.maxBitrateKbps > 0) ctx->rc_buffer_size = rc.maxBitrateKbps * 2000;
}

}  // namespace

AVPixelFormat chooseEncoderPixFmt(const AVCodec* codec, const std::string& requested) {
  AVPixelFormat want = av_get_pix_fmt(requested.c_str());
  const void* cfg = nullptr;
  int n = 0;
  if (avcodec_get_supported_config(nullptr, codec, AV_CODEC_CONFIG_PIX_FORMAT, 0, &cfg, &n) < 0 || !cfg || n == 0) {
    return want != AV_PIX_FMT_NONE ? want : AV_PIX_FMT_YUV420P;
  }
  const auto* list = static_cast<const AVPixelFormat*>(cfg);
  for (int i = 0; i < n; ++i) {
    if (list[i] == want) return want;
  }
  // Pick the closest supported format with the same bit depth when possible.
  const AVPixFmtDescriptor* wd = av_pix_fmt_desc_get(want);
  int depth = wd ? wd->comp[0].depth : 8;
  for (int i = 0; i < n; ++i) {
    const AVPixFmtDescriptor* d = av_pix_fmt_desc_get(list[i]);
    if (d && d->comp[0].depth == depth && !(d->flags & AV_PIX_FMT_FLAG_RGB)) return list[i];
  }
  throwError(ErrorCategory::UnsupportedPixelFormat, "video_encoder",
             std::string("The ") + codec->name + " encoder does not support " + requested + " output.",
             "", {"Choose 8-bit output", "Choose a different codec"});
}

void configureSoftwareVideoEncoder(AVCodecContext* ctx, const VideoPlan& v, AVRational frameRate, Dict& opts) {
  const std::string enc = v.encoder;
  const RateControl& rc = v.rc;
  int gop = keyIntFrames(v, frameRate);
  if (v.threads > 0) ctx->thread_count = v.threads;
  if (v.bFrames >= 0) ctx->max_b_frames = v.bFrames;
  if (v.refFrames > 0) ctx->refs = v.refFrames;

  if (enc == "libopenh264") {
    // OpenH264 has no CRF. "Constant quality" is realised as a fixed QP
    // (rate control off, qmin == qmax), which is what the UI labels it as.
    if (gop > 0) ctx->gop_size = gop;
    if (!v.profile.empty()) opts.set("profile", v.profile);
    opts.set("coder", v.profile == "constrained_baseline" ? "cavlc" : "cabac");
    switch (rc.mode) {
      case RateMode::Crf:
      case RateMode::Cqp:
      case RateMode::Cq: {
        int qp = std::clamp(static_cast<int>(std::lround(rc.quality)), 1, 51);
        opts.set("rc_mode", "off");
        ctx->qmin = qp;
        ctx->qmax = qp;
        ctx->bit_rate = 0;
        break;
      }
      case RateMode::Abr:
      case RateMode::Vbr:
        opts.set("rc_mode", "bitrate");
        ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
        applyVbv(ctx, rc);
        break;
      case RateMode::Cbr:
        opts.set("rc_mode", "bitrate");
        opts.set("allow_skip_frames", "0");
        ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
        ctx->rc_max_rate = ctx->bit_rate;
        break;
      case RateMode::Lossless:
        throwError(ErrorCategory::InvalidConfiguration, "video_encoder", "OpenH264 has no lossless mode.");
    }
  } else if (enc == "libkvazaar") {
    std::vector<std::string> kp;
    kp.push_back("preset=" + (v.preset.empty() ? std::string("medium") : v.preset));
    switch (rc.mode) {
      case RateMode::Crf:
      case RateMode::Cqp:
      case RateMode::Cq:
        // Kvazaar's constant-quality model is a fixed QP with adaptive QP offsets.
        kp.push_back("qp=" + std::to_string(std::clamp(static_cast<int>(std::lround(rc.quality)), 0, 51)));
        ctx->bit_rate = 0;
        break;
      case RateMode::Abr:
      case RateMode::Vbr:
      case RateMode::Cbr:
        ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
        kp.push_back("rc-algorithm=lambda");
        break;
      case RateMode::Lossless:
        kp.push_back("lossless=1");
        ctx->bit_rate = 0;
        break;
    }
    if (v.bFrames == 0) {
      kp.push_back("gop=lp-g4d3t1");
      if (gop > 0) kp.push_back("period=" + std::to_string(gop));
    } else if (gop > 0) {
      // Kvazaar's random-access GOP is 8 frames; the intra period must be a multiple.
      int period = std::max(8, static_cast<int>(std::lround(gop / 8.0)) * 8);
      kp.push_back("period=" + std::to_string(period));
    }
    if (v.refFrames > 0) kp.push_back("ref=" + std::to_string(std::min(v.refFrames, 15)));
    if (!v.level.empty()) kp.push_back("level=" + v.level);
    int threads = v.threads > 0 ? v.threads : 0;
    if (threads > 0) kp.push_back("threads=" + std::to_string(threads));
    // Expert options for Kvazaar are its own CLI parameters.
    for (const auto& [k, val] : v.options) kp.push_back(k + "=" + val);
    std::string joined;
    for (size_t i = 0; i < kp.size(); ++i) joined += (i ? "," : "") + kp[i];
    opts.set("kvazaar-params", joined);
    return;  // options already consumed
  } else if (enc == "libvpx-vp9" || enc == "libvpx") {
    // libvpx only produces first-pass statistics in good/best mode, so a
    // two-pass job never runs with the realtime deadline.
    bool twoPassMode = ctx->flags & (AV_CODEC_FLAG_PASS1 | AV_CODEC_FLAG_PASS2);
    std::string deadline = v.preset == "best" ? "best" : (v.preset == "realtime" && !twoPassMode) ? "realtime" : "good";
    opts.set("deadline", deadline);
    // cpu-used trades speed for efficiency; "good" with cpu-used 2..4 is the usual sweet spot.
    if (!v.tune.empty() && (v.tune == "screen" || v.tune == "film" || v.tune == "default")) {
      if (enc == "libvpx-vp9") opts.set("tune-content", v.tune);
    } else if (v.tune == "psnr" || v.tune == "ssim") {
      opts.set("tune", v.tune);
    }
    if (enc == "libvpx-vp9") {
      opts.set("row-mt", "1");
      int tiles = v.width >= 3840 ? 3 : v.width >= 1920 ? 2 : v.width >= 960 ? 1 : 0;
      opts.set("tile-columns", std::to_string(tiles));
    }
    if (gop > 0) ctx->gop_size = gop;
    switch (rc.mode) {
      case RateMode::Crf:
      case RateMode::Cq:
        // Pure constant quality: bitrate 0 + crf (0..63).
        opts.set("crf", std::to_string(std::clamp(static_cast<int>(std::lround(rc.quality)), 0, 63)));
        ctx->bit_rate = 0;
        if (rc.maxBitrateKbps > 0) applyVbv(ctx, rc);
        break;
      case RateMode::Cqp:
        ctx->qmin = ctx->qmax = std::clamp(static_cast<int>(std::lround(rc.quality)), 0, 63);
        ctx->bit_rate = 0;
        opts.set("crf", std::to_string(ctx->qmin));
        break;
      case RateMode::Abr:
      case RateMode::Vbr:
        ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
        applyVbv(ctx, rc);
        break;
      case RateMode::Cbr:
        ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
        ctx->rc_min_rate = ctx->rc_max_rate = ctx->bit_rate;
        ctx->rc_buffer_size = static_cast<int>(ctx->bit_rate);
        break;
      case RateMode::Lossless:
        if (enc != "libvpx-vp9") {
          throwError(ErrorCategory::InvalidConfiguration, "video_encoder", "VP8 has no lossless mode.");
        }
        opts.set("lossless", "1");
        ctx->bit_rate = 0;
        break;
    }
  } else if (enc == "libsvtav1") {
    opts.set("preset", v.preset.empty() ? "8" : v.preset);
    std::vector<std::string> sp;
    if (v.tune == "vq") sp.emplace_back("tune=0");
    else if (v.tune == "psnr") sp.emplace_back("tune=1");
    else if (v.tune == "ssim") sp.emplace_back("tune=2");
    if (gop > 0) ctx->gop_size = gop;
    switch (rc.mode) {
      case RateMode::Crf:
      case RateMode::Cq:
        opts.set("crf", std::to_string(std::clamp(static_cast<int>(std::lround(rc.quality)), 1, 63)));
        ctx->bit_rate = 0;
        if (rc.maxBitrateKbps > 0) {
          // Capped CRF.
          ctx->rc_max_rate = static_cast<int64_t>(rc.maxBitrateKbps) * 1000;
        }
        break;
      case RateMode::Cqp:
        opts.set("qp", std::to_string(std::clamp(static_cast<int>(std::lround(rc.quality)), 1, 63)));
        sp.emplace_back("rc=0");
        sp.emplace_back("aq-mode=0");
        break;
      case RateMode::Abr:
      case RateMode::Vbr:
        ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
        applyVbv(ctx, rc);
        break;
      case RateMode::Cbr:
        ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
        sp.emplace_back("rc=2");
        break;
      case RateMode::Lossless:
        throwError(ErrorCategory::InvalidConfiguration, "video_encoder",
                   "SVT-AV1 does not provide a lossless mode in this build.", "",
                   {"Use VP9 lossless (MKV/WebM)", "Use FFV1 (MKV)"});
    }
    for (auto it = v.options.begin(); it != v.options.end();) {
      // svtav1-params keys are SVT-AV1's own; pass them through the dictionary.
      sp.push_back(it->first + "=" + it->second);
      ++it;
    }
    if (!sp.empty()) {
      std::string joined;
      for (size_t i = 0; i < sp.size(); ++i) joined += (i ? ":" : "") + sp[i];
      opts.set("svtav1-params", joined);
    }
    return;
  } else if (enc == "mpeg4") {
    if (gop > 0) ctx->gop_size = gop;
    if (rc.mode == RateMode::Crf || rc.mode == RateMode::Cqp || rc.mode == RateMode::Cq) {
      ctx->flags |= AV_CODEC_FLAG_QSCALE;
      ctx->global_quality = FF_QP2LAMBDA * std::clamp(static_cast<int>(std::lround(rc.quality)), 1, 31);
    } else {
      ctx->bit_rate = static_cast<int64_t>(rc.bitrateKbps) * 1000;
      applyVbv(ctx, rc);
    }
    if (ctx->max_b_frames < 0) ctx->max_b_frames = 0;
  } else if (enc == "ffv1") {
    opts.set("level", "3");
    opts.set("slicecrc", "1");
    opts.set("coder", "range_def");
    opts.set("context", "1");
    ctx->gop_size = 1;
  } else {
    throwError(ErrorCategory::UnsupportedCodec, "video_encoder", "Unsupported software encoder: " + enc);
  }

  for (const auto& [k, val] : v.options) opts.set(k.c_str(), val);
}

void openEncoder(AVCodecContext* ctx, const AVCodec* codec, Dict& opts, const std::string& what) {
  LogCapture cap(ctx);
  int ret = avcodec_open2(ctx, codec, opts.addr());
  std::vector<std::string> invalid;
  for (const auto& line : cap.lines()) {
    if (line.find("Invalid option") != std::string::npos || line.find("Unknown option") != std::string::npos) {
      invalid.push_back(trim(line));
    }
  }
  if (ret < 0) {
    EngineError e = fromAvError(ret, "video_encoder_open", what);
    for (const auto& l : cap.lines()) {
      if (e.detail.size() < 1500) e.detail += "\n" + l;
    }
    throw EngineException(e);
  }
  if (opts.count() > 0) {
    std::string unknown;
    const AVDictionaryEntry* e = nullptr;
    while ((e = av_dict_iterate(opts.get(), e))) {
      if (!unknown.empty()) unknown += ", ";
      unknown += std::string(e->key) + "=" + e->value;
    }
    throwError(ErrorCategory::InvalidEncoderOption, "video_encoder_open",
               "Unknown encoder option: " + unknown + ". The job was not started.", what);
  }
  if (!invalid.empty()) {
    std::string msg;
    for (const auto& l : invalid) msg += (msg.empty() ? "" : "; ") + l;
    throwError(ErrorCategory::InvalidEncoderOption, "video_encoder_open",
               "The encoder rejected an option: " + msg + ". The job was not started.", what);
  }
}

std::string validateEncoderOptions(const std::string& encoderName,
                                   const std::vector<std::pair<std::string, std::string>>& options) {
  const AVCodec* codec = avcodec_find_encoder_by_name(encoderName.c_str());
  if (!codec) return "Encoder " + encoderName + " is not available in this build.";
  if (encoderName == "libkvazaar" || encoderName == "libsvtav1") {
    // Parameters for these are passed through to the library's own parser and
    // checked when the encoder opens (see openEncoder); do a syntax check here.
    for (const auto& [k, v] : options) {
      if (k.empty() || k.find_first_of(",:= ") != std::string::npos) return "Invalid option name: " + k;
      if (v.find_first_of(",:") != std::string::npos) return "Invalid value for " + k + ": " + v;
    }
    return "";
  }
  CodecCtxPtr ctx(avcodec_alloc_context3(codec));
  if (!ctx) return "Out of memory";
  for (const auto& [k, v] : options) {
    int ret = av_opt_set(ctx.get(), k.c_str(), v.c_str(), AV_OPT_SEARCH_CHILDREN);
    if (ret == AVERROR_OPTION_NOT_FOUND) return "Unknown encoder option: " + k + "=" + v;
    if (ret < 0) return "Invalid value for " + k + ": " + v + " (" + avErrorString(ret) + ")";
  }
  return "";
}

std::string describeEncoderOptions(const std::string& encoderName) {
  Json out = Json::array();
  const AVCodec* codec = avcodec_find_encoder_by_name(encoderName.c_str());
  if (!codec || !codec->priv_class) return out.dump();
  const AVOption* o = nullptr;
  const AVClass* cls = codec->priv_class;
  while ((o = av_opt_next(&cls, o))) {
    if (o->type == AV_OPT_TYPE_CONST) continue;
    Json j;
    j["name"] = o->name;
    j["help"] = o->help ? o->help : "";
    const char* type = "string";
    switch (o->type) {
      case AV_OPT_TYPE_INT:
      case AV_OPT_TYPE_INT64:
      case AV_OPT_TYPE_UINT64: type = "int"; break;
      case AV_OPT_TYPE_FLOAT:
      case AV_OPT_TYPE_DOUBLE: type = "float"; break;
      case AV_OPT_TYPE_BOOL: type = "bool"; break;
      case AV_OPT_TYPE_DICT: type = "dict"; break;
      default: break;
    }
    j["type"] = type;
    if (o->type == AV_OPT_TYPE_INT || o->type == AV_OPT_TYPE_INT64 || o->type == AV_OPT_TYPE_BOOL) {
      j["default"] = o->default_val.i64;
      j["min"] = o->min;
      j["max"] = o->max;
    } else if (o->type == AV_OPT_TYPE_FLOAT || o->type == AV_OPT_TYPE_DOUBLE) {
      j["default"] = o->default_val.dbl;
      j["min"] = o->min;
      j["max"] = o->max;
    }
    if (o->unit) {
      Json consts = Json::array();
      const AVOption* c = nullptr;
      const AVClass* cls2 = codec->priv_class;
      while ((c = av_opt_next(&cls2, c))) {
        if (c->type == AV_OPT_TYPE_CONST && c->unit && std::string(c->unit) == o->unit) consts.push_back(c->name);
      }
      j["choices"] = consts;
    }
    out.push_back(j);
  }
  (void)TAG;
  return out.dump();
}

}  // namespace vc
