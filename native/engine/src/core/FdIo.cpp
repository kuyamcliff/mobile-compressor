#include "core/FdIo.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <mutex>
#include <set>

#include "core/Errors.h"
#include "core/Log.h"

extern "C" {
#include <libavutil/mem.h>
}

namespace vc {

namespace {
constexpr int kIoBufferSize = 256 * 1024;
constexpr const char* TAG = "FdIo";

struct FdState {
  int fd = -1;
  int64_t pos = 0;
  int64_t size = -1;  // -1 when unknown (pipes)
  int64_t high = 0;   // highest byte offset written
  bool seekable = true;
  int firstError = 0;
};

int fdRead(void* opaque, uint8_t* buf, int len) {
  auto* s = static_cast<FdState*>(opaque);
  for (;;) {
    ssize_t n = s->seekable ? ::pread(s->fd, buf, len, s->pos) : ::read(s->fd, buf, len);
    if (n < 0 && errno == EINTR) continue;
    if (n < 0 && errno == ESPIPE && s->seekable) {
      s->seekable = false;
      continue;
    }
    if (n < 0) return AVERROR(errno);
    if (n == 0) return AVERROR_EOF;
    s->pos += n;
    return static_cast<int>(n);
  }
}

int fdWrite(void* opaque, const uint8_t* buf, int len) {
  auto* s = static_cast<FdState*>(opaque);
  int remaining = len;
  while (remaining > 0) {
    ssize_t n = ::pwrite(s->fd, buf, remaining, s->pos);
    if (n < 0 && errno == EINTR) continue;
    if (n < 0) {
      int err = AVERROR(errno);
      if (!s->firstError) s->firstError = err;
      return err;
    }
    if (n == 0) {
      if (!s->firstError) s->firstError = AVERROR(ENOSPC);
      return AVERROR(ENOSPC);
    }
    buf += n;
    remaining -= static_cast<int>(n);
    s->pos += n;
    if (s->pos > s->high) s->high = s->pos;
  }
  return len;
}

int64_t fdSeek(void* opaque, int64_t offset, int whence) {
  auto* s = static_cast<FdState*>(opaque);
  if (whence == AVSEEK_SIZE) {
    struct stat st{};
    if (::fstat(s->fd, &st) == 0 && S_ISREG(st.st_mode)) return st.st_size;
    return s->size >= 0 ? s->size : AVERROR(ENOSYS);
  }
  if (!s->seekable) return AVERROR(ESPIPE);
  int64_t target;
  switch (whence & ~AVSEEK_FORCE) {
    case SEEK_SET: target = offset; break;
    case SEEK_CUR: target = s->pos + offset; break;
    case SEEK_END: {
      struct stat st{};
      if (::fstat(s->fd, &st) != 0) return AVERROR(errno);
      target = st.st_size + offset;
      break;
    }
    default: return AVERROR(EINVAL);
  }
  if (target < 0) return AVERROR(EINVAL);
  s->pos = target;
  return target;
}

AVIOContext* makeAvio(FdState* st, bool write) {
  auto* buffer = static_cast<uint8_t*>(av_malloc(kIoBufferSize));
  if (!buffer) throw std::bad_alloc();
  AVIOContext* io = avio_alloc_context(buffer, kIoBufferSize, write ? 1 : 0, st, write ? nullptr : fdRead,
                                       write ? fdWrite : nullptr, fdSeek);
  if (!io) {
    av_free(buffer);
    throw std::bad_alloc();
  }
  io->seekable = st->seekable ? AVIO_SEEKABLE_NORMAL : 0;
  return io;
}

void freeAvio(AVIOContext*& io) {
  if (!io) return;
  av_freep(&io->buffer);
  avio_context_free(&io);
}
}  // namespace

// ---------------------------------------------------------------------------
UniqueFd& UniqueFd::operator=(UniqueFd&& o) noexcept {
  if (this != &o) {
    if (fd_ >= 0) ::close(fd_);
    fd_ = o.release();
  }
  return *this;
}
UniqueFd::~UniqueFd() {
  if (fd_ >= 0) ::close(fd_);
}
UniqueFd UniqueFd::dupOf(int fd) {
  int d = ::fcntl(fd, F_DUPFD_CLOEXEC, 0);
  if (d < 0) {
    throwError(errno == EBADF ? ErrorCategory::ContentUriExpired : ErrorCategory::PermissionDenied, "open",
               "The file handle is no longer valid.", std::strerror(errno),
               {"Select the file again"});
  }
  return UniqueFd(d);
}
UniqueFd UniqueFd::openPath(const std::string& path, bool write) {
  int fd = write ? ::open(path.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0644)
                 : ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    int e = errno;
    throwError(e == ENOENT ? ErrorCategory::SourceDisappeared
                           : (e == EACCES ? ErrorCategory::PermissionDenied : ErrorCategory::OutputPathFailure),
               "open", "Unable to open file.", std::strerror(e));
  }
  return UniqueFd(fd);
}

int InterruptFlag::callback(void* opaque) {
  auto* f = static_cast<InterruptFlag*>(opaque);
  return f && f->cancel.load(std::memory_order_relaxed) ? 1 : 0;
}

// ---------------------------------------------------------------------------
struct InputFile::IoState {
  UniqueFd fd;
  FdState st;
};

InputFile::InputFile() = default;

InputFile::~InputFile() {
  if (fmt_) avformat_close_input(&fmt_);
  freeAvio(avio_);
}

void InputFile::open(int fd, const std::string& label, InterruptFlag* interrupt, bool findStreamInfo) {
  label_ = label;
  io_ = std::make_unique<IoState>();
  io_->fd = UniqueFd::dupOf(fd);
  io_->st.fd = io_->fd.get();
  struct stat st{};
  if (::fstat(io_->st.fd, &st) == 0) {
    if (S_ISREG(st.st_mode)) {
      io_->st.size = st.st_size;
      size_ = st.st_size;
    } else {
      io_->st.seekable = ::lseek(io_->st.fd, 0, SEEK_CUR) >= 0;
    }
  }
  if (size_ == 0) {
    throwError(ErrorCategory::InvalidInput, "open_input", "Unable to read video: the file is empty.");
  }
  avio_ = makeAvio(&io_->st, false);

  fmt_ = avformat_alloc_context();
  if (!fmt_) throw std::bad_alloc();
  fmt_->pb = avio_;
  fmt_->flags |= AVFMT_FLAG_CUSTOM_IO;
  if (interrupt) fmt_->interrupt_callback = {&InterruptFlag::callback, interrupt};
  // Bound how much untrusted metadata/probing data the demuxer may accumulate.
  fmt_->probesize = 20 * 1024 * 1024;
  fmt_->max_analyze_duration = 15 * AV_TIME_BASE;
  fmt_->format_probesize = 2 * 1024 * 1024;
  fmt_->max_streams = 256;
  fmt_->max_index_size = 64 * 1024 * 1024;
  fmt_->max_chunk_size = 0;

  Dict opts;
  opts.set("protocol_whitelist", "file,pipe");  // a demuxer must never open network URLs from metadata
  opts.set("safe", "1");
  int ret = avformat_open_input(&fmt_, nullptr, nullptr, opts.addr());
  if (ret < 0) {
    fmt_ = nullptr;  // freed by avformat_open_input on failure
    EngineError e = fromAvError(ret, "open_input", "reading the container");
    if (ret == AVERROR_INVALIDDATA || ret == AVERROR_DEMUXER_NOT_FOUND) {
      e.category = ErrorCategory::InvalidInput;
      e.message = "Unable to read video. The file is not a recognized media file or is corrupted.";
    }
    throw EngineException(e);
  }
  if (findStreamInfo) {
    ret = avformat_find_stream_info(fmt_, nullptr);
    if (ret < 0) {
      EngineError e = fromAvError(ret, "probe", "analysing streams");
      e.category = ErrorCategory::InvalidInput;
      e.message = "Unable to read video. Stream information could not be determined.";
      throw EngineException(e);
    }
  }
  VC_LOGD(TAG, "opened input label=%s format=%s streams=%u size=%lld", label_.c_str(),
          fmt_->iformat ? fmt_->iformat->name : "?", fmt_->nb_streams, static_cast<long long>(size_));
}

// ---------------------------------------------------------------------------
struct OutputFile::IoState {
  FdState st;
  std::mutex mu;
  std::set<AVIOContext*> readers;  // contexts handed out by io_open, owned here
  std::set<FdState*> readerStates;
};

// Only the "re-open the same output for reading" pattern used by MP4 fast start is
// supported. Any other open (in particular for writing) is refused: muxers must
// never touch the filesystem outside the provided descriptor.
struct OutputIoBridge {
  static OutputFile::IoState* state(AVFormatContext* s) { return static_cast<OutputFile::IoState*>(s->opaque); }
};

static int outputIoOpenImpl(AVFormatContext* s, AVIOContext** pb, const char* /*url*/, int flags,
                            AVDictionary** /*opts*/) {
  if (flags & AVIO_FLAG_WRITE) return AVERROR(EPERM);
  auto* io = OutputIoBridge::state(s);
  if (!io) return AVERROR(EINVAL);
  auto* rs = new FdState();
  rs->fd = io->st.fd;
  rs->seekable = true;
  AVIOContext* r = nullptr;
  try {
    r = makeAvio(rs, false);
  } catch (...) {
    delete rs;
    return AVERROR(ENOMEM);
  }
  std::lock_guard<std::mutex> lock(io->mu);
  io->readers.insert(r);
  io->readerStates.insert(rs);
  *pb = r;
  return 0;
}

static int outputIoClose(AVFormatContext* s, AVIOContext* pb) {
  auto* io = OutputIoBridge::state(s);
  if (!io || !pb) return 0;
  std::lock_guard<std::mutex> lock(io->mu);
  if (io->readers.erase(pb)) {
    auto* rs = static_cast<FdState*>(pb->opaque);
    io->readerStates.erase(rs);
    delete rs;
    AVIOContext* tmp = pb;
    freeAvio(tmp);
  }
  return 0;
}

OutputFile::OutputFile() = default;

OutputFile::~OutputFile() {
  close();
  if (fmt_) avformat_free_context(fmt_);
  if (io_) {
    for (auto* r : io_->readers) {
      AVIOContext* tmp = r;
      freeAvio(tmp);
    }
    for (auto* rs : io_->readerStates) delete rs;
  }
}

void OutputFile::open(int fd, const std::string& formatName, InterruptFlag* interrupt) {
  fd_ = UniqueFd::dupOf(fd);
  io_ = std::make_unique<IoState>();
  io_->st.fd = fd_.get();
  io_->st.seekable = ::lseek(fd_.get(), 0, SEEK_CUR) >= 0;
  // Start from an empty file: the descriptor may point at a reused temp file.
  if (io_->st.seekable && ::ftruncate(fd_.get(), 0) != 0 && errno != EINVAL) {
    VC_LOGW(TAG, "ftruncate failed: %s", std::strerror(errno));
  }
  avio_ = makeAvio(&io_->st, true);

  int ret = avformat_alloc_output_context2(&fmt_, nullptr, formatName.c_str(), nullptr);
  if (ret < 0 || !fmt_) {
    EngineError e = fromAvError(ret < 0 ? ret : AVERROR_MUXER_NOT_FOUND, "output_open", "creating the " + formatName + " container");
    throw EngineException(e);
  }
  fmt_->pb = avio_;
  fmt_->flags |= AVFMT_FLAG_CUSTOM_IO;
  fmt_->opaque = io_.get();
  fmt_->io_open = outputIoOpenImpl;
  fmt_->io_close2 = outputIoClose;
  if (interrupt) fmt_->interrupt_callback = {&InterruptFlag::callback, interrupt};
  // `url` is only used as a token for the read-back re-open.
  fmt_->url = av_strdup("fd:output");
}

int64_t OutputFile::bytesWritten() const {
  if (!io_) return 0;
  int64_t buffered = avio_ ? static_cast<int64_t>(avio_->buf_ptr - avio_->buffer) : 0;
  return std::max<int64_t>(io_->st.high, io_->st.pos + (avio_ && avio_->write_flag ? buffered : 0));
}

int OutputFile::flush() {
  if (avio_ && !closed_) avio_flush(avio_);
  if (io_ && io_->st.firstError) return io_->st.firstError;
  if (avio_ && avio_->error) return avio_->error;
  return 0;
}

int OutputFile::close() {
  if (closed_ || !avio_) return io_ ? io_->st.firstError : 0;
  int ret = flush();
  closed_ = true;
  if (fd_.valid() && ::fsync(fd_.get()) != 0 && errno != EINVAL && errno != EROFS && ret == 0) {
    ret = AVERROR(errno);
  }
  if (fmt_) fmt_->pb = nullptr;
  freeAvio(avio_);
  return ret;
}

}  // namespace vc
