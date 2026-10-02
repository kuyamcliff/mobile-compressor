#include "android/GlRenderer.h"

#include <string>

#include "core/Errors.h"
#include "core/Log.h"

#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif

namespace vc {

namespace {
constexpr const char* TAG = "GlRenderer";

const char* kVertex = R"(
attribute vec2 aPos;
varying vec2 vUv;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  // Top-left origin output coordinates: GL's y axis points up.
  vUv = vec2((aPos.x + 1.0) * 0.5, 1.0 - (aPos.y + 1.0) * 0.5);
})";

const char* kFragment = R"(#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES sTex;
uniform mat3 uM;
uniform vec2 uDx;
uniform vec2 uDy;
uniform int uSS;
uniform int uGray;
varying vec2 vUv;
void main() {
  vec2 st = (uM * vec3(vUv, 1.0)).xy;
  vec4 c;
  if (uSS == 1) {
    c = 0.25 * (texture2D(sTex, st - uDx - uDy) + texture2D(sTex, st + uDx - uDy) +
                texture2D(sTex, st - uDx + uDy) + texture2D(sTex, st + uDx + uDy));
  } else {
    c = texture2D(sTex, st);
  }
  if (uGray == 1) {
    float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    c = vec4(l, l, l, 1.0);
  }
  gl_FragColor = vec4(c.rgb, 1.0);
})";

GLuint compile(GLenum type, const char* src) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &src, nullptr);
  glCompileShader(s);
  GLint ok = 0;
  glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[512] = {0};
    glGetShaderInfoLog(s, sizeof(log), nullptr, log);
    glDeleteShader(s);
    throwError(ErrorCategory::HardwareCodecFailure, "gl", "GPU shader compilation failed.", log);
  }
  return s;
}

void eglFail(const char* what) {
  throwError(ErrorCategory::HardwareCodecFailure, "egl", std::string("GPU setup failed: ") + what,
             "EGL error 0x" + std::to_string(eglGetError()), {"Use software encoding"});
}

struct P2 {
  float x, y;
};
}  // namespace

TexTransform computeTransform(const GeometryParams& g, int bufCropL, int bufCropT, int bufCropW, int bufCropH, int bufW,
                              int bufH) {
  int rot = ((g.rotation % 360) + 360) % 360;
  int ow = (rot == 90 || rot == 270) ? g.srcHeight : g.srcWidth;
  int oh = (rot == 90 || rot == 270) ? g.srcWidth : g.srcHeight;
  float cw = static_cast<float>(ow - g.cropLeft - g.cropRight);
  float ch = static_cast<float>(oh - g.cropTop - g.cropBottom);
  auto map = [&](float u, float v) -> P2 {
    // output -> cropped, flipped, rotated frame
    float rx = (g.cropLeft + u * cw) / ow;
    float ry = (g.cropTop + v * ch) / oh;
    if (g.hflip) rx = 1 - rx;
    if (g.vflip) ry = 1 - ry;
    // undo the clockwise rotation: rotated(x,y) <- source(sx,sy)
    float sx, sy;
    switch (rot) {
      case 90: sx = ry; sy = 1 - rx; break;
      case 180: sx = 1 - rx; sy = 1 - ry; break;
      case 270: sx = 1 - ry; sy = rx; break;
      default: sx = rx; sy = ry; break;
    }
    // visible decoded area inside the (possibly padded) buffer
    return P2{(bufCropL + sx * bufCropW) / static_cast<float>(bufW), (bufCropT + sy * bufCropH) / static_cast<float>(bufH)};
  };
  P2 o = map(0, 0), pu = map(1, 0), pv = map(0, 1);
  TexTransform t;
  t.a = pu.x - o.x;
  t.b = pv.x - o.x;
  t.c = o.x;
  t.d = pu.y - o.y;
  t.e = pv.y - o.y;
  t.f = o.y;
  return t;
}

GlRenderer::~GlRenderer() { release(); }

void GlRenderer::init(ANativeWindow* window, int outWidth, int outHeight) {
  outW_ = outWidth;
  outH_ = outHeight;
  display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) eglFail("no display");
  const EGLint attribs[] = {EGL_RED_SIZE,   8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL_NONE};
  EGLConfig config;
  EGLint n = 0;
  if (!eglChooseConfig(display_, attribs, &config, 1, &n) || n < 1) eglFail("no recordable RGBA8888 config");
  const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
  context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, ctxAttribs);
  if (context_ == EGL_NO_CONTEXT) eglFail("context creation");
  surface_ = eglCreateWindowSurface(display_, config, window, nullptr);
  if (surface_ == EGL_NO_SURFACE) eglFail("encoder surface");
  if (!eglMakeCurrent(display_, surface_, surface_, context_)) eglFail("make current");

  getClientBuffer_ = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
  createImage_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
  destroyImage_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
  targetTexture_ = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
  presentationTime_ = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(eglGetProcAddress("eglPresentationTimeANDROID"));
  if (!getClientBuffer_ || !createImage_ || !destroyImage_ || !targetTexture_ || !presentationTime_) {
    eglFail("required EGL/GLES extensions are missing");
  }

  GLuint vs = compile(GL_VERTEX_SHADER, kVertex);
  GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment);
  program_ = glCreateProgram();
  glAttachShader(program_, vs);
  glAttachShader(program_, fs);
  glLinkProgram(program_);
  glDeleteShader(vs);
  glDeleteShader(fs);
  GLint linked = 0;
  glGetProgramiv(program_, GL_LINK_STATUS, &linked);
  if (!linked) throwError(ErrorCategory::HardwareCodecFailure, "gl", "GPU program link failed.");
  locPos_ = glGetAttribLocation(program_, "aPos");
  locM_ = glGetUniformLocation(program_, "uM");
  locDx_ = glGetUniformLocation(program_, "uDx");
  locDy_ = glGetUniformLocation(program_, "uDy");
  locSS_ = glGetUniformLocation(program_, "uSS");
  locGray_ = glGetUniformLocation(program_, "uGray");
  locTex_ = glGetUniformLocation(program_, "sTex");

  const float quad[] = {-1, -1, 1, -1, -1, 1, 1, 1};
  glGenBuffers(1, &vbo_);
  glBindBuffer(GL_ARRAY_BUFFER, vbo_);
  glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
  glGenTextures(1, &texture_);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture_);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  VC_LOGI(TAG, "GL ready %dx%d renderer=%s", outWidth, outHeight, reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
}

EGLImageKHR GlRenderer::imageFor(AHardwareBuffer* hb) {
  auto it = images_.find(hb);
  if (it != images_.end()) return it->second;
  EGLClientBuffer cb = getClientBuffer_(hb);
  const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
  EGLImageKHR img = createImage_(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
  if (img == EGL_NO_IMAGE_KHR) eglFail("EGLImage from decoder buffer");
  // ImageReader recycles a small, fixed set of buffers, so the cache stays tiny.
  AHardwareBuffer_acquire(hb);
  images_[hb] = img;
  return img;
}

void GlRenderer::drawAndPresent(AHardwareBuffer* hb, const TexTransform& t, bool grayscale, bool supersample, int64_t ptsNs) {
  EGLImageKHR img = imageFor(hb);
  glViewport(0, 0, outW_, outH_);
  glUseProgram(program_);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture_);
  targetTexture_(GL_TEXTURE_EXTERNAL_OES, static_cast<GLeglImageOES>(img));
  glUniform1i(locTex_, 0);
  const float m[9] = {t.a, t.d, 0, t.b, t.e, 0, t.c, t.f, 1};  // column-major
  glUniformMatrix3fv(locM_, 1, GL_FALSE, m);
  float du = 0.25f / outW_, dv = 0.25f / outH_;
  glUniform2f(locDx_, t.a * du, t.d * du);
  glUniform2f(locDy_, t.b * dv, t.e * dv);
  glUniform1i(locSS_, supersample ? 1 : 0);
  glUniform1i(locGray_, grayscale ? 1 : 0);
  glBindBuffer(GL_ARRAY_BUFFER, vbo_);
  glEnableVertexAttribArray(locPos_);
  glVertexAttribPointer(locPos_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  presentationTime_(display_, surface_, ptsNs);
  if (!eglSwapBuffers(display_, surface_)) eglFail("submitting a frame to the encoder");
  // The decoder may overwrite this buffer once we release its AImage; make sure
  // the GPU is done sampling it first.
  glFinish();
}

void GlRenderer::release() {
  if (display_ == EGL_NO_DISPLAY) return;
  eglMakeCurrent(display_, surface_, surface_, context_);
  for (auto& [hb, img] : images_) {
    destroyImage_(display_, img);
    AHardwareBuffer_release(hb);
  }
  images_.clear();
  if (texture_) glDeleteTextures(1, &texture_);
  if (vbo_) glDeleteBuffers(1, &vbo_);
  if (program_) glDeleteProgram(program_);
  texture_ = vbo_ = program_ = 0;
  eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
  if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
  eglReleaseThread();
  surface_ = EGL_NO_SURFACE;
  context_ = EGL_NO_CONTEXT;
  display_ = EGL_NO_DISPLAY;
}

}  // namespace vc
