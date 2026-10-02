#pragma once

#include <functional>
#include <string>

#include "core/FfRaii.h"

namespace vc {

// A single-input, single-output libavfilter graph. Owns all filter contexts
// through the AVFilterGraph. Frames are pushed in, and every available output
// frame is handed to the callback (which must not keep a reference).
class FilterGraph {
 public:
  using FrameCallback = std::function<void(AVFrame* out, AVRational tb)>;

  // Video: configured from the first decoded frame.
  void initVideo(const AVFrame* f, AVRational tb, AVRational sar, AVRational frameRate, const std::string& desc,
                 int threads);
  // Audio: configured from the first decoded frame.
  void initAudio(const AVFrame* f, AVRational tb, const std::string& desc, int frameSize);

  bool initialized() const { return graph_ != nullptr; }
  // True if the frame's format differs from what the graph was configured for
  // (e.g. a mid-stream resolution change), requiring a rebuild.
  bool needsReinit(const AVFrame* f) const;

  void push(AVFrame* f, const FrameCallback& cb);  // f == nullptr flushes
  AVRational outputTimeBase() const;
  AVRational outputFrameRate() const;
  const std::string& description() const { return desc_; }
  void reset();

 private:
  void pull(const FrameCallback& cb);

  FilterGraphPtr graph_;
  AVFilterContext* src_ = nullptr;
  AVFilterContext* sink_ = nullptr;
  FramePtr out_;
  std::string desc_;
  bool video_ = true;
  int w_ = 0, h_ = 0, fmt_ = -1, sampleRate_ = 0, channels_ = 0;
};

}  // namespace vc
