#include "core/Errors.h"

#include <cerrno>

extern "C" {
#include <libavutil/error.h>
}

namespace vc {

const char* categoryName(ErrorCategory c) {
  switch (c) {
    case ErrorCategory::None: return "none";
    case ErrorCategory::InvalidInput: return "invalid_input";
    case ErrorCategory::UnsupportedCodec: return "unsupported_codec";
    case ErrorCategory::UnsupportedProfile: return "unsupported_profile";
    case ErrorCategory::UnsupportedPixelFormat: return "unsupported_pixel_format";
    case ErrorCategory::HardwareCodecFailure: return "hardware_codec_failure";
    case ErrorCategory::FfmpegFailure: return "ffmpeg_failure";
    case ErrorCategory::OutputPathFailure: return "output_path_failure";
    case ErrorCategory::InsufficientStorage: return "insufficient_storage";
    case ErrorCategory::PermissionDenied: return "permission_denied";
    case ErrorCategory::ContentUriExpired: return "content_uri_expired";
    case ErrorCategory::SourceDisappeared: return "source_disappeared";
    case ErrorCategory::ThermalRestriction: return "thermal_restriction";
    case ErrorCategory::ProcessInterruption: return "process_interruption";
    case ErrorCategory::MemoryFailure: return "memory_failure";
    case ErrorCategory::OutputValidationFailure: return "output_validation_failure";
    case ErrorCategory::InvalidConfiguration: return "invalid_configuration";
    case ErrorCategory::InvalidEncoderOption: return "invalid_encoder_option";
    case ErrorCategory::Cancelled: return "cancelled";
    case ErrorCategory::Internal: return "internal";
  }
  return "internal";
}

nlohmann::json EngineError::toJson() const {
  return nlohmann::json{{"category", static_cast<int>(category)},
                        {"categoryName", categoryName(category)},
                        {"code", code},
                        {"message", message},
                        {"detail", detail},
                        {"stage", stage},
                        {"context", context},
                        {"suggestions", suggestions}};
}

std::string avErrorString(int err) {
  char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
  av_strerror(err, buf, sizeof(buf));
  return buf;
}

EngineError fromAvError(int err, const std::string& stage, const std::string& what,
                        std::vector<std::string> context) {
  EngineError e;
  e.code = err;
  e.stage = stage;
  e.detail = "FFmpeg error: " + avErrorString(err);
  e.context = std::move(context);
  switch (err) {
    case AVERROR(ENOSPC):
      e.category = ErrorCategory::InsufficientStorage;
      e.message = "The device ran out of storage while writing the output.";
      e.suggestions = {"Free up storage space", "Choose a smaller target size", "Choose another output folder"};
      break;
    case AVERROR(EACCES):
    case AVERROR(EPERM):
      e.category = ErrorCategory::PermissionDenied;
      e.message = "Permission to access the file was denied (" + what + ").";
      e.suggestions = {"Re-select the file or output folder to grant access again"};
      break;
    case AVERROR(ENOENT):
    case AVERROR(EBADF):
      e.category = ErrorCategory::SourceDisappeared;
      e.message = "The file is no longer available (" + what + ").";
      e.suggestions = {"Make sure the file was not moved, deleted or its storage removed"};
      break;
    case AVERROR(EIO):
      e.category = stage.rfind("output", 0) == 0 || stage == "mux" ? ErrorCategory::OutputPathFailure
                                                                    : ErrorCategory::SourceDisappeared;
      e.message = "An I/O error occurred (" + what + ").";
      e.suggestions = {"Check that the storage is still mounted and readable"};
      break;
    case AVERROR(ENOMEM):
      e.category = ErrorCategory::MemoryFailure;
      e.message = "Not enough memory to continue (" + what + ").";
      e.suggestions = {"Close other apps", "Use a lower output resolution", "Run one job at a time"};
      break;
    case AVERROR_INVALIDDATA:
      e.category = ErrorCategory::InvalidInput;
      e.message = "The file contains invalid or corrupted data (" + what + ").";
      e.suggestions = {"Try a different copy of the video", "Re-download or re-export the source"};
      break;
    case AVERROR_DECODER_NOT_FOUND:
    case AVERROR_DEMUXER_NOT_FOUND:
      e.category = ErrorCategory::UnsupportedCodec;
      e.message = "This format or codec is not supported by the built-in engine (" + what + ").";
      break;
    case AVERROR_ENCODER_NOT_FOUND:
    case AVERROR_MUXER_NOT_FOUND:
      e.category = ErrorCategory::UnsupportedCodec;
      e.message = "The selected output codec or container is not available (" + what + ").";
      break;
    case AVERROR_EXIT:
      e.category = ErrorCategory::Cancelled;
      e.message = "Cancelled.";
      break;
    case AVERROR(EINVAL):
      e.category = ErrorCategory::FfmpegFailure;
      e.message = "The " + what + " rejected the configuration (invalid argument).";
      e.suggestions = {"Try a different profile, pixel format or resolution", "Try software encoding"};
      break;
    case AVERROR_BUG:
    case AVERROR_EXTERNAL:
      e.category = ErrorCategory::FfmpegFailure;
      e.message = "The " + what + " rejected this configuration.";
      e.suggestions = {"Use the default encoder preset and GOP settings", "Remove custom encoder options",
                       "Try a different codec"};
      break;
    case AVERROR_PATCHWELCOME:
      e.category = ErrorCategory::UnsupportedCodec;
      e.message = "This stream uses a feature the engine does not implement (" + what + ").";
      break;
    default:
      e.category = ErrorCategory::FfmpegFailure;
      e.message = "Media processing failed (" + what + ").";
      break;
  }
  return e;
}

void throwError(ErrorCategory c, const std::string& stage, const std::string& message, const std::string& detail,
                std::vector<std::string> suggestions) {
  EngineError e;
  e.category = c;
  e.stage = stage;
  e.message = message;
  e.detail = detail;
  e.suggestions = std::move(suggestions);
  throw EngineException(std::move(e));
}

int checkAv(int err, const std::string& stage, const std::string& what, std::vector<std::string> context) {
  if (err < 0) throw EngineException(fromAvError(err, stage, what, std::move(context)));
  return err;
}

}  // namespace vc
