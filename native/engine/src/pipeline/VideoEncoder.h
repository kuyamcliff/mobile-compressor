#pragma once

#include <functional>
#include <string>

#include "core/FfRaii.h"
#include "plan/Plan.h"

namespace vc {

// Receives encoded packets. `tb` is the time base of the packet timestamps.
using PacketCallback = std::function<void(AVPacket* pkt, AVRational tb)>;
// Called once the encoder's final codec parameters are known.
using ParamsCallback = std::function<void(const AVCodecParameters* par, AVRational tb)>;

// A video encoder fed with filtered AVFrames: FFmpeg software encoders, or the
// Android MediaCodec ByteBuffer encoder used by the hybrid pipeline.
class VideoEncoderBackend {
 public:
  virtual ~VideoEncoderBackend() = default;
  // Opens using the first frame's properties (size, format, colour metadata).
  virtual void open(const AVFrame* first, AVRational frameTb, AVRational frameRate) = 0;
  virtual bool isOpen() const = 0;
  // Takes a frame (pts in frameTb) or nullptr to flush.
  virtual void encode(const AVFrame* frame) = 0;
  virtual std::string describe() const = 0;
  // Two-pass support (software only): pass 1 statistics.
  virtual std::string passStats() const { return {}; }
};

struct EncoderSetupInfo {
  const VideoPlan* plan = nullptr;
  const AVStream* source = nullptr;  // for HDR side data / colour defaults
  int pass = 0;                      // 0 single pass, 1 or 2 for two-pass
  std::string passStatsIn;
  bool globalHeader = true;
  PacketCallback onPacket;
  ParamsCallback onParams;
};

class FfVideoEncoder : public VideoEncoderBackend {
 public:
  explicit FfVideoEncoder(EncoderSetupInfo info);
  void open(const AVFrame* first, AVRational frameTb, AVRational frameRate) override;
  bool isOpen() const override { return ctx_ != nullptr; }
  void encode(const AVFrame* frame) override;
  std::string describe() const override;
  std::string passStats() const override { return stats_; }
  AVPixelFormat pixFmt() const { return pixFmt_; }

 private:
  void drain();
  std::string v_name() const;

  EncoderSetupInfo info_;
  const AVCodec* codec_ = nullptr;
  CodecCtxPtr ctx_;
  AVRational frameTb_{1, 1};
  AVPixelFormat pixFmt_ = AV_PIX_FMT_NONE;
  int64_t lastPts_ = AV_NOPTS_VALUE;
  FramePtr scratch_;
  PacketPtr pkt_;
  std::string stats_;
};

// Resolves the pixel format the filter graph must produce for a plan.
std::string encoderPixFmtFor(const VideoPlan& v);

}  // namespace vc
