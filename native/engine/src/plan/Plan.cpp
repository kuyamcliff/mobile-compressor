#include "plan/Plan.h"

#include <algorithm>

#include "core/Errors.h"

namespace vc {

namespace {
[[noreturn]] void bad(const std::string& what) {
  throwError(ErrorCategory::InvalidConfiguration, "plan", "The compression configuration is invalid: " + what);
}

template <typename T>
T get(const Json& j, const char* key, T def) {
  auto it = j.find(key);
  if (it == j.end() || it->is_null()) return def;
  try {
    return it->get<T>();
  } catch (const std::exception&) {
    bad(std::string("field '") + key + "' has the wrong type");
  }
}

RateMode parseRateMode(const std::string& s) {
  if (s == "crf") return RateMode::Crf;
  if (s == "cqp") return RateMode::Cqp;
  if (s == "abr") return RateMode::Abr;
  if (s == "cbr") return RateMode::Cbr;
  if (s == "vbr") return RateMode::Vbr;
  if (s == "cq") return RateMode::Cq;
  if (s == "lossless") return RateMode::Lossless;
  bad("unknown rate-control mode '" + s + "'");
}

Pipeline parsePipeline(const std::string& s) {
  if (s == "software") return Pipeline::Software;
  if (s == "hardware") return Pipeline::Hardware;
  if (s == "hybrid") return Pipeline::Hybrid;
  bad("unknown pipeline '" + s + "'");
}

bool validDimension(int v) { return v >= 16 && v <= 16384 && v % 2 == 0; }
}  // namespace

const char* pipelineName(Pipeline p) {
  switch (p) {
    case Pipeline::Software: return "software";
    case Pipeline::Hardware: return "hardware";
    case Pipeline::Hybrid: return "hybrid";
  }
  return "software";
}

const char* rateModeName(RateMode m) {
  switch (m) {
    case RateMode::Crf: return "crf";
    case RateMode::Cqp: return "cqp";
    case RateMode::Abr: return "abr";
    case RateMode::Cbr: return "cbr";
    case RateMode::Vbr: return "vbr";
    case RateMode::Cq: return "cq";
    case RateMode::Lossless: return "lossless";
  }
  return "crf";
}

Plan parsePlan(const Json& j) {
  if (!j.is_object()) bad("not an object");
  Plan p;
  p.jobId = get<std::string>(j, "jobId", "");
  p.progressIntervalMs = std::clamp(get<int>(j, "progressIntervalMs", 250), 50, 5000);

  const Json& c = j.value("container", Json::object());
  p.container.format = get<std::string>(c, "format", "mp4");
  p.container.fastStart = get<bool>(c, "fastStart", true);
  p.container.fragmented = get<bool>(c, "fragmented", false);
  static const std::vector<std::string> kMuxers = {"mp4", "mov", "matroska", "webm", "3gp", "mpegts", "ipod"};
  if (std::find(kMuxers.begin(), kMuxers.end(), p.container.format) == kMuxers.end()) {
    bad("unsupported container '" + p.container.format + "'");
  }
  p.copyAttachments = get<bool>(j, "copyAttachments", true);

  if (j.contains("segment") && j["segment"].is_object()) {
    Segment s;
    s.startUs = get<int64_t>(j["segment"], "startUs", 0);
    s.durationUs = get<int64_t>(j["segment"], "durationUs", 0);
    if (s.startUs < 0 || s.durationUs <= 0 || s.durationUs > 600 * kUsPerSec) bad("invalid preview segment");
    p.segment = s;
  }

  if (j.contains("video") && j["video"].is_object()) {
    const Json& v = j["video"];
    VideoPlan vp;
    vp.sourceStreamIndex = get<int>(v, "sourceStreamIndex", -1);
    std::string mode = get<std::string>(v, "mode", "transcode");
    vp.mode = mode == "copy" ? VideoMode::Copy : mode == "drop" ? VideoMode::Drop : VideoMode::Transcode;
    vp.pipeline = parsePipeline(get<std::string>(v, "pipeline", "software"));
    vp.codec = get<std::string>(v, "codec", "");
    vp.encoder = get<std::string>(v, "encoder", "");
    vp.mime = get<std::string>(v, "mime", "");
    vp.hwDecoder = get<std::string>(v, "hwDecoder", "");
    vp.width = get<int>(v, "width", 0);
    vp.height = get<int>(v, "height", 0);
    if (v.contains("crop")) {
      vp.crop.top = get<int>(v["crop"], "top", 0);
      vp.crop.bottom = get<int>(v["crop"], "bottom", 0);
      vp.crop.left = get<int>(v["crop"], "left", 0);
      vp.crop.right = get<int>(v["crop"], "right", 0);
      if (vp.crop.top < 0 || vp.crop.bottom < 0 || vp.crop.left < 0 || vp.crop.right < 0) bad("negative crop");
    }
    vp.scaler = get<std::string>(v, "scaler", "bicubic");
    static const std::vector<std::string> kScalers = {"fast_bilinear", "bilinear", "bicubic", "lanczos", "spline",
                                                      "area", "neighbor", "gauss", "sinc", "bicublin"};
    if (std::find(kScalers.begin(), kScalers.end(), vp.scaler) == kScalers.end()) bad("unknown scaler");
    std::string fps = get<std::string>(v, "fpsMode", "source");
    vp.fpsMode = fps == "cfr" ? FpsMode::Cfr : fps == "peak" ? FpsMode::PeakLimit : FpsMode::Source;
    vp.fpsNum = get<int>(v, "fpsNum", 0);
    vp.fpsDen = get<int>(v, "fpsDen", 1);
    if (vp.fpsMode != FpsMode::Source && (vp.fpsNum <= 0 || vp.fpsDen <= 0 || vp.fpsNum / vp.fpsDen > 480)) {
      bad("invalid frame rate");
    }
    vp.pixFmt = get<std::string>(v, "pixFmt", "yuv420p");
    vp.bitDepth = get<int>(v, "bitDepth", 8);
    if (v.contains("rateControl")) {
      const Json& r = v["rateControl"];
      vp.rc.mode = parseRateMode(get<std::string>(r, "mode", "crf"));
      vp.rc.quality = get<double>(r, "quality", 28);
      vp.rc.bitrateKbps = get<int>(r, "bitrateKbps", 0);
      vp.rc.maxBitrateKbps = get<int>(r, "maxBitrateKbps", 0);
      vp.rc.minBitrateKbps = get<int>(r, "minBitrateKbps", 0);
      vp.rc.bufferKbits = get<int>(r, "bufferKbits", 0);
      vp.rc.twoPass = get<bool>(r, "twoPass", false);
      bool needsBitrate = vp.rc.mode == RateMode::Abr || vp.rc.mode == RateMode::Cbr || vp.rc.mode == RateMode::Vbr;
      if (needsBitrate && (vp.rc.bitrateKbps <= 0 || vp.rc.bitrateKbps > 2000000)) bad("invalid video bitrate");
    }
    vp.keyIntSeconds = get<double>(v, "keyIntSeconds", 0);
    vp.bFrames = get<int>(v, "bFrames", -1);
    vp.refFrames = get<int>(v, "refFrames", -1);
    vp.profile = get<std::string>(v, "profile", "");
    vp.level = get<std::string>(v, "level", "");
    vp.preset = get<std::string>(v, "preset", "");
    vp.tune = get<std::string>(v, "tune", "");
    if (v.contains("options") && v["options"].is_object()) {
      for (auto it = v["options"].begin(); it != v["options"].end(); ++it) {
        if (!it.value().is_string()) bad("encoder option values must be strings");
        vp.options.emplace_back(it.key(), it.value().get<std::string>());
      }
    }
    vp.threads = std::clamp(get<int>(v, "threads", 0), 0, 64);
    if (v.contains("filters")) {
      const Json& f = v["filters"];
      vp.filters.deinterlace = get<std::string>(f, "deinterlace", "");
      vp.filters.deinterlaceAuto = get<bool>(f, "deinterlaceAuto", false);
      vp.filters.detelecine = get<bool>(f, "detelecine", false);
      vp.filters.deblock = get<std::string>(f, "deblock", "");
      if (f.contains("denoise")) {
        vp.filters.denoise.algo = get<std::string>(f["denoise"], "algo", "");
        vp.filters.denoise.strength = get<std::string>(f["denoise"], "strength", "light");
      }
      if (f.contains("sharpen")) {
        vp.filters.sharpen.algo = get<std::string>(f["sharpen"], "algo", "");
        vp.filters.sharpen.strength = get<std::string>(f["sharpen"], "strength", "light");
      }
      vp.filters.deband = get<bool>(f, "deband", false);
      vp.filters.grayscale = get<bool>(f, "grayscale", false);
      vp.filters.rotate = get<int>(f, "rotate", 0);
      if (vp.filters.rotate % 90 != 0) bad("rotation must be a multiple of 90");
      vp.filters.rotate = ((vp.filters.rotate % 360) + 360) % 360;
      vp.filters.hflip = get<bool>(f, "hflip", false);
      vp.filters.vflip = get<bool>(f, "vflip", false);
    }
    vp.autorotate = get<bool>(v, "autorotate", true);
    vp.hdrMode = get<std::string>(v, "hdrMode", "preserve");
    vp.tonemap = get<std::string>(v, "tonemap", "hable");
    if (v.contains("color")) {
      vp.color.primaries = get<std::string>(v["color"], "primaries", "");
      vp.color.transfer = get<std::string>(v["color"], "transfer", "");
      vp.color.matrix = get<std::string>(v["color"], "matrix", "");
      vp.color.range = get<std::string>(v["color"], "range", "");
    }
    if (v.contains("burn") && v["burn"].is_object()) {
      SubtitleBurn b;
      b.sourceStreamIndex = get<int>(v["burn"], "sourceStreamIndex", -1);
      b.externalPath = get<std::string>(v["burn"], "externalPath", "");
      b.bitmap = get<bool>(v["burn"], "bitmap", false);
      b.fontsDir = get<std::string>(v["burn"], "fontsDir", "");
      b.fallbackFont = get<std::string>(v["burn"], "fallbackFont", "");
      if (b.sourceStreamIndex < 0 && b.externalPath.empty()) bad("subtitle burn-in without a source");
      vp.burn = b;
    }
    vp.hwColorFormat = get<int>(v, "hwColorFormat", 21);
    vp.hwBitrateMode = get<int>(v, "hwBitrateMode", 1);
    vp.hwQpMin = get<int>(v, "hwQpMin", -1);
    vp.hwQpMax = get<int>(v, "hwQpMax", -1);
    vp.hwProfile = get<int>(v, "hwProfile", -1);
    vp.hwLevel = get<int>(v, "hwLevel", -1);
    vp.hwQuality = get<int>(v, "hwQuality", -1);

    if (vp.mode == VideoMode::Transcode) {
      if (!validDimension(vp.width) || !validDimension(vp.height)) bad("output dimensions must be even and 16..16384");
      if (vp.encoder.empty()) bad("no video encoder selected");
      if (vp.pipeline != Pipeline::Software && vp.mime.empty()) bad("hardware pipeline without MIME type");
    }
    if (vp.mode != VideoMode::Drop) p.video = vp;
  }

  if (j.contains("audio") && j["audio"].is_array()) {
    for (const Json& a : j["audio"]) {
      AudioPlan ap;
      ap.sourceStreamIndex = get<int>(a, "sourceStreamIndex", -1);
      ap.copy = get<std::string>(a, "mode", "encode") == "copy";
      ap.codec = get<std::string>(a, "codec", "aac");
      ap.encoder = get<std::string>(a, "encoder", "aac");
      ap.bitrateKbps = get<int>(a, "bitrateKbps", 128);
      ap.channels = get<int>(a, "channels", 0);
      ap.sampleRate = get<int>(a, "sampleRate", 0);
      ap.language = truncateUtf8(get<std::string>(a, "language", ""), 16);
      ap.title = truncateUtf8(get<std::string>(a, "title", ""), 256);
      ap.isDefault = get<bool>(a, "default", false);
      ap.volumeDb = get<double>(a, "volumeDb", 0);
      if (ap.sourceStreamIndex < 0) bad("audio track without a source stream");
      if (!ap.copy && (ap.bitrateKbps < 0 || ap.bitrateKbps > 6144)) bad("invalid audio bitrate");
      if (ap.channels < 0 || ap.channels > 8) bad("invalid channel count");
      if (ap.sampleRate < 0 || ap.sampleRate > 192000) bad("invalid sample rate");
      p.audio.push_back(ap);
    }
  }

  if (j.contains("subtitles") && j["subtitles"].is_array()) {
    for (const Json& s : j["subtitles"]) {
      SubtitlePlan sp;
      sp.sourceStreamIndex = get<int>(s, "sourceStreamIndex", -1);
      sp.externalPath = get<std::string>(s, "externalPath", "");
      sp.copy = get<std::string>(s, "mode", "copy") == "copy";
      sp.codec = get<std::string>(s, "codec", "");
      sp.language = truncateUtf8(get<std::string>(s, "language", ""), 16);
      sp.title = truncateUtf8(get<std::string>(s, "title", ""), 256);
      sp.isDefault = get<bool>(s, "default", false);
      sp.forced = get<bool>(s, "forced", false);
      if (sp.sourceStreamIndex < 0 && sp.externalPath.empty()) bad("subtitle track without a source");
      p.subtitles.push_back(sp);
    }
  }

  p.chapterMode = get<std::string>(j, "chapterMode", "preserve");
  if (j.contains("chapters") && j["chapters"].is_array()) {
    for (const Json& c2 : j["chapters"]) {
      Chapter ch;
      ch.title = truncateUtf8(get<std::string>(c2, "title", ""), 256);
      ch.startUs = get<int64_t>(c2, "startUs", 0);
      ch.endUs = get<int64_t>(c2, "endUs", 0);
      if (ch.endUs < ch.startUs) bad("chapter ends before it starts");
      p.chapters.push_back(ch);
    }
  }
  p.metadataMode = get<std::string>(j, "metadataMode", "preserve");
  if (j.contains("metadata") && j["metadata"].is_object()) {
    for (auto it = j["metadata"].begin(); it != j["metadata"].end(); ++it) {
      if (it.value().is_string()) p.metadata[truncateUtf8(it.key(), 64)] = truncateUtf8(it.value().get<std::string>(), 1024);
    }
  }
  if (!p.video && p.audio.empty()) bad("no audio or video streams selected");
  return p;
}

Json planSummary(const Plan& p) {
  Json j;
  j["container"] = p.container.format;
  if (p.video) {
    const auto& v = *p.video;
    j["video"] = {{"mode", v.mode == VideoMode::Copy ? "copy" : "transcode"},
                  {"pipeline", pipelineName(v.pipeline)},
                  {"encoder", v.encoder},
                  {"width", v.width},
                  {"height", v.height},
                  {"rateControl", rateModeName(v.rc.mode)}};
  }
  j["audioTracks"] = p.audio.size();
  j["subtitleTracks"] = p.subtitles.size();
  return j;
}

}  // namespace vc
