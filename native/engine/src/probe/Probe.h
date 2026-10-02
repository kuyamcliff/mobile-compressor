#pragma once

#include <string>

#include "core/FdIo.h"
#include "core/Util.h"

namespace vc {

// Source analysis. Reads container headers plus a bounded number of packets
// (never decodes the whole file) and returns a JSON description consumed by the
// Kotlin SourceInfo model.
Json probeSource(int fd, const std::string& displayName, InterruptFlag* interrupt);

// Builds the stream description for an already-opened input (shared with the
// transcoder and the output validator).
Json describeInput(AVFormatContext* fmt, int64_t fileSize);

// HDR classification of a video stream: "sdr", "hdr10", "hdr10plus", "hlg",
// "dolby_vision", "pq" (PQ without static metadata).
std::string classifyHdr(const AVStream* st);

// Rotation (degrees clockwise, normalised to 0/90/180/270) from the display matrix.
int streamRotation(const AVStream* st);

int pixFmtBitDepth(int pixFmt);

std::string dumpJson(const Json& j);

}  // namespace vc
