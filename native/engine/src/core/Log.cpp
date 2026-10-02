#include "core/Log.h"

#include <chrono>
#include <cstdio>
#include <mutex>

extern "C" {
#include <libavutil/log.h>
}

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace vc {

std::atomic<int> Log::level_{static_cast<int>(LogLevel::Info)};

namespace {
std::mutex& sinkMutex() {
  static std::mutex m;
  return m;
}
Log::Sink& sinkRef() {
  static Log::Sink sink;
  return sink;
}

void defaultSink(LogLevel level, const char* tag, const std::string& msg) {
#if defined(__ANDROID__)
  int prio = ANDROID_LOG_INFO;
  switch (level) {
    case LogLevel::Error: prio = ANDROID_LOG_ERROR; break;
    case LogLevel::Warn: prio = ANDROID_LOG_WARN; break;
    case LogLevel::Info: prio = ANDROID_LOG_INFO; break;
    case LogLevel::Debug: prio = ANDROID_LOG_DEBUG; break;
    case LogLevel::Trace: prio = ANDROID_LOG_VERBOSE; break;
  }
  __android_log_write(prio, tag, msg.c_str());
#else
  static const char* names[] = {"E", "W", "I", "D", "T"};
  std::fprintf(stderr, "%s/%s: %s\n", names[static_cast<int>(level)], tag, msg.c_str());
#endif
}

thread_local LogCapture* tCapture = nullptr;

void ffmpegCallback(void* avcl, int level, const char* fmt, va_list vl) {
  if (tCapture && level <= AV_LOG_WARNING) {
    char cap[1024];
    int pfx = 1;
    va_list copy;
    va_copy(copy, vl);
    av_log_format_line2(avcl, level, fmt, copy, cap, sizeof(cap), &pfx);
    va_end(copy);
    LogCapture::offer(avcl, cap);
  }
  LogLevel mapped;
  if (level <= AV_LOG_ERROR) mapped = LogLevel::Warn;  // FFmpeg "errors" are often recoverable; the engine reports real failures itself.
  else if (level <= AV_LOG_WARNING) mapped = LogLevel::Debug;
  else if (level <= AV_LOG_VERBOSE) mapped = LogLevel::Trace;
  else return;
  if (!Log::enabled(mapped)) return;
  char line[1024];
  int prefix = 1;
  av_log_format_line2(avcl, level, fmt, vl, line, sizeof(line), &prefix);
  std::string s(line);
  while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
  if (s.empty()) return;
  // Hard cap: never let a malformed file flood the log with huge lines.
  if (s.size() > 512) s.resize(512);
  Log::write(mapped, "ffmpeg", "%s", s.c_str());
}
}  // namespace

LogCapture::LogCapture(const void* target) : target_(target), prev_(tCapture) { tCapture = this; }
LogCapture::~LogCapture() { tCapture = prev_; }
void LogCapture::offer(const void* avcl, const std::string& line) {
  for (LogCapture* c = tCapture; c; c = c->prev_) {
    if (c->target_ == avcl || c->target_ == nullptr) {
      if (c->lines_.size() < 64) c->lines_.push_back(line);
      return;
    }
  }
}

void Log::setLevel(LogLevel level) { level_.store(static_cast<int>(level)); }
LogLevel Log::level() { return static_cast<LogLevel>(level_.load()); }

void Log::setSink(Sink sink) {
  std::lock_guard<std::mutex> lock(sinkMutex());
  sinkRef() = std::move(sink);
}

void Log::vwrite(LogLevel level, const char* tag, const char* fmt, va_list ap) {
  if (!enabled(level)) return;
  char buf[2048];
  std::vsnprintf(buf, sizeof(buf), fmt, ap);
  std::string msg(buf);
  std::lock_guard<std::mutex> lock(sinkMutex());
  if (sinkRef()) sinkRef()(level, tag, msg);
  else defaultSink(level, tag, msg);
}

void Log::write(LogLevel level, const char* tag, const char* fmt, ...) {
  if (!enabled(level)) return;
  va_list ap;
  va_start(ap, fmt);
  vwrite(level, tag, fmt, ap);
  va_end(ap);
}

void Log::installFfmpegBridge() {
  av_log_set_level(AV_LOG_VERBOSE);
  av_log_set_flags(AV_LOG_SKIP_REPEATED);
  av_log_set_callback(ffmpegCallback);
}

}  // namespace vc
