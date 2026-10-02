#include "core/Util.h"

#include <algorithm>
#include <cctype>
#include <chrono>

namespace vc {

std::string truncateUtf8(const std::string& s, size_t maxBytes) {
  if (s.size() <= maxBytes) return s;
  size_t cut = maxBytes;
  // Do not split a multi-byte UTF-8 sequence.
  while (cut > 0 && (static_cast<unsigned char>(s[cut]) & 0xC0) == 0x80) --cut;
  return s.substr(0, cut);
}

Json dictToJson(const AVDictionary* d, size_t maxValueLen, int maxEntries) {
  Json out = Json::object();
  const AVDictionaryEntry* e = nullptr;
  int n = 0;
  while ((e = av_dict_iterate(d, e)) && n < maxEntries) {
    std::string key = truncateUtf8(e->key ? e->key : "", 128);
    std::string value = truncateUtf8(e->value ? e->value : "", maxValueLen);
    // Reject keys/values that are not valid UTF-8 rather than letting the JSON
    // encoder throw on malicious metadata.
    try {
      Json probe = value;
      (void)probe.dump();
      Json probeKey = key;
      (void)probeKey.dump();
    } catch (...) {
      continue;
    }
    out[key] = value;
    ++n;
  }
  return out;
}

int64_t monotonicUs() {
  return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

std::string toLower(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return s;
}

std::vector<std::string> split(const std::string& s, char sep) {
  std::vector<std::string> out;
  std::string cur;
  for (char c : s) {
    if (c == sep) {
      out.push_back(cur);
      cur.clear();
    } else {
      cur.push_back(c);
    }
  }
  out.push_back(cur);
  return out;
}

std::string trim(const std::string& s) {
  size_t b = 0, e = s.size();
  while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
  while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
  return s.substr(b, e - b);
}

}  // namespace vc
