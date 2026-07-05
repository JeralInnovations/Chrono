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

```
XIAO nRF52840 (USB connector facing up, left column top-to-bottom)

        ┌─────USB─────┐
   D0 ──┤ 0        5V ├──
   D1 ──┤ 1       GND ├──
        │ 2       3V3 ├──┬────────────┐
        │ ...         │  │            │
        └─────────────┘  │            │
                         │            │
  Speaker terminal 1:    │            │
    (+) red clip ── 3V3 ─┘            │
    (–) blk clip ── D0  ← START       │
                                      │
  Speaker terminal 2:                 │
    (+) red clip ── 3V3 ──────────────┘
    (–) blk clip ── D1  ← STOP
```

Mount one two-port spring-loaded speaker terminal per sensor: one port lead goes
to **3V3**, the other to **D0** (sensor 1) or **D1** (sensor 2). Polarity does not
actually matter electrically — the red/black convention just keeps wiring tidy.

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
   `https://github.com/kdog45ak-a11y/Chrono/releases`
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
2. **Sensor 1 (START)** — the app shows the spring-terminal graphic. Insert the
   START sensor leads into port 1, then trigger the sensor once. The graphic
   glows green and the app acknowledges. Tap *Continue*.
3. **Sensor 2 (STOP)** — same procedure for port 2.
4. **Sensor spacing** — enter the measured distance between the sensors
   (inches by default; mm / cm / ft also available). Velocity is computed from
   this, so measure carefully.
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
   connection (and via the clock chip at the top of the dashboard). If a shot
   happened before the clock was ever synced, its date shows grayed out as
   *"No date"* — tap the pencil to type one in (`yyyy-MM-dd HH:mm`).

Each result shows **ft/s**, **m/s**, and the raw **split in milliseconds**
(microsecond resolution). Results are stored on the phone and survive app restarts.

---

## 5. Design notes & limits

- **Timing accuracy** — the split is captured with GPIO interrupts and `micros()`;
  expect a few microseconds of jitter, which is negligible for split times in the
  millisecond range and up.
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
| Result  | `a5c40004` | read/notify | `id:u16, splitUs:u32, epoch:u32, flags:u8` (LE) |
| Time    | `a5c40005` | write | unix seconds `u32` (LE) |

States: 0 idle · 1/3 verifying sensor 1/2 · 2/4 sensor 1/2 OK · 5 armed · 6 running.
Commands: 1/2 verify sensor 1/2 · 3 arm · 4 disarm · 5 ack result (arg=id) ·
6 cancel · 7 re-send stored results.
