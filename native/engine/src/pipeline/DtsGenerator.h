#pragma once

#include <cstdint>
#include <deque>
#include <queue>
#include <vector>

#include "core/FfRaii.h"

namespace vc {

// MediaCodec delivers encoded packets in decode order with only presentation
// timestamps. Muxers need monotonically increasing DTS with DTS <= PTS. When
// the encoder emits B-frames, packets arrive out of PTS order; this class
// reconstructs a valid DTS sequence:
//
//   dts_k = (k - d)-th smallest PTS seen so far,  for k >= d
//   dts_k = minPts - (d - k) * frameDuration,      for k <  d
//
// where d (the reorder delay) is measured over an initial look-ahead window as
// max_i(i - rank_i). With no reordering, d = 0 and DTS == PTS.
class DtsGenerator {
 public:
  explicit DtsGenerator(size_t lookahead = 16) : lookahead_(lookahead) {}

  void push(PacketPtr pkt);
  void markEndOfStream() { eos_ = true; }
  // Returns the next packet with DTS assigned, or null if more input is needed.
  PacketPtr pop();
  int reorderDelay() const { return delay_; }

 private:
  void computeDelay();

  size_t lookahead_;
  std::deque<PacketPtr> pending_;
  std::priority_queue<int64_t, std::vector<int64_t>, std::greater<int64_t>> heap_;
  int delay_ = -1;
  int64_t emitted_ = 0;
  int64_t minPts_ = 0;
  int64_t frameDur_ = 1;
  int64_t lastDts_ = INT64_MIN;
  bool eos_ = false;
};

}  // namespace vc
