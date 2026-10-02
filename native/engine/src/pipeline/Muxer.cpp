#include "pipeline/Muxer.h"

#include "core/Errors.h"
#include "core/Log.h"

namespace vc {

namespace {
constexpr const char* TAG = "Muxer";
constexpr size_t kMaxQueuedPackets = 4096;
}  // namespace

Muxer::Muxer(OutputFile& out, const ContainerPlan& container) : out_(out), container_(container) {}

int Muxer::addStream() {
  AVStream* st = avformat_new_stream(out_.ctx(), nullptr);
  if (!st) throw std::bad_alloc();
  ready_.push_back(false);
  lastDts_.push_back(AV_NOPTS_VALUE);
  return st->index;
}

void Muxer::setMetadata(AVDictionary* d) {
  av_dict_free(&out_.ctx()->metadata);
  if (d) av_dict_copy(&out_.ctx()->metadata, d, 0);
}

void Muxer::markReady(int idx) {
  ready_.at(idx) = true;
  tryWriteHeader();
}

void Muxer::tryWriteHeader() {
  if (headerWritten_) return;
  for (bool r : ready_) {
    if (!r) return;
  }
  Dict opts;
  const std::string& f = container_.format;
  if (f == "mp4" || f == "mov" || f == "ipod" || f == "3gp") {
    std::string flags;
    if (container_.fragmented) flags = "+frag_keyframe+empty_moov+default_base_moof";
    else if (container_.fastStart) flags = "+faststart";
    if (!flags.empty()) opts.set("movflags", flags);
    // Use the edit list to preserve audio priming/start offsets exactly.
    opts.set("use_editlist", "1");
  }
  int ret = avformat_write_header(out_.ctx(), opts.addr());
  if (ret < 0) {
    EngineError e = fromAvError(ret, "mux", "writing the " + f + " header");
    if (ret == AVERROR(EINVAL)) {
      e.category = ErrorCategory::InvalidConfiguration;
      e.message = "The " + f + " container rejected the selected stream combination.";
      e.suggestions = {"Choose MKV, which accepts most codecs", "Change the audio or subtitle codec"};
    }
    throw EngineException(e);
  }
  headerWritten_ = true;
  VC_LOGI(TAG, "header written format=%s streams=%u queued=%zu", f.c_str(), out_.ctx()->nb_streams, queue_.size());
  while (!queue_.empty()) {
    Pending p = std::move(queue_.front());
    queue_.pop_front();
    writeNow(p.idx, p.pkt.get());
  }
}

void Muxer::write(int idx, AVPacket* pkt, AVRational srcTb) {
  AVStream* st = stream(idx);
  av_packet_rescale_ts(pkt, srcTb, st->time_base);
  pkt->stream_index = idx;
  pkt->pos = -1;
  if (!headerWritten_) {
    // Note: the stream time base can still change in avformat_write_header;
    // queued packets are rescaled again from the pre-header time base.
    if (queue_.size() >= kMaxQueuedPackets) {
      throwError(ErrorCategory::Internal, "mux",
                 "An output stream did not start producing data (muxing queue overflow).");
    }
    PacketPtr copy = makePacket();
    av_packet_move_ref(copy.get(), pkt);
    // Remember the time base the packet is currently expressed in.
    copy->time_base = st->time_base;
    queue_.push_back({idx, std::move(copy)});
    return;
  }
  writeNow(idx, pkt);
}

void Muxer::writeNow(int idx, AVPacket* pkt) {
  AVStream* st = stream(idx);
  if (pkt->time_base.num > 0 && av_cmp_q(pkt->time_base, st->time_base) != 0) {
    av_packet_rescale_ts(pkt, pkt->time_base, st->time_base);
  }
  pkt->time_base = st->time_base;
  // Guard monotonic DTS: a few demuxers/encoders produce equal or decreasing DTS
  // around discontinuities; nudge rather than abort the whole job.
  if (pkt->dts != AV_NOPTS_VALUE && lastDts_[idx] != AV_NOPTS_VALUE) {
    bool strict = !(out_.ctx()->oformat->flags & AVFMT_TS_NONSTRICT);
    int64_t minDts = lastDts_[idx] + (strict ? 1 : 0);
    if (pkt->dts < minDts) {
      VC_LOGD(TAG, "stream %d non-monotonic dts %lld -> %lld", idx, static_cast<long long>(pkt->dts),
              static_cast<long long>(minDts));
      if (pkt->pts != AV_NOPTS_VALUE && pkt->pts >= pkt->dts) pkt->pts += minDts - pkt->dts;
      pkt->dts = minDts;
    }
  }
  if (pkt->dts != AV_NOPTS_VALUE) lastDts_[idx] = pkt->dts;
  int ret = av_interleaved_write_frame(out_.ctx(), pkt);
  if (ret < 0) {
    int ioErr = out_.flush();
    throw EngineException(fromAvError(ioErr < 0 ? ioErr : ret, "mux", "writing the output file"));
  }
  ++packets_;
}

void Muxer::finish() {
  if (finished_) return;
  if (!headerWritten_) {
    // A stream that never produced anything (e.g. an audio track with no packets
    // inside a preview segment) still needs valid parameters to write a header.
    for (size_t i = 0; i < ready_.size(); ++i) {
      if (!ready_[i]) {
        throwError(ErrorCategory::Internal, "mux", "An output stream produced no data.",
                   "stream " + std::to_string(i));
      }
    }
    tryWriteHeader();
  }
  int ret = av_write_trailer(out_.ctx());
  finished_ = true;
  int ioErr = out_.flush();
  if (ret < 0 || ioErr < 0) throw EngineException(fromAvError(ioErr < 0 ? ioErr : ret, "mux", "finalizing the output file"));
}

}  // namespace vc
