// Host-side command-line driver for the native engine. Used by the native
// test-suite and for manual verification on a workstation; it is not shipped
// in the APK. It calls exactly the same engine entry points as the JNI bridge.
#include <fcntl.h>
#include <unistd.h>

#include <condition_variable>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <mutex>
#include <sstream>

#include "analysis/BuildInfo.h"
#include "analysis/Complexity.h"
#include "analysis/QualityMetrics.h"
#include "analysis/Validator.h"
#include "core/Errors.h"
#include "core/Log.h"
#include "job/JobManager.h"
#include "probe/Probe.h"

using namespace vc;

namespace {
int openRead(const char* p) {
  int fd = ::open(p, O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    std::perror(p);
    std::exit(2);
  }
  return fd;
}
int openWrite(const char* p) {
  int fd = ::open(p, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (fd < 0) {
    std::perror(p);
    std::exit(2);
  }
  return fd;
}
std::string readFile(const char* p) {
  std::ifstream f(p);
  std::stringstream ss;
  ss << f.rdbuf();
  return ss.str();
}
void usage() {
  std::fprintf(stderr,
               "usage:\n"
               "  vcengine_cli info\n"
               "  vcengine_cli probe <file>\n"
               "  vcengine_cli transcode <plan.json> <in> <out>\n"
               "  vcengine_cli validate <file> <expect.json>\n"
               "  vcengine_cli metrics <source> <sample> <plan.json>\n"
               "  vcengine_cli complexity <file>\n");
}
}  // namespace

int main(int argc, char** argv) {
  Log::setLevel(std::getenv("VC_DEBUG") ? LogLevel::Debug : LogLevel::Warn);
  Log::installFfmpegBridge();
  if (argc < 2) {
    usage();
    return 2;
  }
  std::string cmd = argv[1];
  try {
    if (cmd == "info") {
      std::cout << buildInfo().dump(2) << "\n";
    } else if (cmd == "probe" && argc == 3) {
      int fd = openRead(argv[2]);
      std::cout << dumpJson(probeSource(fd, argv[2], nullptr)) << "\n";
      ::close(fd);
    } else if (cmd == "transcode" && argc == 5) {
      int in = openRead(argv[3]);
      int out = openWrite(argv[4]);
      std::mutex mu;
      std::condition_variable cv;
      bool done = false;
      int exitCode = 0;
      JobManager mgr;
      auto sink = [&](const char* type, const Json& p) {
        std::string t = type;
        if (t == "PROGRESS") {
          if (std::getenv("VC_PROGRESS")) std::cerr << "PROGRESS " << p.dump() << "\n";
          return;
        }
        std::cout << t << " " << dumpJson(p) << std::endl;
        if (t == "FAILED" || t == "CANCELLED" || t == "VALIDATION_COMPLETE" ||
            (t == "ENCODE_COMPLETE" && !std::getenv("VC_EXPECT_VALIDATION"))) {
          std::lock_guard<std::mutex> l(mu);
          if (t == "FAILED") exitCode = 1;
          if (t == "CANCELLED") exitCode = 3;
          if (t == "VALIDATION_COMPLETE" && !p.value("ok", false)) exitCode = 1;
          if (t != "VALIDATION_COMPLETE" || true) done = true;
          cv.notify_all();
        }
      };
      int64_t id = mgr.create(readFile(argv[2]), in, out, sink);
      auto job = mgr.get(id);
      job->start();
      if (const char* c = std::getenv("VC_CANCEL_AFTER_MS")) {
        std::unique_lock<std::mutex> l(mu);
        cv.wait_for(l, std::chrono::milliseconds(std::atoi(c)), [&] { return done; });
        if (!done) job->cancel();
      }
      if (const char* p = std::getenv("VC_PAUSE_AFTER_MS")) {
        std::this_thread::sleep_for(std::chrono::milliseconds(std::atoi(p)));
        bool paused = job->pause();
        std::cerr << "PAUSE " << paused << "\n";
        std::this_thread::sleep_for(std::chrono::milliseconds(1500));
        std::cerr << "RESUME " << job->resume() << "\n";
      }
      job->join();
      mgr.destroy(id);
      ::close(in);
      ::close(out);
      std::cout << "STATE " << jobStateName(job->state()) << std::endl;
      return exitCode;
    } else if (cmd == "validate" && argc == 4) {
      int fd = openRead(argv[2]);
      Json r = validateOutput(fd, Json::parse(readFile(argv[3])), nullptr);
      std::cout << dumpJson(r) << "\n";
      return r.value("ok", false) ? 0 : 1;
    } else if (cmd == "metrics" && argc == 5) {
      int a = openRead(argv[2]);
      int b = openRead(argv[3]);
      Plan plan = parsePlan(Json::parse(readFile(argv[4])));
      std::cout << dumpJson(compareQuality(a, b, plan, nullptr)) << "\n";
    } else if (cmd == "complexity" && argc == 3) {
      int fd = openRead(argv[2]);
      std::cout << dumpJson(analyzeComplexity(fd, 3, 4.0, nullptr)) << "\n";
    } else {
      usage();
      return 2;
    }
  } catch (const EngineException& e) {
    std::cout << "ERROR " << dumpJson(e.error().toJson()) << "\n";
    return 1;
  }
  return 0;
}
