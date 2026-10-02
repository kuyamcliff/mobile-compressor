// Pure-logic unit tests (no media files).
#include <climits>

#include "TestFramework.h"
#include "core/Errors.h"
#include "core/Util.h"
#include "job/JobManager.h"
#include "pipeline/DtsGenerator.h"
#include "pipeline/FilterBuilder.h"
#include "pipeline/Progress.h"
#include "plan/Plan.h"

using namespace vc;

namespace {
Json basePlan() {
  return Json::parse(R"({
    "container": {"format": "mp4"},
    "video": {"sourceStreamIndex": 0, "mode": "transcode", "pipeline": "software", "codec": "hevc",
              "encoder": "libkvazaar", "width": 1280, "height": 720,
              "rateControl": {"mode": "crf", "quality": 28}},
    "audio": [{"sourceStreamIndex": 1, "mode": "encode", "codec": "aac", "encoder": "aac", "bitrateKbps": 128}]
  })");
}

template <typename F>
bool throwsCategory(F f, ErrorCategory c) {
  try {
    f();
  } catch (const EngineException& e) {
    return e.error().category == c;
  }
  return false;
}
}  // namespace

VC_TEST(Plan, ParsesValidPlan) {
  Plan p = parsePlan(basePlan());
  EXPECT_TRUE(p.video.has_value());
  EXPECT_EQ(p.video->width, 1280);
  EXPECT_EQ(p.video->height, 720);
  EXPECT_EQ(p.audio.size(), 1u);
  EXPECT_EQ(p.audio[0].bitrateKbps, 128);
  EXPECT_TRUE(p.video->rc.mode == RateMode::Crf);
}

VC_TEST(Plan, RejectsOddDimensions) {
  Json j = basePlan();
  j["video"]["width"] = 1279;
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
}

VC_TEST(Plan, RejectsUnknownContainerAndRateMode) {
  Json j = basePlan();
  j["container"]["format"] = "exe";
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
  j = basePlan();
  j["video"]["rateControl"]["mode"] = "magic";
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
}

VC_TEST(Plan, BitrateModesNeedBitrate) {
  Json j = basePlan();
  j["video"]["rateControl"] = {{"mode", "abr"}};
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
  j["video"]["rateControl"]["bitrateKbps"] = 2500;
  EXPECT_EQ(parsePlan(j).video->rc.bitrateKbps, 2500);
}

VC_TEST(Plan, RejectsEmptySelectionAndBadSegment) {
  Json j = Json::parse(R"({"container":{"format":"mkv"}})");
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
  j = basePlan();
  j["segment"] = {{"startUs", 0}, {"durationUs", -5}};
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
}

VC_TEST(Plan, WrongTypesAreRejectedNotCrashing) {
  Json j = basePlan();
  j["video"]["width"] = "wide";
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
  j = basePlan();
  j["video"]["options"] = {{"preset", 5}};
  EXPECT_TRUE(throwsCategory([&] { parsePlan(j); }, ErrorCategory::InvalidConfiguration));
}

VC_TEST(FilterBuilder, ScaleAndFormat) {
  Plan p = parsePlan(basePlan());
  VideoSourceProps s;
  s.width = 1920;
  s.height = 1080;
  s.fps = 30;
  std::string f = buildVideoFilter(*p.video, s, "yuv420p");
  EXPECT_CONTAINS(f, "scale=w=1280:h=720");
  EXPECT_CONTAINS(f, "format=pix_fmts=yuv420p");
  EXPECT_TRUE(f.find("transpose") == std::string::npos);
  EXPECT_TRUE(f.find("fps=") == std::string::npos);
}

VC_TEST(FilterBuilder, PortraitRotationIsApplied) {
  Json j = basePlan();
  j["video"]["width"] = 720;
  j["video"]["height"] = 1280;
  Plan p = parsePlan(j);
  VideoSourceProps s;
  s.width = 1920;  // coded landscape, displayed portrait
  s.height = 1080;
  s.rotation = 90;
  int w = 0, h = 0;
  orientedSize(*p.video, s, w, h);
  EXPECT_EQ(w, 1080);
  EXPECT_EQ(h, 1920);
  std::string f = buildVideoFilter(*p.video, s, "yuv420p");
  EXPECT_CONTAINS(f, "transpose=clock");
  EXPECT_CONTAINS(f, "scale=w=720:h=1280");
}

VC_TEST(FilterBuilder, CropAndFpsOrder) {
  Json j = basePlan();
  j["video"]["crop"] = {{"top", 140}, {"bottom", 140}, {"left", 0}, {"right", 0}};
  j["video"]["fpsMode"] = "cfr";
  j["video"]["fpsNum"] = 30;
  j["video"]["fpsDen"] = 1;
  Plan p = parsePlan(j);
  VideoSourceProps s;
  s.width = 1920;
  s.height = 1080;
  s.fps = 60;
  std::string f = buildVideoFilter(*p.video, s, "yuv420p");
  EXPECT_CONTAINS(f, "crop=w=1920:h=800:x=0:y=140");
  EXPECT_TRUE(f.find("fps=fps=30/1") < f.find("crop="));
}

VC_TEST(FilterBuilder, CropRemovingEverythingFails) {
  Json j = basePlan();
  j["video"]["crop"] = {{"top", 600}, {"bottom", 600}};
  Plan p = parsePlan(j);
  VideoSourceProps s;
  s.width = 1920;
  s.height = 1080;
  EXPECT_TRUE(throwsCategory([&] { buildVideoFilter(*p.video, s, "yuv420p"); }, ErrorCategory::InvalidConfiguration));
}

VC_TEST(FilterBuilder, PeakFpsOnlyWhenAboveLimit) {
  Json j = basePlan();
  j["video"]["fpsMode"] = "peak";
  j["video"]["fpsNum"] = 30;
  Plan p = parsePlan(j);
  VideoSourceProps s;
  s.width = 1920;
  s.height = 1080;
  s.fps = 24;
  EXPECT_TRUE(buildVideoFilter(*p.video, s, "yuv420p").find("fps=") == std::string::npos);
  s.fps = 59.94;
  EXPECT_CONTAINS(buildVideoFilter(*p.video, s, "yuv420p"), "fps=fps=30/1");
}

VC_TEST(FilterBuilder, HdrTonemapChain) {
  Json j = basePlan();
  j["video"]["hdrMode"] = "tonemap";
  Plan p = parsePlan(j);
  VideoSourceProps s;
  s.width = 3840;
  s.height = 2160;
  s.hdr = "hdr10";
  std::string f = buildVideoFilter(*p.video, s, "yuv420p");
  EXPECT_CONTAINS(f, "tonemap=tonemap=hable");
  EXPECT_CONTAINS(f, "tin=smpte2084");
  EXPECT_CONTAINS(f, "color_trc=bt709");
  // SDR source: no tone mapping even if requested.
  s.hdr = "sdr";
  EXPECT_TRUE(buildVideoFilter(*p.video, s, "yuv420p").find("tonemap") == std::string::npos);
}

VC_TEST(FilterBuilder, EscapesPaths) {
  EXPECT_EQ(escapeFilterValue("/data/a.ass"), std::string("'/data/a.ass'"));
  std::string e = escapeFilterValue("/x/it's:a,b.ass");
  EXPECT_CONTAINS(e, "\\:");
  EXPECT_TRUE(e.front() == '\'' && e.back() == '\'');
}

VC_TEST(FilterBuilder, AudioChain) {
  std::string a = buildAudioFilter(48000, "stereo", "fltp", 0);
  EXPECT_CONTAINS(a, "aresample=48000:async=1");
  EXPECT_CONTAINS(a, "channel_layouts=stereo");
  EXPECT_TRUE(a.find("volume") == std::string::npos);
  EXPECT_CONTAINS(buildAudioFilter(44100, "mono", "s16", -3), "volume=volume=-3dB");
}

VC_TEST(Progress, RealProgressAndSmoothedEta) {
  int64_t now = 0;
  ProgressTracker t([&] { return now; });
  t.begin(100 * kUsPerSec, 30, 1);
  auto s0 = t.sample();
  EXPECT_NEAR(s0.progress, 0, 1e-9);
  EXPECT_EQ(s0.etaUs, -1);  // no speed yet: unknown, not invented
  // 2x realtime for 10 s of wall time.
  for (int i = 1; i <= 10; ++i) {
    now = i * kUsPerSec;
    for (int k = 0; k < 60; ++k) t.onEncodedVideoFrame(((i - 1) * 60 + k + 1) * kUsPerSec / 30);
    t.sample();
  }
  auto s = t.sample();
  EXPECT_NEAR(s.progress, 0.2, 0.01);
  EXPECT_NEAR(s.speed, 2.0, 0.05);
  EXPECT_NEAR(s.etaUs / 1e6, 40, 2);  // 80 s of media left at 2x
  EXPECT_NEAR(s.averageFps, 60, 1);
  // A single slow interval must not make the ETA jump wildly.
  now += kUsPerSec;  // no new media in this second
  auto slow = t.sample();
  EXPECT_TRUE(slow.etaUs / 1e6 < 60);
}

VC_TEST(Progress, PauseExcludedFromElapsed) {
  int64_t now = 0;
  ProgressTracker t([&] { return now; });
  t.begin(10 * kUsPerSec, 30, 1);
  now = 2 * kUsPerSec;
  t.setPaused(true);
  now = 12 * kUsPerSec;
  t.setPaused(false);
  now = 13 * kUsPerSec;
  EXPECT_EQ(t.sample().elapsedUs, 3 * kUsPerSec);
}

VC_TEST(Progress, TwoPassProgressSpansBothPasses) {
  int64_t now = 0;
  ProgressTracker t([&] { return now; });
  t.begin(10 * kUsPerSec, 30, 2);
  t.onMediaTime(10 * kUsPerSec);
  EXPECT_NEAR(t.sample().progress, 0.5, 1e-6);
  t.setPass(1);
  t.onMediaTime(5 * kUsPerSec);
  EXPECT_NEAR(t.sample().progress, 0.75, 1e-6);
}

VC_TEST(Progress, UnknownDurationIsIndeterminate) {
  int64_t now = 0;
  ProgressTracker t([&] { return now; });
  t.begin(0, 30, 1);
  t.onMediaTime(5 * kUsPerSec);
  EXPECT_NEAR(t.sample().progress, -1, 1e-9);
}

VC_TEST(Dts, NoReorderKeepsPts) {
  DtsGenerator g(4);
  for (int i = 0; i < 10; ++i) {
    PacketPtr p = makePacket();
    p->pts = i * 100;
    g.push(std::move(p));
  }
  g.markEndOfStream();
  int n = 0;
  while (PacketPtr p = g.pop()) {
    EXPECT_EQ(p->dts, p->pts);
    ++n;
  }
  EXPECT_EQ(n, 10);
  EXPECT_EQ(g.reorderDelay(), 0);
}

VC_TEST(Dts, BFramesProduceValidDts) {
  // Decode order I0 P3 B1 B2 P6 B4 B5 ... (pts in frame units * 100)
  std::vector<int64_t> order = {0, 3, 1, 2, 6, 4, 5, 9, 7, 8, 12, 10, 11};
  DtsGenerator g(8);
  for (auto v : order) {
    PacketPtr p = makePacket();
    p->pts = v * 100;
    g.push(std::move(p));
  }
  g.markEndOfStream();
  int64_t last = INT64_MIN;
  int n = 0;
  while (PacketPtr p = g.pop()) {
    EXPECT_TRUE(p->dts <= p->pts);
    EXPECT_TRUE(p->dts > last);
    last = p->dts;
    ++n;
  }
  EXPECT_EQ(n, static_cast<int>(order.size()));
  EXPECT_EQ(g.reorderDelay(), 1);
}

VC_TEST(StateMachine, RejectsInvalidSequences) {
  EXPECT_TRUE(canTransition(JobState::Created, JobState::Running));
  EXPECT_TRUE(canTransition(JobState::Running, JobState::Paused));
  EXPECT_TRUE(canTransition(JobState::Paused, JobState::Running));
  EXPECT_FALSE(canTransition(JobState::Completed, JobState::Paused));
  EXPECT_FALSE(canTransition(JobState::Completed, JobState::Running));
  EXPECT_FALSE(canTransition(JobState::Cancelled, JobState::Running));
  EXPECT_FALSE(canTransition(JobState::Failed, JobState::Completed));
  EXPECT_FALSE(canTransition(JobState::Created, JobState::Paused));
  EXPECT_TRUE(canTransition(JobState::Validating, JobState::Failed));
}

VC_TEST(Errors, TranslatesCommonFfmpegErrors) {
  EXPECT_TRUE(fromAvError(AVERROR(ENOSPC), "mux", "x").category == ErrorCategory::InsufficientStorage);
  EXPECT_TRUE(fromAvError(AVERROR_INVALIDDATA, "probe", "x").category == ErrorCategory::InvalidInput);
  EXPECT_TRUE(fromAvError(AVERROR(EACCES), "open", "x").category == ErrorCategory::PermissionDenied);
  EXPECT_TRUE(fromAvError(AVERROR_ENCODER_NOT_FOUND, "enc", "x").category == ErrorCategory::UnsupportedCodec);
  EngineError e = fromAvError(AVERROR(EINVAL), "video_encoder_open", "HEVC encoder");
  EXPECT_FALSE(e.suggestions.empty());
  EXPECT_CONTAINS(e.toJson().dump(), "invalid argument");
}

VC_TEST(Util, TimestampConversionDoesNotOverflow) {
  // 10 hours at a 90 kHz time base, and a 1/1000000000 time base.
  int64_t ts = 10LL * 3600 * 90000;
  EXPECT_EQ(toUs(ts, AVRational{1, 90000}), 10LL * 3600 * kUsPerSec);
  int64_t ns = 5LL * 3600 * 1000000000LL;
  EXPECT_EQ(toUs(ns, AVRational{1, 1000000000}), 5LL * 3600 * kUsPerSec);
  EXPECT_EQ(fromUs(toUs(123456789, AVRational{1, 48000}), AVRational{1, 48000}), 123456789);
  EXPECT_EQ(toUs(AV_NOPTS_VALUE, AVRational{1, 1000}), AV_NOPTS_VALUE);
}

VC_TEST(Util, Utf8TruncationAndAlignment) {
  std::string s = "h\xC3\xA9llo";  // héllo
  EXPECT_EQ(truncateUtf8(s, 2), std::string("h"));
  EXPECT_EQ(truncateUtf8(s, 3), std::string("h\xC3\xA9"));
  EXPECT_EQ(alignDown(1081, 8), 1080);
  EXPECT_EQ(alignDown(5, 8), 8);
  EXPECT_EQ(alignDown(721, 2), 720);
}
