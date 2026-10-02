#pragma once

#include "core/FdIo.h"
#include "core/Util.h"

namespace vc {

// Post-encode validation. The output is only exposed to the user after every
// check passes. `expect` keys (all optional):
//   container ("mp4"|"matroska"|...), videoCodec, width, height, durationUs,
//   audioTracks, subtitleTracks, expectVideo (bool)
// Returns {"ok":bool, "checks":[{name, ok, detail}], "info":{...}}.
Json validateOutput(int fd, const Json& expect, InterruptFlag* interrupt);

}  // namespace vc
