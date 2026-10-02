#include "pipeline/FilterBuilder.h"

#include <cmath>
#include <sstream>
#include <vector>

#include "core/Errors.h"

namespace vc {

namespace {
void addRotation(std::vector<std::string>& f, int deg) {
  switch (deg) {
    case 90: f.emplace_back("transpose=clock"); break;
    case 180: f.emplace_back("hflip"); f.emplace_back("vflip"); break;
    case 270: f.emplace_back("transpose=cclock"); break;
    default: break;
  }
}

std::string join(const std::vector<std::string>& parts) {
  std::string out;
  for (const auto& p : parts) {
    if (!out.empty()) out += ',';
    out += p;
  }
  return out;
}

bool isHdrTransfer(const std::string& hdr) { return hdr != "sdr"; }

// zscale names differ from FFmpeg's colour names in a few places.
std::string zPrimaries(const std::string& p) {
  if (p == "bt709") return "709";
  if (p == "bt2020") return "2020";
  if (p == "smpte170m" || p == "bt470bg") return "170m";
  if (p == "smpte432") return "smpte432";
  return "";
}
std::string zTransfer(const std::string& t) {
  if (t == "bt709") return "709";
  if (t == "smpte2084") return "smpte2084";
  if (t == "arib-std-b67") return "arib-std-b67";
  if (t == "smpte170m") return "601";
  if (t == "iec61966-2-1") return "iec61966-2-1";
  if (t == "linear") return "linear";
  return "";
}
std::string zMatrix(const std::string& m) {
  if (m == "bt709") return "709";
  if (m == "bt2020nc" || m == "bt2020ncl") return "2020_ncl";
  if (m == "bt2020c") return "2020_cl";
  if (m == "smpte170m" || m == "bt470bg") return "470bg";
  return "";
}
}  // namespace

std::string escapeFilterValue(const std::string& v) {
  // Two levels: option value escaping (\ ' :) then graph-level escaping of the
  // quoted string. Paths are app-generated, but escape defensively anyway.
  std::string level1;
  for (char c : v) {
    if (c == '\\' || c == '\'' || c == ':') level1 += '\\';
    level1 += c;
  }
  std::string out = "'";
  for (char c : level1) {
    if (c == '\'') {
      out += "'\\''";
    } else {
      out += c;
    }
  }
  out += "'";
  return out;
}

void orientedSize(const VideoPlan& v, const VideoSourceProps& s, int& w, int& h) {
  w = s.width;
  h = s.height;
  int rot = (v.autorotate ? s.rotation : 0) + v.filters.rotate;
  rot %= 360;
  if (rot == 90 || rot == 270) std::swap(w, h);
}

std::string buildVideoFilter(const VideoPlan& v, const VideoSourceProps& s, const std::string& encoderPixFmt) {
  std::vector<std::string> f;
  const VideoFilters& vf = v.filters;

  if (vf.detelecine) {
    f.emplace_back("fieldmatch=order=auto:combmatch=full");
    f.emplace_back("yadif=mode=send_frame:deint=interlaced");
    f.emplace_back("decimate");
  } else if (!vf.deinterlace.empty()) {
    if (vf.deinterlace != "yadif" && vf.deinterlace != "bwdif") {
      throwError(ErrorCategory::InvalidConfiguration, "filters", "Unknown deinterlacer: " + vf.deinterlace);
    }
    f.emplace_back(vf.deinterlace + "=mode=send_frame:parity=auto:deint=" + (vf.deinterlaceAuto ? "interlaced" : "all"));
  }

  if (v.fpsMode == FpsMode::Cfr ||
      (v.fpsMode == FpsMode::PeakLimit && s.fps > 0 && s.fps > static_cast<double>(v.fpsNum) / v.fpsDen + 0.01)) {
    f.emplace_back("fps=fps=" + std::to_string(v.fpsNum) + "/" + std::to_string(v.fpsDen) + ":round=near");
  }

  if (v.autorotate) addRotation(f, s.rotation);
  addRotation(f, vf.rotate);
  if (vf.hflip) f.emplace_back("hflip");
  if (vf.vflip) f.emplace_back("vflip");

  int ow = 0, oh = 0;
  orientedSize(v, s, ow, oh);
  if (v.crop.any()) {
    int cw = ow - v.crop.left - v.crop.right;
    int ch = oh - v.crop.top - v.crop.bottom;
    if (cw < 16 || ch < 16) {
      throwError(ErrorCategory::InvalidConfiguration, "filters", "The crop removes the whole picture.");
    }
    cw &= ~1;
    ch &= ~1;
    f.emplace_back("crop=w=" + std::to_string(cw) + ":h=" + std::to_string(ch) + ":x=" + std::to_string(v.crop.left) +
                   ":y=" + std::to_string(v.crop.top) + ":exact=1");
  }

  const Denoise& dn = vf.denoise;
  if (!dn.algo.empty()) {
    int lvl = dn.strength == "strong" ? 2 : dn.strength == "medium" ? 1 : 0;
    if (dn.algo == "nlmeans") {
      static const char* p[] = {"nlmeans=s=1.5:p=5:r=9", "nlmeans=s=3.0:p=7:r=11", "nlmeans=s=5.0:p=7:r=15"};
      f.emplace_back(p[lvl]);
    } else if (dn.algo == "atadenoise") {
      static const char* p[] = {"atadenoise=0a=0.02:0b=0.04:1a=0.02:1b=0.04:2a=0.02:2b=0.04:s=5",
                                "atadenoise=0a=0.04:0b=0.08:1a=0.04:1b=0.08:2a=0.04:2b=0.08:s=9",
                                "atadenoise=0a=0.08:0b=0.16:1a=0.08:1b=0.16:2a=0.08:2b=0.16:s=15"};
      f.emplace_back(p[lvl]);
    } else if (dn.algo == "bm3d") {
      static const char* p[] = {"bm3d=sigma=3:block=8:bstep=4:group=1", "bm3d=sigma=6:block=8:bstep=4:group=1",
                                "bm3d=sigma=10:block=8:bstep=4:group=1"};
      f.emplace_back(p[lvl]);
    } else {
      throwError(ErrorCategory::InvalidConfiguration, "filters", "Unknown denoiser: " + dn.algo);
    }
  }

  if (vf.deblock == "weak") f.emplace_back("deblock=filter=weak:block=8");
  else if (vf.deblock == "strong") f.emplace_back("deblock=filter=strong:block=8");

  bool tonemap = v.hdrMode == "tonemap" && isHdrTransfer(s.hdr);
  if (tonemap) {
    // Linearise with zimg, tone-map in float RGB, then encode as BT.709 SDR.
    std::string tin = s.hdr == "hlg" ? "arib-std-b67" : "smpte2084";
    f.emplace_back("zscale=tin=" + tin + ":pin=2020:min=2020_ncl:t=linear:npl=100");
    f.emplace_back("format=gbrpf32le");
    f.emplace_back("zscale=p=709");
    f.emplace_back("tonemap=tonemap=" + v.tonemap + ":desat=0");
    f.emplace_back("zscale=t=709:m=709:r=tv");
  } else if (!v.color.primaries.empty() || !v.color.transfer.empty() || !v.color.matrix.empty()) {
    // Real conversion (not just relabelling) when the user changes colour metadata.
    std::vector<std::string> z;
    std::string p = zPrimaries(v.color.primaries), t = zTransfer(v.color.transfer), m = zMatrix(v.color.matrix);
    std::string pin = zPrimaries(s.colorPrimaries), tin = zTransfer(s.colorTransfer), min = zMatrix(s.colorSpace);
    if (!p.empty() && !pin.empty() && p != pin) { z.push_back("pin=" + pin); z.push_back("p=" + p); }
    if (!t.empty() && !tin.empty() && t != tin) { z.push_back("tin=" + tin); z.push_back("t=" + t); }
    if (!m.empty() && !min.empty() && m != min) { z.push_back("min=" + min); z.push_back("m=" + m); }
    if (!z.empty()) {
      std::string zs = "zscale=";
      for (size_t i = 0; i < z.size(); ++i) zs += (i ? ":" : "") + z[i];
      f.emplace_back(zs);
    }
  }

  int cw = ow - v.crop.left - v.crop.right;
  int ch = oh - v.crop.top - v.crop.bottom;
  cw &= ~1;
  ch &= ~1;
  if (cw != v.width || ch != v.height || !v.crop.any()) {
    std::string flags = v.scaler + "+accurate_rnd+full_chroma_int";
    f.emplace_back("scale=w=" + std::to_string(v.width) + ":h=" + std::to_string(v.height) + ":flags=" + flags);
  }
  f.emplace_back("setsar=1");

  if (vf.deband) f.emplace_back("deband=1thr=0.02:2thr=0.02:3thr=0.02:range=16:blur=1");

  const Sharpen& sh = vf.sharpen;
  if (!sh.algo.empty()) {
    int lvl = sh.strength == "strong" ? 2 : sh.strength == "medium" ? 1 : 0;
    if (sh.algo == "unsharp") {
      static const char* p[] = {"unsharp=5:5:0.4:5:5:0.0", "unsharp=5:5:0.8:5:5:0.0", "unsharp=5:5:1.3:5:5:0.0"};
      f.emplace_back(p[lvl]);
    } else if (sh.algo == "cas") {
      static const char* p[] = {"cas=strength=0.3", "cas=strength=0.55", "cas=strength=0.8"};
      f.emplace_back(p[lvl]);
    } else {
      throwError(ErrorCategory::InvalidConfiguration, "filters", "Unknown sharpening filter: " + sh.algo);
    }
  }

  if (vf.grayscale) f.emplace_back("hue=s=0");

  if (v.burn && !v.burn->bitmap) {
    std::string sub = "subtitles=filename=" + escapeFilterValue(v.burn->externalPath);
    if (!v.burn->fontsDir.empty()) sub += ":fontsdir=" + escapeFilterValue(v.burn->fontsDir);
    f.emplace_back(sub);
  }

  // Colour metadata relabelling (no conversion) for tone-mapped or overridden output.
  std::vector<std::string> sp;
  if (tonemap) {
    sp = {"color_primaries=bt709", "color_trc=bt709", "colorspace=bt709"};
  } else {
    if (!v.color.primaries.empty()) sp.push_back("color_primaries=" + v.color.primaries);
    if (!v.color.transfer.empty()) sp.push_back("color_trc=" + v.color.transfer);
    if (!v.color.matrix.empty()) sp.push_back("colorspace=" + v.color.matrix);
  }
  if (!v.color.range.empty()) sp.push_back("range=" + v.color.range);
  if (!sp.empty()) {
    std::string s2 = "setparams=";
    for (size_t i = 0; i < sp.size(); ++i) s2 += (i ? ":" : "") + sp[i];
    f.emplace_back(s2);
  }

  f.emplace_back("format=pix_fmts=" + encoderPixFmt);
  return join(f);
}

std::string buildAudioFilter(int outSampleRate, const std::string& outChannelLayout, const std::string& outSampleFmt,
                             double volumeDb) {
  std::vector<std::string> f;
  if (std::fabs(volumeDb) > 0.01) {
    std::ostringstream os;
    os << "volume=volume=" << volumeDb << "dB";
    f.emplace_back(os.str());
  }
  // async=1 stretches/squeezes by at most a few samples to keep audio locked to
  // its timestamps (A/V sync is timestamp-driven, never sample-count-driven).
  f.emplace_back("aresample=" + std::to_string(outSampleRate) + ":async=1:first_pts=0");
  f.emplace_back("aformat=sample_fmts=" + outSampleFmt + ":sample_rates=" + std::to_string(outSampleRate) +
                 ":channel_layouts=" + outChannelLayout);
  return join(f);
}

}  // namespace vc
