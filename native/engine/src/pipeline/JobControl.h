#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

#include "core/FdIo.h"
#include "core/Util.h"

namespace vc {

// Event names shared with Kotlin (EngineEvent.kt).
namespace ev {
constexpr const char* kJobCreated = "JOB_CREATED";
constexpr const char* kAnalysisStarted = "ANALYSIS_STARTED";
constexpr const char* kAnalysisComplete = "ANALYSIS_COMPLETE";
constexpr const char* kPipelineSelected = "PIPELINE_SELECTED";
constexpr const char* kEncodeStarted = "ENCODE_STARTED";
constexpr const char* kProgress = "PROGRESS";
constexpr const char* kPaused = "PAUSED";
constexpr const char* kResumed = "RESUMED";
constexpr const char* kThermalWarning = "THERMAL_WARNING";
constexpr const char* kStorageWarning = "STORAGE_WARNING";
constexpr const char* kEncodeComplete = "ENCODE_COMPLETE";
constexpr const char* kValidationStarted = "VALIDATION_STARTED";
constexpr const char* kValidationComplete = "VALIDATION_COMPLETE";
constexpr const char* kFailed = "FAILED";
constexpr const char* kCancelled = "CANCELLED";
constexpr const char* kLog = "LOG";
}  // namespace ev

using EventSink = std::function<void(const char* type, const Json& payload)>;

// Cooperative control shared between a job's worker thread and its owners.
// Pause is real: the worker blocks between packets and holds no partial state
// that could time out, so resume continues the same encode.
class JobControl {
 public:
  InterruptFlag interrupt;

  void requestCancel() {
    interrupt.cancel.store(true);
    std::lock_guard<std::mutex> l(mu_);
    cv_.notify_all();
  }
  bool cancelled() const { return interrupt.cancel.load(); }

  void setPaused(bool p) {
    std::lock_guard<std::mutex> l(mu_);
    paused_ = p;
    cv_.notify_all();
  }
  bool paused() const {
    std::lock_guard<std::mutex> l(mu_);
    return paused_;
  }

  // 0 = full speed, up to 90 = spend at most 10% of wall time working.
  void setThrottlePercent(int p) { throttle_.store(std::clamp(p, 0, 90)); }
  int throttlePercent() const { return throttle_.load(); }

  // Called by the worker between units of work. Blocks while paused. Returns
  // false when the job was cancelled. `onPauseChange` is invoked on transitions.
  bool checkpoint(const std::function<void(bool)>& onPauseChange) {
    applyThrottle();
    std::unique_lock<std::mutex> l(mu_);
    if (paused_ && !cancelled()) {
      l.unlock();
      if (onPauseChange) onPauseChange(true);
      l.lock();
      cv_.wait(l, [&] { return !paused_ || cancelled(); });
      l.unlock();
      if (onPauseChange) onPauseChange(false);
      workStartUs_ = monotonicUs();
      return !cancelled();
    }
    return !cancelled();
  }

 private:
  // Duty-cycle throttling used for thermal protection: after `busy` time of
  // work, wait busy * t/(100-t) so average utilisation drops to (100-t)%.
  void applyThrottle() {
    int t = throttle_.load();
    int64_t now = monotonicUs();
    if (workStartUs_ == 0) workStartUs_ = now;
    if (t <= 0) {
      workStartUs_ = now;
      return;
    }
    int64_t busy = now - workStartUs_;
    if (busy < 50000) return;  // throttle in >= 50 ms slices
    int64_t waitUs = busy * t / (100 - t);
    std::unique_lock<std::mutex> l(mu_);
    cv_.wait_for(l, std::chrono::microseconds(std::min<int64_t>(waitUs, 2000000)), [&] { return cancelled(); });
    workStartUs_ = monotonicUs();
  }

  mutable std::mutex mu_;
  std::condition_variable cv_;
  bool paused_ = false;
  std::atomic<int> throttle_{0};
  int64_t workStartUs_ = 0;
};

}  // namespace vc
