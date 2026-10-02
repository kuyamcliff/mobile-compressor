#include "pipeline/Transcoder.h"

#include <algorithm>
#include <cmath>
#include <map>

#include "core/Errors.h"
#include "core/Log.h"
#include "pipeline/EncoderSetup.h"
#include "pipeline/FilterBuilder.h"
#include "pipeline/FilterGraph.h"
#include "probe/Probe.h"

extern "C" {
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
}

namespace vc {

namespace {
constexpr const char* TAG = "Transcoder";
constexpr int64_t kMaxDecodeErrors = 500;

bool isMp4Family(const std::string& f) { return f == "mp4" || f == "mov" || f == "ipod" || f == "3gp"; }

// Matroska writes its own statistics tags; copying stale ones would be wrong.
void copyStreamMetadata(AVDictionary** dst, const AVDictionary* src, bool preserve) {
  if (!preserve) return;
  const AVDictionaryEntry* e = nullptr;
  while ((e = av_dict_iterate(src, e))) {
    std::string k = e->key;
    if (k.rfind("BPS", 0) == 0 || k.rfind("NUMBER_OF_", 0) == 0 || k.rfind("DURATION", 0) == 0 ||
        k.rfind("_STATISTICS", 0) == 0 || k == "encoder" || k == "vendor_id") {
      continue;
    }
    av_dict_set(dst, e->key, e->value, 0);
  }
}

void applyTrackLabels(AVStream* os, const std::string& language, const std::string& title, bool isDefault, bool forced) {
  if (!language.empty()) av_dict_set(&os->metadata, "language", language.c_str(), 0);
  if (!title.empty()) {
    av_dict_set(&os->metadata, "title", title.c_str(), 0);
    av_dict_set(&os->metadata, "handler_name", title.c_str(), 0);
  }
  os->disposition = 0;
  if (isDefault) os->disposition |= AV_DISPOSITION_DEFAULT;
  if (forced) os->disposition |= AV_DISPOSITION_FORCED;
}

CodecCtxPtr openDecoder(const AVStream* st, int threads) {
  const AVCodec* dec = avcodec_find_decoder(st->codecpar->codec_id);
  if (!dec) {
    const AVCodecDescriptor* d = avcodec_descriptor_get(st->codecpar->codec_id);
    throwError(ErrorCategory::UnsupportedCodec, "decoder_open",
               std::string("The source codec ") + (d ? d->name : "unknown") + " cannot be decoded by this build.",
               "", {"Try remuxing (stream copy) instead of re-encoding"});
  }
  CodecCtxPtr ctx(avcodec_alloc_context3(dec));
  if (!ctx) throw std::bad_alloc();
  checkAv(avcodec_parameters_to_context(ctx.get(), st->codecpar), "decoder_open", "configuring the decoder");
  ctx->pkt_timebase = st->time_base;
  ctx->thread_count = threads;
  ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  // Corrupt input must not crash the process or loop forever.
  ctx->err_recognition = AV_EF_CRCCHECK | AV_EF_BITSTREAM;
  ctx->max_pixels = static_cast<int64_t>(16384) * 16384;
  Dict opts;
  if (dec->id == AV_CODEC_ID_AV1 && std::string(dec->name) == "libdav1d") {
    opts.set("max_frame_delay", "4");  // bounds dav1d memory on 4K/8K
  }
  int ret = avcodec_open2(ctx.get(), dec, opts.addr());
  if (ret < 0) throw EngineException(fromAvError(ret, "decoder_open", std::string(dec->name) + " decoder"));
  return ctx;
}

int decoderThreads() {
  unsigned n = std::thread::hardware_concurrency();
  if (n == 0) n = 4;
  return static_cast<int>(std::min(8u, n));
}

// ---------------------------------------------------------------------------
// Stream copy (remux).
class CopyHandler : public StreamHandler {
 public:
  CopyHandler(TranscodeContext& ctx, AVStream* in, int outIdx, bool alignToKeyframe)
      : ctx_(ctx), in_(in), outIdx_(outIdx), alignToKeyframe_(alignToKeyframe) {}

  void onPacket(AVPacket* pkt) override {
    int64_t absUs = toUs(pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts, in_->time_base);
    if (ctx_.afterEnd(absUs)) {
      if (toUs(pkt->dts, in_->time_base) >= ctx_.segEndAbsUs) end_ = true;
      return;
    }
    if (!alignToKeyframe_ && ctx_.beforeStart(absUs)) return;
    if (!started_ && in_->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && !(pkt->flags & AV_PKT_FLAG_KEY)) return;
    started_ = true;
    if (!ctx_.muxer) return;
    pkt->pts = ctx_.outTs(pkt->pts, in_->time_base);
    pkt->dts = ctx_.outTs(pkt->dts, in_->time_base);
    if (ctx_.progress) {
      int64_t t = toUs(pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts, in_->time_base);
      if (in_->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) ctx_.progress->onEncodedVideoFrame(t);
      else ctx_.progress->onMediaTime(t);
    }
    ctx_.muxer->write(outIdx_, pkt, in_->time_base);
    if (ctx_.progress) ctx_.progress->setOutputBytes(ctx_.muxer->bytesWritten());
  }
  void finish() override {}
  bool reachedEnd() const override { return end_; }
  std::string encoderDescription() const override { return "copy"; }

 private:
  TranscodeContext& ctx_;
  AVStream* in_;
  int outIdx_;
  bool alignToKeyframe_;
  bool started_ = false;
  bool end_ = false;
};

// ---------------------------------------------------------------------------
// Decode -> filter -> encode (software and hybrid pipelines).
class VideoTranscodeHandler : public StreamHandler {
 public:
  VideoTranscodeHandler(TranscodeContext& ctx, AVStream* in, int outIdx) : ctx_(ctx), in_(in), outIdx_(outIdx) {
    const VideoPlan& v = *ctx.plan->video;
    dec_ = openDecoder(in, decoderThreads());
    frame_ = makeFrame();
    props_.width = in->codecpar->width;
    props_.height = in->codecpar->height;
    props_.rotation = streamRotation(in);
    props_.fps = in->avg_frame_rate.num > 0 ? av_q2d(in->avg_frame_rate) : 0;
    props_.hdr = classifyHdr(in);
    auto nm = [](const char* s) { return std::string(s ? s : ""); };
    props_.colorPrimaries = nm(av_color_primaries_name(in->codecpar->color_primaries));
    props_.colorTransfer = nm(av_color_transfer_name(in->codecpar->color_trc));
    props_.colorSpace = nm(av_color_space_name(in->codecpar->color_space));
    props_.colorRange = nm(av_color_range_name(in->codecpar->color_range));
    filterDesc_ = buildVideoFilter(v, props_, encoderPixFmtFor(v));

    EncoderSetupInfo info;
    info.plan = &v;
    info.source = in;
    info.pass = ctx.pass;
    info.passStatsIn = ctx.passStats;
    info.globalHeader = ctx.globalHeader;
    info.onPacket = [this](AVPacket* pkt, AVRational tb) { onEncodedPacket(pkt, tb); };
    info.onParams = [this](const AVCodecParameters* par, AVRational tb) { onEncoderParams(par, tb); };
    if (v.pipeline == Pipeline::Hybrid) {
      enc_ = createMediaCodecBufferEncoder(std::move(info));
    } else {
      enc_ = std::make_unique<FfVideoEncoder>(std::move(info));
    }
  }

  void onPacket(AVPacket* pkt) override {
    if (end_) return;
    int ret = avcodec_send_packet(dec_.get(), pkt);
    if (ret == AVERROR(EAGAIN)) {
      receiveFrames();
      ret = avcodec_send_packet(dec_.get(), pkt);
    }
    if (ret == AVERROR_INVALIDDATA || (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF && ret != AVERROR(ENOMEM))) {
      ctx_.noteDecodeError("video");
    } else if (ret == AVERROR(ENOMEM)) {
      checkAv(ret, "video_decode", "decoding video");
    }
    receiveFrames();
  }

  void finish() override {
    avcodec_send_packet(dec_.get(), nullptr);
    receiveFrames();
    if (graph_.initialized()) {
      graph_.push(nullptr, [this](AVFrame* f, AVRational tb) { encodeFiltered(f, tb); });
    }
    if (!enc_->isOpen()) {
      throwError(ErrorCategory::InvalidInput, "video_decode",
                 "No video frames could be decoded from the selected range.", "",
                 {"Check that the source plays correctly", "Choose a different preview position"});
    }
    enc_->encode(nullptr);
    stats_ = enc_->passStats();
  }

  bool reachedEnd() const override { return end_; }
  std::string encoderDescription() const override { return enc_->describe(); }
  std::string passStats() const override { return stats_; }

 private:
  void receiveFrames() {
    for (;;) {
      int ret = avcodec_receive_frame(dec_.get(), frame_.get());
      if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return;
      if (ret < 0) {
        ctx_.noteDecodeError("video");
        return;
      }
      handleFrame(frame_.get());
      av_frame_unref(frame_.get());
    }
  }

  void handleFrame(AVFrame* f) {
    int64_t pts = f->best_effort_timestamp;
    if (pts == AV_NOPTS_VALUE) pts = f->pts;
    if (pts == AV_NOPTS_VALUE) {
      // Synthesise from the previous frame using the nominal rate.
      AVRational fr = in_->avg_frame_rate.num > 0 ? in_->avg_frame_rate : AVRational{30, 1};
      pts = lastSrcPts_ == AV_NOPTS_VALUE ? 0 : lastSrcPts_ + av_rescale_q(1, av_inv_q(fr), in_->time_base);
    }
    if (lastSrcPts_ != AV_NOPTS_VALUE && pts <= lastSrcPts_) {
      VC_LOGD(TAG, "dropping non-increasing video pts %lld", static_cast<long long>(pts));
      return;
    }
    int64_t absUs = toUs(pts, in_->time_base);
    if (ctx_.beforeStart(absUs)) return;
    if (ctx_.afterEnd(absUs)) {
      end_ = true;
      return;
    }
    lastSrcPts_ = pts;
    f->pts = ctx_.outTs(pts, in_->time_base);
    if (!graph_.initialized() || graph_.needsReinit(f)) {
      if (graph_.initialized()) {
        VC_LOGI(TAG, "video format changed mid-stream (%dx%d), rebuilding filters", f->width, f->height);
        graph_.push(nullptr, [this](AVFrame* o, AVRational tb) { encodeFiltered(o, tb); });
      }
      props_.width = f->width;
      props_.height = f->height;
      // Frames may carry better colour info than the container.
      if (f->color_trc != AVCOL_TRC_UNSPECIFIED) props_.colorTransfer = av_color_transfer_name(f->color_trc);
      if (f->color_primaries != AVCOL_PRI_UNSPECIFIED) props_.colorPrimaries = av_color_primaries_name(f->color_primaries);
      if (f->colorspace != AVCOL_SPC_UNSPECIFIED) props_.colorSpace = av_color_space_name(f->colorspace);
      filterDesc_ = buildVideoFilter(*ctx_.plan->video, props_, encoderPixFmtFor(*ctx_.plan->video));
      AVRational sar = f->sample_aspect_ratio.num ? f->sample_aspect_ratio : in_->sample_aspect_ratio;
      graph_.initVideo(f, in_->time_base, sar, in_->avg_frame_rate, filterDesc_, 0);
    }
    graph_.push(f, [this](AVFrame* o, AVRational tb) { encodeFiltered(o, tb); });
  }

  void encodeFiltered(AVFrame* f, AVRational tb) {
    if (!enc_->isOpen()) {
      AVRational fr = graph_.outputFrameRate();
      if (fr.num <= 0) fr = in_->avg_frame_rate;
      enc_->open(f, tb, fr);
    }
    if (ctx_.progress) ctx_.progress->onMediaTime(toUs(f->pts, tb));
    enc_->encode(f);
  }

  void onEncoderParams(const AVCodecParameters* par, AVRational tb) {
    if (!ctx_.muxer) return;
    AVStream* os = ctx_.muxer->stream(outIdx_);
    // Keep any metadata/disposition already applied to the output stream.
    checkAv(avcodec_parameters_copy(os->codecpar, par), "mux", "setting video parameters");
    os->time_base = tb;
    os->avg_frame_rate = graph_.outputFrameRate();
    prepareOutputCodecpar(ctx_.plan->container.format, os);
    ctx_.muxer->markReady(outIdx_);
  }

  void onEncodedPacket(AVPacket* pkt, AVRational tb) {
    if (!ctx_.muxer) return;
    if (ctx_.progress) ctx_.progress->onEncodedVideoFrame(toUs(pkt->pts, tb));
    ctx_.muxer->write(outIdx_, pkt, tb);
    if (ctx_.progress) ctx_.progress->setOutputBytes(ctx_.muxer->bytesWritten());
  }

  TranscodeContext& ctx_;
  AVStream* in_;
  int outIdx_;
  CodecCtxPtr dec_;
  FramePtr frame_;
  FilterGraph graph_;
  VideoSourceProps props_;
  std::string filterDesc_;
  std::unique_ptr<VideoEncoderBackend> enc_;
  int64_t lastSrcPts_ = AV_NOPTS_VALUE;
  bool end_ = false;
  std::string stats_;
};

// ---------------------------------------------------------------------------
// Audio decode -> resample -> encode.
class AudioTranscodeHandler : public StreamHandler {
 public:
  AudioTranscodeHandler(TranscodeContext& ctx, AVStream* in, int outIdx, const AudioPlan& ap)
      : ctx_(ctx), in_(in), outIdx_(outIdx), plan_(ap) {
    dec_ = openDecoder(in, 1);
    frame_ = makeFrame();
    pkt_ = makePacket();
    openEncoder();
  }

  void onPacket(AVPacket* pkt) override {
    if (end_) return;
    int ret = avcodec_send_packet(dec_.get(), pkt);
    if (ret == AVERROR(EAGAIN)) {
      receiveFrames();
      ret = avcodec_send_packet(dec_.get(), pkt);
    }
    if (ret < 0 && ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) ctx_.noteDecodeError("audio");
    receiveFrames();
  }

  void finish() override {
    avcodec_send_packet(dec_.get(), nullptr);
    receiveFrames();
    if (graph_.initialized()) graph_.push(nullptr, [this](AVFrame* f, AVRational tb) { encode(f, tb); });
    encode(nullptr, AVRational{1, 1});
  }

  bool reachedEnd() const override { return end_; }

 private:
  void openEncoder() {
    const AVCodec* codec = avcodec_find_encoder_by_name(plan_.encoder.c_str());
    if (!codec) {
      throwError(ErrorCategory::UnsupportedCodec, "audio_encoder",
                 "The " + plan_.encoder + " audio encoder is not available in this build.");
    }
    enc_.reset(avcodec_alloc_context3(codec));
    if (!enc_) throw std::bad_alloc();
    AVCodecContext* c = enc_.get();

    // Sample rate: requested (or source), snapped to what the encoder supports.
    int wantRate = plan_.sampleRate > 0 ? plan_.sampleRate : in_->codecpar->sample_rate;
    if (wantRate <= 0) wantRate = 48000;
    const void* cfg = nullptr;
    int n = 0;
    int rate = wantRate;
    if (avcodec_get_supported_config(nullptr, codec, AV_CODEC_CONFIG_SAMPLE_RATE, 0, &cfg, &n) >= 0 && cfg && n > 0) {
      const int* rates = static_cast<const int*>(cfg);
      int best = rates[0];
      for (int i = 0; i < n; ++i) {
        if (rates[i] == wantRate) { best = wantRate; break; }
        // Prefer the smallest supported rate >= requested, else the largest below.
        if ((rates[i] >= wantRate && (best < wantRate || rates[i] < best)) || (best < wantRate && rates[i] > best)) best = rates[i];
      }
      rate = best;
    }
    c->sample_rate = rate;

    int maxCh = 8;
    if (plan_.encoder == "libmp3lame") maxCh = 2;
    if (plan_.encoder == "ac3" || plan_.encoder == "eac3") maxCh = 6;
    int ch = plan_.channels > 0 ? plan_.channels : in_->codecpar->ch_layout.nb_channels;
    ch = std::clamp(ch, 1, maxCh);
    AVChannelLayout layout;
    av_channel_layout_default(&layout, ch);
    if (avcodec_get_supported_config(nullptr, codec, AV_CODEC_CONFIG_CHANNEL_LAYOUT, 0, &cfg, &n) >= 0 && cfg && n > 0) {
      const AVChannelLayout* layouts = static_cast<const AVChannelLayout*>(cfg);
      bool found = false;
      for (int i = 0; i < n; ++i) {
        if (av_channel_layout_compare(&layouts[i], &layout) == 0) { found = true; break; }
      }
      if (!found) {
        // Fall back to the supported layout with the most channels <= requested.
        int bestIdx = -1;
        for (int i = 0; i < n; ++i) {
          if (layouts[i].nb_channels <= ch && (bestIdx < 0 || layouts[i].nb_channels > layouts[bestIdx].nb_channels)) bestIdx = i;
        }
        if (bestIdx < 0) bestIdx = 0;
        av_channel_layout_uninit(&layout);
        av_channel_layout_copy(&layout, &layouts[bestIdx]);
      }
    }
    av_channel_layout_copy(&c->ch_layout, &layout);
    av_channel_layout_uninit(&layout);

    AVSampleFormat sfmt = AV_SAMPLE_FMT_FLTP;
    if (avcodec_get_supported_config(nullptr, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0, &cfg, &n) >= 0 && cfg && n > 0) {
      sfmt = static_cast<const AVSampleFormat*>(cfg)[0];
      // Keep the source precision for lossless encoders when supported.
      if (plan_.encoder == "flac" || plan_.encoder == "alac") {
        int srcBits = in_->codecpar->bits_per_raw_sample;
        for (int i = 0; i < n; ++i) {
          AVSampleFormat f = static_cast<const AVSampleFormat*>(cfg)[i];
          if (srcBits > 16 && (f == AV_SAMPLE_FMT_S32 || f == AV_SAMPLE_FMT_S32P)) sfmt = f;
        }
      }
    }
    c->sample_fmt = sfmt;
    c->time_base = AVRational{1, c->sample_rate};
    bool lossless = plan_.encoder == "flac" || plan_.encoder == "alac";
    if (!lossless) c->bit_rate = static_cast<int64_t>(plan_.bitrateKbps) * 1000;
    if (ctx_.globalHeader) c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    Dict opts;
    if (plan_.encoder == "libopus") {
      opts.set("application", "audio");
      opts.set("vbr", "on");
    }
    if (plan_.encoder == "aac") opts.set("aac_coder", "twoloop");
    int ret = avcodec_open2(c, codec, opts.addr());
    if (ret < 0) {
      EngineError e = fromAvError(ret, "audio_encoder_open", plan_.encoder + " audio encoder");
      e.context = {std::to_string(c->sample_rate) + " Hz", std::to_string(c->ch_layout.nb_channels) + " channels",
                   std::to_string(plan_.bitrateKbps) + " kbps"};
      e.suggestions = {"Choose a different audio bitrate or channel count", "Choose AAC"};
      throw EngineException(e);
    }
    char layoutName[128] = {0};
    av_channel_layout_describe(&c->ch_layout, layoutName, sizeof(layoutName));
    filterDesc_ = buildAudioFilter(c->sample_rate, layoutName, av_get_sample_fmt_name(c->sample_fmt), plan_.volumeDb);
    if (ctx_.muxer) {
      AVStream* os = ctx_.muxer->stream(outIdx_);
      checkAv(avcodec_parameters_from_context(os->codecpar, c), "mux", "setting audio parameters");
      os->time_base = c->time_base;
      ctx_.muxer->markReady(outIdx_);
    }
  }

  void receiveFrames() {
    for (;;) {
      int ret = avcodec_receive_frame(dec_.get(), frame_.get());
      if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return;
      if (ret < 0) {
        ctx_.noteDecodeError("audio");
        return;
      }
      handleFrame(frame_.get());
      av_frame_unref(frame_.get());
    }
  }

  void handleFrame(AVFrame* f) {
    int64_t pts = f->best_effort_timestamp != AV_NOPTS_VALUE ? f->best_effort_timestamp : f->pts;
    if (pts == AV_NOPTS_VALUE) {
      pts = nextPts_;  // contiguous continuation
    }
    int64_t durTs = av_rescale_q(f->nb_samples, AVRational{1, f->sample_rate > 0 ? f->sample_rate : 48000}, in_->time_base);
    nextPts_ = pts + durTs;
    int64_t startUs = toUs(pts, in_->time_base);
    int64_t endUs = toUs(pts + durTs, in_->time_base);
    if (endUs <= ctx_.segStartAbsUs) return;
    if (ctx_.afterEnd(startUs)) {
      end_ = true;
      return;
    }
    f->pts = ctx_.outTs(pts, in_->time_base);
    if (!graph_.initialized() || graph_.needsReinit(f)) {
      if (graph_.initialized()) graph_.push(nullptr, [this](AVFrame* o, AVRational tb) { encode(o, tb); });
      int frameSize = (enc_->codec->capabilities & AV_CODEC_CAP_VARIABLE_FRAME_SIZE) ? 0 : enc_->frame_size;
      graph_.initAudio(f, in_->time_base, filterDesc_, frameSize);
    }
    graph_.push(f, [this](AVFrame* o, AVRational tb) { encode(o, tb); });
  }

  void encode(AVFrame* f, AVRational tb) {
    AVCodecContext* c = enc_.get();
    if (f) {
      f->pts = av_rescale_q(f->pts, tb, c->time_base);
      if (ctx_.progress && !hasVideo()) ctx_.progress->onMediaTime(toUs(f->pts, c->time_base));
    }
    int ret = avcodec_send_frame(c, f);
    if (ret < 0 && ret != AVERROR_EOF) throw EngineException(fromAvError(ret, "audio_encode", plan_.encoder + " encoder"));
    for (;;) {
      ret = avcodec_receive_packet(c, pkt_.get());
      if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
      if (ret < 0) throw EngineException(fromAvError(ret, "audio_encode", plan_.encoder + " encoder"));
      if (ctx_.muxer) {
        ctx_.muxer->write(outIdx_, pkt_.get(), c->time_base);
        if (ctx_.progress) ctx_.progress->setOutputBytes(ctx_.muxer->bytesWritten());
      }
      av_packet_unref(pkt_.get());
    }
  }

  bool hasVideo() const { return ctx_.plan->video.has_value(); }

  TranscodeContext& ctx_;
  AVStream* in_;
  int outIdx_;
  AudioPlan plan_;
  CodecCtxPtr dec_;
  CodecCtxPtr enc_;
  FramePtr frame_;
  PacketPtr pkt_;
  FilterGraph graph_;
  std::string filterDesc_;
  int64_t nextPts_ = 0;
  bool end_ = false;
};

// ---------------------------------------------------------------------------
// Subtitles: copy, or decode + re-encode between text formats.
class SubtitleHandler : public StreamHandler {
 public:
  SubtitleHandler(TranscodeContext& ctx, AVStream* in, int outIdx, const SubtitlePlan& sp)
      : ctx_(ctx), in_(in), outIdx_(outIdx), plan_(sp) {
    pkt_ = makePacket();
    if (!sp.copy) {
      dec_ = openDecoder(in, 1);
      const AVCodec* codec = avcodec_find_encoder_by_name(sp.codec.c_str());
      if (!codec) throwError(ErrorCategory::UnsupportedCodec, "subtitle_encoder", "Subtitle format " + sp.codec + " is not available.");
      enc_.reset(avcodec_alloc_context3(codec));
      if (!enc_) throw std::bad_alloc();
      enc_->time_base = AV_TIME_BASE_Q;
      if (dec_->subtitle_header && dec_->subtitle_header_size > 0) {
        enc_->subtitle_header = static_cast<uint8_t*>(av_mallocz(dec_->subtitle_header_size + 1));
        if (!enc_->subtitle_header) throw std::bad_alloc();
        memcpy(enc_->subtitle_header, dec_->subtitle_header, dec_->subtitle_header_size);
        enc_->subtitle_header_size = dec_->subtitle_header_size;
      }
      enc_->width = in->codecpar->width;
      enc_->height = in->codecpar->height;
      int ret = avcodec_open2(enc_.get(), codec, nullptr);
      if (ret < 0) {
        EngineError e = fromAvError(ret, "subtitle_encoder", sp.codec + " subtitle encoder");
        e.message = "These subtitles cannot be converted to " + sp.codec + ".";
        e.suggestions = {"Choose MKV and copy the subtitles", "Burn the subtitles into the video"};
        throw EngineException(e);
      }
      buf_.resize(1024 * 1024);
    }
    if (ctx_.muxer) {
      AVStream* os = ctx_.muxer->stream(outIdx_);
      if (sp.copy) {
        checkAv(avcodec_parameters_copy(os->codecpar, in->codecpar), "mux", "copying subtitle parameters");
        os->codecpar->codec_tag = 0;
        os->time_base = in->time_base;
      } else {
        checkAv(avcodec_parameters_from_context(os->codecpar, enc_.get()), "mux", "setting subtitle parameters");
        os->time_base = AVRational{1, 1000};
      }
      ctx_.muxer->markReady(outIdx_);
    }
  }

  void onPacket(AVPacket* pkt) override {
    if (!ctx_.muxer) return;
    int64_t absUs = toUs(pkt->pts != AV_NOPTS_VALUE ? pkt->pts : pkt->dts, in_->time_base);
    if (ctx_.afterEnd(absUs)) return;
    int64_t durUs = pkt->duration > 0 ? toUs(pkt->duration, in_->time_base) : 0;
    if (absUs != AV_NOPTS_VALUE && absUs + durUs < ctx_.segStartAbsUs) return;
    if (plan_.copy) {
      pkt->pts = ctx_.outTs(pkt->pts, in_->time_base);
      pkt->dts = ctx_.outTs(pkt->dts, in_->time_base);
      if (pkt->pts != AV_NOPTS_VALUE && pkt->pts < 0) return;
      ctx_.muxer->write(outIdx_, pkt, in_->time_base);
      return;
    }
    AVSubtitle sub{};
    int got = 0;
    int ret = avcodec_decode_subtitle2(dec_.get(), &sub, &got, pkt);
    if (ret < 0) {
      ctx_.noteDecodeError("subtitle");
      return;
    }
    if (!got) return;
    if (sub.num_rects > 0 && sub.pts != AV_NOPTS_VALUE) {
      int64_t ptsUs = sub.pts + av_rescale_q(sub.start_display_time, AVRational{1, 1000}, AV_TIME_BASE_Q);
      int64_t durMs = static_cast<int64_t>(sub.end_display_time) - sub.start_display_time;
      if (durMs <= 0 && pkt->duration > 0) durMs = toUs(pkt->duration, in_->time_base) / 1000;
      sub.pts = ptsUs - ctx_.baseUs;
      sub.end_display_time = static_cast<uint32_t>(std::max<int64_t>(durMs, 1));
      sub.start_display_time = 0;
      if (sub.pts >= 0) {
        int size = avcodec_encode_subtitle(enc_.get(), buf_.data(), static_cast<int>(buf_.size()), &sub);
        if (size > 0) {
          checkAv(av_new_packet(pkt_.get(), size), "subtitle_encode", "allocating a subtitle packet");
          memcpy(pkt_->data, buf_.data(), size);
          pkt_->pts = pkt_->dts = av_rescale_q(sub.pts, AV_TIME_BASE_Q, AVRational{1, 1000});
          pkt_->duration = sub.end_display_time;
          ctx_.muxer->write(outIdx_, pkt_.get(), AVRational{1, 1000});
          av_packet_unref(pkt_.get());
        }
      }
    }
    avsubtitle_free(&sub);
  }

  void finish() override {}

 private:
  TranscodeContext& ctx_;
  AVStream* in_;
  int outIdx_;
  SubtitlePlan plan_;
  CodecCtxPtr dec_, enc_;
  PacketPtr pkt_;
  std::vector<uint8_t> buf_;
};

// External subtitle file (SRT/ASS/VTT copied into app storage by Kotlin).
// All cues are read up front (subtitle files are small) and released into the
// muxer as the main input advances, preserving interleaving.
struct ExternalSubtitleSource {
  std::unique_ptr<InputFile> input;
  std::unique_ptr<SubtitleHandler> handler;
  std::deque<PacketPtr> packets;  // source time base = input stream 0
};

void addChapters(AVFormatContext* oc, const Plan& plan, AVFormatContext* ic, int64_t baseUs, int64_t segEndAbsUs,
                 int64_t inputStartUs) {
  std::vector<Chapter> chapters;
  if (plan.chapterMode == "strip") return;
  if (plan.chapterMode == "custom") {
    for (const auto& c : plan.chapters) {
      chapters.push_back({c.title, c.startUs + inputStartUs, c.endUs + inputStartUs});
    }
  } else {
    for (unsigned i = 0; i < ic->nb_chapters; ++i) {
      const AVChapter* c = ic->chapters[i];
      const AVDictionaryEntry* t = av_dict_get(c->metadata, "title", nullptr, 0);
      chapters.push_back({t ? t->value : "", toUs(c->start, c->time_base), toUs(c->end, c->time_base)});
    }
  }
  int id = 1;
  for (const auto& c : chapters) {
    int64_t s = std::max(c.startUs, baseUs) - baseUs;
    int64_t e = std::min(c.endUs, segEndAbsUs) - baseUs;
    if (e <= s) continue;
    auto* ch = static_cast<AVChapter*>(av_mallocz(sizeof(AVChapter)));
    if (!ch) throw std::bad_alloc();
    ch->id = id++;
    ch->time_base = AVRational{1, 1000};
    ch->start = s / 1000;
    ch->end = e / 1000;
    if (!c.title.empty()) av_dict_set(&ch->metadata, "title", c.title.c_str(), 0);
    AVChapter** grown = static_cast<AVChapter**>(av_realloc_array(oc->chapters, oc->nb_chapters + 1, sizeof(AVChapter*)));
    if (!grown) {
      av_dict_free(&ch->metadata);
      av_free(ch);
      throw std::bad_alloc();
    }
    oc->chapters = grown;
    oc->chapters[oc->nb_chapters++] = ch;
  }
}

void buildGlobalMetadata(AVFormatContext* oc, const Plan& plan, AVFormatContext* ic) {
  if (plan.metadataMode == "strip") {
    oc->flags |= AVFMT_FLAG_BITEXACT;  // also suppresses the muxer's own encoder tag
    return;
  }
  const AVDictionaryEntry* e = nullptr;
  while ((e = av_dict_iterate(ic->metadata, e))) {
    std::string k = e->key;
    if (k == "major_brand" || k == "minor_version" || k == "compatible_brands" || k == "encoder") continue;
    av_dict_set(&oc->metadata, e->key, e->value, 0);
  }
  if (plan.metadataMode == "custom") {
    for (const auto& [k, v] : plan.metadata) {
      if (v.empty()) av_dict_set(&oc->metadata, k.c_str(), nullptr, 0);
      else av_dict_set(&oc->metadata, k.c_str(), v.c_str(), 0);
    }
  }
}

}  // namespace

void TranscodeContext::noteDecodeError(const char* what) {
  ++decodeErrors;
  if (decodeErrors == 1) warnings.push_back(std::string("Some ") + what + " data was damaged and was skipped.");
  if (decodeErrors > kMaxDecodeErrors) {
    throwError(ErrorCategory::InvalidInput, "decode",
               "Unable to read video: the source is too badly corrupted to continue.", std::string(what) + " decode errors",
               {"Try a different copy of the file"});
  }
}

void prepareOutputCodecpar(const std::string& containerFormat, AVStream* os) {
  AVCodecParameters* p = os->codecpar;
  p->codec_tag = 0;
  // 'hvc1' (parameter sets in the sample entry) is what Apple players and most
  // Android galleries expect for HEVC in MP4/MOV.
  if (isMp4Family(containerFormat) && p->codec_id == AV_CODEC_ID_HEVC) p->codec_tag = MKTAG('h', 'v', 'c', '1');
}

// ---------------------------------------------------------------------------
Transcoder::Transcoder(Plan plan, int inputFd, int outputFd, JobControl& control, EventSink sink)
    : plan_(std::move(plan)), inputFd_(inputFd), outputFd_(outputFd), control_(control), sink_(std::move(sink)) {}

Transcoder::~Transcoder() = default;

void Transcoder::emitProgress(bool force) {
  int64_t now = monotonicUs();
  if (!force && now - lastEmitUs_ < plan_.progressIntervalMs * 1000LL) return;
  lastEmitUs_ = now;
  auto snap = progress_.sample();
  if (sink_) sink_(ev::kProgress, progress_.toJson(snap));
}

void Transcoder::extractBurnSubtitles() {
  if (!plan_.video || !plan_.video->burn) return;
  SubtitleBurn& b = *plan_.video->burn;
  if (b.bitmap) {
    throwError(ErrorCategory::InvalidConfiguration, "subtitles",
               "Burning image-based subtitles (PGS/VobSub/DVB) into video is not supported by this build.", "",
               {"Copy the subtitles into an MKV instead"});
  }
  if (b.sourceStreamIndex < 0) return;  // external file already on disk
  if (b.externalPath.empty()) {
    throwError(ErrorCategory::InvalidConfiguration, "subtitles", "No scratch location for subtitle burn-in.");
  }
  // Copy the embedded subtitle stream (and font attachments) into a small
  // Matroska file in app storage so libass can read it by path.
  InputFile in;
  in.open(inputFd_, "subtitle-extract", &control_.interrupt, true);
  AVFormatContext* ic = in.ctx();
  if (b.sourceStreamIndex >= static_cast<int>(ic->nb_streams) ||
      ic->streams[b.sourceStreamIndex]->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) {
    throwError(ErrorCategory::InvalidConfiguration, "subtitles", "The selected subtitle track does not exist.");
  }
  UniqueFd fd = UniqueFd::openPath(b.externalPath, true);
  OutputFile out;
  out.open(fd.get(), "matroska", &control_.interrupt);
  ContainerPlan cp;
  cp.format = "matroska";
  Muxer mux(out, cp);
  std::map<int, int> map;
  for (unsigned i = 0; i < ic->nb_streams; ++i) {
    AVStream* st = ic->streams[i];
    bool att = st->codecpar->codec_type == AVMEDIA_TYPE_ATTACHMENT;
    if (static_cast<int>(i) != b.sourceStreamIndex && !att) continue;
    int idx = mux.addStream();
    AVStream* os = mux.stream(idx);
    checkAv(avcodec_parameters_copy(os->codecpar, st->codecpar), "subtitles", "copying subtitle parameters");
    os->codecpar->codec_tag = 0;
    os->time_base = st->time_base;
    av_dict_copy(&os->metadata, st->metadata, 0);
    mux.markReady(idx);
    if (!att) map[i] = idx;
  }
  PacketPtr pkt = makePacket();
  while (av_read_frame(ic, pkt.get()) >= 0) {
    auto it = map.find(pkt->stream_index);
    if (it != map.end()) mux.write(it->second, pkt.get(), ic->streams[pkt->stream_index]->time_base);
    av_packet_unref(pkt.get());
    if (control_.cancelled()) throwError(ErrorCategory::Cancelled, "subtitles", "Cancelled.");
  }
  mux.finish();
  out.close();
}

TranscodeResult Transcoder::run() {
  TranscodeResult result;
  extractBurnSubtitles();
  bool twoPass = plan_.video && plan_.video->mode == VideoMode::Transcode && plan_.video->rc.twoPass &&
                 plan_.video->pipeline == Pipeline::Software;
  if (twoPass && plan_.video->encoder.rfind("libvpx", 0) != 0) {
    throwError(ErrorCategory::InvalidConfiguration, "plan",
               "Two-pass encoding is only available for VP8/VP9 (libvpx) in this build.");
  }
  if (twoPass) {
    runPass(1, result);
    if (passStats_.empty()) throwError(ErrorCategory::Internal, "two_pass", "The first pass produced no statistics.");
    runPass(2, result);
  } else {
    runPass(0, result);
  }
  return result;
}

void Transcoder::runPass(int pass, TranscodeResult& result) {
  const int64_t wallStart = monotonicUs();
  InputFile in;
  in.open(inputFd_, "source", &control_.interrupt, true);
  AVFormatContext* ic = in.ctx();

  // Resolve the primary video stream.
  AVStream* vIn = nullptr;
  if (plan_.video) {
    int vi = plan_.video->sourceStreamIndex;
    if (vi < 0 || vi >= static_cast<int>(ic->nb_streams) || ic->streams[vi]->codecpar->codec_type != AVMEDIA_TYPE_VIDEO) {
      throwError(ErrorCategory::InvalidConfiguration, "plan", "The selected video track does not exist in the source.");
    }
    vIn = ic->streams[vi];
  }
  const bool analysisPass = pass == 1;
  const bool videoCopy = plan_.video && plan_.video->mode == VideoMode::Copy;

  // Timing: base = container start (or segment start).
  int64_t inputStartUs = ic->start_time != AV_NOPTS_VALUE ? ic->start_time : 0;
  TranscodeContext ctx;
  ctx.plan = &plan_;
  ctx.progress = &progress_;
  ctx.control = &control_;
  ctx.pass = pass;
  ctx.passStats = passStats_;
  ctx.baseUs = inputStartUs;
  int64_t totalUs = ic->duration > 0 ? ic->duration : 0;
  if (plan_.segment) {
    int64_t segAbs = inputStartUs + plan_.segment->startUs;
    int ret = av_seek_frame(ic, -1, segAbs, AVSEEK_FLAG_BACKWARD);
    if (ret < 0 && plan_.segment->startUs > 0) {
      throw EngineException(fromAvError(ret, "seek", "seeking to the preview position"));
    }
    ctx.baseUs = segAbs;
    ctx.segStartAbsUs = segAbs;
    ctx.segEndAbsUs = segAbs + plan_.segment->durationUs;
    totalUs = plan_.segment->durationUs;
    if (ic->duration > 0) totalUs = std::min(totalUs, inputStartUs + ic->duration - segAbs);
    if (videoCopy && vIn) {
      // Stream copy must start on a keyframe: move the segment start back to
      // the keyframe so that every stream stays aligned to the same instant.
      PacketPtr p = makePacket();
      while (av_read_frame(ic, p.get()) >= 0) {
        bool hit = p->stream_index == vIn->index && (p->flags & AV_PKT_FLAG_KEY) && p->pts != AV_NOPTS_VALUE;
        int64_t kUs = hit ? toUs(p->pts, vIn->time_base) : 0;
        av_packet_unref(p.get());
        if (hit) {
          ctx.baseUs = ctx.segStartAbsUs = std::min(kUs, segAbs);
          break;
        }
      }
      checkAv(av_seek_frame(ic, -1, ctx.segStartAbsUs, AVSEEK_FLAG_BACKWARD), "seek", "seeking to the preview position");
    }
  }

  std::unique_ptr<OutputFile> out;
  std::unique_ptr<Muxer> mux;
  if (!analysisPass) {
    out = std::make_unique<OutputFile>();
    out->open(outputFd_, plan_.container.format, &control_.interrupt);
    mux = std::make_unique<Muxer>(*out, plan_.container);
    ctx.muxer = mux.get();
    ctx.globalHeader = (out->ctx()->oformat->flags & AVFMT_GLOBALHEADER) != 0;
  }

  std::map<int, std::unique_ptr<StreamHandler>> handlers;  // keyed by input stream
  std::vector<ExternalSubtitleSource> externals;
  int progressStream = -1;
  std::string pipelineLabel = "remux";
  std::string encoderName;

  // Check container/codec compatibility before any work.
  auto checkCodec = [&](AVCodecID id, const std::string& what) {
    if (analysisPass) return;
    int q = avformat_query_codec(out->ctx()->oformat, id, FF_COMPLIANCE_NORMAL);
    if (q == 0) {
      const AVCodecDescriptor* d = avcodec_descriptor_get(id);
      throwError(ErrorCategory::InvalidConfiguration, "compatibility",
                 "Configuration conflict: " + plan_.container.format + " cannot contain " + what + " (" +
                     (d ? d->name : "?") + ").",
                 "", {"Choose MKV", "Choose a different codec for this track"});
    }
  };

  if (plan_.video && vIn) {
    int outIdx = mux ? mux->addStream() : -1;
    if (mux) {
      AVStream* os = mux->stream(outIdx);
      copyStreamMetadata(&os->metadata, vIn->metadata, plan_.metadataMode != "strip");
      os->disposition = AV_DISPOSITION_DEFAULT;
    }
    const VideoPlan& v = *plan_.video;
    if (v.mode == VideoMode::Copy) {
      checkCodec(vIn->codecpar->codec_id, "this video codec");
      if (mux) {
        AVStream* os = mux->stream(outIdx);
        checkAv(avcodec_parameters_copy(os->codecpar, vIn->codecpar), "mux", "copying video parameters");
        os->time_base = vIn->time_base;
        os->avg_frame_rate = vIn->avg_frame_rate;
        os->r_frame_rate = vIn->r_frame_rate;
        os->sample_aspect_ratio = vIn->sample_aspect_ratio;
        prepareOutputCodecpar(plan_.container.format, os);
        mux->markReady(outIdx);
      }
      handlers[vIn->index] = std::make_unique<CopyHandler>(ctx, vIn, outIdx, true);
      encoderName = "copy";
    } else {
      const AVCodec* encCodec = v.pipeline == Pipeline::Software ? avcodec_find_encoder_by_name(v.encoder.c_str()) : nullptr;
      if (encCodec) checkCodec(encCodec->id, "this video codec");
      if (v.pipeline == Pipeline::Hardware) {
        if (!hardwarePipelineAvailable()) {
          throwError(ErrorCategory::HardwareCodecFailure, "pipeline", "Hardware encoding is not available on this platform.");
        }
        handlers[vIn->index] = createHardwareVideoHandler(ctx, vIn, outIdx);
      } else {
        handlers[vIn->index] = std::make_unique<VideoTranscodeHandler>(ctx, vIn, outIdx);
      }
      pipelineLabel = pipelineName(v.pipeline);
      encoderName = v.encoder;
    }
    progressStream = vIn->index;
  }

  if (!analysisPass) {
    for (const AudioPlan& ap : plan_.audio) {
      if (ap.sourceStreamIndex >= static_cast<int>(ic->nb_streams) ||
          ic->streams[ap.sourceStreamIndex]->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
        throwError(ErrorCategory::InvalidConfiguration, "plan", "A selected audio track does not exist in the source.");
      }
      AVStream* aIn = ic->streams[ap.sourceStreamIndex];
      if (handlers.count(aIn->index)) {
        throwError(ErrorCategory::InvalidConfiguration, "plan", "The same audio track was selected twice.");
      }
      int outIdx = mux->addStream();
      AVStream* os = mux->stream(outIdx);
      copyStreamMetadata(&os->metadata, aIn->metadata, plan_.metadataMode != "strip");
      if (ap.copy) {
        checkCodec(aIn->codecpar->codec_id, "this audio codec (passthrough)");
        checkAv(avcodec_parameters_copy(os->codecpar, aIn->codecpar), "mux", "copying audio parameters");
        os->codecpar->codec_tag = 0;
        os->time_base = aIn->time_base;
        mux->markReady(outIdx);
        handlers[aIn->index] = std::make_unique<CopyHandler>(ctx, aIn, outIdx, videoCopy);
      } else {
        const AVCodec* ac = avcodec_find_encoder_by_name(ap.encoder.c_str());
        if (ac) checkCodec(ac->id, "this audio codec");
        handlers[aIn->index] = std::make_unique<AudioTranscodeHandler>(ctx, aIn, outIdx, ap);
      }
      applyTrackLabels(os, ap.language, ap.title, ap.isDefault, false);
      if (progressStream < 0) progressStream = aIn->index;
    }

    for (const SubtitlePlan& sp : plan_.subtitles) {
      if (sp.sourceStreamIndex >= 0) {
        if (sp.sourceStreamIndex >= static_cast<int>(ic->nb_streams) ||
            ic->streams[sp.sourceStreamIndex]->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) {
          throwError(ErrorCategory::InvalidConfiguration, "plan", "A selected subtitle track does not exist in the source.");
        }
        AVStream* sIn = ic->streams[sp.sourceStreamIndex];
        if (sp.copy) checkCodec(sIn->codecpar->codec_id, "these subtitles");
        else if (const AVCodec* sc = avcodec_find_encoder_by_name(sp.codec.c_str())) checkCodec(sc->id, "these subtitles");
        int outIdx = mux->addStream();
        AVStream* os = mux->stream(outIdx);
        copyStreamMetadata(&os->metadata, sIn->metadata, plan_.metadataMode != "strip");
        handlers[sIn->index] = std::make_unique<SubtitleHandler>(ctx, sIn, outIdx, sp);
        applyTrackLabels(os, sp.language, sp.title, sp.isDefault, sp.forced);
      } else {
        ExternalSubtitleSource ext;
        ext.input = std::make_unique<InputFile>();
        UniqueFd fd = UniqueFd::openPath(sp.externalPath, false);
        ext.input->open(fd.get(), "external-subtitle", &control_.interrupt, true);
        AVFormatContext* sc = ext.input->ctx();
        if (sc->nb_streams < 1 || sc->streams[0]->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) {
          throwError(ErrorCategory::InvalidInput, "subtitles", "The external subtitle file could not be read.");
        }
        if (sp.copy) checkCodec(sc->streams[0]->codecpar->codec_id, "these subtitles");
        int outIdx = mux->addStream();
        AVStream* os = mux->stream(outIdx);
        // External cues are relative to the start of the video.
        TranscodeContext* extCtx = &ctx;
        ext.handler = std::make_unique<SubtitleHandler>(*extCtx, sc->streams[0], outIdx, sp);
        applyTrackLabels(os, sp.language, sp.title, sp.isDefault, sp.forced);
        PacketPtr p = makePacket();
        int64_t offsetTs = av_rescale_q(inputStartUs, AV_TIME_BASE_Q, sc->streams[0]->time_base);
        while (av_read_frame(sc, p.get()) >= 0) {
          PacketPtr keep = makePacket();
          av_packet_move_ref(keep.get(), p.get());
          if (keep->pts != AV_NOPTS_VALUE) keep->pts += offsetTs;
          if (keep->dts != AV_NOPTS_VALUE) keep->dts += offsetTs;
          ext.packets.push_back(std::move(keep));
        }
        std::stable_sort(ext.packets.begin(), ext.packets.end(),
                         [](const PacketPtr& a, const PacketPtr& b) { return a->pts < b->pts; });
        externals.push_back(std::move(ext));
      }
    }

    // Font attachments (needed by ASS subtitles) for Matroska outputs.
    if (plan_.copyAttachments && (plan_.container.format == "matroska")) {
      for (unsigned i = 0; i < ic->nb_streams; ++i) {
        AVStream* st = ic->streams[i];
        if (st->codecpar->codec_type != AVMEDIA_TYPE_ATTACHMENT) continue;
        int outIdx = mux->addStream();
        AVStream* os = mux->stream(outIdx);
        checkAv(avcodec_parameters_copy(os->codecpar, st->codecpar), "mux", "copying an attachment");
        os->time_base = st->time_base;
        av_dict_copy(&os->metadata, st->metadata, 0);
        mux->markReady(outIdx);
      }
    }

    buildGlobalMetadata(out->ctx(), plan_, ic);
    addChapters(out->ctx(), plan_, ic, ctx.baseUs, ctx.segEndAbsUs, inputStartUs);
  }

  if (handlers.empty()) throwError(ErrorCategory::InvalidConfiguration, "plan", "Nothing to encode.");

  if (pass <= 1) {
    progress_.begin(totalUs, vIn && vIn->avg_frame_rate.num > 0 ? av_q2d(vIn->avg_frame_rate) : 0, pass == 1 ? 2 : 1);
  } else {
    progress_.setPass(1);
  }
  if (sink_) {
    sink_(ev::kPipelineSelected, Json{{"pipeline", pipelineLabel}, {"encoder", encoderName}, {"pass", pass}});
    if (pass <= 1) sink_(ev::kEncodeStarted, Json{{"totalUs", totalUs}, {"passes", pass == 1 ? 2 : 1}});
  }

  // ---- main demux loop ----
  PacketPtr pkt = makePacket();
  int64_t sourceBytes = 0;
  int readErrors = 0;
  auto onPauseChange = [&](bool paused) {
    progress_.setPaused(paused);
    if (sink_) sink_(paused ? ev::kPaused : ev::kResumed, Json::object());
  };
  for (;;) {
    if (!control_.checkpoint(onPauseChange)) throwError(ErrorCategory::Cancelled, "encode", "Cancelled.");
    int ret = av_read_frame(ic, pkt.get());
    if (ret == AVERROR_EOF) break;
    if (ret == AVERROR_EXIT || control_.cancelled()) throwError(ErrorCategory::Cancelled, "encode", "Cancelled.");
    if (ret == AVERROR(EAGAIN)) continue;
    if (ret < 0) {
      // Truncated or damaged files: stop at the damage rather than fail when
      // most of the file has been read; otherwise report the source as broken.
      if (++readErrors > 8 || ret == AVERROR(EIO) || ret == AVERROR(EBADF)) {
        if (ret == AVERROR_INVALIDDATA && progress_.sample().progress > 0.95) {
          ctx.warnings.push_back("The end of the source file is damaged; output stops at the damage.");
          break;
        }
        throw EngineException(fromAvError(ret, "demux", "reading the source"));
      }
      continue;
    }
    auto it = handlers.find(pkt->stream_index);
    if (it == handlers.end()) {
      av_packet_unref(pkt.get());
      continue;
    }
    AVStream* st = ic->streams[pkt->stream_index];
    int64_t tsUs = toUs(pkt->dts != AV_NOPTS_VALUE ? pkt->dts : pkt->pts, st->time_base);
    if (!(ctx.afterEnd(tsUs) || ctx.beforeStart(tsUs))) sourceBytes += pkt->size;
    progress_.addInputBytes(pkt->size);
    it->second->onPacket(pkt.get());
    av_packet_unref(pkt.get());

    // Release external subtitle cues up to the current position.
    if (!externals.empty() && tsUs != AV_NOPTS_VALUE) {
      for (auto& ext : externals) {
        AVStream* es = ext.input->ctx()->streams[0];
        while (!ext.packets.empty() && toUs(ext.packets.front()->pts, es->time_base) <= tsUs) {
          ext.handler->onPacket(ext.packets.front().get());
          ext.packets.pop_front();
        }
      }
    }

    if (plan_.segment) {
      // In a preview, stop once the driving stream has passed the end
      // (plus margin for B-frame reordering) or every handler is done.
      bool allDone = true;
      for (auto& [idx, h] : handlers) allDone = allDone && h->reachedEnd();
      if (allDone || (pkt->stream_index == progressStream && tsUs != AV_NOPTS_VALUE && tsUs > ctx.segEndAbsUs + 2 * kUsPerSec)) {
        break;
      }
    }
    emitProgress(false);
  }

  for (auto& [idx, h] : handlers) {
    if (!control_.checkpoint(onPauseChange)) throwError(ErrorCategory::Cancelled, "encode", "Cancelled.");
    h->finish();
  }
  for (auto& ext : externals) {
    while (!ext.packets.empty()) {
      ext.handler->onPacket(ext.packets.front().get());
      ext.packets.pop_front();
    }
  }
  if (analysisPass) {
    if (vIn) passStats_ = handlers[vIn->index]->passStats();
    return;
  }
  mux->finish();
  int closeErr = out->close();
  if (closeErr < 0) throw EngineException(fromAvError(closeErr, "output_close", "writing the output file"));
  emitProgress(true);

  auto snap = progress_.sample();
  Json s = progress_.toJson(snap);
  s["outputBytes"] = mux->bytesWritten();
  s["sourceSegmentBytes"] = sourceBytes;
  s["mediaDurationUs"] = totalUs;
  s["wallTimeUs"] = monotonicUs() - wallStart;
  s["pipeline"] = pipelineLabel;
  s["encoder"] = encoderName;
  s["passes"] = pass == 2 ? 2 : 1;
  s["decodeErrors"] = ctx.decodeErrors;
  s["warnings"] = ctx.warnings;
  result.stats = s;
}

}  // namespace vc
