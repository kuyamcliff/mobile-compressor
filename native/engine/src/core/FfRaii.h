#pragma once

// RAII ownership for FFmpeg objects. Every FFmpeg allocation in the engine is held
// by one of these so that early returns and exceptions never leak native memory.

#include <memory>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/dict.h>
#include <libavutil/frame.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace vc {

struct AvFrameDeleter {
  void operator()(AVFrame* f) const { av_frame_free(&f); }
};
struct AvPacketDeleter {
  void operator()(AVPacket* p) const { av_packet_free(&p); }
};
struct AvCodecContextDeleter {
  void operator()(AVCodecContext* c) const { avcodec_free_context(&c); }
};
struct AvFilterGraphDeleter {
  void operator()(AVFilterGraph* g) const { avfilter_graph_free(&g); }
};
struct SwsDeleter {
  void operator()(SwsContext* s) const { sws_freeContext(s); }
};
struct SwrDeleter {
  void operator()(SwrContext* s) const { swr_free(&s); }
};
struct AvBsfDeleter {
  void operator()(AVBSFContext* b) const { av_bsf_free(&b); }
};

using FramePtr = std::unique_ptr<AVFrame, AvFrameDeleter>;
using PacketPtr = std::unique_ptr<AVPacket, AvPacketDeleter>;
using CodecCtxPtr = std::unique_ptr<AVCodecContext, AvCodecContextDeleter>;
using FilterGraphPtr = std::unique_ptr<AVFilterGraph, AvFilterGraphDeleter>;
using SwsPtr = std::unique_ptr<SwsContext, SwsDeleter>;
using SwrPtr = std::unique_ptr<SwrContext, SwrDeleter>;
using BsfPtr = std::unique_ptr<AVBSFContext, AvBsfDeleter>;

inline FramePtr makeFrame() {
  FramePtr f(av_frame_alloc());
  if (!f) throw std::bad_alloc();
  return f;
}
inline PacketPtr makePacket() {
  PacketPtr p(av_packet_alloc());
  if (!p) throw std::bad_alloc();
  return p;
}

// Owns an AVDictionary.
class Dict {
 public:
  Dict() = default;
  Dict(const Dict&) = delete;
  Dict& operator=(const Dict&) = delete;
  Dict(Dict&& o) noexcept : d_(o.d_) { o.d_ = nullptr; }
  ~Dict() { av_dict_free(&d_); }
  void set(const char* k, const char* v) { av_dict_set(&d_, k, v, 0); }
  void set(const char* k, const std::string& v) { av_dict_set(&d_, k, v.c_str(), 0); }
  void setInt(const char* k, int64_t v) { av_dict_set_int(&d_, k, v, 0); }
  AVDictionary** addr() { return &d_; }
  AVDictionary* get() const { return d_; }
  int count() const { return av_dict_count(d_); }

 private:
  AVDictionary* d_ = nullptr;
};

}  // namespace vc
