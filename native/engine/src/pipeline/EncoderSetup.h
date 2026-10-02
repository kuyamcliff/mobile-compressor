#pragma once

#include <string>
#include <vector>

#include "core/FfRaii.h"
#include "core/Log.h"
#include "plan/Plan.h"

namespace vc {

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
