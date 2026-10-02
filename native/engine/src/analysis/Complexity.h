#pragma once

#include "core/FdIo.h"
#include "core/Util.h"

namespace vc {

// Samples a few short windows (beginning / middle / end) at reduced resolution
// and measures, deterministically:
//   motion      - mean absolute luma change between frames (signalstats YDIF)
//   detail      - mean luma entropy (bits)
//   brightness  - mean luma (0..255)
//   darkFraction- fraction of frames with mean luma < 50 (banding risk)
//   noise       - mean temporal-outlier fraction (signalstats TOUT)
//   sceneChangesPerMinute - scdet detections
// and derives a complexity score used to tighten size estimates. Only
// `windows` x `windowSeconds` of video are decoded, never the whole file.
Json analyzeComplexity(int fd, int windows, double windowSeconds, InterruptFlag* interrupt);

}  // namespace vc
