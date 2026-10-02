#pragma once

#include <deque>
#include <string>
#include <vector>

#include "core/FdIo.h"
#include "core/FfRaii.h"
#include "plan/Plan.h"

namespace vc {

// Wraps the output AVFormatContext. Streams are registered up front; the header
// is written once every stream has final codec parameters (hardware encoders
// only report their codec-specific data with the first output buffer), and
// packets arriving earlier are queued (bounded).
class Muxer {
 public:
  Muxer(OutputFile& out, const ContainerPlan& container);

  int addStream();  // returns output stream index
  AVStream* stream(int idx) const { return out_.ctx()->streams[idx]; }
  void markReady(int idx);
  // Declares that every stream, the global metadata and the chapters are set up.
  // The header is written only after sealing and once all streams are ready.
  void seal();
  bool headerWritten() const { return headerWritten_; }

  // Takes ownership of pkt's data (unrefs it). Timestamps are in `srcTb`.
  void write(int idx, AVPacket* pkt, AVRational srcTb);
  // Writes the trailer. Streams that never became ready are an error.
  void finish();

  void setMetadata(AVDictionary* d);  // takes a copy
  int64_t bytesWritten() const { return out_.bytesWritten(); }
  int64_t packetsWritten() const { return packets_; }

 private:
  struct Pending {
    int idx;
    PacketPtr pkt;
  };
  void tryWriteHeader();
  void writeNow(int idx, AVPacket* pkt);

  OutputFile& out_;
  ContainerPlan container_;
  std::vector<bool> ready_;
  std::vector<int64_t> lastDts_;
  std::deque<Pending> queue_;
  bool headerWritten_ = false;
  bool sealed_ = false;
  bool finished_ = false;
  int64_t packets_ = 0;
};

}  // namespace vc
