# Chrono — BLE Chronograph

A two-sensor chronograph built on the **Seeed Studio XIAO nRF52840**, controlled by a
native **Android app** over Bluetooth Low Energy.

- Sensor 1 (START) pulls input **D0** high → the clock starts
- Sensor 2 (STOP) pulls input **D1** high → the clock stops
- The split time is measured in **microseconds** with GPIO interrupts
- Results survive BLE disconnects: the device stores up to 16 un-collected results
  and the app reconnects automatically and downloads them

```
Chrono/
├── firmware/Chronograph/Chronograph.ino   ← flash this to the XIAO
├── android/                               ← open this folder in Android Studio
└── README.md
```

---

## 1. Hardware & wiring

Each "sensor" is anything that momentarily **connects a pin to 3.3 V** — a switch,
a make-screen, a break-beam circuit driving a transistor, etc. The pins use the
chip's internal pull-**down** resistors, so they sit at 0 V until a sensor fires.

Each channel has a piezo front-end and a calibration charge path:

```
                        1k                          node
  piezo (+) ───────────/\/\/\────────────┬──────────── D0 (ch 1) / D1 (ch 2)
  piezo (–) ── GND                       │
                                 Schottky│clamps
                          3V3 ──|<|──────┤   (cathode to 3V3: clamps positive)
                          GND ──|>|──────┘   (anode to GND: clamps negative)
                                         │
                        10k 1%           │
  D2 (ch 1) / D3 (ch 2) ──/\/\/\─────────┘   calibration charge path
```

Per channel:

| From | Component | To |
|---|---|---|
| Piezo + | 1 kΩ series resistor | sensor node |
| Sensor node | — | **D0** (ch 1) / **D1** (ch 2) |
| Sensor node | Schottky, anode at node | **3V3** (positive clamp) |
| Sensor node | Schottky, cathode at node | **GND** (negative clamp) |
| **D2** (ch 1) / **D3** (ch 2) | 10 kΩ **1% metal film** | sensor node |
| Piezo – | wire | **GND** |

The 10 k charge resistors are only used for calibration; those pins are held in
true hi-Z during live fire so they don't load the node. Match the two channels —
same piezo type, same clamp diodes, and **the same cable length** (~5 ns/m).

> ⚠️ Use **3V3**, not 5V. The nRF52840 pins are 3.3 V only.

---

## 2. Firmware — flashing the XIAO nRF52840

1. **Install the Arduino IDE** (2.x) from https://www.arduino.cc/en/software
2. **Add the Seeed board package:**
   - *File → Preferences → Additional boards manager URLs*, paste:
     ```
     https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
     ```
   - *Tools → Board → Boards Manager*, search **"seeed nrf52"** and install
     **Seeed nRF52 Boards** (NOT the "mbed-enabled" one — this project uses the
     Bluefruit BLE library that ships with the non-mbed package).
3. **Open the sketch:** `firmware/Chronograph/Chronograph.ino`
4. **Select the board:** *Tools → Board → Seeed nRF52 Boards → Seeed XIAO nRF52840*
   (the "Sense" variant also works).
5. **Plug in the XIAO** over USB-C and select its port under *Tools → Port*.
6. Click **Upload** (→ arrow button).

**If the upload fails or no port appears:** double-tap the tiny RESET button next
to the USB connector quickly (like a mouse double-click). The onboard LED breathes
and a new port appears — that's the bootloader. Select that port and upload again.

When running, the device advertises as **"Chrono"**. The onboard LED turns on
solid while armed/timing.

---

## 3. Android app — installing on your phone

### Option A — install straight from your phone (easiest)

Every push to this GitHub repo automatically builds the app (see the *Actions*
tab). To install it, on your phone's browser:

1. Open the repo's **Releases** page:
   `https://github.com/JeralInnovations/Chrono/releases`
2. Under **Latest app build**, download `app-debug.apk`.
3. Open the downloaded file. Android will warn about installing unknown apps —
   allow it for your browser when prompted, then tap *Install*.

> If a future update refuses to install over the old version ("App not
> installed"), uninstall the old one first. Automated builds are signed with a
> throwaway debug key that can change between builds.

### Option B — build it yourself with Android Studio

You don't need any Android experience; Android Studio does everything.

1. **Install Android Studio** (free) from https://developer.android.com/studio
   — accept the defaults during setup so it installs the Android SDK.
2. *File → Open…* and select the **`Chrono/android`** folder (the folder itself,
   not a file inside it). Click *Trust Project* if asked.
3. Wait for the first **Gradle sync** to finish (bottom status bar — the first one
   downloads a few hundred MB of build tools; later syncs are fast). If prompted to
   accept SDK licenses or install missing SDK components, click accept/install.
4. **Enable USB debugging on your phone:**
   - *Settings → About phone* → tap **Build number** seven times ("You are now a developer!")
   - *Settings → System → Developer options* → turn on **USB debugging**
5. Plug the phone into the computer with USB. On the phone, allow the
   "USB debugging?" prompt.
6. Your phone appears in the device dropdown at the top of Android Studio.
   Press the green **Run ▶** button. The app builds, installs, and launches.

To share the app without a cable: *Build → Build App Bundle(s) / APK(s) → Build APK(s)*,
then copy `android/app/build/outputs/apk/debug/app-debug.apk` to any phone and open
it (allow "install from unknown sources" when asked).

> Command-line note: the project ships without the Gradle wrapper JAR. Android
> Studio doesn't need it, but if you want `gradlew` on the command line, run
> `gradle wrapper` once in `android/` (requires a local Gradle install).

---

## 4. Using it

1. **Connect** — open the app, grant the Bluetooth permission, power the device.
   It appears in the list; tap it.
2. **Port baseline** — with both ports EMPTY, the app measures each bare port's
   electrical signature (an RC sweep through the 10 k calibration resistor).
   Keep the device still during the sweep.
3. **Sensor 1 (START)** — insert the START sensor leads into port 1, then
   trigger the sensor once. The graphic glows green, and the app automatically
   re-measures the channel with the sensor attached — the difference against
   the baseline isolates the cable+sensor load. If the reading looks like an
   empty port, the app warns you the sensor may not be plugged in.
4. **Sensor 2 (STOP)** — same procedure for port 2.
5. **Sensor spacing** — enter the measured distance between the sensors
   (inches by default; mm / cm / ft also available). Velocity is computed from
   this, so measure carefully. At a 6 in gap, 0.02 in of error is 1%.

The dashboard's **Channel calibration** card shows each port's measured load
(≈ pF) and flags whether the two channels are matched; **Recheck** reruns the
loaded sweep any time (sensors attached, undisturbed). Every sweep is appended
to `cal_history.jsonl` in the app's private storage for later analysis.

**Test folders, photos, and manual entries.** All field data lives in
**`Documents/ChronoData/Test_<date>/Shot_NNNN/`** in the phone's public
storage — visible in any file manager, with `shot.json` (the full log) plus
that shot's photos in each folder. Tap the folder name at the top of the
dashboard (or the *Files* button above the results) to open it. On every app
launch you're asked to keep logging into the previous test folder or start a
new one. After setup the app prompts for **setup photos** of the rig; after
each shot you first get a **results screen** listing what just came in, then
the **after photos** prompt. **Log manual entry** (dashboard, or "Manual
logging" on the connect screen — a simplified, device-free view) records a
shot with no chronograph: typed velocity (ft/s or m/s, optional), all detail
blanks, date/time, and the same photo prompts. Tapping an empty date box
autofills the current date and pre-selects the time for quick editing.

**Replacing a consumed sensor** is a guided three-step flow (tap the torn
sensor): fit the wire with nothing armed, press *Sensor attached* to run a
capacitance check against the bare-port baseline (catches a sensor that
isn't actually connected), and only then arm the tap test — so movement
during placement can't cause a trigger.

**Exporting data.** The *Export* button above the results list shares a CSV of
every result (label, date, split, distance, velocities) plus the raw
calibration history (`.jsonl`) through Android's share sheet — email it,
save to Drive, etc.

**Hardware identification & confidence.** The device reports its hardware
revision and timing spec (timer tick, crystal tolerance, front-end jitter)
over BLE, and each result shows a ~95% confidence interval computed from
those numbers plus your rig's measured channel mismatch and a 0.5 mm
gate-spacing assumption. A future hardware revision that reports tighter
numbers (e.g. a TDC front end) automatically shows tighter confidence — no
app update needed. Unidentified (older) firmware is assumed to be rev 1.

**Break-screens are consumable.** Every recorded shot automatically marks both
sensors as consumed on the dashboard's rig diagram (torn amber screens) and
disables ARM. Fit fresh wire, tap the torn sensor in the diagram to retest it —
the app re-verifies the trigger and automatically re-measures the new screen's
electrical load before the next shot can be armed.
5. **Dashboard** — from here you can:
   - **Retest 1 / Retest 2** — re-run either sensor test any time
   - **Change** — edit the sensor spacing
   - **Test label** — optional name applied to the next result (editable later too)
   - **ARM — TEST STANDBY** — the device waits for sensor 1, times the split to
     sensor 2, and reports the result
6. **Walk away if you need to.** If the phone goes out of range while armed, the
   device keeps waiting, times the shot, and stores the result (up to 16). The app
   shows *Reconnecting…* and automatically pulls the results in as soon as you're
   back in range.
7. **Time & date** — the app syncs the phone's clock to the device on every
   connection (and via the clock chip at the top of the dashboard). The device
   runs a **flywheel clock from boot**: every shot is stamped against it, so a
   sync at *any* point during that boot — before or after the shot — applies
   the correct wall-clock time retroactively or forward. Only shots from a
   boot that never got a single sync show a grayed *"No date"* — tap the
   pencil to type one in (`yyyy-MM-dd HH:mm`).
8. **Test details** — the *Next test* card has blanks for **Label**, **Tool**,
   **Target**, and **Distance to target**, applied to the next shot (tool and
   target persist between sessions). The **Result** blank is filled in via the
   pencil on the result card after you've seen the target. Everything exports
   to CSV.

Each result shows **ft/s**, **m/s**, and the raw **split in milliseconds**
(microsecond resolution). Results are stored on the phone and survive app restarts.

---

## 4a. Simulation mode (no hardware needed)

Simulation never requires Bluetooth or its permission: if the permission
screen appears, tap **"Continue in simulation mode"**; otherwise use the
**"Simulation mode — no hardware needed"** button at the bottom of the connect
screen. Either way you can walk the entire UI with no chronograph present — handy for demos or trying the
app before the hardware is built. A fake device drives the same screens the real
one does: the sensor-verify steps auto-acknowledge after a moment, **ARM** produces
a realistic randomized shot for the configured gap, time-sync works, and a **"Drop
signal"** button on the dashboard briefly forces the reconnect state so you can see
that banner. A small **SIM** badge marks the session. Tap *Disconnect* to leave.

## 5. Design notes & limits

- **Timing accuracy** — the split is captured entirely in hardware
  (GPIOTE → PPI → TIMER2 at 16 MHz), so the timestamp is frozen the instant the
  edge arrives, independent of the CPU or the BLE stack. Resolution is 62.5 ns and
  the split is reported in nanoseconds. The crystal (HFXO) is held on while armed so
  the timer runs accurately; a PPI fork disables each channel after its first edge
  so piezo ringing can't overwrite a timestamp. At a 250 µs split (≈3000 fps over
  9 in) the timer contributes well under 0.05% error — your sensor matching and
  gate-spacing measurement dominate, not the electronics.
- **One-shot arming** — after each shot the device disarms itself; tap ARM again
  for the next test. Results are only deleted from the device after the app
  confirms it has stored them.
- **Keep the app open during standby.** Android pauses Bluetooth for backgrounded
  apps after a while; the reconnect logic is most reliable with the app in the
  foreground (screen can be off briefly).
- **Device clock** — the XIAO has no battery-backed clock. Time is re-synced on
  every connection and is lost on power-off, which is exactly why untimed results
  get the grayed-out manual date field.
- **False triggers** — the START input is latched once while armed; noise on the
  wires can still trigger it, so keep sensor leads short or twisted.

## 6. BLE protocol (for reference / hacking)

Service `a5c40001-9d95-4e4c-8c5a-c1d6f2a80de1`

| Characteristic | UUID (…-9d95-4e4c-8c5a-c1d6f2a80de1) | Access | Payload |
|---|---|---|---|
| Status  | `a5c40002` | read/notify | `[state, pendingCount, timeValid]` |
| Control | `a5c40003` | write | `[cmd, argLo, argHi]` |
| Result  | `a5c40004` | read/notify | `id:u16, splitNs:u32, epoch:u32, flags:u8` (LE) |
| Time    | `a5c40005` | write | unix seconds `u32` (LE) |
| Cal     | `a5c40006` | read/notify | `ch:u8, status:u8, n:u16, median:u32, mean:u32, stddev:u32, min:u32` ns (LE) |

States: 0 idle · 1/3 verifying sensor 1/2 · 2/4 sensor 1/2 OK · 5 armed ·
6 running · 7 calibrating.
Commands: 1/2 verify sensor 1/2 · 3 arm · 4 disarm · 5 ack result (arg=id) ·
6 cancel · 7 re-send stored results · 8 calibrate (arg=channel).
