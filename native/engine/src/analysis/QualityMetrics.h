#pragma once

#include "core/FdIo.h"
#include "core/Util.h"
#include "plan/Plan.h"

namespace vc {

// Objective quality of an encoded preview sample against the same source
// range, after applying the same geometric transforms (rotation, crop, scale,
// frame rate, tone mapping) to the reference. Enhancement filters (denoise,
// sharpen, ...) are deliberately NOT applied to the reference: the metric
// measures distance from the original.
//
// Returns {"psnr": dB, "ssim": 0..1, "ssimDb": dB, "frames": n}.
// These are objective metrics, not a measure of perceived quality.
Json compareQuality(int sourceFd, int sampleFd, const Plan& plan, InterruptFlag* interrupt);

}  // namespace vc
