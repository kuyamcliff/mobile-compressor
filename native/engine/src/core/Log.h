#pragma once

#include <atomic>
#include <cstdarg>
#include <functional>
#include <string>
#include <vector>

namespace vc {

enum class LogLevel : int { Error = 0, Warn = 1, Info = 2, Debug = 3, Trace = 4 };

// Process-wide structured logger. Messages are "key=value"-friendly single lines;
// the sink decides where they go (logcat on Android, stderr on the host).
// Never pass packet payloads or file contents to the logger.
class Log {
 public:
  using Sink = std::function<void(LogLevel, const char* tag, const std::string& msg)>;

  static void setLevel(LogLevel level);
  static LogLevel level();
  static void setSink(Sink sink);
  static bool enabled(LogLevel level) { return static_cast<int>(level) <= level_.load(std::memory_order_relaxed); }
  static void write(LogLevel level, const char* tag, const char* fmt, ...) __attribute__((format(printf, 3, 4)));
  static void vwrite(LogLevel level, const char* tag, const char* fmt, va_list ap);

  // Routes FFmpeg's av_log through this logger (mapped levels, rate-limited).
  static void installFfmpegBridge();

 private:
  static std::atomic<int> level_;
};

// Collects FFmpeg log lines emitted on the calling thread for one object
// (e.g. an encoder during avcodec_open2) so that warnings like Kvazaar's
// "Invalid option" can be turned into a hard, explained failure.
class LogCapture {
 public:
  explicit LogCapture(const void* target);
  ~LogCapture();
  LogCapture(const LogCapture&) = delete;
  LogCapture& operator=(const LogCapture&) = delete;
  const std::vector<std::string>& lines() const { return lines_; }
  static void offer(const void* avcl, const std::string& line);

 private:
  const void* target_;
  std::vector<std::string> lines_;
  LogCapture* prev_;
};

}  // namespace vc

#define VC_LOGE(tag, ...) ::vc::Log::write(::vc::LogLevel::Error, tag, __VA_ARGS__)
#define VC_LOGW(tag, ...) ::vc::Log::write(::vc::LogLevel::Warn, tag, __VA_ARGS__)
#define VC_LOGI(tag, ...) ::vc::Log::write(::vc::LogLevel::Info, tag, __VA_ARGS__)
#define VC_LOGD(tag, ...) \
  do { if (::vc::Log::enabled(::vc::LogLevel::Debug)) ::vc::Log::write(::vc::LogLevel::Debug, tag, __VA_ARGS__); } while (0)
#define VC_LOGT(tag, ...) \
  do { if (::vc::Log::enabled(::vc::LogLevel::Trace)) ::vc::Log::write(::vc::LogLevel::Trace, tag, __VA_ARGS__); } while (0)
