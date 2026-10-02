#pragma once

#include <cstdint>
#include <functional>
#include <mutex>

#include "core/Util.h"

namespace vc {

// Real progress derived from encoded media timestamps (never from loop counts).
// Speed and ETA are smoothed with an exponential moving average so the ETA does
// not jump every update. The clock is injectable for unit tests.
class ProgressTracker {
 public:
  using Clock = std::function<int64_t()>;  // microseconds, monotonic

  explicit ProgressTracker(Clock clock = monotonicUs);

  // totalUs: media duration being processed (segment or whole file); 0 = unknown.
  void begin(int64_t totalUs, double sourceFps, int passes);
  void setPass(int passIndex);  // 0-based
  void onEncodedVideoFrame(int64_t mediaUs);
  void onMediaTime(int64_t mediaUs);
  void setOutputBytes(int64_t bytes);
  void addInputBytes(int64_t bytes);
  void setPaused(bool paused);

  struct Snapshot {
    double progress = -1;    // 0..1, -1 when indeterminate
    double currentFps = 0;   // instantaneous (last window)
    double averageFps = 0;
    double speed = 0;        // media seconds per wall second (smoothed)
    int64_t etaUs = -1;      // -1 unknown
    int64_t elapsedUs = 0;   // excludes paused time
    int64_t mediaUs = 0;
    int64_t frames = 0;
    int64_t outputBytes = 0;
    int64_t projectedBytes = -1;
    int64_t outputBitrate = 0;  // bits/s of media processed so far
    int64_t inputBitrate = 0;
    int pass = 0, passes = 1;
  };
  // Updates the smoothing state; call at the reporting interval.
  Snapshot sample();
  Json toJson(const Snapshot& s) const;

  // EMA weight used per sample; exposed for tests.
  static constexpr double kAlpha = 0.15;

 private:
  int64_t activeNowUs() const;  // wall time excluding pauses

  mutable std::mutex mu_;
  Clock clock_;
  int64_t totalUs_ = 0;
  double sourceFps_ = 0;
  int passes_ = 1, pass_ = 0;
  int64_t startUs_ = 0;
  int64_t pausedAccumUs_ = 0;
  int64_t pauseStartUs_ = -1;
  int64_t mediaUs_ = 0;
  int64_t frames_ = 0;
  int64_t outputBytes_ = 0;
  int64_t inputBytes_ = 0;
  // smoothing
  int64_t lastSampleWall_ = -1;
  int64_t lastSampleMedia_ = 0;
  int64_t lastSampleFrames_ = 0;
  double emaSpeed_ = 0;
  double instFps_ = 0;
  int64_t passStartWall_ = 0;
  bool haveEma_ = false;
};

}  // namespace vc
