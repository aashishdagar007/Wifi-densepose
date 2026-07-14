# ESP32-WROOM-32 + Android Presence Fusion App — Development Plan

## 1. What you're building

An Android app that:
1. Talks to an ESP32-WROOM-32 over **USB-OTG (wired serial)**
2. Gets the ESP32 to report **CSI-based motion/presence** on the WiFi network your phone is connected to
3. Independently scans that same network for **connected devices** (ARP/mDNS)
4. **Fuses** both signals into a single "who and what is actually here" view, with alerts for anomalies

This is essentially a lightweight physical-security correlation tool — similar in spirit to your OmniSecuritySuite work, but for physical presence instead of endpoint telemetry.

---

## 2. Architecture overview

```
┌─────────────────────┐         USB-OTG (Serial/UART)        ┌────────────────────┐
│   ESP32-WROOM-32     │◄─────────────────────────────────────►│  Android Phone     │
│   - WiFi STA          │                                        │  - USB Serial lib  │
│   - CSI capture        │                                        │  - Parses CSI frames│
│   - Sends CSI frames   │                                        │  - Runs ARP scan    │
│     over UART           │                                        │    (same WiFi)      │
└─────────────────────┘                                        │  - Fusion engine    │
                                                                  │  - UI + alerts       │
                                                                  │  - Local SQLite log  │
                                                                  └────────────────────┘
```

Key decision: the ESP32 does **only** CSI capture + lightweight framing over UART. All fusion, ARP scanning, and heavy processing happens on the **phone**, since it's dual/quad-core and already on the network. This sidesteps the "single-core ESP32 can't do heavy DSP" limitation you ran into with the RuView project notes.

---

## 3. Phase 0 — Environment setup (Week 0)

- [ ] Install ESP-IDF (v5.2+) — use Docker (`espressif/idf:v5.2`) to avoid toolchain pain, since you're already comfortable with Docker from your other projects
- [ ] Confirm your WROOM-32 board, get its exact chip revision (`esptool.py chip_id`)
- [ ] Set up Android Studio + Flutter (recommended — you already have a working Flutter+Kotlin pipeline from Nemotron Code) or native Kotlin if you want tighter USB serial control
- [ ] Pick a USB-serial library:
  - Flutter: `usb_serial` or `flutter_libserialport` (via platform channel)
  - Native Android: `usb-serial-for-android` (mik3y) — most battle-tested for this exact use case
- [ ] Buy/confirm a USB-OTG adapter + micro-USB or USB-C cable to the WROOM-32's UART

---

## 4. Phase 1 — ESP32 firmware: raw CSI over UART (Weeks 1–2)

Goal: get raw, framed CSI data flowing over USB serial to a laptop first (easier to debug than mobile).

- [ ] Start from `espressif/esp-csi`'s `csi_recv` example — official, classic-ESP32-compatible
- [ ] Enable CSI in menuconfig: `Component config → Wi-Fi → WiFi CSI`
- [ ] Modify the CSI callback to:
  - Extract amplitude + phase per subcarrier
  - Pack into a small binary frame (reuse a header structure similar to what RuView uses: magic bytes, node ID, subcarrier count, RSSI, sequence number, I/Q pairs)
  - Write the frame to UART instead of (or in addition to) WiFi/UDP
- [ ] Verify with `idf.py monitor` that frames are streaming and look sane (RSSI in a believable range, subcarrier count matches, no dropped/garbled frames)
- [ ] Set a sustainable sample rate — 10–20 Hz is enough for presence/motion detection and keeps UART bandwidth manageable

Reference tools worth pulling from:
- `espressif/esp-csi` (official, has a `wifi_sensing_demo` with presence detection logic you can study)
- `StevenMHernandez/ESP32-CSI-Tool` (simpler, classic-ESP32-first, CSV output — good for validating your understanding before you build the binary framing)

---

## 5. Phase 2 — Android: USB-OTG serial link (Weeks 2–3)

- [ ] Implement USB device detection + permission request flow (Android's USB host API requires runtime permission per device)
- [ ] Open the serial connection at your chosen baud rate (921600 or higher recommended — same reasoning as ESP32-CSI-Tool's notes on baud rate mattering for sample rate)
- [ ] Implement frame parsing: read the UART byte stream, sync on your magic-byte header, extract subcarrier amplitude/phase arrays
- [ ] Build a simple in-app debug view: raw amplitude waveform, RSSI, frame rate — this is your sanity check before adding any "intelligence"
- [ ] Handle reconnect/disconnect gracefully (cable wiggle, app backgrounding — Android will suspend USB access when the app isn't foreground unless you use a foreground service)

---

## 6. Phase 3 — Motion/presence detection logic (Weeks 3–4)

This runs entirely on the phone, in Kotlin/Dart, not on the ESP32.

- [ ] Compute a rolling variance/amplitude-change metric across subcarriers (this is what most lightweight WiFi sensing projects use — it's the same "variance threshold" approach RuView's own docs described as a heuristic, not deep learning)
- [ ] Auto-calibrate a baseline over the first 30–60 seconds of "known empty room" data
- [ ] Trigger a presence/motion event when variance exceeds the calibrated threshold for a sustained window (avoids false positives from single noisy frames)
- [ ] Log events with timestamp to local SQLite

Don't over-invest in a neural pose model at this stage — start with the heuristic, since it gets you 80% of the value (presence detection) for a fraction of the effort. You can add a trained model later if you want actual pose/vitals.

---

## 7. Phase 4 — Network device discovery (Weeks 4–5)

This runs on the phone, using its own WiFi connection — no ESP32 involvement needed here.

- [ ] Get the phone's current subnet (e.g., via `ConnectivityManager` + `WifiManager`)
- [ ] Implement ARP scanning or use a lightweight ping-sweep + ARP table read
  - Flutter: shell out to a bundled scanning approach, or use a plugin like `network_info_plus` for subnet info + a custom ARP implementation
  - Native: raw sockets or `/proc/net/arp` reading (works without root on many devices, since it's just reading the kernel ARP cache after a ping sweep)
- [ ] For each discovered device, resolve: IP, MAC, vendor (via OUI lookup — bundle a local OUI database, don't hit an external API every scan), hostname if mDNS responds
- [ ] Maintain a "known devices" allowlist (your own phone, laptop, etc.) that you populate manually on first run

---

## 8. Phase 5 — Fusion engine (Weeks 5–6)

- [ ] Define fusion states:
  - Known devices connected + no motion → idle/empty
  - Known devices connected + motion → expected presence
  - No known devices + motion detected → **flag**: someone present without a recognized device
  - New/unknown MAC joins + motion spike within a correlation window → **flag**: new device + physical arrival correlated
- [ ] Build a simple event bus/state machine combining the two async streams (CSI events, network scan events) with timestamps
- [ ] Push flagged events to a notification + persistent log

---

## 9. Phase 6 — UI and polish (Weeks 6–7)

- [ ] Dashboard: live CSI waveform/variance graph, device list with known/unknown tagging, event timeline
- [ ] Settings: calibration reset, known-device management, alert thresholds, baud rate/port selection
- [ ] Foreground service + persistent notification so scanning continues while backgrounded
- [ ] Local-only data — no cloud sync unless you explicitly want it (keeps this squarely in "personal network security tool" territory)

---

## 10. Phase 7 — Testing and hardening (Week 8)

- [ ] Test false-positive sources: fans, microwaves, pets, WiFi channel interference — document and tune thresholds
- [ ] Test USB-OTG reconnect resilience (cable disconnect mid-session)
- [ ] Test on your actual home network topology, multiple rooms/distances from the ESP32
- [ ] Battery/thermal check — USB host mode + continuous WiFi scanning will drain faster than normal use

---

## 11. Stretch goals (later)

- Second ESP32 node for multistatic coverage (better spatial resolution)
- Trained pose/keypoint model instead of the variance heuristic, once you've validated the pipeline works
- Export event log in the same structured JSON format style as OmniSecuritySuite for consistency across your projects
- CI/CD via GitHub Actions for the Android build, mirroring your Nemotron Code setup

---

## Tech stack summary

| Layer | Recommendation |
|---|---|
| ESP32 firmware | ESP-IDF v5.2+, C, `esp-csi` as base |
| Android app | Flutter (reuse your Nemotron Code experience) |
| USB serial | `usb_serial` (Flutter) or `usb-serial-for-android` (native fallback via platform channel) |
| Local storage | SQLite (`sqflite` in Flutter) |
| Network scan | `network_info_plus` + custom ARP/ping sweep |

**Legal/ethical note:** since this only monitors your own home network and devices you own or have consent to monitor, you're in clean territory. Keep it that way if you ever demo or extend this — the moment it's pointed at a network or space that isn't yours, it becomes a different (and legally risky) tool.

Total estimate: **~8 weeks** part-time, could compress to 4–5 if you move fast given your existing Flutter/ESP32/security background.
