#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

extern "C" {
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/rational.h>
}

namespace vc {

using Json = nlohmann::json;

constexpr int64_t kUsPerSec = 1000000;

// Timestamp conversion helpers. All engine-level times are int64 microseconds;
// FFmpeg stream timestamps are converted at the boundary with av_rescale_q_rnd,
// which is overflow-safe for multi-hour sources and large time bases.
inline int64_t toUs(int64_t ts, AVRational tb) {
  if (ts == AV_NOPTS_VALUE) return AV_NOPTS_VALUE;
  return av_rescale_q_rnd(ts, tb, AVRational{1, 1000000},
                          static_cast<AVRounding>(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
}
inline int64_t fromUs(int64_t us, AVRational tb) {
  if (us == AV_NOPTS_VALUE) return AV_NOPTS_VALUE;
  return av_rescale_q_rnd(us, AVRational{1, 1000000}, tb,
                          static_cast<AVRounding>(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
}

Json dictToJson(const AVDictionary* d, size_t maxValueLen = 1024, int maxEntries = 64);

// Monotonic wall clock in microseconds.
int64_t monotonicUs();

// Utility to bound untrusted strings before they leave the engine.
std::string truncateUtf8(const std::string& s, size_t maxBytes);

std::string toLower(std::string s);
std::vector<std::string> split(const std::string& s, char sep);
std::string trim(const std::string& s);

// Rounds `v` down to a multiple of `align` (>= align).
inline int alignDown(int v, int align) {
  if (align <= 1) return v;
  int r = (v / align) * align;
  return r < align ? align : r;
}

}  // namespace vc
