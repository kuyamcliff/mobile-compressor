#pragma once

#include <string>
#include <vector>

#include "core/FfRaii.h"
#include "plan/Plan.h"

namespace vc {

// Collects FFmpeg log lines emitted on the calling thread for one object
// (e.g. an encoder during avcodec_open2) so that warnings like Kvazaar's
// "Invalid option" can be turned into a hard, explained failure.
class LogCapture {
 public:
  explicit LogCapture(const void* target);
  ~LogCapture();
  const std::vector<std::string>& lines() const { return lines_; }
  static void offer(const void* avcl, const std::string& line);

 private:
  const void* target_;
  std::vector<std::string> lines_;
  LogCapture* prev_;
};

// Maps the generic plan (rate control, GOP, B-frames, preset/tune/profile,
// expert options) onto one FFmpeg software encoder. Each encoder has its own
// quality scale; nothing here pretends CRF values are interchangeable.
void configureSoftwareVideoEncoder(AVCodecContext* ctx, const VideoPlan& v, AVRational frameRate, Dict& opts);

// Opens the encoder; unknown/invalid expert options become InvalidEncoderOption.
void openEncoder(AVCodecContext* ctx, const AVCodec* codec, Dict& opts, const std::string& what);

// Pixel format the encoder will receive (validated against the encoder's list).
AVPixelFormat chooseEncoderPixFmt(const AVCodec* codec, const std::string& requested);

// Validates `options` against the encoder's AVOptions without opening it.
// Returns an empty string when valid, else a human-readable error.
std::string validateEncoderOptions(const std::string& encoderName,
                                   const std::vector<std::pair<std::string, std::string>>& options);

// Lists an encoder's private options (name, help, type, default) for the expert UI.
std::string describeEncoderOptions(const std::string& encoderName);

}  // namespace vc
