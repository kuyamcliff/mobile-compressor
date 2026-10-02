#include "analysis/BuildInfo.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace vc {

Json buildInfo() {
  Json j;
  j["engineVersion"] = kEngineVersion;
  j["ffmpegVersion"] = av_version_info();
  j["license"] = avcodec_license();
  j["configuration"] = avcodec_configuration();
  auto ver = [](unsigned v) {
    return std::to_string(AV_VERSION_MAJOR(v)) + "." + std::to_string(AV_VERSION_MINOR(v)) + "." +
           std::to_string(AV_VERSION_MICRO(v));
  };
  j["libraries"] = {{"libavcodec", ver(avcodec_version())},     {"libavformat", ver(avformat_version())},
                    {"libavfilter", ver(avfilter_version())},   {"libavutil", ver(avutil_version())},
                    {"libswscale", ver(swscale_version())},     {"libswresample", ver(swresample_version())}};
  Json enc = Json::array(), dec = Json::array(), mux = Json::array(), demux = Json::array(), filters = Json::array();
  void* it = nullptr;
  while (const AVCodec* c = av_codec_iterate(&it)) {
    Json e{{"name", c->name}, {"type", av_get_media_type_string(c->type) ? av_get_media_type_string(c->type) : ""},
           {"longName", c->long_name ? c->long_name : ""}};
    (av_codec_is_encoder(c) ? enc : dec).push_back(e);
  }
  it = nullptr;
  while (const AVOutputFormat* f = av_muxer_iterate(&it)) mux.push_back(f->name);
  it = nullptr;
  while (const AVInputFormat* f = av_demuxer_iterate(&it)) demux.push_back(f->name);
  it = nullptr;
  while (const AVFilter* f = av_filter_iterate(&it)) filters.push_back(f->name);
  j["encoders"] = enc;
  j["decoders"] = dec;
  j["muxers"] = mux;
  j["demuxers"] = demux;
  j["filters"] = filters;
  return j;
}

int containerSupportsCodec(const std::string& muxer, const std::string& codecName) {
  const AVOutputFormat* of = av_guess_format(muxer.c_str(), nullptr, nullptr);
  const AVCodecDescriptor* d = avcodec_descriptor_get_by_name(codecName.c_str());
  if (!of || !d) return -1;
  int r = avformat_query_codec(of, d->id, FF_COMPLIANCE_NORMAL);
  return r < 0 ? -1 : (r ? 1 : 0);
}

}  // namespace vc
