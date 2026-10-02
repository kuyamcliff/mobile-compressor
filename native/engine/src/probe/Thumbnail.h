#pragma once

#include <cstdint>
#include <vector>

#include "core/FdIo.h"

namespace vc {

struct Thumbnail {
  int width = 0, height = 0;
  std::vector<uint8_t> rgba;  // width*height*4, rotation already applied
};

// Decodes the frame nearest to `timeUs` and scales it to fit `maxDim`. Used for
// formats that Android's MediaMetadataRetriever cannot handle (e.g. VP9 in MKV
// on some devices). Only a handful of packets are decoded.
Thumbnail extractThumbnail(int fd, int64_t timeUs, int maxDim, InterruptFlag* interrupt);

}  // namespace vc
