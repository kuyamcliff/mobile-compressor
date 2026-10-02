#include "probe/Thumbnail.h"

#include <algorithm>

#include "core/Errors.h"
#include "pipeline/FilterGraph.h"
#include "probe/Probe.h"

namespace vc {

Thumbnail extractThumbnail(int fd, int64_t timeUs, int maxDim, InterruptFlag* interrupt) {
  maxDim = std::clamp(maxDim, 32, 2048);
  InputFile in;
  in.open(fd, "thumbnail", interrupt, true);
  AVFormatContext* f = in.ctx();
  int vi = av_find_best_stream(f, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  if (vi < 0) throwError(ErrorCategory::InvalidInput, "thumbnail", "No video stream.");
  AVStream* st = f->streams[vi];
  int64_t base = f->start_time != AV_NOPTS_VALUE ? f->start_time : 0;
  if (timeUs > 0) av_seek_frame(f, -1, base + timeUs, AVSEEK_FLAG_BACKWARD);
  const AVCodec* dec = avcodec_find_decoder(st->codecpar->codec_id);
  if (!dec) throwError(ErrorCategory::UnsupportedCodec, "thumbnail", "Cannot decode this video.");
  CodecCtxPtr ctx(avcodec_alloc_context3(dec));
  avcodec_parameters_to_context(ctx.get(), st->codecpar);
  ctx->pkt_timebase = st->time_base;
  ctx->thread_count = 2;
  checkAv(avcodec_open2(ctx.get(), dec, nullptr), "thumbnail", "opening the decoder");

  PacketPtr pkt = makePacket();
  FramePtr frame = makeFrame();
  bool got = false;
  int packets = 0;
  while (!got && packets < 2000) {
    int r = avcodec_receive_frame(ctx.get(), frame.get());
    if (r == 0) {
      int64_t us = toUs(frame->best_effort_timestamp, st->time_base);
      if (timeUs > 0 && us != AV_NOPTS_VALUE && us < base + timeUs - 40000) {
        av_frame_unref(frame.get());
        continue;
      }
      got = true;
      break;
    }
    if (r == AVERROR_EOF) break;
    r = av_read_frame(f, pkt.get());
    if (r < 0) {
      avcodec_send_packet(ctx.get(), nullptr);
      if (avcodec_receive_frame(ctx.get(), frame.get()) == 0) got = true;
      break;
    }
    if (pkt->stream_index == vi) {
      ++packets;
      avcodec_send_packet(ctx.get(), pkt.get());
    }
    av_packet_unref(pkt.get());
  }
  if (!got) throwError(ErrorCategory::InvalidInput, "thumbnail", "No frame could be decoded.");

  int rot = streamRotation(st);
  std::string rotF = rot == 90 ? "transpose=clock," : rot == 180 ? "hflip,vflip," : rot == 270 ? "transpose=cclock," : "";
  std::string desc = rotF + "scale=w=" + std::to_string(maxDim) + ":h=" + std::to_string(maxDim) +
                     ":force_original_aspect_ratio=decrease:flags=bicubic,format=rgba";
  FilterGraph g;
  AVRational sar = frame->sample_aspect_ratio.num ? frame->sample_aspect_ratio : AVRational{1, 1};
  g.initVideo(frame.get(), st->time_base, sar, st->avg_frame_rate, desc, 1);
  Thumbnail t;
  auto cb = [&](AVFrame* o, AVRational) {
    if (!t.rgba.empty()) return;
    t.width = o->width;
    t.height = o->height;
    t.rgba.resize(static_cast<size_t>(o->width) * o->height * 4);
    for (int y = 0; y < o->height; ++y) {
      memcpy(&t.rgba[static_cast<size_t>(y) * o->width * 4], o->data[0] + static_cast<ptrdiff_t>(y) * o->linesize[0],
             static_cast<size_t>(o->width) * 4);
    }
  };
  g.push(frame.get(), cb);
  g.push(nullptr, cb);
  if (t.rgba.empty()) throwError(ErrorCategory::Internal, "thumbnail", "Thumbnail conversion failed.");
  return t;
}

}  // namespace vc
