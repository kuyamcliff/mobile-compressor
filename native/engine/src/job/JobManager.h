#pragma once

#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include "core/FdIo.h"
#include "pipeline/JobControl.h"
#include "plan/Plan.h"

namespace vc {

// Native job lifecycle. Transitions not listed are rejected (and reported as
// false), so out-of-order requests such as "pause after complete" can never
// corrupt state:
//   Created  -> Running | Cancelled
//   Running  -> Paused | Validating | Completed | Failed | Cancelled
//   Paused   -> Running | Cancelled | Failed | Validating | Completed
//   Validating -> Completed | Failed | Cancelled
enum class JobState : int { Created = 0, Running = 1, Paused = 2, Validating = 3, Completed = 4, Failed = 5, Cancelled = 6 };

const char* jobStateName(JobState s);
bool isTerminal(JobState s);
bool canTransition(JobState from, JobState to);

class Job {
 public:
  // Takes duplicates of the descriptors; callers keep their own.
  Job(int64_t id, const std::string& planJson, int inputFd, int outputFd, EventSink sink);
  ~Job();
  Job(const Job&) = delete;
  Job& operator=(const Job&) = delete;

  int64_t id() const { return id_; }
  bool start();
  bool pause();
  bool resume();
  bool cancel();
  void setThrottle(int percent) { control_.setThrottlePercent(percent); }
  JobState state() const;
  Json lastStats() const;
  void join();

 private:
  void run();
  bool transition(JobState to);
  void emit(const char* type, const Json& payload);

  int64_t id_;
  Plan plan_;
  Json validation_;
  UniqueFd in_, out_;
  EventSink sink_;
  JobControl control_;
  mutable std::mutex mu_;
  JobState state_ = JobState::Created;
  Json lastStats_;
  std::thread worker_;
};

class JobManager {
 public:
  int64_t create(const std::string& planJson, int inputFd, int outputFd, EventSink sink);
  std::shared_ptr<Job> get(int64_t id) const;
  // Cancels if needed, waits for the worker, releases all resources.
  void destroy(int64_t id);
  void cancelAll();
  size_t size() const;

 private:
  mutable std::mutex mu_;
  std::map<int64_t, std::shared_ptr<Job>> jobs_;
  std::atomic<int64_t> nextId_{1};
};

}  // namespace vc
