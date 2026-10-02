#include "pipeline/Progress.h"

#include <algorithm>
#include <cmath>

namespace vc {

ProgressTracker::ProgressTracker(Clock clock) : clock_(std::move(clock)) {}

void ProgressTracker::begin(int64_t totalUs, double sourceFps, int passes) {
  std::lock_guard<std::mutex> l(mu_);
  totalUs_ = std::max<int64_t>(0, totalUs);
  sourceFps_ = sourceFps;
  passes_ = std::max(1, passes);
  pass_ = 0;
  startUs_ = clock_();
  pausedAccumUs_ = 0;
  pauseStartUs_ = -1;
  mediaUs_ = frames_ = outputBytes_ = inputBytes_ = 0;
  lastSampleWall_ = -1;
  emaSpeed_ = 0;
  haveEma_ = false;
  passStartWall_ = 0;
}

void ProgressTracker::setPass(int passIndex) {
  std::lock_guard<std::mutex> l(mu_);
  pass_ = std::clamp(passIndex, 0, passes_ - 1);
  mediaUs_ = 0;
  frames_ = 0;
  lastSampleWall_ = -1;
  passStartWall_ = activeNowUs();
  // Keep the EMA: speed of pass 1 is a reasonable prior for pass 2.
}

int64_t ProgressTracker::activeNowUs() const {
  int64_t now = clock_();
  int64_t paused = pausedAccumUs_ + (pauseStartUs_ >= 0 ? now - pauseStartUs_ : 0);
  return now - startUs_ - paused;
}

void ProgressTracker::onEncodedVideoFrame(int64_t mediaUs) {
  std::lock_guard<std::mutex> l(mu_);
  ++frames_;
  if (mediaUs > mediaUs_) mediaUs_ = mediaUs;
}

void ProgressTracker::onMediaTime(int64_t mediaUs) {
  std::lock_guard<std::mutex> l(mu_);
  if (mediaUs > mediaUs_) mediaUs_ = mediaUs;
}

void ProgressTracker::setOutputBytes(int64_t bytes) {
  std::lock_guard<std::mutex> l(mu_);
  outputBytes_ = bytes;
}

void ProgressTracker::addInputBytes(int64_t bytes) {
  std::lock_guard<std::mutex> l(mu_);
  inputBytes_ += bytes;
}

void ProgressTracker::setPaused(bool paused) {
  std::lock_guard<std::mutex> l(mu_);
  int64_t now = clock_();
  if (paused && pauseStartUs_ < 0) {
    pauseStartUs_ = now;
  } else if (!paused && pauseStartUs_ >= 0) {
    pausedAccumUs_ += now - pauseStartUs_;
    pauseStartUs_ = -1;
    lastSampleWall_ = -1;  // do not count the pause as a slow interval
  }
}

ProgressTracker::Snapshot ProgressTracker::sample() {
  std::lock_guard<std::mutex> l(mu_);
  Snapshot s;
  int64_t wall = activeNowUs();
  s.elapsedUs = wall;
  s.mediaUs = std::min(mediaUs_, totalUs_ > 0 ? totalUs_ : mediaUs_);
  s.frames = frames_;
  s.outputBytes = outputBytes_;
  s.pass = pass_;
  s.passes = passes_;

  if (lastSampleWall_ >= 0 && wall > lastSampleWall_) {
    double dWall = (wall - lastSampleWall_) / 1e6;
    double dMedia = (mediaUs_ - lastSampleMedia_) / 1e6;
    double inst = std::max(0.0, dMedia / dWall);
    instFps_ = (frames_ - lastSampleFrames_) / dWall;
    if (!haveEma_) {
      emaSpeed_ = inst;
      haveEma_ = inst > 0;
    } else {
      emaSpeed_ = kAlpha * inst + (1 - kAlpha) * emaSpeed_;
    }
  }
  if (lastSampleWall_ < 0 || wall > lastSampleWall_) {
    lastSampleWall_ = wall;
    lastSampleMedia_ = mediaUs_;
    lastSampleFrames_ = frames_;
  }
  s.currentFps = instFps_;
  int64_t passWall = wall - passStartWall_;
  s.averageFps = passWall > 0 ? frames_ / (passWall / 1e6) : 0;
  s.speed = emaSpeed_;

  if (totalUs_ > 0) {
    double within = std::clamp(static_cast<double>(mediaUs_) / totalUs_, 0.0, 1.0);
    s.progress = std::min(0.999, (pass_ + within) / passes_);
    if (emaSpeed_ > 1e-6) {
      double remainingMedia = (totalUs_ - std::min(mediaUs_, totalUs_)) / 1e6 + (passes_ - 1 - pass_) * (totalUs_ / 1e6);
      s.etaUs = static_cast<int64_t>(remainingMedia / emaSpeed_ * 1e6);
    }
    if (mediaUs_ > kUsPerSec && pass_ == passes_ - 1) {
      s.projectedBytes = static_cast<int64_t>(static_cast<double>(outputBytes_) * totalUs_ / mediaUs_);
    }
  }
  if (mediaUs_ > 0) {
    s.outputBitrate = static_cast<int64_t>(outputBytes_ * 8.0 * 1e6 / mediaUs_);
    s.inputBitrate = static_cast<int64_t>(inputBytes_ * 8.0 * 1e6 / mediaUs_);
  }
  return s;
}

Json ProgressTracker::toJson(const Snapshot& s) const {
  return Json{{"progress", s.progress},
              {"currentFps", std::round(s.currentFps * 10) / 10},
              {"averageFps", std::round(s.averageFps * 10) / 10},
              {"speed", std::round(s.speed * 1000) / 1000},
              {"etaUs", s.etaUs},
              {"elapsedUs", s.elapsedUs},
              {"mediaUs", s.mediaUs},
              {"frames", s.frames},
              {"outputBytes", s.outputBytes},
              {"projectedBytes", s.projectedBytes},
              {"outputBitrate", s.outputBitrate},
              {"inputBitrate", s.inputBitrate},
              {"pass", s.pass},
              {"passes", s.passes}};
}

}  // namespace vc
