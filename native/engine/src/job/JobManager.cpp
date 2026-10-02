#include "job/JobManager.h"

#include "analysis/Validator.h"
#include "core/Errors.h"
#include "core/Log.h"
#include "pipeline/Transcoder.h"

namespace vc {

namespace {
constexpr const char* TAG = "Job";
}

const char* jobStateName(JobState s) {
  switch (s) {
    case JobState::Created: return "created";
    case JobState::Running: return "running";
    case JobState::Paused: return "paused";
    case JobState::Validating: return "validating";
    case JobState::Completed: return "completed";
    case JobState::Failed: return "failed";
    case JobState::Cancelled: return "cancelled";
  }
  return "unknown";
}

bool isTerminal(JobState s) { return s == JobState::Completed || s == JobState::Failed || s == JobState::Cancelled; }

bool canTransition(JobState from, JobState to) {
  switch (from) {
    case JobState::Created: return to == JobState::Running || to == JobState::Cancelled || to == JobState::Failed;
    case JobState::Running:
      return to == JobState::Paused || to == JobState::Validating || to == JobState::Completed || to == JobState::Failed ||
             to == JobState::Cancelled;
    // The worker may finish (or fail) right as a pause request lands.
    case JobState::Paused:
      return to == JobState::Running || to == JobState::Cancelled || to == JobState::Failed ||
             to == JobState::Validating || to == JobState::Completed;
    case JobState::Validating: return to == JobState::Completed || to == JobState::Failed || to == JobState::Cancelled;
    default: return false;  // terminal
  }
}

Job::Job(int64_t id, const std::string& planJson, int inputFd, int outputFd, EventSink sink)
    : id_(id), sink_(std::move(sink)) {
  Json j;
  try {
    j = Json::parse(planJson);
  } catch (const std::exception& e) {
    throwError(ErrorCategory::InvalidConfiguration, "plan", "The compression configuration could not be read.", e.what());
  }
  plan_ = parsePlan(j);
  if (j.contains("validate") && j["validate"].is_object()) validation_ = j["validate"];
  in_ = UniqueFd::dupOf(inputFd);
  out_ = UniqueFd::dupOf(outputFd);
}

Job::~Job() {
  control_.requestCancel();
  join();
}

void Job::join() {
  if (worker_.joinable() && worker_.get_id() != std::this_thread::get_id()) worker_.join();
}

JobState Job::state() const {
  std::lock_guard<std::mutex> l(mu_);
  return state_;
}

Json Job::lastStats() const {
  std::lock_guard<std::mutex> l(mu_);
  return lastStats_;
}

bool Job::transition(JobState to) {
  std::lock_guard<std::mutex> l(mu_);
  if (!canTransition(state_, to)) {
    VC_LOGD(TAG, "job %lld rejected transition %s -> %s", static_cast<long long>(id_), jobStateName(state_), jobStateName(to));
    return false;
  }
  state_ = to;
  return true;
}

void Job::emit(const char* type, const Json& payload) {
  if (type == std::string(ev::kProgress)) {
    std::lock_guard<std::mutex> l(mu_);
    lastStats_ = payload;
  }
  if (sink_) {
    try {
      sink_(type, payload);
    } catch (...) {
      // A failing listener must never take down the encode thread.
    }
  }
}

bool Job::start() {
  if (!transition(JobState::Running)) return false;
  worker_ = std::thread([this] { run(); });
  return true;
}

bool Job::pause() {
  if (!transition(JobState::Paused)) return false;
  control_.setPaused(true);
  return true;
}

bool Job::resume() {
  if (!transition(JobState::Running)) return false;
  control_.setPaused(false);
  return true;
}

bool Job::cancel() {
  {
    std::lock_guard<std::mutex> l(mu_);
    if (isTerminal(state_)) return false;
  }
  control_.requestCancel();
  // A job that never started is cancelled immediately; a running one reports
  // CANCELLED from its worker once it has released everything.
  std::lock_guard<std::mutex> l(mu_);
  if (state_ == JobState::Created) {
    state_ = JobState::Cancelled;
    if (sink_) sink_(ev::kCancelled, Json::object());
  }
  return true;
}

void Job::run() {
  auto fail = [&](const EngineError& e) {
    if (transition(JobState::Failed)) emit(ev::kFailed, e.toJson());
  };
  try {
    auto sink = [this](const char* type, const Json& p) { emit(type, p); };
    Transcoder t(plan_, in_.get(), out_.get(), control_, sink);
    TranscodeResult r = t.run();
    if (control_.cancelled()) throwError(ErrorCategory::Cancelled, "encode", "Cancelled.");
    emit(ev::kEncodeComplete, r.stats);
    if (!validation_.is_null()) {
      if (!transition(JobState::Validating)) return;
      emit(ev::kValidationStarted, Json::object());
      Json v = validateOutput(out_.get(), validation_, &control_.interrupt);
      emit(ev::kValidationComplete, v);
      if (!v.value("ok", false)) {
        EngineError e;
        e.category = ErrorCategory::OutputValidationFailure;
        e.stage = "validate";
        e.message = "Encoding finished but the output file failed validation. The original file was not modified.";
        for (const auto& c : v["checks"]) {
          if (!c.value("ok", true)) e.context.push_back(c.value("name", "") + ": " + c.value("detail", ""));
        }
        fail(e);
        return;
      }
    }
    transition(JobState::Completed);
  } catch (const EngineException& ex) {
    if (ex.error().category == ErrorCategory::Cancelled || control_.cancelled()) {
      if (transition(JobState::Cancelled)) emit(ev::kCancelled, Json::object());
    } else {
      VC_LOGE(TAG, "job %lld failed: [%s] %s | %s", static_cast<long long>(id_), categoryName(ex.error().category),
              ex.error().message.c_str(), ex.error().detail.c_str());
      fail(ex.error());
    }
  } catch (const std::bad_alloc&) {
    EngineError e;
    e.category = ErrorCategory::MemoryFailure;
    e.stage = "encode";
    e.message = "The device ran out of memory while encoding.";
    e.suggestions = {"Use a lower output resolution", "Run one job at a time", "Close other apps"};
    fail(e);
  } catch (const std::exception& ex) {
    EngineError e;
    e.category = ErrorCategory::Internal;
    e.stage = "encode";
    e.message = "An unexpected engine error occurred.";
    e.detail = ex.what();
    fail(e);
  }
}

int64_t JobManager::create(const std::string& planJson, int inputFd, int outputFd, EventSink sink) {
  int64_t id = nextId_.fetch_add(1);
  auto job = std::make_shared<Job>(id, planJson, inputFd, outputFd, sink);
  {
    std::lock_guard<std::mutex> l(mu_);
    jobs_[id] = job;
  }
  if (sink) sink(ev::kJobCreated, Json{{"handle", id}});
  return id;
}

std::shared_ptr<Job> JobManager::get(int64_t id) const {
  std::lock_guard<std::mutex> l(mu_);
  auto it = jobs_.find(id);
  return it == jobs_.end() ? nullptr : it->second;
}

void JobManager::destroy(int64_t id) {
  std::shared_ptr<Job> job;
  {
    std::lock_guard<std::mutex> l(mu_);
    auto it = jobs_.find(id);
    if (it == jobs_.end()) return;
    job = it->second;
    jobs_.erase(it);
  }
  job->cancel();
  job->join();
}

void JobManager::cancelAll() {
  std::lock_guard<std::mutex> l(mu_);
  for (auto& [id, j] : jobs_) j->cancel();
}

size_t JobManager::size() const {
  std::lock_guard<std::mutex> l(mu_);
  return jobs_.size();
}

}  // namespace vc
