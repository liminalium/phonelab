# Brief for Claude Design (claude.ai/design)

Paste the prompt below into a new Claude Design project. It describes the app,
the screens, and the real data, so the prototypes start from facts rather than
placeholders. Add one of the three directions at the end, or ask for all three.

---

Design the Android app **PhoneLab**: it turns a phone into a recording,
measurement and automation lab. One power user, phone in hand, often while
using other apps. Target phone: moto g55, 1080×2400, Android 15. Build it as
an interactive mobile prototype, 412×915 dp.

**Structure (bottom navigation, four places):**
1. **Lab / Home**: the tools this phone supports, grouped as Capture, Motion,
   Environment, Radios, Device, Output, Health, Data (52 tools; 46 work on
   this phone). Quick starts for Record, Snapshot, Sound meter. Search.
2. **Record**: records the screen, the microphone and floating front and back
   camera bubbles into one MP4 while you use other apps. Toggles: screen,
   mic, front, back, sensor overlay, app sound. Quality 720p / 1080p / max.
   Stop from a notification. A list of past recordings.
3. **Snapshots / Library**: a 5-second "world snapshot" captures everything on
   one clock (place, satellites, Wi-Fi, Bluetooth, sound level, light,
   motion, photos from both cameras) into one encrypted file. Timeline and
   map; compare two snapshots of the same place.
4. **Agent**: chat with an AI agent that can call the phone's tools (read a
   sensor, scan Wi-Fi, take a snapshot, start recording). Tool calls appear
   as cards with the data they returned.

**One shared screen for live data:** every sensor or radio tool uses the same
layout: a live chart, current values, rate, a spectrum where it makes sense,
and actions (log to file, overlay on the recording, export CSV, ask the agent).

**Real values to use (moto g55):** accelerometer and gyroscope 395/398 Hz,
magnetometer 50 Hz, heading 271°, light 340 lx, sound 52 dB(A), 48 satellites
(BeiDou 15, Galileo 13, GPS 11, GLONASS 8), fix in 11 s, 13 Wi-Fi networks,
49 Bluetooth devices, battery 43 % at −1.16 A, 21 °C, 4994 mAh, 124 cycles.
No barometer, UWB or infrared.

**Constraints:** Android Material 3 components underneath (it's built in
Jetpack Compose); one-handed use; numbers in a monospace face; works in light
and dark; status visible at a glance (recording, which sensors are live).

**Direction** (pick one):
- *Instrument panel*: dark, dense, live sparkline on every tile, monospace
  readouts, one accent colour per tool group, like lab equipment.
- *Field notebook*: warm paper tones, a serif for headings, a timeline of
  everything captured today; tools come out on demand from a Capture button.
- *Material You*: tonal surfaces, big rounded tiles, friendly and calm.

---

Mockups of all three, rendered from `design/mockups`, are in `design/renders`.
