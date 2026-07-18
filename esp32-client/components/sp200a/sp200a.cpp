#include "sp200a.h"

#include "esphome/core/helpers.h"

// esp-idf lwip BSD sockets
#include <lwip/sockets.h>
#include <lwip/inet.h>
#include "esp_heap_caps.h"

#if __has_include("esphome/components/mipi_rgb/mipi_rgb.h")
#include "esphome/components/mipi_rgb/mipi_rgb.h"
#define SP200A_HAS_MIPI_RGB 1
#endif

#include "esphome/components/wifi/wifi_component.h"
#include <esp_wifi.h>
#include <esp_event.h>
#include <errno.h>
#include <fcntl.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

LV_FONT_DECLARE(lv_font_montserrat_20);  // compiled in via YAML font usage

namespace esphome {
namespace sp200a {

static const char *const TAG = "sp200a";

// ---- protocol constants (see sp200a-nmea-bridge-spec.md) ----
static constexpr int SAMPLES = SP200AClient::SAMPLES_N;
static constexpr double SAMPLES_PER_M = 9.475;

// waterfall geometry / tuning (ported from SonarView)
static const double RANGE_STEPS_M[] = {2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 40.0, 60.0, 80.0};
static constexpr int RANGE_STEPS_N = sizeof(RANGE_STEPS_M) / sizeof(RANGE_STEPS_M[0]);
static constexpr double FIT = 1.25;
static constexpr uint32_t STEP_DOWN_MS = 6000;
static constexpr int ASCOPE_W = 16;  // live strip on the right edge
static constexpr int ASCOPE_GAP = 4;
// Each data column is drawn this many pixels wide (Android draws ~3dp columns;
// 2 px here matches its scroll pace and horizontal texture on 800 px).
static constexpr int COL_PX = 2;

static inline int clamp255(int x) { return x < 0 ? 0 : (x > 255 ? 255 : x); }

// RGB565 (native LVGL color format at depth 16)
static inline uint16_t pack565(int r, int g, int b) {
  return (uint16_t) (((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
}

int SP200AClient::plot_w_() const { return this->w_ - ASCOPE_W - ASCOPE_GAP; }

// ============================================================ Component

void SP200AClient::setup() {
  this->recent_fish_.fill(-1.0f);
  this->build_luts_();
  this->apply_style_();
  this->range_m_ = RANGE_STEPS_M[this->range_idx_];
  // bind the UDP diag responder NOW, while sockets/heap are guaranteed
  // available — a lazy bind can fail permanently once the heap degrades
  this->diag_responder_();
  ESP_LOGCONFIG(TAG, "SP200A client init (host=%s:%u, diag_sock=%d)", this->host_.c_str(),
                this->port_, this->diag_sock_);
}

void SP200AClient::loop() {
  // stall diagnostics: track worst loop-to-loop gap per 5 s window
  {
    const uint32_t now = millis();
    if (this->diag_last_iter_ != 0 && now - this->diag_last_iter_ > this->diag_worst_)
      this->diag_worst_ = now - this->diag_last_iter_;
    this->diag_last_iter_ = now;
    if (now - this->diag_last_report_ > 5000) {
      this->diag_last_report_ = now;
      this->diag_worst_prev_ = this->diag_worst_;
      ESP_LOGI(TAG, "loop heartbeat: worst gap %u ms", (unsigned) this->diag_worst_);
      this->diag_worst_ = 0;
    }
  }

  this->diag_responder_();

  if (this->scan_done_flag_) {
    this->scan_done_flag_ = false;
    this->collect_scan_results_();
  }

  if (this->pending_canvas_ != nullptr && this->buf16_ == nullptr)
    this->bind_canvas_();

  // batched screen update: columns accumulate at stream rate. While the user
  // is interacting (touched within the last 3 s), the waterfall yields to
  // ~2.5 Hz so input and widget redraws stay snappy; idle viewing runs full
  // cadence.
  {
    const uint32_t now = millis();
    // ~4 Hz idle: the full pipeline (memmove + LVGL blit + fb copy) costs
    // ~175 ms per tick regardless of column count, so fewer/bigger ticks
    // buy CPU with near-identical scroll (Android steps 3 px per 200 ms)
    const bool interacting =
        this->canvas_ != nullptr && lv_display_get_inactive_time(nullptr) < 3000;
    const uint32_t period = interacting ? 450 : 250;
    if (now - this->last_render_ >= period) {
      this->last_render_ = now;
      this->render_pending_();
    }
  }

  if (this->demo_) {
    this->demo_step_();
    return;
  }

  this->ensure_socket_();
  if (this->sock_ < 0)
    return;

  this->drain_socket_();

  const uint32_t now = millis();
  if (this->state_ == State::DISCOVER) {
    if (now - this->last_tx_ > 1000) {
      this->send_fx_();
      this->last_tx_ = now;
    }
  } else {  // RUN
    if (now - this->last_data_ > 15000) {
      ESP_LOGW(TAG, "stream silent >15s, re-discovering");
      this->enter_discover_();
    } else if (now - this->last_tx_ > 10000) {
      this->send_fc_();  // re-arm: T-Box streams ~30s per FC
      this->last_tx_ = now;
    }
  }
  this->connected_ = (this->state_ == State::RUN) && (now - this->last_data_ < 15000);
}

void SP200AClient::dump_config() {
  ESP_LOGCONFIG(TAG, "SP200A SonarPhone client:");
  ESP_LOGCONFIG(TAG, "  Host: %s:%u", this->host_.c_str(), this->port_);
  ESP_LOGCONFIG(TAG, "  Beam byte: 0x%02X", this->beam_);
  ESP_LOGCONFIG(TAG, "  Canvas: %dx%d", this->w_, this->h_);
}

// ============================================================ Socket

void SP200AClient::ensure_socket_() {
  if (this->sock_ >= 0)
    return;
  int s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  if (s < 0) {
    ESP_LOGW(TAG, "socket() failed: errno=%d", errno);
    return;
  }
  int flags = ::fcntl(s, F_GETFL, 0);
  ::fcntl(s, F_SETFL, flags | O_NONBLOCK);
  this->sock_ = s;
  this->last_tx_ = 0;  // fire a request promptly
  ESP_LOGI(TAG, "UDP socket open");
}

void SP200AClient::enter_discover_() {
  this->state_ = State::DISCOVER;
  this->have_mac_ = false;
  this->connected_ = false;
  this->last_tx_ = 0;
}

void SP200AClient::drain_socket_() {
  uint8_t buf[1500];
  struct sockaddr_in from{};
  socklen_t fl = sizeof(from);
  for (int guard = 0; guard < 32; guard++) {  // bound work per loop
    int n = ::recvfrom(this->sock_, buf, sizeof(buf), 0, (struct sockaddr *) &from, &fl);
    if (n <= 0) {
      // EWOULDBLOCK/EAGAIN => nothing pending
      break;
    }
    this->handle_reply_(buf, n);
  }
}

uint16_t SP200AClient::additive_checksum_(const uint8_t *b, int n) {
  uint32_t sum = 0;
  for (int i = 0; i < n; i++)
    sum += b[i];
  return (uint16_t) (sum & 0xFFFF);
}

void SP200AClient::send_fx_() {
  // constant 29-byte FX; byte19 = 0xB3 additive checksum
  uint8_t fx[29] = {0};
  fx[0] = 'F';
  fx[1] = 'X';
  fx[2] = 0x15;
  fx[19] = 0xB3;
  struct sockaddr_in dst{};
  dst.sin_family = AF_INET;
  dst.sin_port = htons(this->port_);
  dst.sin_addr.s_addr = inet_addr(this->host_.c_str());
  int r = ::sendto(this->sock_, fx, sizeof(fx), 0, (struct sockaddr *) &dst, sizeof(dst));
  if (r < 0) {
    ESP_LOGV(TAG, "FX sendto errno=%d", errno);
  }
}

void SP200AClient::send_fc_() {
  if (!this->have_mac_)
    return;
  // FC: settings + master MAC + additive 16-bit LE checksum of bytes 0..18.
  // We always request METERS (feet=0) from the T-Box; the `feet_` option only
  // affects on-screen unit labelling.
  uint8_t b[29] = {0};
  b[0] = 'F';
  b[1] = 'C';
  b[2] = 0x15;
  b[4] = 0xF4;
  b[5] = 0x02;
  // depthMin/depthMax @6..9 left 0 = auto range
  b[11] = 0;  // units: 0 = meters
  b[13] = this->beam_;
  uint16_t sum = additive_checksum_(b, 19);
  b[19] = (uint8_t) (sum & 0xFF);
  b[20] = (uint8_t) ((sum >> 8) & 0xFF);
  std::memcpy(b + 21, this->mac_, 6);

  struct sockaddr_in dst{};
  dst.sin_family = AF_INET;
  dst.sin_port = htons(this->port_);
  dst.sin_addr.s_addr = inet_addr(this->host_.c_str());
  int r = ::sendto(this->sock_, b, sizeof(b), 0, (struct sockaddr *) &dst, sizeof(dst));
  if (r < 0) {
    ESP_LOGV(TAG, "FC sendto errno=%d", errno);
  }
}

static inline int u8(const uint8_t *d, int o) { return d[o]; }
static inline int u16le(const uint8_t *d, int o) { return d[o] | (d[o + 1] << 8); }
static inline bool tag_eq(const uint8_t *d, int off, const char *s, int n) {
  return std::memcmp(d + off, s, n) == 0;
}

void SP200AClient::handle_reply_(const uint8_t *d, int len) {
  if (len >= 10 && tag_eq(d, 6, "BUSY", 4)) {
    ESP_LOGD(TAG, "T-Box BUSY (owned by another master)");
    return;
  }
  if (len >= 32 && tag_eq(d, 6, "REDYFX", 6)) {
    if (this->state_ != State::RUN) {
      std::memcpy(this->mac_, d + 26, 6);
      this->have_mac_ = true;
      char serial[11];
      std::memcpy(serial, d + 16, 10);
      serial[10] = 0;
      ESP_LOGI(TAG, "REDYFX serial=%s mac=%02x:%02x:%02x:%02x:%02x:%02x", serial, this->mac_[0],
               this->mac_[1], this->mac_[2], this->mac_[3], this->mac_[4], this->mac_[5]);
      this->state_ = State::RUN;
      this->last_data_ = millis();
      this->send_fc_();
      this->last_tx_ = millis();
    }
    return;
  }
  if (len >= 38 && tag_eq(d, 6, "REDYFC", 6)) {
    const bool units_feet = u8(d, 21) == 1;
    double depth = u16le(d, 23) + u8(d, 25) / 100.0;
    if (units_feet)
      depth *= 0.3048;  // normalise to metres (we asked for m, but trust byte 21)
    const int temp_c = u8(d, 26);
    const double vbatt = u8(d, 30) + u8(d, 31) / 100.0;
    const int echo_len = (len - 38) > SAMPLES ? SAMPLES : (len - 38);
    const uint8_t *echo = d + 38;

    this->depth_m_ = (float) depth;
    this->temp_c_ = (float) temp_c;
    this->battery_v_ = (float) vbatt;
    this->last_data_ = millis();

    this->update_auto_range_(depth);
    this->push_column_(echo, echo_len, depth);

    // publish sensors (throttled to ~2 Hz — plenty for a numeric readout)
    const uint32_t now = millis();
    if (now - this->last_publish_ > 500) {
      this->last_publish_ = now;
#ifdef USE_SENSOR
      if (this->depth_sensor_ != nullptr)
        this->depth_sensor_->publish_state(depth);
      if (this->temp_sensor_ != nullptr)
        this->temp_sensor_->publish_state(temp_c);
      if (this->battery_sensor_ != nullptr)
        this->battery_sensor_->publish_state(vbatt);
#endif
    }
    return;
  }
}

// ============================================================ Palettes

void SP200AClient::build_luts_() {
  this->bg_modern_ = pack565(1, 3, 10);
  this->bg_classic_ = pack565(6, 20, 66);  // classic navy
  for (int v = 0; v < 256; v++) {
    // Modern water: deep blue -> cyan -> yellow -> red (from SonarView)
    uint16_t c;
    if (v < 32) {
      c = this->bg_modern_;
    } else if (v < 128) {
      c = pack565(0, clamp255((v - 32) * 2), clamp255(100 + (v - 32)));
    } else if (v < 192) {
      c = pack565(clamp255((v - 128) * 4), clamp255(190 + (v - 128)),
                  clamp255(195 - (v - 128) * 3));
    } else {
      c = pack565(255, clamp255(255 - (v - 192) * 3), 0);
    }
    this->lut_water_m_[v] = c;

    // Modern bottom: orange monochrome, brown floor (density = hardness)
    float t = v < 26 ? 0.0f : (v - 26) / 229.0f;
    this->lut_bottom_m_[v] = pack565((int) (78 + 177 * t), (int) (52 + 92 * t), (int) (28 + 2 * t));

    // Classic: full sonar rainbow on navy, hottest to white (bottom uses the
    // same ramp — old units don't separate water from earth)
    uint16_t k;
    if (v < 24) {
      k = this->bg_classic_;
    } else if (v < 70) {
      k = pack565(0, clamp255(40 + (v - 24)), clamp255(170 + (v - 24) * 2));  // blue
    } else if (v < 120) {
      k = pack565(0, clamp255(90 + (v - 70) * 3), clamp255(255 - (v - 70) * 2));  // cyan
    } else if (v < 165) {
      k = pack565(clamp255((v - 120) * 5), 240, clamp255(155 - (v - 120) * 3));  // green
    } else if (v < 205) {
      k = pack565(255, clamp255(240 - (v - 165) * 3), 0);  // yellow->orange
    } else if (v < 235) {
      k = pack565(255, clamp255(120 - (v - 205) * 4), 0);  // red
    } else {
      k = pack565(255, clamp255(60 + (v - 235) * 9), clamp255((v - 235) * 12));  // white-hot
    }
    this->lut_classic_[v] = k;
  }
}

void SP200AClient::apply_style_() {
  if (this->style_ == 1) {
    this->lut_water_ = this->lut_classic_;
    this->lut_bottom_ = this->lut_classic_;
    this->bg_color_ = this->bg_classic_;
  } else {
    this->lut_water_ = this->lut_water_m_;
    this->lut_bottom_ = this->lut_bottom_m_;
    this->bg_color_ = this->bg_modern_;
  }
}

// ============================================================ Canvas binding

void SP200AClient::bind_canvas_() {
  lv_obj_t *canvas = this->pending_canvas_;
  this->pending_canvas_ = nullptr;
  this->canvas_ = canvas;
  lv_draw_buf_t *db = lv_canvas_get_draw_buf(canvas);
  if (db == nullptr) {
    ESP_LOGE(TAG, "canvas has no draw buffer");
    this->canvas_ = nullptr;
    return;
  }
  this->w_ = (int) db->header.w;
  this->h_ = (int) db->header.h;
  this->stride_px_ = (int) (db->header.stride / sizeof(uint16_t));
  this->buf16_ = (uint16_t *) db->data;

  // history ring: one echo column per COL_PX-wide plot column (~290 KB → PSRAM)
  this->ring_cap_ = this->plot_w_() / COL_PX;
  this->echo_ring_ =
      (uint8_t *) heap_caps_malloc((size_t) this->ring_cap_ * SAMPLES, MALLOC_CAP_SPIRAM);
  this->depth_ring_ =
      (float *) heap_caps_malloc(this->ring_cap_ * sizeof(float), MALLOC_CAP_SPIRAM);
  this->fish_ring_ = (float *) heap_caps_malloc(this->ring_cap_ * sizeof(float), MALLOC_CAP_SPIRAM);
  if (this->echo_ring_ == nullptr || this->depth_ring_ == nullptr || this->fish_ring_ == nullptr) {
    ESP_LOGE(TAG, "history ring alloc failed (%d KB PSRAM)",
             (int) ((size_t) this->ring_cap_ * SAMPLES / 1024));
    this->echo_ring_ = nullptr;  // render single columns only; no re-render
  }

  this->update_row_map_();
  this->clear_canvas_();
  this->rebuild_scale_();
  this->enable_direct_render_();
  ESP_LOGI(TAG, "canvas bound %dx%d stride=%dpx buf=%p ring=%d cols", this->w_, this->h_,
           this->stride_px_, (void *) this->buf16_, this->ring_cap_);
}

/** Re-point LVGL at the RGB panel's two framebuffers (DIRECT render mode).
 *  Flushing then swaps scanout on the frame boundary instead of copying —
 *  tear-free and one full-frame memcpy cheaper per update. Requires the
 *  forked mipi_rgb (num_fbs=2 + get_frame_buffers). */
void SP200AClient::enable_direct_render_() {
#ifdef SP200A_HAS_MIPI_RGB
  if (this->direct_display_ == nullptr)
    return;
  auto *disp = static_cast<mipi_rgb::MipiRgb *>(this->direct_display_);
  void *fb0 = nullptr, *fb1 = nullptr;
  disp->get_frame_buffers(&fb0, &fb1);
  if (fb0 == nullptr || fb1 == nullptr) {
    ESP_LOGW(TAG, "direct render unavailable (single framebuffer?)");
    return;
  }
  lv_display_t *ld = lv_display_get_default();
  const uint32_t fb_bytes = (uint32_t) disp->get_width() * disp->get_height() * 2;
  lv_display_set_buffers(ld, fb0, fb1, fb_bytes, LV_DISPLAY_RENDER_MODE_DIRECT);
  ESP_LOGI(TAG, "LVGL DIRECT mode: fb0=%p fb1=%p (%u KB each)", fb0, fb1,
           (unsigned) (fb_bytes / 1024));
#endif
}

void SP200AClient::clear_canvas_() {
  if (this->buf16_ == nullptr)
    return;
  for (int y = 0; y < this->h_; y++) {
    uint16_t *row = this->buf16_ + (size_t) y * this->stride_px_;
    for (int x = 0; x < this->w_; x++)
      row[x] = this->bg_color_;
  }
  if (this->canvas_ != nullptr)
    lv_obj_invalidate(this->canvas_);
}

// ============================================================ Range logic

void SP200AClient::update_auto_range_(double depth_m) {
  if (!this->auto_range_ || depth_m <= 0.0)
    return;
  const double need = depth_m * FIT;
  double fit_step = RANGE_STEPS_M[RANGE_STEPS_N - 1];
  for (int i = 0; i < RANGE_STEPS_N; i++) {
    if (RANGE_STEPS_M[i] >= need) {
      fit_step = RANGE_STEPS_M[i];
      break;
    }
  }
  const uint32_t now = millis();
  const double prev = this->range_m_;
  if (fit_step > this->range_m_) {
    this->range_m_ = fit_step;  // deepen instantly
    this->fits_smaller_since_ = 0;
  } else if (fit_step < this->range_m_) {
    if (this->fits_smaller_since_ == 0)
      this->fits_smaller_since_ = now;
    if (now - this->fits_smaller_since_ > STEP_DOWN_MS) {
      this->range_m_ = fit_step;  // shallow only after a dwell
      this->fits_smaller_since_ = 0;
    }
  } else {
    this->fits_smaller_since_ = 0;
  }
  if (this->range_m_ != prev) {
    for (int i = 0; i < RANGE_STEPS_N; i++)
      if (RANGE_STEPS_M[i] == this->range_m_)
        this->range_idx_ = i;
    this->full_redraw_();  // re-render history at the new scale (never wipe)
  }
}

std::string SP200AClient::range_label() const {
  if (this->auto_range_)
    return "AUTO";
  char b[20];
  const double r = this->range_m_ * (this->feet_ ? 3.28084 : 1.0);
  snprintf(b, sizeof(b), "%.0f %s", r, this->feet_ ? "ft" : "m");
  return b;
}

// ============================================================ Fish detection

bool SP200AClient::recent_fish_near_(float depth_m) {
  for (float f : this->recent_fish_)
    if (f > 0.0f && std::fabs(f - depth_m) < 1.5f)
      return true;
  return false;
}

float SP200AClient::detect_fish_(const uint8_t *echo, int echo_len, double depth_m) {
  const double spm = SAMPLES_PER_M;
  const int top = (int) (0.8 * spm);  // skip surface clutter
  // exclude a full metre above the bottom: the bottom return's upper skirt
  // otherwise reads as a "fish" hugging the floor (Android used ~0.3 m and
  // its demo predates fish markers, so it never showed)
  int bottom_idx = depth_m > 0.3 ? (int) ((depth_m - 1.0) * spm) : echo_len;
  const int lo = top;
  const int hi = bottom_idx < echo_len ? bottom_idx : echo_len;
  if (hi - lo < 4)
    return -1.0f;
  int peak = 0, peak_i = -1;
  for (int i = lo; i < hi; i++) {
    int v = echo[i];
    if (v > peak) {
      peak = v;
      peak_i = i;
    }
  }
  if (peak_i < 0 || peak < 130)
    return -1.0f;
  int bg = 0, cnt = 0;
  for (int dd = 6; dd <= 10; dd++) {
    if (peak_i - dd >= 0 && peak_i - dd < echo_len) {
      bg += echo[peak_i - dd];
      cnt++;
    }
    if (peak_i + dd >= 0 && peak_i + dd < echo_len) {
      bg += echo[peak_i + dd];
      cnt++;
    }
  }
  const float mean = cnt > 0 ? (float) bg / cnt : 0.0f;
  if (peak - mean < 70)
    return -1.0f;  // not isolated enough (weeds/thermocline)
  return peak_i / (float) spm;
}

// ============================================================ Rendering

/** Precompute the y -> echo-sample map and surface-clarity factor for the
 *  current range/clarity. Kills the per-pixel double math in draw_col_. */
void SP200AClient::update_row_map_() {
  const int H = std::min(this->h_, MAX_H);
  const double window_samples = std::min(this->range_m_ * SAMPLES_PER_M, (double) SAMPLES);
  const double clarity_m = this->clarity_ == 1 ? 0.6 : this->clarity_ == 2 ? 1.0
      : this->clarity_ == 3                                                ? 1.5
                                                                           : 0.0;
  const double clarity_samples = clarity_m * SAMPLES_PER_M;
  for (int y = 0; y < H; y++) {
    const double sidx = (double) y / H * window_samples;
    int i = (int) sidx;
    const float t = (float) (sidx - i);
    if (i < 0)
      i = 0;
    if (i >= SAMPLES)
      i = SAMPLES - 1;
    this->row_sample_[y] = (uint16_t) i;
    // Catmull-Rom tap weights for taps [i-1, i, i+1, i+2] (SonarView's
    // intensity-space smoothing — this is what makes the marks creamy
    // instead of banded when one sample spans several rows)
    const float t2 = t * t, t3 = t2 * t;
    this->row_w_[y][0] = 0.5f * (-t + 2.0f * t2 - t3);
    this->row_w_[y][1] = 0.5f * (2.0f - 5.0f * t2 + 3.0f * t3);
    this->row_w_[y][2] = 0.5f * (t + 4.0f * t2 - 3.0f * t3);
    this->row_w_[y][3] = 0.5f * (-t2 + t3);
    this->row_clar_[y] = (clarity_samples > 0 && sidx < clarity_samples)
                             ? 0.15f + 0.85f * (float) (sidx / clarity_samples)
                             : 1.0f;
  }
}

/** Paint one plot column (Catmull-Rom smoothed echo + bottom hairline) at
 *  pixel column x. */
void SP200AClient::draw_col_(int x, const uint8_t *samples, float depth_m) {
  const int H = std::min(this->h_, MAX_H), S = this->stride_px_;
  const double window_samples = std::min(this->range_m_ * SAMPLES_PER_M, (double) SAMPLES);
  const int bottom_idx = depth_m > 0.3f ? (int) (depth_m * SAMPLES_PER_M) : INT32_MAX;

  const float gain = 1.18f * this->gain_user_;
  const float floor = 18.0f + this->noise_ * 14.0f;

  for (int y = 0; y < H; y++) {
    const int i = this->row_sample_[y];
    const float *w = this->row_w_[y];
    const float p0 = samples[i > 0 ? i - 1 : 0];
    const float p1 = samples[i];
    const float p2 = samples[i + 1 < SAMPLES ? i + 1 : SAMPLES - 1];
    const float p3 = samples[i + 2 < SAMPLES ? i + 2 : SAMPLES - 1];
    const float sv = w[0] * p0 + w[1] * p1 + w[2] * p2 + w[3] * p3;
    float v = (sv - floor) * gain * this->row_clar_[y];
    int vi = clamp255((int) v);
    uint16_t c = (i < bottom_idx) ? this->lut_water_[vi] : this->lut_bottom_[vi];
    this->buf16_[(size_t) y * S + x] = c;
  }
  if (depth_m > 0.3f) {
    // hairline ~3 px thick, matching the Android 1.5dp stroke
    const int by = (int) (depth_m * SAMPLES_PER_M / window_samples * H);
    const uint16_t cream = pack565(255, 214, 140);
    for (int dy = -1; dy <= 1; dy++) {
      const int yy = by + dy;
      if (yy >= 0 && yy < H)
        this->buf16_[(size_t) yy * S + x] = cream;
    }
  }
}

/** A proper fish silhouette (ported from SonarView.drawFish): magenta body
 *  with a dark outline so it reads on top of any echo color, drawn over the
 *  plot. ~34 px long, teardrop body pointing left, tail fin right. */
void SP200AClient::draw_fish_blob_(int x, float fish_depth_m) {
  const int W = this->plot_w_(), H = this->h_, S = this->stride_px_;
  const double window_samples = std::min(this->range_m_ * SAMPLES_PER_M, (double) SAMPLES);
  const int fy = (int) (fish_depth_m * SAMPLES_PER_M / window_samples * H);
  const uint16_t fill = pack565(255, 60, 230);   // magenta
  const uint16_t line = pack565(10, 10, 10);     // near-black outline
  const uint16_t eye = pack565(255, 255, 255);

  auto in_body = [](float dx, float dy, float a, float b) {
    return (dx * dx) / (a * a) + (dy * dy) / (b * b) <= 1.0f;
  };
  auto in_tail = [](float dx, float dy, float x0, float x1, float slope) {
    return dx >= x0 && dx <= x1 && std::fabs(dy) <= (dx - x0) * slope;
  };

  for (int dy = -10; dy <= 10; dy++) {
    const int yy = fy + dy;
    if (yy < 0 || yy >= H)
      continue;
    uint16_t *row = this->buf16_ + (size_t) yy * S;
    for (int dx = -15; dx <= 18; dx++) {
      const int xx = x + dx;
      if (xx < 0 || xx >= W)
        continue;
      const bool outer = in_body(dx, dy, 13.0f, 8.0f) || in_tail(dx, dy, 8.0f, 18.0f, 0.75f);
      if (!outer)
        continue;
      const bool inner = in_body(dx, dy, 10.5f, 5.5f) || in_tail(dx, dy, 10.0f, 16.0f, 0.55f);
      row[xx] = inner ? fill : line;
    }
  }
  // eye near the nose
  for (int dy = -3; dy <= -2; dy++) {
    for (int dx = -9; dx <= -8; dx++) {
      const int yy = fy + dy, xx = x + dx;
      if (yy >= 0 && yy < H && xx >= 0 && xx < W)
        this->buf16_[(size_t) yy * S + xx] = eye;
    }
  }
}

/** Depth text above a fish mark, drawn into the canvas so it scrolls along. */
void SP200AClient::draw_fish_label_(int x, float fish_depth_m) {
  if (this->canvas_ == nullptr)
    return;
  const int H = this->h_;
  const double window_samples = std::min(this->range_m_ * SAMPLES_PER_M, (double) SAMPLES);
  const int fy = (int) (fish_depth_m * SAMPLES_PER_M / window_samples * H);
  if (fy < 38)
    return;

  char txt[16];
  if (this->feet_) {
    const double tf = fish_depth_m * 3.28084;
    int ft = (int) tf;
    int in = (int) ((tf - ft) * 12.0 + 0.5);
    if (in == 12) {
      ft++;
      in = 0;
    }
    snprintf(txt, sizeof(txt), "%d'%d\"", ft, in);
  } else {
    snprintf(txt, sizeof(txt), "%.1f", fish_depth_m);
  }

  lv_layer_t layer;
  lv_canvas_init_layer(this->canvas_, &layer);
  lv_draw_label_dsc_t dsc;
  lv_draw_label_dsc_init(&dsc);
  dsc.color = lv_color_white();
  dsc.font = &lv_font_montserrat_20;
  dsc.text = txt;
  dsc.text_local = 1;  // copy: txt is stack memory
  dsc.align = LV_TEXT_ALIGN_CENTER;
  lv_area_t a;
  a.x1 = x - 34;
  a.y1 = fy - 36;
  a.x2 = x + 34;
  a.y2 = fy - 12;
  lv_draw_label(&layer, &dsc, &a);
  lv_canvas_finish_layer(this->canvas_, &layer);
}

/** Live A-scope strip on the right edge (magnified newest column + marker).
 *  Uses the precomputed row map — per-row double math is software-emulated
 *  on Xtensa and made this strip absurdly expensive (~50 ms). */
void SP200AClient::draw_ascope_() {
  if (this->buf16_ == nullptr)
    return;
  const int H = std::min(this->h_, MAX_H), S = this->stride_px_;
  const int x0 = this->plot_w_() + ASCOPE_GAP;
  const int bottom_idx =
      this->depth_m_ > 0.3f ? (int) (this->depth_m_ * SAMPLES_PER_M) : INT32_MAX;
  const float gain = 1.18f * this->gain_user_;
  const float floor = 18.0f + this->noise_ * 14.0f;
  const uint16_t black = pack565(0, 0, 0);

  for (int y = 0; y < H; y++) {
    uint16_t *row = this->buf16_ + (size_t) y * S;
    const int i = this->row_sample_[y];
    const int raw = this->have_echo_ ? this->last_echo_[i] : 0;
    const int vi = clamp255((int) ((raw - floor) * gain));
    const uint16_t c = (i < bottom_idx) ? this->lut_water_[vi] : this->lut_bottom_[vi];
    for (int x = this->plot_w_(); x < x0; x++)
      row[x] = black;
    for (int x = x0; x < this->w_; x++)
      row[x] = c;
  }
  // depth marker dot on the strip's left edge
  if (this->depth_m_ > 0.3f) {
    const float window_samples =
        (float) std::min(this->range_m_ * SAMPLES_PER_M, (double) SAMPLES);
    const int dy0 = (int) (bottom_idx / window_samples * H);
    const uint16_t dot = pack565(255, 179, 64);
    for (int dy = -3; dy <= 3; dy++) {
      for (int dx = 0; dx <= 3; dx++) {
        if (std::abs(dy) + dx > 3)
          continue;
        int yy = dy0 + dy, xx = x0 + dx;
        if (yy >= 0 && yy < H)
          this->buf16_[(size_t) yy * S + xx] = dot;
      }
    }
  }
}

/** 1/2/5×10^k interval that yields ~4–6 gridlines (from SonarView). */
static double nice_interval(double raw) {
  double mag = 1.0;
  while (raw / mag >= 10)
    mag *= 10;
  while (raw / mag < 1)
    mag /= 10;
  const double n = raw / mag;
  double base;
  if (n < 1.5) {
    base = 1.0;
  } else if (n < 3.5) {
    base = 2.0;
  } else if (n < 7.5) {
    base = 5.0;
  } else {
    base = 10.0;
  }
  return base * mag;
}

/** Depth ticks + labels as LVGL overlay objects (static; don't scroll). */
void SP200AClient::rebuild_scale_() {
  if (this->canvas_ == nullptr)
    return;
  lv_obj_t *parent = lv_obj_get_parent(this->canvas_);
  const int plot_w = this->plot_w_();

  const double window_m = std::min(this->range_m_ * SAMPLES_PER_M, (double) SAMPLES) / SAMPLES_PER_M;
  const double window_disp = window_m * (this->feet_ ? 3.28084 : 1.0);
  const double interval = nice_interval(window_disp / 4.5);

  int n = 0;
  for (double d = interval; d < window_disp && n < MAX_TICKS; d += interval, n++) {
    const int y = (int) (d / window_disp * this->h_);

    if (this->tick_line_[n] == nullptr) {
      lv_obj_t *t = lv_obj_create(parent);
      lv_obj_remove_style_all(t);
      lv_obj_set_style_bg_color(t, lv_color_white(), 0);
      lv_obj_set_style_bg_opa(t, LV_OPA_COVER, 0);
      lv_obj_clear_flag(t, LV_OBJ_FLAG_CLICKABLE);
      lv_obj_set_size(t, 14, 3);
      this->tick_line_[n] = t;
    }
    lv_obj_set_pos(this->tick_line_[n], plot_w - 14, y - 1);
    lv_obj_clear_flag(this->tick_line_[n], LV_OBJ_FLAG_HIDDEN);

    if (this->tick_lbl_[n] == nullptr) {
      lv_obj_t *l = lv_label_create(parent);
      lv_obj_set_style_text_color(l, lv_color_white(), 0);
      lv_obj_set_style_text_font(l, &lv_font_montserrat_20, 0);
      lv_obj_set_style_bg_color(l, lv_color_black(), 0);
      lv_obj_set_style_bg_opa(l, 120, 0);
      lv_obj_set_style_radius(l, 4, 0);
      lv_obj_set_style_pad_hor(l, 5, 0);
      lv_obj_set_style_pad_ver(l, 2, 0);
      this->tick_lbl_[n] = l;
    }
    char txt[12];
    snprintf(txt, sizeof(txt), "%.0f", d);
    lv_label_set_text(this->tick_lbl_[n], txt);
    // auto-width label, right edge just left of the tick, vertically centred
    // on the tick line (label box ≈ 28 px tall with padding)
    lv_obj_align(this->tick_lbl_[n], LV_ALIGN_TOP_RIGHT, -(this->w_ - plot_w + 20), y - 14);
    // keep clear of the unit legend pinned at the bottom
    if (y > this->h_ - 64) {
      lv_obj_add_flag(this->tick_lbl_[n], LV_OBJ_FLAG_HIDDEN);
    } else {
      lv_obj_clear_flag(this->tick_lbl_[n], LV_OBJ_FLAG_HIDDEN);
    }
  }
  // hide unused ticks
  for (int k = n; k < MAX_TICKS; k++) {
    if (this->tick_line_[k] != nullptr)
      lv_obj_add_flag(this->tick_line_[k], LV_OBJ_FLAG_HIDDEN);
    if (this->tick_lbl_[k] != nullptr)
      lv_obj_add_flag(this->tick_lbl_[k], LV_OBJ_FLAG_HIDDEN);
  }

  // unit label pinned at the bottom of the scale
  if (this->unit_lbl_ == nullptr) {
    lv_obj_t *l = lv_label_create(parent);
    lv_obj_set_style_text_color(l, lv_color_white(), 0);
    lv_obj_set_style_text_font(l, &lv_font_montserrat_20, 0);
    lv_obj_set_style_bg_color(l, lv_color_black(), 0);
    lv_obj_set_style_bg_opa(l, 120, 0);
    lv_obj_set_style_radius(l, 4, 0);
    lv_obj_set_style_pad_hor(l, 5, 0);
    lv_obj_set_style_pad_ver(l, 2, 0);
    this->unit_lbl_ = l;
  }
  lv_label_set_text(this->unit_lbl_, this->feet_ ? "ft" : "m");
  lv_obj_align(this->unit_lbl_, LV_ALIGN_BOTTOM_RIGHT, -(this->w_ - plot_w + 18), -10);
}

/** Re-render the whole canvas from the history ring at the current range. */
void SP200AClient::full_redraw_() {
  if (this->buf16_ == nullptr)
    return;
  this->update_row_map_();
  this->rebuild_scale_();
  this->pending_cols_ = 0;  // everything gets painted below
  const int W = this->plot_w_();
  if (this->echo_ring_ == nullptr || this->ring_count_ == 0) {
    this->clear_canvas_();
    this->draw_ascope_();
    return;
  }
  const int empty_px = W - this->ring_count_ * COL_PX;
  for (int x = 0; x < empty_px; x++) {
    for (int y = 0; y < this->h_; y++)
      this->buf16_[(size_t) y * this->stride_px_ + x] = this->bg_color_;
  }
  for (int k = 0; k < this->ring_count_; k++) {  // oldest first
    const int idx =
        (this->ring_head_ - (this->ring_count_ - 1) + k + 2 * this->ring_cap_) % this->ring_cap_;
    const int x = empty_px + k * COL_PX;
    for (int px = 0; px < COL_PX; px++)
      this->draw_col_(x + px, this->echo_ring_ + (size_t) idx * SAMPLES, this->depth_ring_[idx]);
  }
  // second pass so blobs (which span columns) aren't overdrawn
  if (this->fish_mode_ > 0) {
    for (int k = 0; k < this->ring_count_; k++) {
      const int idx =
          (this->ring_head_ - (this->ring_count_ - 1) + k + 2 * this->ring_cap_) % this->ring_cap_;
      if (this->fish_ring_[idx] > 0.0f) {
        const int x = empty_px + k * COL_PX + COL_PX / 2;
        this->draw_fish_blob_(x, this->fish_ring_[idx]);
        if (this->fish_mode_ == 2)
          this->draw_fish_label_(x, this->fish_ring_[idx]);
      }
    }
  }
  this->draw_ascope_();
  lv_obj_invalidate(this->canvas_);
}

/** Store one incoming echo column (stream rate). Cheap: no drawing. */
void SP200AClient::push_column_(const uint8_t *echo, int echo_len, double depth_m) {
  // remember the newest column for the A-scope
  {
    const int n = std::min(echo_len, SAMPLES);
    std::memcpy(this->last_echo_, echo, n);
    if (n < SAMPLES)
      std::memset(this->last_echo_ + n, 0, SAMPLES - n);
    this->have_echo_ = true;
  }

  // fish marker: one per target (dedup adjacent detections)
  float fd = this->detect_fish_(this->last_echo_, SAMPLES, depth_m);
  float marked = (fd > 0.0f && !this->recent_fish_near_(fd)) ? fd : -1.0f;
  this->recent_fish_[this->fish_ring_pos_] = marked;
  this->fish_ring_pos_ = (this->fish_ring_pos_ + 1) % (int) this->recent_fish_.size();

  if (this->echo_ring_ != nullptr) {
    this->ring_head_ = (this->ring_head_ + 1) % this->ring_cap_;
    std::memcpy(this->echo_ring_ + (size_t) this->ring_head_ * SAMPLES, this->last_echo_, SAMPLES);
    this->depth_ring_[this->ring_head_] = (float) depth_m;
    this->fish_ring_[this->ring_head_] = marked;
    if (this->ring_count_ < this->ring_cap_)
      this->ring_count_++;
    if (this->pending_cols_ < this->ring_cap_)
      this->pending_cols_++;
  }
}

/** Batched screen update (~4 Hz): shift by N pending columns, draw them,
 *  refresh the A-scope, ONE invalidate. */
void SP200AClient::render_pending_() {
  if (this->buf16_ == nullptr || this->pending_cols_ == 0 || this->echo_ring_ == nullptr)
    return;
  const uint32_t t0 = millis();
  const int W = this->plot_w_(), H = this->h_, S = this->stride_px_;
  const int n = std::min(this->pending_cols_, this->ring_cap_);
  this->pending_cols_ = 0;
  const int shift = n * COL_PX;

  // scroll the plot area left by n data columns (row-contiguous, fast)
  for (int y = 0; y < H; y++) {
    uint16_t *row = this->buf16_ + (size_t) y * S;
    std::memmove(row, row + shift, (size_t) (W - shift) * sizeof(uint16_t));
  }
  // draw the n newest ring columns at the right edge, oldest first
  for (int k = n - 1; k >= 0; k--) {
    const int idx = (this->ring_head_ - k + 2 * this->ring_cap_) % this->ring_cap_;
    const int x = W - (k + 1) * COL_PX;
    for (int px = 0; px < COL_PX; px++)
      this->draw_col_(x + px, this->echo_ring_ + (size_t) idx * SAMPLES, this->depth_ring_[idx]);
    const float marked = this->fish_ring_[idx];
    if (this->fish_mode_ > 0 && marked > 0.0f) {
      this->draw_fish_blob_(x + COL_PX / 2, marked);
      if (this->fish_mode_ == 2)
        this->draw_fish_label_(x + COL_PX / 2, marked);
    }
  }

  this->draw_ascope_();
  lv_obj_invalidate(this->canvas_);
  const uint32_t dt = millis() - t0;
  if (dt > this->diag_render_ms_)
    this->diag_render_ms_ = dt;
}

// ============================================================ Sonar-AP scan

static void scan_done_handler(void *arg, esp_event_base_t base, int32_t id, void *data) {
  static_cast<SP200AClient *>(arg)->notify_scan_done();
}

void SP200AClient::notify_scan_done() { this->scan_done_flag_ = true; }

void SP200AClient::wifi_scan_start() {
  if (!this->scan_handler_registered_) {
    esp_event_handler_instance_t inst;
    esp_event_handler_instance_register(WIFI_EVENT, WIFI_EVENT_SCAN_DONE, &scan_done_handler, this,
                                        &inst);
    this->scan_handler_registered_ = true;
  }
  wifi_scan_config_t cfg{};
  const esp_err_t err = esp_wifi_scan_start(&cfg, false /* non-blocking */);
  if (err != ESP_OK) {
    ESP_LOGW(TAG, "wifi scan start failed: %s", esp_err_to_name(err));
    this->scan_options_ = "(scan failed - retry)";
    this->scan_fresh_ = true;
  } else {
    ESP_LOGI(TAG, "wifi scan started");
    this->scan_options_ = "(scanning...)";
    this->scan_fresh_ = true;
  }
}

void SP200AClient::collect_scan_results_() {
  uint16_t n = 20;
  wifi_ap_record_t recs[20];
  if (esp_wifi_scan_get_ap_records(&n, recs) != ESP_OK) {
    this->scan_options_ = "(scan failed - retry)";
    this->scan_fresh_ = true;
    return;
  }
  std::string opts;
  for (int i = 0; i < n; i++) {
    const char *ssid = (const char *) recs[i].ssid;
    if (strncmp(ssid, "SonarPhone_", 11) != 0)
      continue;
    if (opts.find(ssid) != std::string::npos)
      continue;  // dedupe multi-BSSID
    if (!opts.empty())
      opts += "\n";
    opts += ssid;
  }
  this->scan_options_ = opts.empty() ? "(no SonarPhone found)" : opts;
  this->scan_fresh_ = true;
  ESP_LOGI(TAG, "scan done: %u APs, sonar options: %s", (unsigned) n, this->scan_options_.c_str());
}

bool SP200AClient::wifi_scan_fresh() {
  if (!this->scan_fresh_)
    return false;
  this->scan_fresh_ = false;
  return true;
}

std::string SP200AClient::wifi_scan_options() { return this->scan_options_; }

void SP200AClient::wifi_adopt(const std::string &ssid) {
  if (ssid.rfind("SonarPhone_", 0) != 0)
    return;  // placeholder rows ("(scanning...)" etc.) are not adoptable
  ESP_LOGI(TAG, "adopting sonar AP \"%s\" (persisted)", ssid.c_str());
  wifi::global_wifi_component->save_wifi_sta(ssid, "12345678");
}

// ============================================================ Diagnostics

/** UDP :19998 — reply to any datagram with one status line. Needs only a
 *  single loop iteration, so it works when the TCP servers are starved. */
void SP200AClient::diag_responder_() {
  if (this->diag_sock_ < 0) {
    int s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s < 0)
      return;
    int flags = ::fcntl(s, F_GETFL, 0);
    ::fcntl(s, F_SETFL, flags | O_NONBLOCK);
    struct sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(19998);
    a.sin_addr.s_addr = INADDR_ANY;
    if (::bind(s, (struct sockaddr *) &a, sizeof(a)) < 0) {
      ::close(s);
      return;
    }
    this->diag_sock_ = s;
  }
  uint8_t rx[8];
  struct sockaddr_in from{};
  socklen_t fl = sizeof(from);
  int n = ::recvfrom(this->diag_sock_, rx, sizeof(rx), 0, (struct sockaddr *) &from, &fl);
  if (n <= 0)
    return;
  char out[200];
  snprintf(out, sizeof(out),
           "up=%us gap=%ums rend=%ums heap=%uk big=%uk psram=%uk lvused=%uk ring=%d",
           (unsigned) (millis() / 1000), (unsigned) this->diag_worst_prev_,
           (unsigned) this->diag_render_ms_,
           (unsigned) (heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
           (unsigned) (heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL) / 1024),
           (unsigned) (heap_caps_get_free_size(MALLOC_CAP_SPIRAM) / 1024),
           (unsigned) 0, this->ring_count_);
  ::sendto(this->diag_sock_, out, strlen(out), 0, (struct sockaddr *) &from, fl);
}

// ============================================================ Demo synth

// Faithful port of the Android BridgeService.demoLoop() synth: slow 94 s
// depth cycle, multiple wandering fish with lifespans, a bottom-hardness
// cycle (bright + long sub-bottom glow when hard, thin shell when soft),
// and a second echo at ~2x depth over hard bottom (the Deeper cues).
void SP200AClient::demo_step_() {
  const uint32_t now = millis();
  if (now - this->last_demo_ < 200)  // 5 columns/s, like the app
    return;
  this->last_demo_ = now;
  this->demo_t_ += 0.2;
  const double t = this->demo_t_;

  auto rndu = [this]() -> uint32_t {  // xorshift32
    uint32_t x = this->demo_rng_;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    this->demo_rng_ = x;
    return x;
  };
  auto rndi = [&](int lo, int hi) -> int {  // [lo, hi)
    return lo + (int) (rndu() % (uint32_t) (hi - lo));
  };
  auto rndd = [&](double lo, double hi) -> double {
    return lo + (hi - lo) * (rndu() / 4294967296.0);
  };

  const double depth = 8.0 + 4.0 * std::sin(t / 15.0) + rndd(-0.05, 0.05);

  // fish population: spawn ~1/40 frames, wander, age out
  if (rndi(0, 40) == 0 && depth > 3.0) {
    for (int f = 0; f < MAX_DEMO_FISH; f++) {
      if (this->demo_fish_life_[f] <= 0) {
        this->demo_fish_depth_[f] = (float) rndd(1.5, depth - 1.0);
        this->demo_fish_life_[f] = rndi(15, 60);
        this->demo_fish_strength_[f] = rndi(150, 240);
        break;
      }
    }
  }
  for (int f = 0; f < MAX_DEMO_FISH; f++) {
    if (this->demo_fish_life_[f] <= 0)
      continue;
    this->demo_fish_depth_[f] += (float) rndd(-0.08, 0.08);
    this->demo_fish_life_[f]--;
    if (this->demo_fish_depth_[f] >= depth || this->demo_fish_depth_[f] < 0.5f)
      this->demo_fish_life_[f] = 0;
  }

  uint8_t echo[SAMPLES];
  for (int i = 0; i < SAMPLES; i++)
    echo[i] = (uint8_t) rndi(0, 26);  // noise floor
  for (int i = 0; i < 6; i++)
    echo[i] = (uint8_t) (110 + rndi(0, 70));  // surface clutter

  const int bottom = (int) (depth * SAMPLES_PER_M);
  // hardness cycles so the white-line band visibly varies:
  // hard = bright return with a long tail, soft = dim and fast-fading
  const double hard = 0.5 + 0.5 * std::sin(t / 8.0);
  const double peak = 195.0 + 60.0 * hard;
  const double decay = 8.5 - 6.0 * hard;
  const int band_end = std::min(bottom + 45, SAMPLES);
  for (int i = bottom; i < band_end; i++) {
    if (i < 0)
      continue;
    int v = (int) (peak - (i - bottom) * decay + rndi(0, 12));
    echo[i] = (uint8_t) (v < 24 ? 24 : (v > 255 ? 255 : v));
  }
  // sub-bottom tail: hard bottoms keep echoing far down (thick glow)
  double tail = peak - 45.0 * decay;
  for (int i = band_end; i < SAMPLES && tail > 18.0; i++, tail -= 0.35) {
    const int v = (int) (tail + rndi(0, 14));
    if (v > echo[i])
      echo[i] = (uint8_t) clamp255(v);
  }
  // hard bottoms bounce a second return at ~2x depth
  const int second = bottom * 2;
  if (hard > 0.55 && second < SAMPLES) {
    for (int i = second; i < std::min(second + 22, SAMPLES); i++) {
      const int v = (int) (peak - 130.0 - (i - second) * 5.0 + rndi(0, 10));
      if (v > echo[i])
        echo[i] = (uint8_t) clamp255(v);
    }
  }
  for (int f = 0; f < MAX_DEMO_FISH; f++) {
    if (this->demo_fish_life_[f] <= 0)
      continue;
    const int fi = (int) (this->demo_fish_depth_[f] * SAMPLES_PER_M);
    for (int j = -3; j <= 3; j++) {
      const int k = fi + j;
      if (k >= 0 && k < SAMPLES) {
        const int v = this->demo_fish_strength_[f] - std::abs(j) * 35;
        if (v > echo[k])
          echo[k] = (uint8_t) v;
      }
    }
  }

  this->depth_m_ = (float) depth;
  this->temp_c_ = (float) (18.0 + std::sin(t / 90.0));
  this->battery_v_ = (float) (12.4 + rndd(-0.03, 0.03));
  this->connected_ = true;
  this->last_data_ = now;

  this->update_auto_range_(depth);
  this->push_column_(echo, SAMPLES, depth);

  if (now - this->last_publish_ > 500) {
    this->last_publish_ = now;
#ifdef USE_SENSOR
    if (this->depth_sensor_ != nullptr)
      this->depth_sensor_->publish_state(depth);
    if (this->temp_sensor_ != nullptr)
      this->temp_sensor_->publish_state(this->temp_c_);
    if (this->battery_sensor_ != nullptr)
      this->battery_sensor_->publish_state(this->battery_v_);
#endif
  }
}

// ============================================================ Controls

void SP200AClient::range_up() {
  this->auto_range_ = false;
  if (this->range_idx_ < RANGE_STEPS_N - 1)
    this->range_idx_++;
  this->range_m_ = RANGE_STEPS_M[this->range_idx_];
  this->full_redraw_();
}

void SP200AClient::range_down() {
  this->auto_range_ = false;
  if (this->range_idx_ > 0)
    this->range_idx_--;
  this->range_m_ = RANGE_STEPS_M[this->range_idx_];
  this->full_redraw_();
}

void SP200AClient::range_auto() {
  this->auto_range_ = true;
  this->fits_smaller_since_ = 0;
}

void SP200AClient::set_style(int s) {
  this->style_ = s;
  this->apply_style_();
  this->full_redraw_();
}

void SP200AClient::set_gain(float g) {
  this->gain_user_ = g;
  this->full_redraw_();
}

void SP200AClient::set_noise_filter(int n) {
  this->noise_ = n;
  this->full_redraw_();
}

void SP200AClient::set_surface_clarity(int n) {
  this->clarity_ = n;
  this->full_redraw_();
}

void SP200AClient::set_fish_markers(int m) {
  this->fish_mode_ = m;
  this->full_redraw_();
}

void SP200AClient::set_feet_runtime(bool f) {
  this->feet_ = f;
  this->full_redraw_();
}

void SP200AClient::set_beam_runtime(uint8_t b) {
  this->beam_ = b;
  if (this->state_ == State::RUN && !this->demo_) {
    this->send_fc_();  // apply immediately
    this->last_tx_ = millis();
  }
}

void SP200AClient::set_demo_runtime(bool d) {
  if (d == this->demo_)
    return;
  this->demo_ = d;
  this->enter_discover_();
  this->ring_head_ = -1;
  this->ring_count_ = 0;
  this->have_echo_ = false;
  this->depth_m_ = 0.0f;
  this->clear_canvas_();
  this->draw_ascope_();
}

}  // namespace sp200a
}  // namespace esphome
