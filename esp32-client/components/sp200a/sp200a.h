#pragma once

// SP200A T-Box UDP client for ESPHome (esp-idf) + LVGL waterfall renderer.
//
// Protocol ported near-verbatim from the Android bridge's Sp200a.kt /
// BridgeService.kt state machine; rendering ported from SonarView.kt.
// See sp200a-nmea-bridge-spec.md in the parent repo for the byte-level spec.
//
// This is a *client only*: it joins the sonar AP, discovers the T-Box, streams
// REDYFC frames, publishes depth/temp/battery as sensors, and draws the echo
// waterfall + A-scope + depth scale to an LVGL canvas. No NMEA bridge half.

#include "esphome/core/component.h"
#include "esphome/core/log.h"

#ifdef USE_SENSOR
#include "esphome/components/sensor/sensor.h"
#endif

#include <lvgl.h>

#include <array>
#include <cmath>
#include <cstdint>
#include <string>

namespace esphome {
namespace sp200a {

class SP200AClient : public Component {
 public:
  // --- config setters (called from generated to_code) ---
  void set_host(const std::string &h) { this->host_ = h; }
  void set_port(uint16_t p) { this->port_ = p; }
  void set_feet(bool f) { this->feet_ = f; }
  void set_beam(uint8_t b) { this->beam_ = b; }
  void set_demo(bool d) { this->demo_ = d; }
  // forked mipi_rgb display (stored untyped to keep this header
  // include-light); enables LVGL DIRECT rendering into the panel framebuffers
  void set_direct_display(void *d) { this->direct_display_ = d; }
#ifdef USE_SENSOR
  void set_depth_sensor(sensor::Sensor *s) { this->depth_sensor_ = s; }
  void set_temp_sensor(sensor::Sensor *s) { this->temp_sensor_ = s; }
  void set_battery_sensor(sensor::Sensor *s) { this->battery_sensor_ = s; }
#endif

  // --- Component ---
  void setup() override;
  void loop() override;
  void dump_config() override;
  float get_setup_priority() const override { return setup_priority::LATE; }

  // --- range controls (call from LVGL button on_click lambdas) ---
  void range_up();
  void range_down();
  void range_auto();

  // --- sonar-AP discovery (settings page: scan, list, adopt) ---
  void wifi_scan_start();           // kick an async scan (safe while connected)
  bool wifi_scan_fresh();           // true once when new results are ready
  std::string wifi_scan_options();  // newline-joined SonarPhone_* SSIDs
  void wifi_adopt(const std::string &ssid);  // persist + connect (pwd 12345678)
  void notify_scan_done();                   // called from the WiFi event task

  // --- runtime display settings (ported from the Android Units object) ---
  void set_style(int s);            // 0 = modern, 1 = classic
  void set_gain(float g);           // user gain multiplier (0.5 .. 2.0)
  void set_noise_filter(int n);     // 0..3 (off/low/med/high)
  void set_surface_clarity(int n);  // 0..3 (off/low/med/high)
  void set_fish_markers(int m);     // 0 off, 1 symbols, 2 symbols+depth
  void set_feet_runtime(bool f);
  void set_demo_runtime(bool d);
  void set_beam_runtime(uint8_t b);

  // Hand over the YAML-declared canvas widget (call from on_boot lambda):
  //   id(sonar).set_canvas((lv_obj_t *) id(waterfall_cv));
  // Only stores the pointer — the actual bind (buffer lookup, ring alloc,
  // overlay creation) runs on the first loop() iteration, never inside
  // on_boot: LVGL calls from boot context wedge the main loop.
  void set_canvas(lv_obj_t *canvas) { this->pending_canvas_ = canvas; }

  // --- live values, for label lambdas / diagnostics ---
  float depth_m() const { return this->depth_m_; }
  float temp_c() const { return this->temp_c_; }
  float battery_v() const { return this->battery_v_; }
  bool is_connected() const { return this->connected_; }
  bool feet() const { return this->feet_; }
  bool demo() const { return this->demo_; }
  // stall diagnostics (worst loop-to-loop gap in the last window, render cost)
  uint32_t diag_worst_gap_ms() const { return this->diag_worst_prev_; }
  uint32_t diag_render_ms() const { return this->diag_render_ms_; }
  std::string range_label() const;  // "AUTO" or e.g. "15 m" / "50 ft"
  const char *phase_str() const { return this->state_ == State::RUN ? "RUN" : "DISCOVER"; }

  static constexpr int SAMPLES_N = 758;  // echo samples per REDYFC column

 protected:
  // ---- protocol ----
  enum class State { DISCOVER, RUN };

  void ensure_socket_();
  void drain_socket_();               // read + dispatch all pending datagrams
  void send_fx_();                    // discovery request
  void send_fc_();                    // stream/keepalive request (needs mac_)
  void handle_reply_(const uint8_t *d, int len);
  void enter_discover_();
  static uint16_t additive_checksum_(const uint8_t *b, int n);

  // demo: synthesize REDYFC-like columns with no T-Box present
  void demo_step_();

  // ---- rendering ----
  // Data path is decoupled from the render path: push_column_() stores columns
  // at stream rate (~12 Hz); render_pending_() batches them onto the canvas at
  // ~4 Hz with ONE invalidate — a full-canvas recomposite per column would
  // saturate the CPU (PSRAM bandwidth is shared with the RGB panel DMA).
  void build_luts_();
  void apply_style_();  // point active LUTs/bg at the selected palette
  void push_column_(const uint8_t *echo, int echo_len, double depth_m);
  void render_pending_();
  void update_row_map_();  // precomputed y -> sample index + clarity factor
  void update_auto_range_(double depth_m);
  float detect_fish_(const uint8_t *echo, int echo_len, double depth_m);
  bool recent_fish_near_(float depth_m);
  void draw_col_(int x, const uint8_t *samples, float depth_m);
  void draw_fish_blob_(int x, float fish_depth_m);
  void draw_fish_label_(int x, float fish_depth_m);  // canvas-layer text
  void draw_ascope_();                               // live strip, right edge
  void rebuild_scale_();  // depth ticks/labels as LVGL overlay objects
  void full_redraw_();    // re-render all history at the current range
  void clear_canvas_();
  int plot_w_() const;  // canvas width minus the A-scope strip

  // config
  std::string host_{"192.168.1.1"};
  uint16_t port_{5000};
  bool feet_{false};
  uint8_t beam_{0x08};
  bool demo_{false};

  // display settings (Android Units defaults)
  int style_{0};
  float gain_user_{1.0f};
  int noise_{0};
  int clarity_{0};
  int fish_mode_{1};

  // demo synth state (ported from BridgeService.demoLoop)
  uint32_t last_demo_{0};
  double demo_t_{0.0};
  uint32_t demo_rng_{0x1234567};
  static constexpr int MAX_DEMO_FISH = 8;
  float demo_fish_depth_[MAX_DEMO_FISH]{};
  int demo_fish_life_[MAX_DEMO_FISH]{};
  int demo_fish_strength_[MAX_DEMO_FISH]{};

#ifdef USE_SENSOR
  sensor::Sensor *depth_sensor_{nullptr};
  sensor::Sensor *temp_sensor_{nullptr};
  sensor::Sensor *battery_sensor_{nullptr};
#endif

  // socket / state machine
  int sock_{-1};
  State state_{State::DISCOVER};
  uint8_t mac_[6]{0, 0, 0, 0, 0, 0};
  bool have_mac_{false};
  uint32_t last_tx_{0};
  uint32_t last_data_{0};
  uint32_t last_publish_{0};
  bool connected_{false};

  // live values
  float depth_m_{0.0f};
  float temp_c_{NAN};
  float battery_v_{NAN};

  void bind_canvas_();  // deferred canvas bind, first loop() only
  void enable_direct_render_();  // switch LVGL to panel-framebuffer DIRECT mode

  void *direct_display_{nullptr};

  // ---- LVGL canvas / waterfall (LVGL 9: RGB565 draw buf, stride-aware) ----
  lv_obj_t *pending_canvas_{nullptr};
  lv_obj_t *canvas_{nullptr};
  uint16_t *buf16_{nullptr};
  int w_{0};          // canvas width  (px)
  int h_{0};          // canvas height (px)
  int stride_px_{0};  // row stride in *pixels* (from draw buf header)

  // palettes: modern (Deeper-style) + classic navy rainbow; active = pointers
  uint16_t lut_water_m_[256];
  uint16_t lut_bottom_m_[256];
  uint16_t lut_classic_[256];
  uint16_t bg_modern_{0}, bg_classic_{0};
  const uint16_t *lut_water_{nullptr};
  const uint16_t *lut_bottom_{nullptr};
  uint16_t bg_color_{0};

  // data-space history ring (PSRAM): one echo column + depth + fish mark per
  // on-screen pixel column, so a range change re-renders instead of wiping
  // (mirrors the Android EchoHistory + SonarView rings).
  uint8_t *echo_ring_{nullptr};  // ring_cap_ * SAMPLES_N bytes
  float *depth_ring_{nullptr};
  float *fish_ring_{nullptr};
  int ring_cap_{0};    // == plot width
  int ring_head_{-1};  // newest column index
  int ring_count_{0};

  // latest echo column for the A-scope strip
  uint8_t last_echo_[SAMPLES_N]{};
  bool have_echo_{false};

  // batched rendering
  int pending_cols_{0};
  uint32_t last_render_{0};

  // per-row precomputed maps (max panel height 480): base sample index +
  // Catmull-Rom tap weights (SonarView's 4x intensity-space upsample,
  // generalised to arbitrary row/sample ratios) + surface-clarity factor
  static constexpr int MAX_H = 480;
  uint16_t row_sample_[MAX_H]{};
  float row_w_[MAX_H][4]{};
  float row_clar_[MAX_H]{};

  // depth-scale overlay (LVGL objects; they must not scroll with the canvas)
  static constexpr int MAX_TICKS = 8;
  lv_obj_t *tick_line_[MAX_TICKS]{};
  lv_obj_t *tick_lbl_[MAX_TICKS]{};
  lv_obj_t *unit_lbl_{nullptr};

  // range state (metres) — mirrors SonarView
  bool auto_range_{true};
  int range_idx_{2};
  double range_m_{10.0};
  uint32_t fits_smaller_since_{0};

  // fish dedup ring (last N columns' detected depth, -1 = none)
  std::array<float, 16> recent_fish_{};
  int fish_ring_pos_{0};

  // diagnostics
  uint32_t diag_last_iter_{0};
  uint32_t diag_worst_{0};
  uint32_t diag_worst_prev_{0};
  uint32_t diag_last_report_{0};
  uint32_t diag_render_ms_{0};

  // UDP status responder (works even when TCP servers are starved): any
  // datagram to :19998 gets a one-line status reply
  int diag_sock_{-1};
  void diag_responder_();

  // sonar-AP scan state (flag set from the WiFi event task, drained in loop)
  volatile bool scan_done_flag_{false};
  bool scan_fresh_{false};
  bool scan_handler_registered_{false};
  std::string scan_options_{"(open settings to scan)"};
  void collect_scan_results_();
};

}  // namespace sp200a
}  // namespace esphome
