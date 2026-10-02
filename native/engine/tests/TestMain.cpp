#include <chrono>
#include <cstdio>
#include <cstring>
#include <exception>

#include "TestFramework.h"
#include "core/Errors.h"
#include "core/Log.h"

namespace vctest {
std::vector<TestCase>& registry() {
  static std::vector<TestCase> r;
  return r;
}
}  // namespace vctest

int main(int argc, char** argv) {
  vc::Log::setLevel(std::getenv("VC_DEBUG") ? vc::LogLevel::Debug : vc::LogLevel::Error);
  vc::Log::installFfmpegBridge();
  const char* filter = argc > 1 ? argv[1] : nullptr;
  int passed = 0, failed = 0;
  std::vector<std::string> failures;
  for (auto& t : vctest::registry()) {
    std::string full = std::string(t.suite) + "." + t.name;
    if (filter && full.find(filter) == std::string::npos) continue;
    auto start = std::chrono::steady_clock::now();
    std::printf("[ RUN  ] %s\n", full.c_str());
    std::fflush(stdout);
    try {
      t.fn();
      auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
      std::printf("[  OK  ] %s (%lld ms)\n", full.c_str(), static_cast<long long>(ms));
      ++passed;
    } catch (const vctest::Failure& f) {
      std::printf("[ FAIL ] %s\n         %s\n", full.c_str(), f.message.c_str());
      failures.push_back(full);
      ++failed;
    } catch (const vc::EngineException& e) {
      std::printf("[ FAIL ] %s\n         engine error: %s | %s\n", full.c_str(), e.error().message.c_str(),
                  e.error().detail.c_str());
      failures.push_back(full);
      ++failed;
    } catch (const std::exception& e) {
      std::printf("[ FAIL ] %s\n         exception: %s\n", full.c_str(), e.what());
      failures.push_back(full);
      ++failed;
    }
    std::fflush(stdout);
  }
  std::printf("\n%d passed, %d failed\n", passed, failed);
  for (auto& f : failures) std::printf("  FAILED: %s\n", f.c_str());
  return failed == 0 ? 0 : 1;
}
