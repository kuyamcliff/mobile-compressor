#include "probe/Probe.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <vector>

#include "core/Errors.h"
#include "core/Log.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libavutil/pixdesc.h>
}

namespace vc {

namespace {
constexpr const char* TAG = "Probe";

const char* mediaTypeName(AVMediaType t) {
  switch (t) {
    case AVMEDIA_TYPE_VIDEO: return "video";
    case AVMEDIA_TYPE_AUDIO: return "audio";
    case AVMEDIA_TYPE_SUBTITLE: return "subtitle";
    case AVMEDIA_TYPE_ATTACHMENT: return "attachment";
    case AVMEDIA_TYPE_DATA: return "data";
    default: return "unknown";
  }
}

std::string orEmpty(const char* s) { return s ? s : ""; }

const char* tagValue(const AVDictionary* d, const char* key) {
  const AVDictionaryEntry* e = av_dict_get(d, key, nullptr, 0);
  return e ? e->value : nullptr;
}

bool isBitmapSubtitle(AVCodecID id) {
  const AVCodecDescriptor* d = avcodec_descriptor_get(id);
  return d && (d->props & AV_CODEC_PROP_BITMAP_SUB);
}

int64_t streamBitrate(const AVStream* st) {
  if (st->codecpar->bit_rate > 0) return st->codecpar->bit_rate;
  // Matroska muxers commonly write per-track statistics tags.
  for (const char* k : {"BPS", "BPS-eng", "variant_bitrate"}) {
    if (const char* v = tagValue(st->metadata, k)) {
      char* end = nullptr;
      long long b = std::strtoll(v, &end, 10);
      if (end != v && b > 0) return b;
    }
  }
  return 0;
}

struct VfrSample {
  std::vector<int64_t> pts;
  int64_t bytes = 0;
  int64_t firstUs = AV_NOPTS_VALUE;
  int64_t lastUs = AV_NOPTS_VALUE;
};

// Reads up to `maxPackets` video packets (headers only, no decoding) to estimate
// whether timing is variable. Packet pts arrive in decode order, so they are
// sorted before computing frame durations.
Json detectFrameTiming(AVFormatContext* fmt, int videoIndex) {
  Json out = Json::object();
  if (videoIndex < 0) return out;
  AVStream* st = fmt->streams[videoIndex];
  VfrSample s;
  AVPacket* pkt = av_packet_alloc();
  if (!pkt) return out;
  int total = 0;
  while (s.pts.size() < 360 && total < 4000) {
    int r = av_read_frame(fmt, pkt);
    if (r < 0) break;
    ++total;
    if (pkt->stream_index == videoIndex && pkt->pts != AV_NOPTS_VALUE) {
      s.pts.push_back(pkt->pts);
      s.bytes += pkt->size;
    }
    av_packet_unref(pkt);
  }
  av_packet_free(&pkt);
  if (s.pts.size() < 8) {
    out["vfr"] = nullptr;  // unknown
    return out;
  }
  std::sort(s.pts.begin(), s.pts.end());
  std::vector<double> d;
  for (size_t i = 1; i < s.pts.size(); ++i) {
    int64_t delta = s.pts[i] - s.pts[i - 1];
    if (delta > 0) d.push_back(delta * av_q2d(st->time_base));
  }
  if (d.size() < 4) {
    out["vfr"] = nullptr;
    return out;
  }
  std::vector<double> sorted = d;
  std::sort(sorted.begin(), sorted.end());
  double median = sorted[sorted.size() / 2];
  double p05 = sorted[sorted.size() * 5 / 100];
  double p95 = sorted[std::min(sorted.size() - 1, sorted.size() * 95 / 100)];
  // Container timestamp rounding (e.g. 1 ms Matroska ticks at 29.97 fps) jitters
  // by up to one tick; treat spreads under ~12% of a frame as constant.
  bool vfr = median > 0 && (p95 - p05) / median > 0.12;
  double span = (s.pts.back() - s.pts.front()) * av_q2d(st->time_base);
  out["vfr"] = vfr;
  out["medianFrameDurationUs"] = static_cast<int64_t>(median * 1e6);
  out["minFrameDurationUs"] = static_cast<int64_t>(sorted.front() * 1e6);
  out["maxFrameDurationUs"] = static_cast<int64_t>(sorted.back() * 1e6);
  if (span > 0) out["sampledFps"] = (s.pts.size() - 1) / span;
  if (span > 0.5) out["sampledVideoBitrate"] = static_cast<int64_t>(s.bytes * 8 / span);
  return out;
}
}  // namespace

std::string dumpJson(const Json& j) { return j.dump(-1, ' ', false, Json::error_handler_t::replace); }

int pixFmtBitDepth(int pixFmt) {
  const AVPixFmtDescriptor* d = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(pixFmt));
  if (!d || d->nb_components == 0) return 0;
  return d->comp[0].depth;
}

int streamRotation(const AVStream* st) {
  const AVPacketSideData* sd = av_packet_side_data_get(st->codecpar->coded_side_data, st->codecpar->nb_coded_side_data,
                                                       AV_PKT_DATA_DISPLAYMATRIX);
  if (!sd || sd->size < 9 * 4) return 0;
  double theta = -av_display_rotation_get(reinterpret_cast<const int32_t*>(sd->data));
  if (std::isnan(theta)) return 0;
  theta -= 360 * std::floor(theta / 360 + 0.9 / 360);
  int r = static_cast<int>(std::lround(theta / 90.0)) * 90;
  r = ((r % 360) + 360) % 360;
  return r;
}

std::string classifyHdr(const AVStream* st) {
  const AVCodecParameters* p = st->codecpar;
  auto has = [&](AVPacketSideDataType t) {
    return av_packet_side_data_get(p->coded_side_data, p->nb_coded_side_data, t) != nullptr;
  };
  if (has(AV_PKT_DATA_DOVI_CONF)) return "dolby_vision";
  if (p->color_trc == AVCOL_TRC_SMPTE2084) {
    if (has(AV_PKT_DATA_DYNAMIC_HDR10_PLUS)) return "hdr10plus";
    if (has(AV_PKT_DATA_MASTERING_DISPLAY_METADATA) || has(AV_PKT_DATA_CONTENT_LIGHT_LEVEL)) return "hdr10";
    return "pq";
  }
  if (p->color_trc == AVCOL_TRC_ARIB_STD_B67) return "hlg";
  return "sdr";
}

Json describeInput(AVFormatContext* fmt, int64_t fileSize) {
  Json root;
  Json format;
  format["name"] = orEmpty(fmt->iformat ? fmt->iformat->name : nullptr);
  format["longName"] = orEmpty(fmt->iformat ? fmt->iformat->long_name : nullptr);
  format["durationUs"] = fmt->duration != AV_NOPTS_VALUE ? fmt->duration : 0;
  format["startTimeUs"] = fmt->start_time != AV_NOPTS_VALUE ? fmt->start_time : 0;
  format["bitrate"] = fmt->bit_rate;
  format["size"] = fileSize;
  format["tags"] = dictToJson(fmt->metadata);
  Json chapters = Json::array();
  for (unsigned i = 0; i < fmt->nb_chapters && i < 1000; ++i) {
    const AVChapter* c = fmt->chapters[i];
    const char* title = tagValue(c->metadata, "title");
    chapters.push_back({{"id", c->id},
                        {"startUs", toUs(c->start, c->time_base)},
                        {"endUs", toUs(c->end, c->time_base)},
                        {"title", truncateUtf8(orEmpty(title), 256)}});
  }
  format["chapters"] = chapters;
  root["format"] = format;

  Json streams = Json::array();
  int64_t knownNonVideoBitrate = 0;
  int firstVideo = -1;
  for (unsigned i = 0; i < fmt->nb_streams; ++i) {
    const AVStream* st = fmt->streams[i];
    const AVCodecParameters* p = st->codecpar;
    Json s;
    s["index"] = st->index;
    s["type"] = mediaTypeName(p->codec_type);
    const AVCodecDescriptor* cd = avcodec_descriptor_get(p->codec_id);
    s["codec"] = cd ? cd->name : "unknown";
    s["codecLongName"] = cd && cd->long_name ? cd->long_name : "";
    const char* profile = avcodec_profile_name(p->codec_id, p->profile);
    s["profile"] = orEmpty(profile);
    s["level"] = p->level;
    int64_t br = streamBitrate(st);
    s["bitrate"] = br;
    s["language"] = truncateUtf8(orEmpty(tagValue(st->metadata, "language")), 16);
    s["title"] = truncateUtf8(orEmpty(tagValue(st->metadata, "title")), 256);
    s["handler"] = truncateUtf8(orEmpty(tagValue(st->metadata, "handler_name")), 128);
    s["encoder"] = truncateUtf8(orEmpty(tagValue(st->metadata, "encoder")), 128);
    s["default"] = (st->disposition & AV_DISPOSITION_DEFAULT) != 0;
    s["forced"] = (st->disposition & AV_DISPOSITION_FORCED) != 0;
    s["hearingImpaired"] = (st->disposition & AV_DISPOSITION_HEARING_IMPAIRED) != 0;
    s["comment"] = (st->disposition & AV_DISPOSITION_COMMENT) != 0;
    s["attachedPic"] = (st->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0;
    s["durationUs"] = st->duration != AV_NOPTS_VALUE ? toUs(st->duration, st->time_base) : 0;
    s["frameCount"] = st->nb_frames;
    s["timeBaseNum"] = st->time_base.num;
    s["timeBaseDen"] = st->time_base.den;
    s["tags"] = dictToJson(st->metadata, 256, 32);
    s["decoderAvailable"] = avcodec_find_decoder(p->codec_id) != nullptr;

    if (p->codec_type == AVMEDIA_TYPE_VIDEO) {
      Json v;
      v["width"] = p->width;
      v["height"] = p->height;
      AVRational sar = st->sample_aspect_ratio.num ? st->sample_aspect_ratio : p->sample_aspect_ratio;
      if (sar.num <= 0 || sar.den <= 0) sar = AVRational{1, 1};
      v["sarNum"] = sar.num;
      v["sarDen"] = sar.den;
      int rotation = streamRotation(st);
      v["rotation"] = rotation;
      // Display size: apply SAR then rotation, which is what a player shows.
      int dispW = static_cast<int>(std::lround(p->width * av_q2d(sar)));
      int dispH = p->height;
      if (rotation == 90 || rotation == 270) std::swap(dispW, dispH);
      v["displayWidth"] = dispW;
      v["displayHeight"] = dispH;
      v["fpsNum"] = st->avg_frame_rate.num;
      v["fpsDen"] = st->avg_frame_rate.den;
      v["rFpsNum"] = st->r_frame_rate.num;
      v["rFpsDen"] = st->r_frame_rate.den;
      const char* pf = av_get_pix_fmt_name(static_cast<AVPixelFormat>(p->format));
      v["pixFmt"] = orEmpty(pf);
      v["bitDepth"] = p->format >= 0 ? pixFmtBitDepth(p->format) : 0;
      const AVPixFmtDescriptor* pd = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(p->format));
      v["chroma"] = pd ? (pd->log2_chroma_w == 1 && pd->log2_chroma_h == 1 ? "4:2:0"
                          : pd->log2_chroma_w == 1                         ? "4:2:2"
                                                                           : "4:4:4")
                       : "";
      v["colorSpace"] = orEmpty(av_color_space_name(p->color_space));
      v["colorPrimaries"] = orEmpty(av_color_primaries_name(p->color_primaries));
      v["colorTransfer"] = orEmpty(av_color_transfer_name(p->color_trc));
      v["colorRange"] = orEmpty(av_color_range_name(p->color_range));
      v["chromaLocation"] = orEmpty(av_chroma_location_name(p->chroma_location));
      v["hdr"] = classifyHdr(st);
      v["fieldOrder"] = p->field_order == AV_FIELD_PROGRESSIVE ? "progressive"
                        : p->field_order == AV_FIELD_UNKNOWN   ? "unknown"
                                                               : "interlaced";
      s["video"] = v;
      if (firstVideo < 0 && !(st->disposition & AV_DISPOSITION_ATTACHED_PIC)) firstVideo = st->index;
    } else if (p->codec_type == AVMEDIA_TYPE_AUDIO) {
      Json a;
      a["sampleRate"] = p->sample_rate;
      a["channels"] = p->ch_layout.nb_channels;
      char layout[128] = {0};
      av_channel_layout_describe(&p->ch_layout, layout, sizeof(layout));
      a["channelLayout"] = layout;
      a["sampleFmt"] = orEmpty(av_get_sample_fmt_name(static_cast<AVSampleFormat>(p->format)));
      a["bitsPerSample"] = p->bits_per_raw_sample ? p->bits_per_raw_sample : p->bits_per_coded_sample;
      a["lossless"] = cd && (cd->props & AV_CODEC_PROP_LOSSLESS) && !(cd->props & AV_CODEC_PROP_LOSSY);
      s["audio"] = a;
      knownNonVideoBitrate += br;
    } else if (p->codec_type == AVMEDIA_TYPE_SUBTITLE) {
      Json t;
      t["bitmap"] = isBitmapSubtitle(p->codec_id);
      t["text"] = cd && (cd->props & AV_CODEC_PROP_TEXT_SUB);
      s["subtitle"] = t;
    } else if (p->codec_type == AVMEDIA_TYPE_ATTACHMENT) {
      s["filename"] = truncateUtf8(orEmpty(tagValue(st->metadata, "filename")), 256);
      s["mimetype"] = truncateUtf8(orEmpty(tagValue(st->metadata, "mimetype")), 128);
    }
    streams.push_back(s);
  }
  root["streams"] = streams;
  root["primaryVideoIndex"] = firstVideo;

  // Estimated video bitrate when the container doesn't state it.
  if (firstVideo >= 0) {
    Json& vs = root["streams"][firstVideo];
    if (vs["bitrate"].get<int64_t>() <= 0 && fmt->duration > 0 && fileSize > 0) {
      int64_t total = fileSize * 8 * AV_TIME_BASE / fmt->duration;
      int64_t est = total - knownNonVideoBitrate;
      if (est > 0) {
        vs["bitrate"] = est;
        vs["bitrateEstimated"] = true;
      }
    }
  }
  const char* enc = tagValue(fmt->metadata, "encoder");
  root["encoder"] = truncateUtf8(orEmpty(enc), 128);
  return root;
}

Json probeSource(int fd, const std::string& displayName, InterruptFlag* interrupt) {
  InputFile in;
  in.open(fd, displayName, interrupt, true);
  Json info = describeInput(in.ctx(), in.fileSize());
  int vi = info["primaryVideoIndex"].get<int>();
  if (vi >= 0) {
    Json timing = detectFrameTiming(in.ctx(), vi);
    info["streams"][vi]["timing"] = timing;
  }
  bool hasAV = false;
  for (const auto& s : info["streams"]) {
    if (s["type"] == "video" || s["type"] == "audio") hasAV = true;
  }
  if (!hasAV) {
    throwError(ErrorCategory::InvalidInput, "probe", "Unable to read video: the file has no audio or video streams.");
  }
  VC_LOGI(TAG, "probe ok streams=%zu duration=%lldus", info["streams"].size(),
          static_cast<long long>(info["format"]["durationUs"].get<int64_t>()));
  return info;
}

}  // namespace vc
