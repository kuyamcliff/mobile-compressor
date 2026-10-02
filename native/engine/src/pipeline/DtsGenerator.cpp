#include "pipeline/DtsGenerator.h"

#include <algorithm>

namespace vc {

void DtsGenerator::push(PacketPtr pkt) {
  heap_.push(pkt->pts);
  pending_.push_back(std::move(pkt));
}

void DtsGenerator::computeDelay() {
  std::vector<int64_t> pts;
  pts.reserve(pending_.size());
  for (const auto& p : pending_) pts.push_back(p->pts);
  std::vector<int64_t> sorted = pts;
  std::sort(sorted.begin(), sorted.end());
  int d = 0;
  for (size_t i = 0; i < pts.size(); ++i) {
    // rank = index of this pts in sorted order (first occurrence)
    int rank = static_cast<int>(std::lower_bound(sorted.begin(), sorted.end(), pts[i]) - sorted.begin());
    d = std::max(d, static_cast<int>(i) - rank);
  }
  delay_ = d;
  minPts_ = sorted.empty() ? 0 : sorted.front();
  std::vector<int64_t> deltas;
  for (size_t i = 1; i < sorted.size(); ++i) {
    if (sorted[i] > sorted[i - 1]) deltas.push_back(sorted[i] - sorted[i - 1]);
  }
  if (!deltas.empty()) {
    std::nth_element(deltas.begin(), deltas.begin() + deltas.size() / 2, deltas.end());
    frameDur_ = std::max<int64_t>(1, deltas[deltas.size() / 2]);
  }
}

PacketPtr DtsGenerator::pop() {
  if (pending_.empty()) return nullptr;
  if (delay_ < 0) {
    if (pending_.size() < lookahead_ && !eos_) return nullptr;
    computeDelay();
  }
  // Keep enough look-ahead that every smaller PTS has been seen.
  if (!eos_ && pending_.size() <= static_cast<size_t>(delay_) + 2) return nullptr;
  PacketPtr p = std::move(pending_.front());
  pending_.pop_front();
  int64_t dts;
  if (emitted_ < delay_) {
    dts = minPts_ - (delay_ - emitted_) * frameDur_;
  } else {
    dts = heap_.top();
    heap_.pop();
  }
  ++emitted_;
  if (lastDts_ != INT64_MIN && dts <= lastDts_) dts = lastDts_ + 1;
  if (dts > p->pts) dts = p->pts;  // never decode after presentation
  lastDts_ = std::max(lastDts_, dts);
  p->dts = dts;
  return p;
}

}  // namespace vc
