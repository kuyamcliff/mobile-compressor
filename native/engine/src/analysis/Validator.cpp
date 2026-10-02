#include "analysis/Validator.h"

#include <sys/stat.h>

#include <cmath>

#include "core/Errors.h"
#include "core/Log.h"
#include "probe/Probe.h"

namespace vc {

namespace {
constexpr const char* TAG = "Validator";

struct Checks {
  Json list = Json::array();
  bool ok = true;
  void add(const std::string& name, bool pass, const std::string& detail = {}) {
    list.push_back({{"name", name}, {"ok", pass}, {"detail", detail}});
    if (!pass) ok = false;
  }
};

bool containerMatches(const std::string& want, const std::string& demuxer) {
  if (want == "mp4" || want == "mov" || want == "ipod" || want == "3gp") return demuxer.find("mp4") != std::string::npos;
  if (want == "matroska" || want == "webm") return demuxer.find("matroska") != std::string::npos;
  if (want == "mpegts") return demuxer == "mpegts";
  return demuxer.find(want) != std::string::npos;
}

// Decodes the first frame of `streamIndex` from the current position.
bool decodeOneFrame(AVFormatContext* fmt, int streamIndex, int maxPackets, std::string& err) {
  AVStream* st = fmt->streams[streamIndex];
  const AVCodec* dec = avcodec_find_decoder(st->codecpar->codec_id);
  if (!dec) {
    err = "no decoder";
    return false;
  }
  CodecCtxPtr ctx(avcodec_alloc_context3(dec));
  if (!ctx || avcodec_parameters_to_context(ctx.get(), st->codecpar) < 0) return false;
  ctx->thread_count = 1;
  if (avcodec_open2(ctx.get(), dec, nullptr) < 0) {
    err = "decoder open failed";
    return false;
  }
  PacketPtr pkt = makePacket();
  FramePtr frame = makeFrame();
  for (int n = 0; n < maxPackets;) {
    int r = av_read_frame(fmt, pkt.get());
    if (r < 0) break;
    if (pkt->stream_index != streamIndex) {
      av_packet_unref(pkt.get());
      continue;
    }
    ++n;
    avcodec_send_packet(ctx.get(), pkt.get());
    av_packet_unref(pkt.get());
    if (avcodec_receive_frame(ctx.get(), frame.get()) == 0) return true;
  }
  avcodec_send_packet(ctx.get(), nullptr);
  if (avcodec_receive_frame(ctx.get(), frame.get()) == 0) return true;
  err = "no frame decoded";
  return false;
}
}  // namespace

Json validateOutput(int fd, const Json& expect, InterruptFlag* interrupt) {
  Checks c;
  Json info;
  struct stat st{};
  bool exists = ::fstat(fd, &st) == 0;
  c.add("exists", exists, exists ? "" : "output descriptor is not valid");
  int64_t size = exists ? st.st_size : 0;
  c.add("non_empty", size > 0, "size=" + std::to_string(size));
  if (!c.ok) return Json{{"ok", false}, {"checks", c.list}, {"info", info}};

  InputFile in;
  try {
    in.open(fd, "validate", interrupt, true);
  } catch (const EngineException& e) {
    c.add("reopen", false, e.error().message);
    return Json{{"ok", false}, {"checks", c.list}, {"info", info}};
  }
  c.add("reopen", true);
  AVFormatContext* fmt = in.ctx();
  Json desc = describeInput(fmt, size);
  info = desc;

  std::string demuxer = fmt->iformat ? fmt->iformat->name : "";
  if (expect.contains("container")) {
    std::string want = expect["container"].get<std::string>();
    c.add("container", containerMatches(want, demuxer), "expected " + want + ", found " + demuxer);
  }

  int videoIdx = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  bool expectVideo = expect.value("expectVideo", true);
  if (expectVideo) {
    c.add("video_stream", videoIdx >= 0, videoIdx >= 0 ? "" : "no video stream in output");
  }
  int audioCount = 0, subCount = 0;
  for (unsigned i = 0; i < fmt->nb_streams; ++i) {
    auto t = fmt->streams[i]->codecpar->codec_type;
    if (t == AVMEDIA_TYPE_AUDIO) ++audioCount;
    if (t == AVMEDIA_TYPE_SUBTITLE) ++subCount;
  }
  if (expect.contains("audioTracks")) {
    int want = expect["audioTracks"].get<int>();
    c.add("audio_streams", audioCount == want,
          "expected " + std::to_string(want) + ", found " + std::to_string(audioCount));
  }
  if (expect.contains("subtitleTracks")) {
    int want = expect["subtitleTracks"].get<int>();
    c.add("subtitle_streams", subCount == want,
          "expected " + std::to_string(want) + ", found " + std::to_string(subCount));
  }

  if (videoIdx >= 0) {
    AVStream* vs = fmt->streams[videoIdx];
    if (expect.contains("videoCodec")) {
      std::string want = expect["videoCodec"].get<std::string>();
      const AVCodecDescriptor* d = avcodec_descriptor_get(vs->codecpar->codec_id);
      std::string got = d ? d->name : "?";
      c.add("video_codec", got == want, "expected " + want + ", found " + got);
    }
    if (expect.contains("width") && expect.contains("height")) {
      int w = vs->codecpar->width, h = vs->codecpar->height;
      int rot = streamRotation(vs);
      if (rot == 90 || rot == 270) std::swap(w, h);
      int ew = expect["width"].get<int>(), eh = expect["height"].get<int>();
      c.add("resolution", w == ew && h == eh,
            "expected " + std::to_string(ew) + "x" + std::to_string(eh) + ", found " + std::to_string(w) + "x" + std::to_string(h));
    }
  }

  int64_t dur = fmt->duration > 0 ? fmt->duration : 0;
  if (expect.contains("durationUs")) {
    int64_t want = expect["durationUs"].get<int64_t>();
    int64_t tol = std::max<int64_t>(kUsPerSec, want / 50);  // max(1 s, 2 %)
    c.add("duration", want <= 0 || std::llabs(dur - want) <= tol,
          "expected " + std::to_string(want / 1000) + " ms, found " + std::to_string(dur / 1000) + " ms");
  }

  // Decodability at the start.
  if (videoIdx >= 0) {
    std::string err;
    bool okStart = decodeOneFrame(fmt, videoIdx, 300, err);
    c.add("decode_start", okStart, err);
    // Seekability: jump to the middle and decode again.
    if (dur > 2 * kUsPerSec && (fmt->pb && (fmt->pb->seekable & AVIO_SEEKABLE_NORMAL))) {
      int64_t target = (fmt->start_time != AV_NOPTS_VALUE ? fmt->start_time : 0) + dur / 2;
      int r = av_seek_frame(fmt, -1, target, AVSEEK_FLAG_BACKWARD);
      bool okSeek = r >= 0 && decodeOneFrame(fmt, videoIdx, 600, err);
      c.add("seek_middle", okSeek, okSeek ? "" : (r < 0 ? avErrorString(r) : err));
    }
  } else {
    int a = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (a >= 0) {
      std::string err;
      c.add("decode_start", decodeOneFrame(fmt, a, 100, err), err);
    }
  }
  VC_LOGI(TAG, "validation %s (%zu checks)", c.ok ? "passed" : "FAILED", c.list.size());
  return Json{{"ok", c.ok}, {"checks", c.list}, {"info", info}};
}

}  // namespace vc
