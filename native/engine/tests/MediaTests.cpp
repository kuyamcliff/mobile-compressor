// End-to-end media tests. Fixture clips are synthesised with the host FFmpeg
// CLI (test tooling only, never shipped); every job runs in-process through the
// same JobManager the JNI bridge uses; outputs are verified independently with
// ffprobe.
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <mutex>
#include <random>

#include "TestFramework.h"
#include "analysis/Complexity.h"
#include "analysis/QualityMetrics.h"
#include "analysis/Validator.h"
#include "core/Errors.h"
#include "job/JobManager.h"
#include "probe/Probe.h"
#include "probe/Thumbnail.h"

using namespace vc;

namespace {

std::string workDir() {
  static std::string dir = [] {
    const char* env = std::getenv("VC_TEST_DIR");
    std::string d = env ? env : "/tmp/vcengine-tests";
    std::string cmd = "mkdir -p '" + d + "'";
    if (std::system(cmd.c_str()) != 0) std::abort();
    return d;
  }();
  return dir;
}
std::string path(const std::string& name) { return workDir() + "/" + name; }

std::string libEnv() {
  std::string bin = VC_FFMPEG_BIN;
  std::string lib = bin.substr(0, bin.rfind("/bin/")) + "/lib";
  return "LD_LIBRARY_PATH='" + lib + "' ";
}

void ffmpeg(const std::string& args) {
  std::string cmd = libEnv() + VC_FFMPEG_BIN + " -hide_banner -loglevel error -y " + args;
  if (std::system(cmd.c_str()) != 0) VC_FAIL("fixture generation failed: " << args);
}

std::string capture(const std::string& cmd) {
  std::string out;
  FILE* p = popen(cmd.c_str(), "r");
  if (!p) return out;
  char buf[4096];
  size_t n;
  while ((n = fread(buf, 1, sizeof(buf), p)) > 0) out.append(buf, n);
  pclose(p);
  return out;
}

Json ffprobe(const std::string& file) {
  std::string out = capture(libEnv() + VC_FFPROBE_BIN +
                            " -v error -print_format json -show_format -show_streams -show_chapters '" + file + "'");
  return Json::parse(out);
}

int64_t fileSize(const std::string& f) {
  struct stat st{};
  return ::stat(f.c_str(), &st) == 0 ? st.st_size : -1;
}

void writeText(const std::string& f, const std::string& s) {
  std::ofstream o(f);
  o << s;
}

// ---- fixtures (generated once per run) ----
const std::string& fixture(const std::string& name) {
  static std::map<std::string, std::string> cache;
  auto it = cache.find(name);
  if (it != cache.end()) return it->second;
  std::string f;
  if (name == "1080p60") {
    f = path("src_1080p60.mp4");
    ffmpeg("-filter_complex \"testsrc2=size=1920x1080:rate=60,format=yuv420p[v];sine=frequency=440:sample_rate=48000[a]\" "
           "-map [v] -map [a] -t 10 -c:v libopenh264 -b:v 8M -c:a aac -b:a 192k -ac 2 '" + f + "'");
  } else if (name == "portrait") {
    std::string tmp = path("tmp_land.mp4");
    ffmpeg("-filter_complex \"testsrc2=size=1920x1080:rate=30,format=yuv420p[v];sine=sample_rate=48000[a]\" "
           "-map [v] -map [a] -t 4 -c:v libopenh264 -b:v 4M -c:a aac '" + tmp + "'");
    f = path("src_portrait.mp4");
    // Phone-style: landscape coded frames with a 90-degree display matrix.
    ffmpeg("-display_rotation:v:0 -90 -i '" + tmp + "' -c copy '" + f + "'");
  } else if (name == "multitrack") {
    writeText(path("eng.srt"), "1\n00:00:01,000 --> 00:00:03,000\nHello world\n\n2\n00:00:05,000 --> 00:00:07,000\nSecond line\n");
    writeText(path("spa.srt"), "1\n00:00:01,500 --> 00:00:03,500\nHola mundo\n");
    writeText(path("chapters.txt"),
              ";FFMETADATA1\ntitle=Fixture Title\nartist=Fixture Artist\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=4000\ntitle=Opening\n"
              "[CHAPTER]\nTIMEBASE=1/1000\nSTART=4000\nEND=8000\ntitle=Ending\n");
    f = path("src_multitrack.mkv");
    ffmpeg("-filter_complex \"testsrc2=size=1280x720:rate=30,format=yuv420p[v];sine=frequency=300:sample_rate=48000[a1];"
           "sine=frequency=600:sample_rate=44100[a2]\" -i '" + path("eng.srt") + "' -i '" + path("spa.srt") + "' -i '" +
           path("chapters.txt") + "' -map [v] -map [a1] -map [a2] -map 0:s -map 1:s -map_metadata 2 -map_chapters 2 -t 8 "
           "-c:v libopenh264 -b:v 3M -c:a aac -b:a 128k -c:s srt "
           "-metadata:s:a:0 language=eng -metadata:s:a:1 language=jpn -metadata:s:s:0 language=eng "
           "-metadata:s:s:1 language=spa '" + f + "'");
  } else if (name == "vfr") {
    f = path("src_vfr.mkv");
    ffmpeg("-filter_complex \"testsrc2=size=640x360:rate=30:duration=3[a];testsrc2=size=640x360:rate=12:duration=3[b];"
           "[a][b]concat=n=2:v=1:a=0,format=yuv420p[v]\" -map [v] -fps_mode vfr -c:v libopenh264 -b:v 1M '" + f + "'");
  } else if (name == "hdr10bit") {
    f = path("src_hdr.mkv");
    ffmpeg("-filter_complex \"testsrc2=size=1280x720:rate=24,format=yuv420p10le,"
           "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]\" -map [v] -t 3 "
           "-c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 2M '" + f + "'");
  } else if (name == "static") {
    f = path("src_static.mp4");
    ffmpeg("-filter_complex \"color=c=gray:size=640x360:rate=30,format=yuv420p[v]\" -map [v] -t 6 -c:v libopenh264 -b:v 1M '" + f + "'");
  } else if (name == "tsoffset") {
    f = path("src_offset.ts");
    ffmpeg("-filter_complex \"testsrc2=size=640x360:rate=25,format=yuv420p[v];sine=sample_rate=48000[a]\" -map [v] -map [a] "
           "-t 5 -c:v libopenh264 -b:v 1M -c:a aac -output_ts_offset 36000 -f mpegts '" + f + "'");
  } else if (name == "corrupt") {
    std::string good = fixture("1080p60");
    f = path("src_corrupt.mp4");
    std::ifstream in(good, std::ios::binary);
    std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::mt19937 rng(42);
    for (size_t i = data.size() / 4; i < data.size() * 3 / 4; i += 997) data[i] = static_cast<char>(rng());
    std::ofstream(f, std::ios::binary) << data;
  } else if (name == "truncated") {
    std::string good = fixture("multitrack");
    f = path("src_truncated.mkv");
    std::ifstream in(good, std::ios::binary);
    std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::ofstream(f, std::ios::binary) << data.substr(0, data.size() * 6 / 10);
  } else if (name == "garbage") {
    f = path("src_garbage.mp4");
    std::mt19937 rng(7);
    std::string data(200000, '\0');
    for (auto& c : data) c = static_cast<char>(rng());
    std::ofstream(f, std::ios::binary) << data;
  } else if (name == "empty") {
    f = path("src_empty.mp4");
    std::ofstream(f, std::ios::binary).close();
  } else {
    VC_FAIL("unknown fixture " << name);
  }
  return cache[name] = f;
}

struct JobOutcome {
  std::string state;
  Json failure;
  Json encodeStats;
  Json validation;
  std::vector<std::string> events;
  int progressEvents = 0;
  double lastProgress = -1;
  bool progressMonotonic = true;
};

enum class Action { None, CancelMidway, PauseResume };

JobOutcome runJob(const Json& plan, const std::string& in, const std::string& out, Action action = Action::None) {
  int inFd = ::open(in.c_str(), O_RDONLY | O_CLOEXEC);
  int outFd = ::open(out.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (inFd < 0 || outFd < 0) VC_FAIL("cannot open files");
  JobOutcome o;
  std::mutex mu;
  std::condition_variable cv;
  auto sink = [&](const char* type, const Json& p) {
    std::lock_guard<std::mutex> l(mu);
    std::string t = type;
    o.events.push_back(t);
    if (t == "PROGRESS") {
      ++o.progressEvents;
      double pr = p.value("progress", -1.0);
      if (pr >= 0) {
        if (pr + 1e-9 < o.lastProgress) o.progressMonotonic = false;
        o.lastProgress = pr;
      }
    }
    if (t == "FAILED") o.failure = p;
    if (t == "ENCODE_COMPLETE") o.encodeStats = p;
    if (t == "VALIDATION_COMPLETE") o.validation = p;
    cv.notify_all();
  };
  JobManager mgr;
  int64_t id = 0;
  try {
    id = mgr.create(plan.dump(), inFd, outFd, sink);
  } catch (const EngineException& e) {
    ::close(inFd);
    ::close(outFd);
    o.state = "rejected";
    o.failure = e.error().toJson();
    return o;
  }
  ::close(inFd);
  ::close(outFd);  // the job holds its own duplicates
  auto job = mgr.get(id);
  job->start();
  if (action == Action::CancelMidway) {
    std::unique_lock<std::mutex> l(mu);
    cv.wait_for(l, std::chrono::seconds(30), [&] { return o.lastProgress > 0.3 || isTerminal(job->state()); });
    l.unlock();
    job->cancel();
  } else if (action == Action::PauseResume) {
    std::unique_lock<std::mutex> l(mu);
    cv.wait_for(l, std::chrono::seconds(30), [&] { return o.lastProgress > 0.2 || isTerminal(job->state()); });
    l.unlock();
    EXPECT_TRUE(job->pause());
    EXPECT_FALSE(job->pause());  // already paused
    double before;
    {
      std::lock_guard<std::mutex> g(mu);
      before = o.lastProgress;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(800));
    {
      std::lock_guard<std::mutex> g(mu);
      // Paused means no progress is made (allow one in-flight packet).
      EXPECT_TRUE(o.lastProgress - before < 0.05);
    }
    EXPECT_TRUE(job->state() == JobState::Paused);
    EXPECT_TRUE(job->resume());
  }
  job->join();
  o.state = jobStateName(job->state());
  mgr.destroy(id);
  return o;
}

Json basePlan(const std::string& container = "mp4") {
  return Json{{"container", {{"format", container}, {"fastStart", true}}}};
}

Json videoPlan(const std::string& codec, const std::string& encoder, int w, int h) {
  return Json{{"sourceStreamIndex", 0}, {"mode", "transcode"},  {"pipeline", "software"},
              {"codec", codec},         {"encoder", encoder},   {"width", w},
              {"height", h},            {"rateControl", {{"mode", "crf"}, {"quality", 30}}}};
}

Json aacTrack(int src, int kbps = 128) {
  return Json{{"sourceStreamIndex", src}, {"mode", "encode"}, {"codec", "aac"}, {"encoder", "aac"}, {"bitrateKbps", kbps}};
}

const Json* streamOfType(const Json& probe, const std::string& type, int nth = 0) {
  for (const auto& s : probe["streams"]) {
    if (s["codec_type"] == type && nth-- == 0) return &s;
  }
  return nullptr;
}

double numField(const Json& j, const char* key) {
  if (!j.contains(key)) return 0;
  if (j[key].is_string()) return std::atof(j[key].get<std::string>().c_str());
  return j[key].get<double>();
}

double rate(const std::string& r) {
  auto slash = r.find('/');
  if (slash == std::string::npos) return std::atof(r.c_str());
  double d = std::atof(r.substr(slash + 1).c_str());
  return d > 0 ? std::atof(r.substr(0, slash).c_str()) / d : 0;
}

void expectSync(const Json& pr, double tolSec = 0.1) {
  const Json* v = streamOfType(pr, "video");
  const Json* a = streamOfType(pr, "audio");
  if (!v || !a) return;
  EXPECT_NEAR(numField(*v, "start_time"), numField(*a, "start_time"), tolSec);
  EXPECT_NEAR(numField(*v, "duration"), numField(*a, "duration"), tolSec + 0.05);
}

}  // namespace

// ---------------------------------------------------------------------------

VC_TEST(Media, ProbeDescribesSource) {
  int fd = ::open(fixture("multitrack").c_str(), O_RDONLY);
  Json info = probeSource(fd, "multitrack.mkv", nullptr);
  ::close(fd);
  EXPECT_EQ(info["streams"].size(), 5u);
  EXPECT_EQ(info["format"]["chapters"].size(), 2u);
  EXPECT_EQ(info["format"]["tags"]["title"].get<std::string>(), std::string("Fixture Title"));
  EXPECT_EQ(info["streams"][1]["language"].get<std::string>(), std::string("eng"));
  EXPECT_EQ(info["streams"][2]["language"].get<std::string>(), std::string("jpn"));
  EXPECT_EQ(info["streams"][2]["audio"]["sampleRate"].get<int>(), 44100);
  EXPECT_EQ(info["streams"][3]["type"].get<std::string>(), std::string("subtitle"));
  EXPECT_FALSE(info["streams"][3]["subtitle"]["bitmap"].get<bool>());
  EXPECT_FALSE(info["streams"][0]["timing"]["vfr"].get<bool>());
}

VC_TEST(Media, ProbeDetectsRotationVfrAndHdr) {
  int fd = ::open(fixture("portrait").c_str(), O_RDONLY);
  Json p = probeSource(fd, "p", nullptr);
  ::close(fd);
  EXPECT_EQ(p["streams"][0]["video"]["rotation"].get<int>(), 90);
  EXPECT_EQ(p["streams"][0]["video"]["displayWidth"].get<int>(), 1080);
  EXPECT_EQ(p["streams"][0]["video"]["displayHeight"].get<int>(), 1920);

  fd = ::open(fixture("vfr").c_str(), O_RDONLY);
  Json v = probeSource(fd, "v", nullptr);
  ::close(fd);
  EXPECT_TRUE(v["streams"][0]["timing"]["vfr"].get<bool>());

  fd = ::open(fixture("hdr10bit").c_str(), O_RDONLY);
  Json h = probeSource(fd, "h", nullptr);
  ::close(fd);
  EXPECT_EQ(h["streams"][0]["video"]["bitDepth"].get<int>(), 10);
  EXPECT_EQ(h["streams"][0]["video"]["hdr"].get<std::string>(), std::string("pq"));
}

VC_TEST(Media, ProbeRejectsBadInputs) {
  for (const char* name : {"empty", "garbage"}) {
    int fd = ::open(fixture(name).c_str(), O_RDONLY);
    bool threw = false;
    try {
      probeSource(fd, name, nullptr);
    } catch (const EngineException& e) {
      threw = true;
      EXPECT_TRUE(e.error().category == ErrorCategory::InvalidInput);
      EXPECT_CONTAINS(e.error().message, "Unable to read video");
    }
    ::close(fd);
    EXPECT_TRUE(threw);
  }
}

VC_TEST(Media, AcceptanceBasicHevc720p30) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("hevc", "libkvazaar", 1280, 720);
  plan["video"]["fpsMode"] = "cfr";
  plan["video"]["fpsNum"] = 30;
  plan["video"]["fpsDen"] = 1;
  plan["video"]["preset"] = "ultrafast";
  plan["video"]["keyIntSeconds"] = 2;
  plan["audio"] = Json::array({aacTrack(1, 128)});
  plan["validate"] = {{"container", "mp4"}, {"videoCodec", "hevc"}, {"width", 1280}, {"height", 720},
                      {"durationUs", 10000000}, {"audioTracks", 1}};
  std::string out = path("out_basic.mp4");
  JobOutcome o = runJob(plan, fixture("1080p60"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  EXPECT_TRUE(o.validation.value("ok", false));
  EXPECT_TRUE(o.progressEvents > 0);
  EXPECT_TRUE(o.progressMonotonic);
  Json pr = ffprobe(out);
  const Json* v = streamOfType(pr, "video");
  EXPECT_EQ((*v)["codec_name"].get<std::string>(), std::string("hevc"));
  EXPECT_EQ((*v)["codec_tag_string"].get<std::string>(), std::string("hvc1"));
  EXPECT_EQ((*v)["width"].get<int>(), 1280);
  EXPECT_NEAR(rate((*v)["avg_frame_rate"]), 30.0, 0.01);
  const Json* a = streamOfType(pr, "audio");
  EXPECT_EQ((*a)["codec_name"].get<std::string>(), std::string("aac"));
  EXPECT_NEAR(numField(*a, "bit_rate"), 128000, 20000);
  expectSync(pr);
  EXPECT_TRUE(fileSize(out) < fileSize(fixture("1080p60")));
  // Event order: ENCODE_STARTED before ENCODE_COMPLETE before VALIDATION_COMPLETE.
  auto pos = [&](const char* e) { return std::find(o.events.begin(), o.events.end(), e) - o.events.begin(); };
  EXPECT_TRUE(pos("ENCODE_STARTED") < pos("ENCODE_COMPLETE"));
  EXPECT_TRUE(pos("ENCODE_COMPLETE") < pos("VALIDATION_COMPLETE"));
}

VC_TEST(Media, H264QualityScaleIsMonotonic) {
  int64_t sizes[2];
  int qps[2] = {20, 38};
  for (int i = 0; i < 2; ++i) {
    Json plan = basePlan("mp4");
    plan["video"] = videoPlan("h264", "libopenh264", 960, 540);
    plan["video"]["rateControl"] = {{"mode", "cqp"}, {"quality", qps[i]}};
    plan["segment"] = {{"startUs", 2000000}, {"durationUs", 3000000}};
    std::string out = path("out_h264_q" + std::to_string(qps[i]) + ".mp4");
    JobOutcome o = runJob(plan, fixture("1080p60"), out);
    EXPECT_EQ(o.state, std::string("completed"));
    sizes[i] = fileSize(out);
  }
  EXPECT_TRUE(sizes[0] > sizes[1] * 2);
}

VC_TEST(Media, Vp9TwoPassHitsTargetBitrate) {
  Json plan = basePlan("webm");
  plan["video"] = videoPlan("vp9", "libvpx-vp9", 960, 540);
  plan["video"]["rateControl"] = {{"mode", "abr"}, {"bitrateKbps", 600}, {"twoPass", true}};
  plan["video"]["preset"] = "realtime";
  plan["video"]["options"] = {{"cpu-used", "8"}};
  plan["audio"] = Json::array({Json{{"sourceStreamIndex", 1}, {"mode", "encode"}, {"codec", "opus"},
                                    {"encoder", "libopus"}, {"bitrateKbps", 64}}});
  std::string out = path("out_vp9_2pass.webm");
  JobOutcome o = runJob(plan, fixture("1080p60"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  EXPECT_EQ(o.encodeStats["passes"].get<int>(), 2);
  Json pr = ffprobe(out);
  double seconds = numField(pr["format"], "duration");
  double totalKbps = fileSize(out) * 8 / seconds / 1000;
  EXPECT_NEAR(totalKbps, 664, 664 * 0.3);
  EXPECT_EQ((*streamOfType(pr, "audio"))["codec_name"].get<std::string>(), std::string("opus"));
}

VC_TEST(Media, Av1SoftwareMkvWithOpus) {
  Json plan = basePlan("matroska");
  plan["video"] = videoPlan("av1", "libsvtav1", 640, 360);
  plan["video"]["preset"] = "12";
  plan["video"]["rateControl"] = {{"mode", "crf"}, {"quality", 40}};
  plan["segment"] = {{"startUs", 0}, {"durationUs", 3000000}};
  plan["audio"] = Json::array({Json{{"sourceStreamIndex", 1}, {"mode", "encode"}, {"codec", "opus"},
                                    {"encoder", "libopus"}, {"bitrateKbps", 96}}});
  std::string out = path("out_av1.mkv");
  JobOutcome o = runJob(plan, fixture("1080p60"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  Json pr = ffprobe(out);
  EXPECT_EQ((*streamOfType(pr, "video"))["codec_name"].get<std::string>(), std::string("av1"));
  EXPECT_NEAR(numField(pr["format"], "duration"), 3.0, 0.15);
  expectSync(pr);
}

VC_TEST(Media, FastRemuxCopiesStreams) {
  Json plan = basePlan("matroska");
  plan["video"] = {{"sourceStreamIndex", 0}, {"mode", "copy"}};
  plan["audio"] = Json::array({Json{{"sourceStreamIndex", 1}, {"mode", "copy"}}});
  std::string out = path("out_remux.mkv");
  auto t0 = std::chrono::steady_clock::now();
  JobOutcome o = runJob(plan, fixture("1080p60"), out);
  double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
  EXPECT_EQ(o.state, std::string("completed"));
  EXPECT_TRUE(secs < 3.0);
  Json pr = ffprobe(out);
  EXPECT_EQ((*streamOfType(pr, "video"))["codec_name"].get<std::string>(), std::string("h264"));
  EXPECT_NEAR(numField(pr["format"], "duration"), 10.0, 0.1);
  EXPECT_NEAR(static_cast<double>(fileSize(out)), static_cast<double>(fileSize(fixture("1080p60"))),
              fileSize(fixture("1080p60")) * 0.03);
}

VC_TEST(Media, AudioPassthroughWithVideoTranscode) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  plan["audio"] = Json::array({Json{{"sourceStreamIndex", 1}, {"mode", "copy"}}});
  std::string out = path("out_passthrough.mp4");
  JobOutcome o = runJob(plan, fixture("1080p60"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  Json src = ffprobe(fixture("1080p60"));
  Json pr = ffprobe(out);
  // Copied, not re-encoded: identical bitrate and frame count.
  EXPECT_EQ(numField(*streamOfType(pr, "audio"), "bit_rate"), numField(*streamOfType(src, "audio"), "bit_rate"));
  EXPECT_EQ((*streamOfType(pr, "audio"))["nb_frames"], (*streamOfType(src, "audio"))["nb_frames"]);
  expectSync(pr);
}

VC_TEST(Media, MultiTrackSelectionChaptersMetadata) {
  Json plan = basePlan("matroska");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  plan["audio"] = Json::array({aacTrack(1, 96), aacTrack(2, 96)});
  plan["audio"][1]["language"] = "jpn";
  plan["subtitles"] = Json::array({Json{{"sourceStreamIndex", 3}, {"mode", "copy"}, {"default", true}}});
  plan["validate"] = {{"container", "matroska"}, {"audioTracks", 2}, {"subtitleTracks", 1}, {"durationUs", 8000000}};
  std::string out = path("out_multitrack.mkv");
  JobOutcome o = runJob(plan, fixture("multitrack"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  Json pr = ffprobe(out);
  int audio = 0, subs = 0;
  for (const auto& s : pr["streams"]) {
    if (s["codec_type"] == "audio") ++audio;
    if (s["codec_type"] == "subtitle") {
      ++subs;
      EXPECT_EQ(s["tags"]["language"].get<std::string>(), std::string("eng"));
    }
  }
  EXPECT_EQ(audio, 2);
  EXPECT_EQ(subs, 1);
  EXPECT_EQ((*streamOfType(pr, "audio", 1))["tags"]["language"].get<std::string>(), std::string("jpn"));
  EXPECT_EQ(pr["chapters"].size(), 2u);
  EXPECT_EQ(pr["chapters"][1]["tags"]["title"].get<std::string>(), std::string("Ending"));
  EXPECT_EQ(pr["format"]["tags"]["title"].get<std::string>(), std::string("Fixture Title"));
}

VC_TEST(Media, MetadataStripAndCustom) {
  Json plan = basePlan("matroska");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  plan["metadataMode"] = "strip";
  plan["chapterMode"] = "strip";
  std::string out = path("out_strip.mkv");
  EXPECT_EQ(runJob(plan, fixture("multitrack"), out).state, std::string("completed"));
  Json pr = ffprobe(out);
  EXPECT_FALSE(pr["format"].contains("tags") && pr["format"]["tags"].contains("title"));
  EXPECT_EQ(pr["chapters"].size(), 0u);

  plan["metadataMode"] = "custom";
  plan["metadata"] = {{"title", "New Title"}, {"artist", ""}};
  plan["chapterMode"] = "custom";
  plan["chapters"] = Json::array({Json{{"title", "Intro"}, {"startUs", 0}, {"endUs", 2000000}},
                                  Json{{"title", "Rest"}, {"startUs", 2000000}, {"endUs", 8000000}}});
  out = path("out_custom_meta.mkv");
  EXPECT_EQ(runJob(plan, fixture("multitrack"), out).state, std::string("completed"));
  pr = ffprobe(out);
  EXPECT_EQ(pr["format"]["tags"]["title"].get<std::string>(), std::string("New Title"));
  EXPECT_FALSE(pr["format"]["tags"].contains("artist") || pr["format"]["tags"].contains("ARTIST"));
  EXPECT_EQ(pr["chapters"][0]["tags"]["title"].get<std::string>(), std::string("Intro"));
}

VC_TEST(Media, SubtitlesConvertToMovTextForMp4) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  plan["subtitles"] = Json::array({Json{{"sourceStreamIndex", 3}, {"mode", "convert"}, {"codec", "mov_text"}},
                                   Json{{"externalPath", path("spa.srt")}, {"mode", "convert"}, {"codec", "mov_text"},
                                        {"language", "spa"}}});
  std::string out = path("out_movtext.mp4");
  JobOutcome o = runJob(plan, fixture("multitrack"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  Json pr = ffprobe(out);
  EXPECT_EQ((*streamOfType(pr, "subtitle"))["codec_name"].get<std::string>(), std::string("mov_text"));
  EXPECT_EQ((*streamOfType(pr, "subtitle", 1))["tags"]["language"].get<std::string>(), std::string("spa"));
  std::string text = capture(libEnv() + VC_FFMPEG_BIN + " -v error -i '" + out + "' -map 0:s:0 -f srt -");
  EXPECT_CONTAINS(text, "Hello world");
  EXPECT_CONTAINS(text, "00:00:05,000");
}

VC_TEST(Media, BurnInTextSubtitles) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  // libass is built without fontconfig: the app passes a directory holding a
  // single font copied from /system/fonts; the test does the same.
  std::string fonts = path("fonts");
  std::string cp = "mkdir -p '" + fonts + "' && cp /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf '" + fonts + "/'";
  if (std::system(cp.c_str()) != 0) VC_FAIL("no test font available");
  plan["video"]["burn"] = {{"sourceStreamIndex", 3}, {"externalPath", path("burn_extract.mkv")}, {"fontsDir", fonts},
                            {"fallbackFont", fonts + "/DejaVuSans.ttf"}};
  std::string out = path("out_burn.mp4");
  JobOutcome o = runJob(plan, fixture("multitrack"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  Json pr = ffprobe(out);
  EXPECT_TRUE(streamOfType(pr, "subtitle") == nullptr);
  // Frame at 2 s (subtitle shown) must differ from the same frame without burn-in.
  Json plain = plan;
  plain["video"].erase("burn");
  std::string out2 = path("out_noburn.mp4");
  EXPECT_EQ(runJob(plain, fixture("multitrack"), out2).state, std::string("completed"));
  auto md5At = [&](const std::string& f) {
    return capture(libEnv() + VC_FFMPEG_BIN + " -v error -ss 2 -i '" + f + "' -frames:v 1 -f md5 -");
  };
  EXPECT_FALSE(md5At(out) == md5At(out2));
}

VC_TEST(Media, PortraitRotationProducesPortraitOutput) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("h264", "libopenh264", 720, 1280);
  plan["audio"] = Json::array({aacTrack(1)});
  plan["validate"] = {{"width", 720}, {"height", 1280}};
  std::string out = path("out_portrait.mp4");
  JobOutcome o = runJob(plan, fixture("portrait"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  Json pr = ffprobe(out);
  const Json* v = streamOfType(pr, "video");
  EXPECT_EQ((*v)["width"].get<int>(), 720);
  EXPECT_EQ((*v)["height"].get<int>(), 1280);
  // Rotation was applied to the pixels, so no display matrix remains.
  EXPECT_FALSE(v->contains("side_data_list"));
}

VC_TEST(Media, VfrPreservedOrConvertedToCfr) {
  Json plan = basePlan("matroska");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  std::string out = path("out_vfr.mkv");
  EXPECT_EQ(runJob(plan, fixture("vfr"), out).state, std::string("completed"));
  int fdv = ::open(out.c_str(), O_RDONLY);
  Json p = probeSource(fdv, "o", nullptr);
  ::close(fdv);
  EXPECT_TRUE(p["streams"][0]["timing"]["vfr"].get<bool>());
  std::string frames = capture(libEnv() + VC_FFPROBE_BIN +
                               " -v error -count_packets -select_streams v:0 -show_entries stream=nb_read_packets -of csv=p=0 '" + out + "'");
  EXPECT_EQ(std::atoi(frames.c_str()), 3 * 30 + 3 * 12);

  plan["video"]["fpsMode"] = "cfr";
  plan["video"]["fpsNum"] = 30;
  plan["video"]["fpsDen"] = 1;
  out = path("out_vfr_cfr.mkv");
  EXPECT_EQ(runJob(plan, fixture("vfr"), out).state, std::string("completed"));
  frames = capture(libEnv() + VC_FFPROBE_BIN +
                   " -v error -count_packets -select_streams v:0 -show_entries stream=nb_read_packets -of csv=p=0 '" + out + "'");
  EXPECT_NEAR(std::atoi(frames.c_str()), 180, 2);
}

VC_TEST(Media, HdrPreserveAndTonemap) {
  Json plan = basePlan("matroska");
  plan["video"] = videoPlan("vp9", "libvpx-vp9", 1280, 720);
  plan["video"]["bitDepth"] = 10;
  plan["video"]["pixFmt"] = "yuv420p10le";
  plan["video"]["preset"] = "realtime";
  plan["video"]["options"] = {{"cpu-used", "8"}};
  std::string out = path("out_hdr_keep.mkv");
  EXPECT_EQ(runJob(plan, fixture("hdr10bit"), out).state, std::string("completed"));
  const Json* v = streamOfType(ffprobe(out), "video");
  Json pr = ffprobe(out);
  v = streamOfType(pr, "video");
  EXPECT_EQ((*v)["pix_fmt"].get<std::string>(), std::string("yuv420p10le"));
  EXPECT_EQ((*v)["color_transfer"].get<std::string>(), std::string("smpte2084"));

  Json sdr = basePlan("mp4");
  sdr["video"] = videoPlan("h264", "libopenh264", 1280, 720);
  sdr["video"]["hdrMode"] = "tonemap";
  out = path("out_hdr_tonemap.mp4");
  EXPECT_EQ(runJob(sdr, fixture("hdr10bit"), out).state, std::string("completed"));
  pr = ffprobe(out);
  v = streamOfType(pr, "video");
  EXPECT_EQ((*v)["pix_fmt"].get<std::string>(), std::string("yuv420p"));
  EXPECT_EQ((*v)["color_transfer"].get<std::string>(), std::string("bt709"));
}

VC_TEST(Media, PreviewSegmentAndMetrics) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("hevc", "libkvazaar", 1280, 720);
  plan["video"]["preset"] = "ultrafast";
  plan["audio"] = Json::array({aacTrack(1)});
  plan["segment"] = {{"startUs", 3000000}, {"durationUs", 2000000}};
  std::string out = path("out_preview.mp4");
  JobOutcome o = runJob(plan, fixture("1080p60"), out);
  EXPECT_EQ(o.state, std::string("completed"));
  Json pr = ffprobe(out);
  EXPECT_NEAR(numField(pr["format"], "duration"), 2.0, 0.1);
  expectSync(pr);
  EXPECT_TRUE(o.encodeStats["sourceSegmentBytes"].get<int64_t>() > 0);
  EXPECT_TRUE(o.encodeStats["sourceSegmentBytes"].get<int64_t>() < fileSize(fixture("1080p60")) / 3);

  int a = ::open(fixture("1080p60").c_str(), O_RDONLY);
  int b = ::open(out.c_str(), O_RDONLY);
  Json m = compareQuality(a, b, parsePlan(plan), nullptr);
  ::close(a);
  ::close(b);
  EXPECT_TRUE(m["psnr"].get<double>() > 25);
  EXPECT_TRUE(m["ssim"].get<double>() > 0.85);
  EXPECT_NEAR(m["frames"].get<int>(), 120, 3);
}

VC_TEST(Media, LosslessIsNearPerfect) {
  Json plan = basePlan("matroska");
  plan["video"] = videoPlan("ffv1", "ffv1", 1920, 1080);
  plan["video"]["rateControl"] = {{"mode", "lossless"}};
  plan["segment"] = {{"startUs", 1000000}, {"durationUs", 1000000}};
  std::string out = path("out_ffv1.mkv");
  EXPECT_EQ(runJob(plan, fixture("1080p60"), out).state, std::string("completed"));
  int a = ::open(fixture("1080p60").c_str(), O_RDONLY);
  int b = ::open(out.c_str(), O_RDONLY);
  Json m = compareQuality(a, b, parsePlan(plan), nullptr);
  ::close(a);
  ::close(b);
  EXPECT_TRUE(m["psnr"].get<double>() > 90);
  EXPECT_NEAR(m["ssim"].get<double>(), 1.0, 1e-6);
}

VC_TEST(Media, CopyPreviewStartsOnKeyframe) {
  Json plan = basePlan("mp4");
  plan["video"] = {{"sourceStreamIndex", 0}, {"mode", "copy"}};
  plan["audio"] = Json::array({aacTrack(1)});
  plan["segment"] = {{"startUs", 4000000}, {"durationUs", 3000000}};
  std::string out = path("out_copy_preview.mp4");
  EXPECT_EQ(runJob(plan, fixture("1080p60"), out).state, std::string("completed"));
  Json pr = ffprobe(out);
  EXPECT_TRUE(numField(pr["format"], "duration") >= 2.9);
  expectSync(pr, 0.15);
}

VC_TEST(Media, CancelLeavesNoCompletedState) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("hevc", "libkvazaar", 1920, 1080);
  plan["video"]["preset"] = "medium";
  std::string out = path("out_cancel.mp4");
  JobOutcome o = runJob(plan, fixture("1080p60"), out, Action::CancelMidway);
  EXPECT_EQ(o.state, std::string("cancelled"));
  EXPECT_TRUE(std::find(o.events.begin(), o.events.end(), "CANCELLED") != o.events.end());
  EXPECT_TRUE(std::find(o.events.begin(), o.events.end(), "ENCODE_COMPLETE") == o.events.end());
}

VC_TEST(Media, PauseIsRealAndResumeCompletes) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("hevc", "libkvazaar", 1280, 720);
  plan["video"]["preset"] = "fast";
  plan["audio"] = Json::array({aacTrack(1)});
  plan["validate"] = {{"durationUs", 10000000}};
  std::string out = path("out_pause.mp4");
  JobOutcome o = runJob(plan, fixture("1080p60"), out, Action::PauseResume);
  EXPECT_EQ(o.state, std::string("completed"));
  EXPECT_TRUE(std::find(o.events.begin(), o.events.end(), "PAUSED") != o.events.end());
  EXPECT_TRUE(std::find(o.events.begin(), o.events.end(), "RESUMED") != o.events.end());
  EXPECT_TRUE(o.validation.value("ok", false));
}

VC_TEST(Media, CorruptAndTruncatedSourcesFailCleanly) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  plan["audio"] = Json::array({aacTrack(1)});
  JobOutcome o = runJob(plan, fixture("garbage"), path("out_garbage.mp4"));
  EXPECT_EQ(o.state, std::string("failed"));
  EXPECT_EQ(o.failure["categoryName"].get<std::string>(), std::string("invalid_input"));

  o = runJob(plan, fixture("empty"), path("out_empty.mp4"));
  EXPECT_EQ(o.state, std::string("failed"));

  // Damaged payload: either decodes around the damage (with a warning) or fails
  // with a clear category; never crashes.
  o = runJob(plan, fixture("corrupt"), path("out_corrupt.mp4"));
  EXPECT_TRUE(o.state == "completed" || o.failure["categoryName"] == "invalid_input");

  Json mk = basePlan("matroska");
  mk["video"] = videoPlan("h264", "libopenh264", 640, 360);
  o = runJob(mk, fixture("truncated"), path("out_truncated.mkv"));
  EXPECT_TRUE(o.state == "completed" || o.failure["categoryName"] == "invalid_input");
}

VC_TEST(Media, InvalidEncoderOptionRejected) {
  Json plan = basePlan("webm");
  plan["video"] = videoPlan("vp9", "libvpx-vp9", 640, 360);
  plan["video"]["options"] = {{"foo", "bar"}};
  JobOutcome o = runJob(plan, fixture("1080p60"), path("out_badopt.webm"));
  EXPECT_EQ(o.state, std::string("failed"));
  EXPECT_EQ(o.failure["categoryName"].get<std::string>(), std::string("invalid_encoder_option"));
  EXPECT_CONTAINS(o.failure["message"].get<std::string>(), "foo=bar");

  Json k = basePlan("mp4");
  k["video"] = videoPlan("hevc", "libkvazaar", 640, 360);
  k["video"]["options"] = {{"notarealoption", "1"}};
  o = runJob(k, fixture("1080p60"), path("out_badopt_kvz.mp4"));
  EXPECT_EQ(o.failure["categoryName"].get<std::string>(), std::string("invalid_encoder_option"));
}

VC_TEST(Media, ContainerConflictRejected) {
  Json plan = basePlan("webm");
  plan["video"] = videoPlan("vp9", "libvpx-vp9", 640, 360);
  plan["audio"] = Json::array({aacTrack(1)});
  JobOutcome o = runJob(plan, fixture("1080p60"), path("out_conflict.webm"));
  EXPECT_EQ(o.state, std::string("failed"));
  EXPECT_EQ(o.failure["categoryName"].get<std::string>(), std::string("invalid_configuration"));
  EXPECT_CONTAINS(o.failure["message"].get<std::string>(), "cannot contain");
}

VC_TEST(Media, TargetBitrateAndExtremeTarget) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("hevc", "libkvazaar", 1280, 720);
  plan["video"]["preset"] = "ultrafast";
  plan["video"]["rateControl"] = {{"mode", "abr"}, {"bitrateKbps", 700}};
  std::string out = path("out_abr.mp4");
  EXPECT_EQ(runJob(plan, fixture("1080p60"), out).state, std::string("completed"));
  double kbps = fileSize(out) * 8.0 / 10.0 / 1000;
  EXPECT_NEAR(kbps, 700, 700 * 0.35);

  // Absurdly small target: poor quality, but valid output and no crash.
  plan["video"]["width"] = 1920;
  plan["video"]["height"] = 1080;
  plan["video"]["rateControl"] = {{"mode", "abr"}, {"bitrateKbps", 30}};
  plan["validate"] = {{"width", 1920}, {"height", 1080}, {"durationUs", 10000000}};
  JobOutcome o = runJob(plan, fixture("1080p60"), path("out_extreme.mp4"));
  EXPECT_EQ(o.state, std::string("completed"));
  EXPECT_TRUE(o.validation.value("ok", false));
}

VC_TEST(Media, LargeStartOffsetNormalised) {
  Json plan = basePlan("mp4");
  plan["video"] = videoPlan("h264", "libopenh264", 640, 360);
  plan["audio"] = Json::array({aacTrack(1)});
  std::string out = path("out_offset.mp4");
  EXPECT_EQ(runJob(plan, fixture("tsoffset"), out).state, std::string("completed"));
  Json pr = ffprobe(out);
  EXPECT_NEAR(numField(pr["format"], "duration"), 5.0, 0.15);
  EXPECT_NEAR(numField(*streamOfType(pr, "video"), "start_time"), 0.0, 0.1);
  expectSync(pr);
}

VC_TEST(Media, ValidatorCatchesMismatches) {
  int fd = ::open(fixture("1080p60").c_str(), O_RDONLY);
  Json r = validateOutput(fd, Json{{"width", 1280}, {"height", 720}, {"videoCodec", "hevc"}}, nullptr);
  ::close(fd);
  EXPECT_FALSE(r["ok"].get<bool>());
  fd = ::open(fixture("garbage").c_str(), O_RDONLY);
  EXPECT_FALSE(validateOutput(fd, Json::object(), nullptr)["ok"].get<bool>());
  ::close(fd);
}

VC_TEST(Media, ComplexityRanksMotion) {
  int a = ::open(fixture("1080p60").c_str(), O_RDONLY);
  Json moving = analyzeComplexity(a, 3, 2.0, nullptr);
  ::close(a);
  int b = ::open(fixture("static").c_str(), O_RDONLY);
  Json still = analyzeComplexity(b, 3, 2.0, nullptr);
  ::close(b);
  EXPECT_TRUE(moving["motion"].get<double>() > still["motion"].get<double>());
  EXPECT_TRUE(moving["score"].get<double>() > still["score"].get<double>());
  EXPECT_EQ(still["class"].get<std::string>(), std::string("low"));
}

VC_TEST(Media, ThumbnailHonoursRotation) {
  int fd = ::open(fixture("portrait").c_str(), O_RDONLY);
  Thumbnail t = extractThumbnail(fd, 1000000, 320, nullptr);
  ::close(fd);
  EXPECT_EQ(t.height, 320);
  EXPECT_EQ(t.width, 180);
  EXPECT_EQ(t.rgba.size(), static_cast<size_t>(t.width * t.height * 4));
}
