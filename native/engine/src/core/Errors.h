#pragma once

#include <stdexcept>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

namespace vc {

// Stable error categories shared with the Kotlin layer (see EngineErrorCategory.kt).
// The numeric values are part of the JNI contract: never renumber.
enum class ErrorCategory : int {
  None = 0,
  InvalidInput = 1,
  UnsupportedCodec = 2,
  UnsupportedProfile = 3,
  UnsupportedPixelFormat = 4,
  HardwareCodecFailure = 5,
  FfmpegFailure = 6,
  OutputPathFailure = 7,
  InsufficientStorage = 8,
  PermissionDenied = 9,
  ContentUriExpired = 10,
  SourceDisappeared = 11,
  ThermalRestriction = 12,
  ProcessInterruption = 13,
  MemoryFailure = 14,
  OutputValidationFailure = 15,
  InvalidConfiguration = 16,
  InvalidEncoderOption = 17,
  Cancelled = 18,
  Internal = 19,
};

const char* categoryName(ErrorCategory c);

// A failure with enough context for the UI to explain what happened and what the
// user can try next. `detail` is technical (FFmpeg/MediaCodec text); `message` is
// human-readable; `suggestions` are actionable alternatives.
struct EngineError {
  ErrorCategory category = ErrorCategory::None;
  int code = 0;  // FFmpeg AVERROR or media_status_t when applicable
  std::string message;
  std::string detail;
  std::string stage;  // e.g. "open_input", "video_encoder_open", "mux"
  std::vector<std::string> context;
  std::vector<std::string> suggestions;

  bool ok() const { return category == ErrorCategory::None; }
  nlohmann::json toJson() const;
};

class EngineException : public std::runtime_error {
 public:
  explicit EngineException(EngineError e) : std::runtime_error(e.message), error_(std::move(e)) {}
  const EngineError& error() const { return error_; }

 private:
  EngineError error_;
};

std::string avErrorString(int err);

// Translate an FFmpeg error code into a categorized, human-readable error.
EngineError fromAvError(int err, const std::string& stage, const std::string& what,
                        std::vector<std::string> context = {});

[[noreturn]] void throwError(ErrorCategory c, const std::string& stage, const std::string& message,
                             const std::string& detail = {}, std::vector<std::string> suggestions = {});

// Throws a translated EngineException when err < 0, returns err otherwise.
int checkAv(int err, const std::string& stage, const std::string& what, std::vector<std::string> context = {});

}  // namespace vc
