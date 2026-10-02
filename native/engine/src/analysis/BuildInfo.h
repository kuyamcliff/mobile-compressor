#pragma once

#include "core/Util.h"

namespace vc {

constexpr const char* kEngineVersion = "1.0.0";

// FFmpeg version, configuration, licence and the enabled components, for the
// diagnostics and licence screens.
Json buildInfo();

// Whether a container can hold a codec (avformat_query_codec), for UI checks:
// returns 1 yes, 0 no, -1 unknown.
int containerSupportsCodec(const std::string& muxer, const std::string& codecName);

}  // namespace vc
