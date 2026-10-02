#include "pipeline/FilterGraph.h"

#include "core/Errors.h"
#include "core/Log.h"

extern "C" {
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/channel_layout.h>
#include <libavutil/pixdesc.h>
}

namespace vc {

namespace {
constexpr const char* TAG = "FilterGraph";

void link(AVFilterGraph* g, AVFilterContext* src, AVFilterContext* sink, const std::string& desc) {
  AVFilterInOut* outputs = avfilter_inout_alloc();
  AVFilterInOut* inputs = avfilter_inout_alloc();
  if (!outputs || !inputs) {
    avfilter_inout_free(&outputs);
    avfilter_inout_free(&inputs);
    throw std::bad_alloc();
  }
  outputs->name = av_strdup("in");
  outputs->filter_ctx = src;
  outputs->pad_idx = 0;
  outputs->next = nullptr;
  inputs->name = av_strdup("out");
  inputs->filter_ctx = sink;
  inputs->pad_idx = 0;
  inputs->next = nullptr;
  int ret = avfilter_graph_parse_ptr(g, desc.empty() ? "null" : desc.c_str(), &inputs, &outputs, nullptr);
  avfilter_inout_free(&inputs);
  avfilter_inout_free(&outputs);
  if (ret < 0) {
    EngineError e = fromAvError(ret, "filters", "building the filter chain");
    e.detail += "\nfilters: " + desc;
    if (ret == AVERROR_FILTER_NOT_FOUND) {
      e.category = ErrorCategory::InvalidConfiguration;
      e.message = "A requested filter is not available in this build.";
    }
    throw EngineException(e);
  }
  ret = avfilter_graph_config(g, nullptr);
  if (ret < 0) {
    EngineError e = fromAvError(ret, "filters", "configuring the filter chain");
    e.detail += "\nfilters: " + desc;
    throw EngineException(e);
  }
}
}  // namespace

void FilterGraph::reset() {
  graph_.reset();
  src_ = sink_ = nullptr;
}

void FilterGraph::initVideo(const AVFrame* f, AVRational tb, AVRational sar, AVRational frameRate, const std::string& desc,
                            int threads) {
  reset();
  video_ = true;
  desc_ = desc;
  graph_.reset(avfilter_graph_alloc());
  if (!graph_) throw std::bad_alloc();
  if (threads > 0) graph_->nb_threads = threads;
  src_ = avfilter_graph_alloc_filter(graph_.get(), avfilter_get_by_name("buffer"), "in");
  if (!src_) throw std::bad_alloc();
  AVBufferSrcParameters* par = av_buffersrc_parameters_alloc();
  if (!par) throw std::bad_alloc();
  par->format = f->format;
  par->width = f->width;
  par->height = f->height;
  par->time_base = tb;
  par->sample_aspect_ratio = sar.num > 0 ? sar : AVRational{1, 1};
  par->frame_rate = frameRate;
  par->color_space = f->colorspace;
  par->color_range = f->color_range;
  int ret = av_buffersrc_parameters_set(src_, par);
  av_free(par);
  checkAv(ret, "filters", "configuring the video source");
  checkAv(avfilter_init_str(src_, nullptr), "filters", "initialising the video source");
  checkAv(avfilter_graph_create_filter(&sink_, avfilter_get_by_name("buffersink"), "out", nullptr, nullptr, graph_.get()),
          "filters", "creating the video sink");
  link(graph_.get(), src_, sink_, desc);
  w_ = f->width;
  h_ = f->height;
  fmt_ = f->format;
  out_ = makeFrame();
  VC_LOGI(TAG, "video graph %dx%d %s -> %s", f->width, f->height,
          av_get_pix_fmt_name(static_cast<AVPixelFormat>(f->format)), desc.c_str());
}

void FilterGraph::initAudio(const AVFrame* f, AVRational tb, const std::string& desc, int frameSize) {
  reset();
  video_ = false;
  desc_ = desc;
  graph_.reset(avfilter_graph_alloc());
  if (!graph_) throw std::bad_alloc();
  char layout[256] = {0};
  if (f->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
    AVChannelLayout def;
    av_channel_layout_default(&def, f->ch_layout.nb_channels);
    av_channel_layout_describe(&def, layout, sizeof(layout));
    av_channel_layout_uninit(&def);
  } else {
    av_channel_layout_describe(&f->ch_layout, layout, sizeof(layout));
  }
  char args[512];
  snprintf(args, sizeof(args), "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s", tb.num, tb.den,
           f->sample_rate, av_get_sample_fmt_name(static_cast<AVSampleFormat>(f->format)), layout);
  checkAv(avfilter_graph_create_filter(&src_, avfilter_get_by_name("abuffer"), "in", args, nullptr, graph_.get()),
          "filters", "creating the audio source");
  checkAv(avfilter_graph_create_filter(&sink_, avfilter_get_by_name("abuffersink"), "out", nullptr, nullptr, graph_.get()),
          "filters", "creating the audio sink");
  link(graph_.get(), src_, sink_, desc);
  if (frameSize > 0) av_buffersink_set_frame_size(sink_, frameSize);
  sampleRate_ = f->sample_rate;
  channels_ = f->ch_layout.nb_channels;
  fmt_ = f->format;
  out_ = makeFrame();
}

bool FilterGraph::needsReinit(const AVFrame* f) const {
  if (!graph_) return true;
  if (video_) return f->width != w_ || f->height != h_ || f->format != fmt_;
  return f->sample_rate != sampleRate_ || f->ch_layout.nb_channels != channels_ || f->format != fmt_;
}

AVRational FilterGraph::outputTimeBase() const { return av_buffersink_get_time_base(sink_); }
AVRational FilterGraph::outputFrameRate() const { return av_buffersink_get_frame_rate(sink_); }

void FilterGraph::push(AVFrame* f, const FrameCallback& cb) {
  if (!graph_) return;
  int ret = av_buffersrc_add_frame_flags(src_, f, f ? AV_BUFFERSRC_FLAG_KEEP_REF : 0);
  if (ret < 0 && ret != AVERROR_EOF) checkAv(ret, "filters", "filtering a frame");
  pull(cb);
}

void FilterGraph::pull(const FrameCallback& cb) {
  AVRational tb = av_buffersink_get_time_base(sink_);
  for (;;) {
    int ret = av_buffersink_get_frame(sink_, out_.get());
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return;
    checkAv(ret, "filters", "reading filtered frames");
    cb(out_.get(), tb);
    av_frame_unref(out_.get());
  }
}

}  // namespace vc
