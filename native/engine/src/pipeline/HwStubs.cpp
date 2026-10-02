// Host (non-Android) build: the MediaCodec pipelines do not exist. The planner
// never selects them off-device; if a plan asks for one anyway it fails loudly.
#include "core/Errors.h"
#include "pipeline/Transcoder.h"

namespace vc {

bool hardwarePipelineAvailable() { return false; }

std::unique_ptr<StreamHandler> createHardwareVideoHandler(TranscodeContext&, AVStream*, int) {
  throwError(ErrorCategory::HardwareCodecFailure, "pipeline", "Hardware encoding is only available on Android devices.");
}

std::unique_ptr<VideoEncoderBackend> createMediaCodecBufferEncoder(EncoderSetupInfo) {
  throwError(ErrorCategory::HardwareCodecFailure, "pipeline", "Hardware encoding is only available on Android devices.");
}

}  // namespace vc
