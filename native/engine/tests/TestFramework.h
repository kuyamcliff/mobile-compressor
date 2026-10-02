#pragma once

// Minimal dependency-free test harness for the native engine.

#include <cmath>
#include <functional>
#include <sstream>
#include <string>
#include <vector>

namespace vctest {

struct TestCase {
  const char* suite;
  const char* name;
  std::function<void()> fn;
};

std::vector<TestCase>& registry();

struct Registrar {
  Registrar(const char* suite, const char* name, std::function<void()> fn) {
    registry().push_back({suite, name, std::move(fn)});
  }
};

struct Failure {
  std::string message;
};

}  // namespace vctest

#define VC_TEST(suite, name)                                                         \
  static void suite##_##name();                                                      \
  static ::vctest::Registrar suite##_##name##_reg(#suite, #name, suite##_##name);    \
  static void suite##_##name()

#define VC_FAIL(msg)                                                    \
  do {                                                                  \
    std::ostringstream os_;                                             \
    os_ << __FILE__ << ":" << __LINE__ << ": " << msg;                  \
    throw ::vctest::Failure{os_.str()};                                 \
  } while (0)

#define EXPECT_TRUE(c) \
  do { if (!(c)) VC_FAIL("expected true: " #c); } while (0)
#define EXPECT_FALSE(c) \
  do { if (c) VC_FAIL("expected false: " #c); } while (0)
#define EXPECT_EQ(a, b)                                                                  \
  do {                                                                                   \
    auto va_ = (a);                                                                      \
    auto vb_ = (b);                                                                      \
    if (!(va_ == vb_)) VC_FAIL("expected " #a " == " #b " (" << va_ << " vs " << vb_ << ")"); \
  } while (0)
#define EXPECT_NEAR(a, b, tol)                                                                \
  do {                                                                                        \
    double va_ = (a), vb_ = (b);                                                              \
    if (std::fabs(va_ - vb_) > (tol)) VC_FAIL("expected " #a " ~= " #b " (" << va_ << " vs " << vb_ << ")"); \
  } while (0)
#define EXPECT_CONTAINS(hay, needle)                                                         \
  do {                                                                                        \
    std::string h_ = (hay), n_ = (needle);                                                    \
    if (h_.find(n_) == std::string::npos) VC_FAIL("expected '" << h_ << "' to contain '" << n_ << "'"); \
  } while (0)
