#pragma once

// Android NDK MediaCodec helpers: RAII owners, codec-specific-data extraction
// from FFmpeg codec parameters, MediaFormat construction for encoders, and
// error translation. Android-only.

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <memory>
#include <string>
#include <vector>

#include "core/Errors.h"
#include "core/FfRaii.h"
#include "plan/Plan.h"

namespace vc {

struct MediaCodecDeleter {
  void operator()(AMediaCodec* c) const {
    if (c) {
      AMediaCodec_stop(c);
      AMediaCodec_delete(c);
    }
  }
};
struct MediaFormatDeleter {
  void operator()(AMediaFormat* f) const {
    if (f) AMediaFormat_delete(f);
  }
};
using MediaCodecPtr = std::unique_ptr<AMediaCodec, MediaCodecDeleter>;
using MediaFormatPtr = std::unique_ptr<AMediaFormat, MediaFormatDeleter>;

// MediaCodecInfo.CodecCapabilities constants used by the engine.
namespace mc {
constexpr int32_t COLOR_FormatSurface = 0x7F000789;
constexpr int32_t COLOR_FormatYUV420Planar = 19;
constexpr int32_t COLOR_FormatYUV420SemiPlanar = 21;
constexpr int32_t BUFFER_FLAG_KEY_FRAME = 1;
constexpr int32_t BUFFER_FLAG_CODEC_CONFIG = 2;
constexpr int32_t BUFFER_FLAG_END_OF_STREAM = 4;
constexpr int32_t COLOR_STANDARD_BT709 = 1;
constexpr int32_t COLOR_STANDARD_BT601_PAL = 2;
constexpr int32_t COLOR_STANDARD_BT2020 = 6;
constexpr int32_t COLOR_RANGE_FULL = 1;
constexpr int32_t COLOR_RANGE_LIMITED = 2;
constexpr int32_t COLOR_TRANSFER_SDR_VIDEO = 3;
}  // namespace mc

// Converts a source stream's packets to the layout MediaCodec decoders expect
// (Annex B for H.264/HEVC) and provides csd-0/csd-1.
class DecoderInputAdapter {
 public:
  explicit DecoderInputAdapter(const AVStream* st);
  const std::string& mime() const { return mime_; }
  void applyCsd(AMediaFormat* fmt) const;
  // Converts in place (pkt may be replaced). Returns false if the packet should be skipped.
  bool convert(AVPacket* pkt);

 private:
  std::string mime_;
  std::vector<uint8_t> csd0_, csd1_;
  BsfPtr bsf_;
  PacketPtr tmp_;
};

std::string mimeForCodec(AVCodecID id);
AVCodecID codecForMime(const std::string& mime);

// Builds the encoder MediaFormat from the plan.
MediaFormatPtr buildEncoderFormat(const VideoPlan& v, int width, int height, double fps, int32_t colorFormat);

// Turns media_status_t into an explained hardware failure.
[[noreturn]] void throwMediaError(media_status_t st, const std::string& stage, const std::string& what,
                                  const VideoPlan* v, int width, int height);

// Splits an Annex B buffer into NAL units (with start codes).
std::vector<std::pair<const uint8_t*, size_t>> splitAnnexB(const uint8_t* data, size_t size);

}  // namespace vc
