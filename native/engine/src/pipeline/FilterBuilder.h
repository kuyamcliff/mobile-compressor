#pragma once

#include <string>

#include "plan/Plan.h"

namespace vc {

struct VideoSourceProps {
  int width = 0, height = 0;  // coded frame size
  int rotation = 0;           // display-matrix rotation (cw degrees)
  double fps = 0;             // average source frame rate (0 = unknown)
  std::string hdr = "sdr";    // classifyHdr()
  std::string colorPrimaries, colorTransfer, colorSpace, colorRange;  // FFmpeg names
};

// Size after autorotation, before crop (the "display orientation" frame).
void orientedSize(const VideoPlan& v, const VideoSourceProps& s, int& w, int& h);

// Builds the libavfilter chain (without buffer/buffersink) that turns decoded
// source frames into encoder-ready frames, in this order:
//   deinterlace/detelecine -> fps -> autorotate -> user rotate/flip -> crop
//   -> denoise -> deblock -> HDR tone-map / colour conversion -> scale
//   -> deband -> sharpen -> grayscale -> subtitle burn-in -> pixel format
// Frame-rate conversion runs early so later (expensive) filters see fewer frames.
std::string buildVideoFilter(const VideoPlan& v, const VideoSourceProps& s, const std::string& encoderPixFmt);

// Escapes a value for use inside a filter option (filtergraph quoting rules).
std::string escapeFilterValue(const std::string& v);

std::string buildAudioFilter(int outSampleRate, const std::string& outChannelLayout, const std::string& outSampleFmt,
                             double volumeDb);

}  // namespace vc
