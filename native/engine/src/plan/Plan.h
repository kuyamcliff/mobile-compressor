#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "core/Util.h"

namespace vc {

// The immutable execution plan handed over by the Kotlin planner
// (ExecutionPlan.kt). The Kotlin layer owns validation against device
// capabilities; the engine re-validates structurally and refuses anything
// inconsistent rather than guessing.

enum class VideoMode { Transcode, Copy, Drop };
enum class Pipeline { Software, Hardware, Hybrid };
enum class RateMode { Crf, Cqp, Abr, Cbr, Vbr, Cq, Lossless };
enum class FpsMode { Source, Cfr, PeakLimit };

struct Crop {
  int top = 0, bottom = 0, left = 0, right = 0;
  bool any() const { return top || bottom || left || right; }
};

struct RateControl {
  RateMode mode = RateMode::Crf;
  double quality = 28;      // encoder-native scale (CRF/QP/MediaCodec quality)
  int bitrateKbps = 0;      // average/target bitrate
  int maxBitrateKbps = 0;   // VBV maximum
  int minBitrateKbps = 0;
  int bufferKbits = 0;      // VBV buffer
  bool twoPass = false;
};

struct Denoise {
  std::string algo;      // "", nlmeans, atadenoise, bm3d
  std::string strength;  // light, medium, strong
};

struct Sharpen {
  std::string algo;  // "", unsharp, cas
  std::string strength;
};

struct VideoFilters {
  std::string deinterlace;  // "", yadif, bwdif
  bool deinterlaceAuto = false;  // only deinterlace frames flagged interlaced
  bool detelecine = false;
  std::string deblock;  // "", weak, strong
  Denoise denoise;
  Sharpen sharpen;
  bool deband = false;
  bool grayscale = false;
  int rotate = 0;  // additional user rotation (cw degrees)
  bool hflip = false;
  bool vflip = false;
};

struct ColorOverrides {
  std::string primaries, transfer, matrix, range;
};

struct SubtitleBurn {
  int sourceStreamIndex = -1;  // embedded stream, or -1
  std::string externalPath;    // app-private copy of an external subtitle file
  bool bitmap = false;
  std::string fontsDir;
};

struct VideoPlan {
  int sourceStreamIndex = -1;
  VideoMode mode = VideoMode::Transcode;
  Pipeline pipeline = Pipeline::Software;
  std::string codec;    // h264, hevc, av1, vp9, vp8, mpeg4, ffv1
  std::string encoder;  // FFmpeg encoder name, or MediaCodec component name
  std::string mime;     // MediaCodec MIME for hardware/hybrid
  std::string hwDecoder;  // MediaCodec decoder component (hardware pipeline)
  int width = 0, height = 0;  // final output size in display orientation
  Crop crop;                  // in display orientation (after autorotation)
  std::string scaler = "bicubic";
  FpsMode fpsMode = FpsMode::Source;
  int fpsNum = 0, fpsDen = 1;
  std::string pixFmt = "yuv420p";
  int bitDepth = 8;
  RateControl rc;
  double keyIntSeconds = 0;  // 0 = encoder default
  int bFrames = -1;          // -1 = encoder default
  int refFrames = -1;
  std::string profile, level, preset, tune;
  std::vector<std::pair<std::string, std::string>> options;  // validated extra encoder options
  int threads = 0;
  VideoFilters filters;
  bool autorotate = true;
  std::string hdrMode = "preserve";  // preserve | tonemap
  std::string tonemap = "hable";
  ColorOverrides color;
  std::optional<SubtitleBurn> burn;
  int hwColorFormat = 21;   // ByteBuffer input colour format for the hybrid pipeline
  int hwBitrateMode = 1;    // MediaCodec BITRATE_MODE_* (0 CQ, 1 VBR, 2 CBR)
  int hwQpMin = -1, hwQpMax = -1;
};

struct AudioPlan {
  int sourceStreamIndex = -1;
  bool copy = false;
  std::string codec;    // aac, opus, mp3, flac, alac, ac3, eac3
  std::string encoder;  // FFmpeg encoder name
  int bitrateKbps = 128;
  int channels = 0;     // 0 = source
  int sampleRate = 0;   // 0 = source (if supported by encoder)
  std::string language, title;
  bool isDefault = false;
  double volumeDb = 0;
};

struct SubtitlePlan {
  int sourceStreamIndex = -1;  // -1 when external
  std::string externalPath;
  bool copy = true;
  std::string codec;  // target codec when converting: mov_text, subrip, ass, webvtt
  std::string language, title;
  bool isDefault = false, forced = false;
};

struct Chapter {
  std::string title;
  int64_t startUs = 0, endUs = 0;
};

struct ContainerPlan {
  std::string format = "mp4";  // FFmpeg muxer name
  bool fastStart = true;
  bool fragmented = false;
};

struct Segment {
  int64_t startUs = 0;
  int64_t durationUs = 0;
};

struct Plan {
  std::string jobId;
  ContainerPlan container;
  std::optional<VideoPlan> video;
  std::vector<AudioPlan> audio;
  std::vector<SubtitlePlan> subtitles;
  std::string chapterMode = "preserve";  // preserve | strip | custom
  std::vector<Chapter> chapters;
  std::string metadataMode = "preserve";  // preserve | strip | custom
  std::map<std::string, std::string> metadata;
  std::optional<Segment> segment;  // preview sample
  int progressIntervalMs = 250;
  bool copyAttachments = true;  // fonts for ASS subtitles in Matroska
};

Plan parsePlan(const Json& j);
Json planSummary(const Plan& p);
const char* pipelineName(Pipeline p);
const char* rateModeName(RateMode m);

}  // namespace vc
