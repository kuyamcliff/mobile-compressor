#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "core/FdIo.h"
#include "pipeline/JobControl.h"
#include "pipeline/Muxer.h"
#include "pipeline/Progress.h"
#include "pipeline/VideoEncoder.h"
#include "plan/Plan.h"

namespace vc {

// Shared state for one pass over the input. Timestamp convention:
//  * "absolute" = source stream time converted to microseconds,
//  * output time = absolute - baseUs (so the first output sample is at ~0),
//  * frames with absolute time outside [segStartAbsUs, segEndAbsUs) are dropped.
struct TranscodeContext {
  const Plan* plan = nullptr;
  Muxer* muxer = nullptr;  // null during an analysis pass (two-pass pass 1)
  ProgressTracker* progress = nullptr;
  JobControl* control = nullptr;
  int64_t baseUs = 0;
  int64_t segStartAbsUs = INT64_MIN;
  int64_t segEndAbsUs = INT64_MAX;
  int pass = 0;  // 0 single, 1 analysis, 2 final
  std::string passStats;
  int64_t decodeErrors = 0;
  std::vector<std::string> warnings;
  bool globalHeader = false;

  int64_t outTs(int64_t ts, AVRational tb) const {
    return ts == AV_NOPTS_VALUE ? AV_NOPTS_VALUE : ts - av_rescale_q(baseUs, AVRational{1, 1000000}, tb);
  }
  bool beforeStart(int64_t absUs) const { return absUs != AV_NOPTS_VALUE && absUs < segStartAbsUs; }
  bool afterEnd(int64_t absUs) const { return absUs != AV_NOPTS_VALUE && absUs >= segEndAbsUs; }
  void noteDecodeError(const char* what);
};

class StreamHandler {
 public:
  virtual ~StreamHandler() = default;
  // `pkt` carries source timestamps; the handler owns conversion.
  virtual void onPacket(AVPacket* pkt) = 0;
  virtual void finish() = 0;
  // True once the handler has seen data past the segment end.
  virtual bool reachedEnd() const { return false; }
  // Video encode statistics for the report.
  virtual std::string encoderDescription() const { return {}; }
  virtual std::string passStats() const { return {}; }
};

// Factories implemented by the Android hardware layer (stubs on the host).
std::unique_ptr<StreamHandler> createHardwareVideoHandler(TranscodeContext& ctx, AVStream* in, int outIdx);
std::unique_ptr<VideoEncoderBackend> createMediaCodecBufferEncoder(EncoderSetupInfo info);
bool hardwarePipelineAvailable();

// Applies codec tags and parameter fix-ups when a stream is muxed.
void prepareOutputCodecpar(const std::string& containerFormat, AVStream* os);

struct TranscodeResult {
  Json stats;
};

class Transcoder {
 public:
  Transcoder(Plan plan, int inputFd, int outputFd, JobControl& control, EventSink sink);
  ~Transcoder();
  TranscodeResult run();

 private:
  void runPass(int pass, TranscodeResult& result);
  void extractBurnSubtitles();
  void emitProgress(bool force);

  Plan plan_;
  int inputFd_;
  int outputFd_;
  JobControl& control_;
  EventSink sink_;
  ProgressTracker progress_;
  int64_t lastEmitUs_ = 0;
  std::string passStats_;
};

}  // namespace vc
