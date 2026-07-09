# Development Log

- 2026-07-07: Added simulation training parity for buffered reconnect results and MCU serial tracking in firmware, app logs, and exports.
- 2026-07-07: Renamed shot log fields for disruptor use, added loading/projectile/pass-fail/notes, and replaced CI display with calibration-informed Guaranteed Accuracy Envelope.
- 2026-07-07: Added shot type classification, field help popups, and simplified/larger setup and dashboard instructions.
- 2026-07-07: Renamed the app to Chrono Logger, enlarged text, clarified GAE/channel calibration help, corrected disruptor placeholders, and made photo selection toggleable.
- 2026-07-08: Expanded the GAE info popup with the acronym meaning, a velocity-range example, and the factors that widen or narrow the envelope.
- 2026-07-08: Added visible automatic BLE reconnect retries and restored simulated-mode photo thumbnails after camera or gallery returns.
- 2026-07-08: Added device battery voltage reporting, app battery level display, and low-battery warning behavior.
- 2026-07-09: Added high port-baseline capacitance warnings and calibration cancel paths back to the connected dashboard.
- 2026-07-09: Updated port baseline readings to show capacitance with centered port labels and prevented shot-type buttons from wrapping.
- 2026-07-09: Fixed unit formatting so whole-number capacitance and distance values keep significant trailing zeros.
- 2026-07-09: Compacted the dashboard header for narrow phones and widened shot-type button text handling.
- 2026-07-09: Calibrated capacitance estimates from Fluke-verified 30.0 nF and 34.2 nF loads.
- 2026-07-09: Replaced user-facing capacitance estimates with raw RC delay/signature values and channel mismatch percent.
