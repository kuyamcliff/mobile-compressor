#include "analysis/QualityMetrics.h"

#include <cmath>
#include <limits>

#include "core/Errors.h"
#include "core/Log.h"
#include "pipeline/FilterBuilder.h"
#include "probe/Probe.h"

extern "C" {
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/pixdesc.h>
}

namespace vc {

namespace {
constexpr const char* TAG = "QualityMetrics";

// Decodes frames of one video stream in [startUs, endUs) (absolute), yielding
// frames with pts in microseconds relative to startUs.
class FrameSource {
 public:
  FrameSource(int fd, const char* label, InterruptFlag* intr, int64_t startRelUs, int64_t durUs, bool seek) {
    in_.open(fd, label, intr, true);
    AVFormatContext* f = in_.ctx();
    idx_ = av_find_best_stream(f, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (idx_ < 0) throwError(ErrorCategory::InvalidInput, "metrics", "No video stream to compare.");
    st_ = f->streams[idx_];
    int64_t base = f->start_time != AV_NOPTS_VALUE ? f->start_time : 0;
    start_ = base + startRelUs;
    end_ = start_ + durUs;
    if (seek && startRelUs > 0) av_seek_frame(f, -1, start_, AVSEEK_FLAG_BACKWARD);
    const AVCodec* dec = avcodec_find_decoder(st_->codecpar->codec_id);
    if (!dec) throwError(ErrorCategory::UnsupportedCodec, "metrics", "Cannot decode stream for comparison.");
    ctx_.reset(avcodec_alloc_context3(dec));
    avcodec_parameters_to_context(ctx_.get(), st_->codecpar);
    ctx_->pkt_timebase = st_->time_base;
    ctx_->thread_count = 4;
    checkAv(avcodec_open2(ctx_.get(), dec, nullptr), "metrics", "opening a decoder");
    pkt_ = makePacket();
    frame_ = makeFrame();
  }

  AVStream* stream() const { return st_; }

  // Returns the next frame in range (owned by this object until the next call), or null at end.
  AVFrame* next() {
    for (;;) {
      if (done_) return nullptr;
      int r = avcodec_receive_frame(ctx_.get(), frame_.get());
      if (r == 0) {
        int64_t pts = frame_->best_effort_timestamp;
        int64_t us = toUs(pts, st_->time_base);
        if (pts == AV_NOPTS_VALUE || us < start_) {
          av_frame_unref(frame_.get());
          continue;
        }
        if (us >= end_) {
          done_ = true;
          return nullptr;
        }
        frame_->pts = us - start_;
        return frame_.get();
      }
      if (r == AVERROR_EOF) {
        done_ = true;
        return nullptr;
      }
      if (eof_) {
        done_ = true;
        return nullptr;
      }
      r = av_read_frame(in_.ctx(), pkt_.get());
      if (r < 0) {
        eof_ = true;
        avcodec_send_packet(ctx_.get(), nullptr);
        continue;
      }
      if (pkt_->stream_index == idx_) avcodec_send_packet(ctx_.get(), pkt_.get());
      av_packet_unref(pkt_.get());
    }
  }

 private:
  InputFile in_;
  int idx_ = -1;
  AVStream* st_ = nullptr;
  int64_t start_ = 0, end_ = 0;
  CodecCtxPtr ctx_;
  PacketPtr pkt_;
  FramePtr frame_;
  bool eof_ = false, done_ = false;
};

std::string bufferArgs(const AVFrame* f, AVRational sar) {
  char buf[256];
  snprintf(buf, sizeof(buf), "video_size=%dx%d:pix_fmt=%d:time_base=1/1000000:pixel_aspect=%d/%d", f->width, f->height,
           f->format, sar.num > 0 ? sar.num : 1, sar.den > 0 ? sar.den : 1);
  return buf;
}

double metaDouble(const AVFrame* f, const char* key) {
  const AVDictionaryEntry* e = av_dict_get(f->metadata, key, nullptr, 0);
  if (!e) return std::numeric_limits<double>::quiet_NaN();
  return std::strtod(e->value, nullptr);
}
}  // namespace

Json compareQuality(int sourceFd, int sampleFd, const Plan& plan, InterruptFlag* interrupt) {
  if (!plan.video || !plan.segment) {
    throwError(ErrorCategory::InvalidConfiguration, "metrics", "Quality comparison needs a video preview segment.");
  }
  const Segment& seg = *plan.segment;
  FrameSource ref(sourceFd, "metrics-ref", interrupt, seg.startUs, seg.durationUs, true);
  FrameSource dist(sampleFd, "metrics-dist", interrupt, 0, seg.durationUs + kUsPerSec, false);

  AVFrame* r = ref.next();
  AVFrame* d = dist.next();
  if (!r || !d) throwError(ErrorCategory::InvalidInput, "metrics", "Not enough frames to compare.");

  // Reference chain: geometry and colour transforms only.
  VideoPlan vp = *plan.video;
  vp.filters.denoise = {};
  vp.filters.sharpen = {};
  vp.filters.deband = false;
  vp.filters.grayscale = false;
  vp.filters.deblock.clear();
  vp.burn.reset();
  vp.width = d->width;
  vp.height = d->height;
  VideoSourceProps props;
  props.width = r->width;
  props.height = r->height;
  props.rotation = vp.mode == VideoMode::Copy ? 0 : streamRotation(ref.stream());
  props.fps = ref.stream()->avg_frame_rate.num > 0 ? av_q2d(ref.stream()->avg_frame_rate) : 0;
  props.hdr = classifyHdr(ref.stream());
  if (vp.mode == VideoMode::Copy) {
    vp.autorotate = false;
    vp.crop = {};
    vp.fpsMode = FpsMode::Source;
    vp.filters = {};
  }
  const AVPixFmtDescriptor* dd = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(d->format));
  std::string cmpFmt = dd && dd->comp[0].depth > 8 ? "yuv420p10le" : "yuv420p";
  std::string refChain = buildVideoFilter(vp, props, cmpFmt);

  FilterGraphPtr g(avfilter_graph_alloc());
  if (!g) throw std::bad_alloc();
  std::string desc = "buffer@ref=" + bufferArgs(r, ref.stream()->sample_aspect_ratio) + "," + refChain +
                     ",split=2[r1][r2];" + "buffer@dist=" + bufferArgs(d, AVRational{1, 1}) + ",format=pix_fmts=" +
                     cmpFmt + ",setsar=1,split=2[d1][d2];" +
                     "[d1][r1]psnr=shortest=1,buffersink@psnr;[d2][r2]ssim=shortest=1,buffersink@ssim";
  AVFilterInOut *ins = nullptr, *outs = nullptr;
  int ret = avfilter_graph_parse2(g.get(), desc.c_str(), &ins, &outs);
  avfilter_inout_free(&ins);
  avfilter_inout_free(&outs);
  checkAv(ret, "metrics", "building the comparison graph");
  checkAv(avfilter_graph_config(g.get(), nullptr), "metrics", "configuring the comparison graph");
  AVFilterContext* srcRef = avfilter_graph_get_filter(g.get(), "buffer@ref");
  AVFilterContext* srcDist = avfilter_graph_get_filter(g.get(), "buffer@dist");
  AVFilterContext* sinkP = avfilter_graph_get_filter(g.get(), "buffersink@psnr");
  AVFilterContext* sinkS = avfilter_graph_get_filter(g.get(), "buffersink@ssim");
  if (!srcRef || !srcDist || !sinkP || !sinkS) throwError(ErrorCategory::Internal, "metrics", "Comparison graph incomplete.");

  double psnrSum = 0, ssimSum = 0;
  int psnrN = 0, ssimN = 0;
  FramePtr out = makeFrame();
  auto drain = [&]() {
    while (av_buffersink_get_frame(sinkP, out.get()) >= 0) {
      double p = metaDouble(out.get(), "lavfi.psnr.psnr_avg");
      if (!std::isnan(p)) {
        psnrSum += std::isinf(p) ? 100.0 : std::min(p, 100.0);
        ++psnrN;
      }
      av_frame_unref(out.get());
    }
    while (av_buffersink_get_frame(sinkS, out.get()) >= 0) {
      double s = metaDouble(out.get(), "lavfi.ssim.All");
      if (!std::isnan(s)) {
        ssimSum += s;
        ++ssimN;
      }
      av_frame_unref(out.get());
    }
  };
  // Interleave by timestamp so the frame-sync queues stay small.
  bool refEnd = false, distEnd = false;
  while (!refEnd || !distEnd) {
    if (interrupt && interrupt->cancel.load()) throwError(ErrorCategory::Cancelled, "metrics", "Cancelled.");
    bool pushRef = !refEnd && (distEnd || (r && d && r->pts <= d->pts));
    if (pushRef) {
      checkAv(av_buffersrc_add_frame_flags(srcRef, r, AV_BUFFERSRC_FLAG_KEEP_REF), "metrics", "feeding reference");
      av_frame_unref(r);
      r = ref.next();
      if (!r) {
        refEnd = true;
        av_buffersrc_add_frame(srcRef, nullptr);
      }
    } else {
      checkAv(av_buffersrc_add_frame_flags(srcDist, d, AV_BUFFERSRC_FLAG_KEEP_REF), "metrics", "feeding sample");
      av_frame_unref(d);
      d = dist.next();
      if (!d) {
        distEnd = true;
        av_buffersrc_add_frame(srcDist, nullptr);
      }
    }
    drain();
  }
  drain();
  if (psnrN == 0 || ssimN == 0) throwError(ErrorCategory::Internal, "metrics", "No frames were compared.");
  double ssim = ssimSum / ssimN;
  double ssimDb = ssim >= 1.0 ? 100.0 : -10.0 * std::log10(1.0 - ssim);
  VC_LOGI(TAG, "psnr=%.2f ssim=%.4f frames=%d", psnrSum / psnrN, ssim, psnrN);
  return Json{{"psnr", psnrSum / psnrN}, {"ssim", ssim}, {"ssimDb", ssimDb}, {"frames", psnrN}};
}

}  // namespace vc
