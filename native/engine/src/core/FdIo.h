#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

#include "core/FfRaii.h"

namespace vc {

// Owns a POSIX file descriptor. Android content URIs are handed to the engine as
// file descriptors (ParcelFileDescriptor.detachFd/dup); the engine never assumes a
// filesystem path exists for them.
class UniqueFd {
 public:
  UniqueFd() = default;
  explicit UniqueFd(int fd) : fd_(fd) {}
  UniqueFd(const UniqueFd&) = delete;
  UniqueFd& operator=(const UniqueFd&) = delete;
  UniqueFd(UniqueFd&& o) noexcept : fd_(o.release()) {}
  UniqueFd& operator=(UniqueFd&& o) noexcept;
  ~UniqueFd();
  int get() const { return fd_; }
  int release() { int f = fd_; fd_ = -1; return f; }
  bool valid() const { return fd_ >= 0; }
  static UniqueFd dupOf(int fd);
  static UniqueFd openPath(const std::string& path, bool write);

 private:
  int fd_ = -1;
};

// Interrupt hook shared by every AVFormatContext a job opens: FFmpeg calls it
// during blocking I/O so cancellation is honoured promptly.
struct InterruptFlag {
  std::atomic<bool> cancel{false};
  static int callback(void* opaque);
};

// A demuxer reading from a file descriptor through a custom AVIOContext.
// Reads use pread() on a private offset, so several readers may share one fd.
class InputFile {
 public:
  InputFile();
  InputFile(const InputFile&) = delete;
  InputFile& operator=(const InputFile&) = delete;
  ~InputFile();

  // Takes a dup of `fd`; the caller keeps ownership of the original.
  void open(int fd, const std::string& label, InterruptFlag* interrupt, bool findStreamInfo = true);
  AVFormatContext* ctx() const { return fmt_; }
  int64_t fileSize() const { return size_; }
  const std::string& label() const { return label_; }

 private:
  struct IoState;
  std::unique_ptr<IoState> io_;
  AVIOContext* avio_ = nullptr;
  AVFormatContext* fmt_ = nullptr;
  int64_t size_ = -1;
  std::string label_;
};

// A muxer writing to a file descriptor. Supports seeking (needed for MP4 moov
// patching) and re-opening for read (needed for MP4 fast-start relocation).
class OutputFile {
 public:
  OutputFile();
  OutputFile(const OutputFile&) = delete;
  OutputFile& operator=(const OutputFile&) = delete;
  ~OutputFile();

  void open(int fd, const std::string& formatName, InterruptFlag* interrupt);
  AVFormatContext* ctx() const { return fmt_; }
  int64_t bytesWritten() const;
  // Flushes buffered data; returns the first write error seen, if any.
  int flush();
  // Closes the AVIO layer and syncs the fd to storage. Safe to call twice.
  int close();
  int fd() const { return fd_.get(); }

 public:
  struct IoState;  // opaque; public so the C callbacks can name it

 private:
  std::unique_ptr<IoState> io_;
  AVIOContext* avio_ = nullptr;
  AVFormatContext* fmt_ = nullptr;
  UniqueFd fd_;
  bool closed_ = false;
};

}  // namespace vc
