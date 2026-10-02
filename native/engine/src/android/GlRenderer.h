#pragma once

// GPU stage of the hardware pipeline. Decoded frames arrive as AHardwareBuffers
// (from an AImageReader the decoder renders into), are bound zero-copy as
// external OES textures through EGLImages, and are drawn into the hardware
// encoder's input Surface. Geometry (rotation, flips, crop, scale) is one
// affine texture-coordinate transform; optional grayscale and 2x2
// supersampling for strong downscales happen in the fragment shader.

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>

#include <map>

namespace vc {

struct TexTransform {
  // Maps output (u,v) in [0,1]^2 (top-left origin) to source texture coords:
  //   s = a*u + b*v + c ; t = d*u + e*v + f
  float a = 1, b = 0, c = 0, d = 0, e = 1, f = 0;
};

struct GeometryParams {
  int srcWidth = 0, srcHeight = 0;  // visible decoded size (coded orientation)
  int rotation = 0;                 // total clockwise rotation (auto + user)
  bool hflip = false, vflip = false;  // applied after rotation
  int cropLeft = 0, cropTop = 0, cropRight = 0, cropBottom = 0;  // in rotated orientation
};

// Pure function (unit-tested on the host via the same formula in Kotlin docs).
TexTransform computeTransform(const GeometryParams& g, int bufCropL, int bufCropT, int bufCropW, int bufCropH, int bufW,
                              int bufH);

class GlRenderer {
 public:
  GlRenderer() = default;
  GlRenderer(const GlRenderer&) = delete;
  GlRenderer& operator=(const GlRenderer&) = delete;
  ~GlRenderer();

  void init(ANativeWindow* encoderWindow, int outWidth, int outHeight);
  // Draws `hb` into the encoder surface and submits it with `ptsNs`.
  void drawAndPresent(AHardwareBuffer* hb, const TexTransform& t, bool grayscale, bool supersample, int64_t ptsNs);
  void release();

 private:
  EGLImageKHR imageFor(AHardwareBuffer* hb);

  EGLDisplay display_ = EGL_NO_DISPLAY;
  EGLContext context_ = EGL_NO_CONTEXT;
  EGLSurface surface_ = EGL_NO_SURFACE;
  GLuint program_ = 0, texture_ = 0, vbo_ = 0;
  GLint locPos_ = -1, locM_ = -1, locDx_ = -1, locDy_ = -1, locSS_ = -1, locGray_ = -1, locTex_ = -1;
  int outW_ = 0, outH_ = 0;
  std::map<AHardwareBuffer*, EGLImageKHR> images_;
  // Extension entry points.
  PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getClientBuffer_ = nullptr;
  PFNEGLCREATEIMAGEKHRPROC createImage_ = nullptr;
  PFNEGLDESTROYIMAGEKHRPROC destroyImage_ = nullptr;
  PFNGLEGLIMAGETARGETTEXTURE2DOESPROC targetTexture_ = nullptr;
  PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime_ = nullptr;
};

}  // namespace vc
