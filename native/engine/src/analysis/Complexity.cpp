#include "analysis/Complexity.h"

#include <algorithm>
#include <cmath>

#include "core/Errors.h"
#include "core/Log.h"
#include "pipeline/FilterGraph.h"

namespace vc {

namespace {
constexpr const char* TAG = "Complexity";

double meta(const AVFrame* f, const char* key, double def = NAN) {
  const AVDictionaryEntry* e = av_dict_get(f->metadata, key, nullptr, 0);
  return e ? std::strtod(e->value, nullptr) : def;
}
}  // namespace

Json analyzeComplexity(int fd, int windows, double windowSeconds, InterruptFlag* interrupt) {
  windows = std::clamp(windows, 1, 8);
  windowSeconds = std::clamp(windowSeconds, 1.0, 20.0);
  InputFile in;
  in.open(fd, "complexity", interrupt, true);
  AVFormatContext* f = in.ctx();
  int vi = av_find_best_stream(f, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  if (vi < 0) throwError(ErrorCategory::InvalidInput, "complexity", "No video stream to analyse.");
  AVStream* st = f->streams[vi];
  const AVCodec* dec = avcodec_find_decoder(st->codecpar->codec_id);
  if (!dec) throwError(ErrorCategory::UnsupportedCodec, "complexity", "Cannot decode the video for analysis.");

  int64_t base = f->start_time != AV_NOPTS_VALUE ? f->start_time : 0;
  int64_t dur = f->duration > 0 ? f->duration : 0;
  int64_t winUs = static_cast<int64_t>(windowSeconds * kUsPerSec);

  double sumDif = 0, sumEnt = 0, sumY = 0, sumTout = 0;
  int n = 0, dark = 0, scenes = 0;
  double analysedSeconds = 0;
  PacketPtr pkt = makePacket();
  FramePtr frame = makeFrame();

  for (int w = 0; w < windows; ++w) {
    // Window positions spread over the file, avoiding the very first/last seconds.
    double pos = windows == 1 ? 0.5 : 0.08 + 0.84 * w / (windows - 1);
    int64_t start = dur > winUs ? base + static_cast<int64_t>((dur - winUs) * pos) : base;
    av_seek_frame(f, -1, start, AVSEEK_FLAG_BACKWARD);
    CodecCtxPtr ctx(avcodec_alloc_context3(dec));
    avcodec_parameters_to_context(ctx.get(), st->codecpar);
    ctx->pkt_timebase = st->time_base;
    ctx->thread_count = 4;
    ctx->skip_loop_filter = AVDISCARD_ALL;  // analysis does not need bit-exact output
    if (avcodec_open2(ctx.get(), dec, nullptr) < 0) continue;
    FilterGraph g;
    bool eof = false;
    int64_t firstUs = AV_NOPTS_VALUE, lastUs = AV_NOPTS_VALUE;
    auto onFrame = [&](AVFrame* o, AVRational) {
      double dif = meta(o, "lavfi.signalstats.YDIF");
      double y = meta(o, "lavfi.signalstats.YAVG");
      double tout = meta(o, "lavfi.signalstats.TOUT");
      double ent = meta(o, "lavfi.entropy.normalized_entropy.normal.Y");
      if (std::isnan(ent)) ent = meta(o, "lavfi.entropy.entropy.normal.Y") / 8.0;
      double sc = meta(o, "lavfi.scd.score", 0);
      if (!std::isnan(dif)) sumDif += dif;
      if (!std::isnan(y)) {
        sumY += y;
        if (y < 50) ++dark;
      }
      if (!std::isnan(tout)) sumTout += tout;
      if (!std::isnan(ent)) sumEnt += ent;
      if (av_dict_get(o->metadata, "lavfi.scd.time", nullptr, 0) && sc > 0) ++scenes;
      ++n;
    };
    while (!eof) {
      if (interrupt && interrupt->cancel.load()) throwError(ErrorCategory::Cancelled, "complexity", "Cancelled.");
      int r = avcodec_receive_frame(ctx.get(), frame.get());
      if (r == 0) {
        int64_t us = toUs(frame->best_effort_timestamp, st->time_base);
        if (us != AV_NOPTS_VALUE && us < start) {
          av_frame_unref(frame.get());
          continue;
        }
        if (us != AV_NOPTS_VALUE && us >= start + winUs) break;
        if (firstUs == AV_NOPTS_VALUE) firstUs = us;
        lastUs = us;
        if (!g.initialized()) {
          g.initVideo(frame.get(), st->time_base, AVRational{1, 1}, st->avg_frame_rate,
                      "scale=w=320:h=-2:flags=fast_bilinear,format=yuv420p,signalstats=stat=tout,entropy,scdet=threshold=10",
                      1);
        }
        g.push(frame.get(), onFrame);
        av_frame_unref(frame.get());
        continue;
      }
      if (r == AVERROR_EOF) break;
      r = av_read_frame(f, pkt.get());
      if (r < 0) {
        avcodec_send_packet(ctx.get(), nullptr);
        if (avcodec_receive_frame(ctx.get(), frame.get()) < 0) eof = true;
        else av_frame_unref(frame.get());
        continue;
      }
      if (pkt->stream_index == vi) avcodec_send_packet(ctx.get(), pkt.get());
      av_packet_unref(pkt.get());
    }
    if (g.initialized()) g.push(nullptr, onFrame);
    if (firstUs != AV_NOPTS_VALUE && lastUs > firstUs) analysedSeconds += (lastUs - firstUs) / 1e6;
  }
  if (n == 0) throwError(ErrorCategory::InvalidInput, "complexity", "No frames could be analysed.");

  double motion = sumDif / n;            // 0..255, typical 0.5 (static) .. 15+ (very high motion)
  double detail = sumEnt / n;            // 0..1 normalised entropy
  double brightness = sumY / n;
  double noise = sumTout / n;            // fraction of outlier pixels
  double scenesPerMin = analysedSeconds > 0 ? scenes * 60.0 / analysedSeconds : 0;
  // Bounded, monotonic mapping to 0..1. Weights favour motion (dominant driver
  // of inter-frame bitrate), then spatial detail, then noise and cuts.
  double mScore = std::min(1.0, std::log1p(motion) / std::log1p(20.0));
  double dScore = std::clamp((detail - 0.55) / 0.4, 0.0, 1.0);
  double nScore = std::min(1.0, noise * 40.0);
  double cScore = std::min(1.0, scenesPerMin / 30.0);
  double score = 0.5 * mScore + 0.25 * dScore + 0.15 * nScore + 0.10 * cScore;
  std::string cls = score < 0.3 ? "low" : score < 0.6 ? "medium" : "high";
  VC_LOGI(TAG, "frames=%d motion=%.2f detail=%.3f noise=%.4f score=%.2f", n, motion, detail, noise, score);
  return Json{{"frames", n},
              {"analysedSeconds", analysedSeconds},
              {"motion", motion},
              {"detail", detail},
              {"brightness", brightness},
              {"darkFraction", static_cast<double>(dark) / n},
              {"noise", noise},
              {"sceneChangesPerMinute", scenesPerMin},
              {"score", score},
              {"class", cls}};
}

}  // namespace vc
