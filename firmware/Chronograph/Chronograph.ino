/*
 * Chrono — BLE chronograph firmware for the nice!nano v2 nRF52840
 * ---------------------------------------------------------------------
 * Two piezo triggers act as START and STOP gates. When armed, a rising
 * edge on SENSOR 1 marks the start and a rising edge on SENSOR 2 marks
 * the stop; the split between them is delivered to the phone over BLE
 * (including after a disconnect and automatic reconnect).
 *
 * TIMING PATH — this is the whole point of the device, so it is done in
 * hardware, not software:
 *
 *   sensor pin -> GPIOTE (edge) -> PPI -> TIMER2 CAPTURE
 *
 * The edge freezes the 16 MHz timer value in hardware the instant it
 * arrives, with zero dependence on the CPU or the BLE SoftDevice. The
 * capture path is identical on both channels, so its fixed latency
 * cancels in (stop - start). A PPI "fork" disables each channel's own
 * capture on its first edge, so piezo ringing cannot overwrite a
 * timestamp. The high-frequency crystal (HFXO) is requested while armed
 * so the timer runs at an accurate 16 MHz (62.5 ns/tick) rather than off
 * the ±1.5% internal RC oscillator.
 *
 * Resolution: 62.5 ns. The split is reported to the phone in NANOSECONDS
 * so none of that resolution is lost to rounding.
 *
 * Wiring (see README.md):
 *   SENSOR 1 (start): piezo output to pin D0, Schottky-clamped to the rails
 *   SENSOR 2 (stop):  piezo output to pin D1, Schottky-clamped to the rails
 *   Pins use the internal pull-DOWN; a trigger is any rising edge past the
 *   input threshold. Match the two channels (piezo, clamp, CABLE LENGTH).
 *
 * Board package: JeralInnovations/nicenano-v2-arduino. The
 * Bluefruit BLE library ships with that core — nothing extra to install.
 */

#include <bluefruit.h>
#include <nrf_gpio.h>
#include <nrf_soc.h>

// ---------------------------------------------------------------- pins
const uint8_t SENSOR1_PIN = 0;   // nice!nano D0 - START
const uint8_t SENSOR2_PIN = 1;   // nice!nano D1 - STOP
const uint8_t CHARGE1_PIN = 2;   // D2 -> 10k 1% -> sensor-1 node (calibration)
const uint8_t CHARGE2_PIN = 3;   // D3 -> 10k 1% -> sensor-2 node (calibration)

// The nice!nano board profile does not expose this LED as an Arduino pin.
// Drive the nRF52840 GPIO directly: P0.15, high-drive, active-high.
const uint32_t STATUS_LED_GPIO = 15;

#define STATUS_LED_BEGIN() do { \
  nrf_gpio_cfg(STATUS_LED_GPIO, NRF_GPIO_PIN_DIR_OUTPUT, \
               NRF_GPIO_PIN_INPUT_DISCONNECT, NRF_GPIO_PIN_NOPULL, \
               NRF_GPIO_PIN_H0H1, NRF_GPIO_PIN_NOSENSE); \
  nrf_gpio_pin_clear(STATUS_LED_GPIO); \
} while (0)

#define STATUS_LED_WRITE(on) nrf_gpio_pin_write(STATUS_LED_GPIO, (on) ? 1 : 0)

// Hardware channels for the capture path. The S140 SoftDevice reserves
// PPI channels 17-31 and groups 4-5, so low numbers are free for us.
const uint8_t GPIOTE_S1 = 0;
const uint8_t GPIOTE_S2 = 1;
const uint8_t PPI_S1     = 0;
const uint8_t PPI_S2     = 1;
const uint8_t PPI_GRP_S1 = 0;
const uint8_t PPI_GRP_S2 = 1;
const uint32_t TIMER_HZ = 16000000UL;   // TIMER2 @ 16 MHz, PRESCALER 0

// ------------------------------------------------------------ protocol
// Device states reported on the STATUS characteristic
enum : uint8_t {
  ST_IDLE       = 0,
  ST_VERIFY1    = 1,  // watching sensor 1 for a test trigger
  ST_VERIFY1_OK = 2,
  ST_VERIFY2    = 3,  // watching sensor 2 for a test trigger
  ST_VERIFY2_OK = 4,
  ST_ARMED      = 5,  // standby: waiting for sensor 1
  ST_RUNNING    = 6,  // sensor 1 tripped, waiting for sensor 2
  ST_CALIBRATING = 7, // measuring a channel's RC signature
};

// Commands written to the CONTROL characteristic: [cmd, argLo, argHi]
enum : uint8_t {
  CMD_VERIFY1 = 1,  // enter sensor-1 test mode
  CMD_VERIFY2 = 2,  // enter sensor-2 test mode
  CMD_ARM     = 3,  // standby — wait for a shot
  CMD_DISARM  = 4,
  CMD_ACK     = 5,  // arg = result id; phone has stored it, delete here
  CMD_CANCEL  = 6,  // back to idle from any state
  CMD_FETCH   = 7,  // re-send every stored (un-acked) result
  CMD_CALIBRATE = 8, // arg = channel (1|2): run an RC calibration sweep
};

// 128-bit UUIDs. Base: A5C4xxxx-9D95-4E4C-8C5A-C1D6F2A80DE1
// (Bluefruit wants the bytes in little-endian order.)
#define CHRONO_UUID(id)                                              \
  { 0xE1, 0x0D, 0xA8, 0xF2, 0xD6, 0xC1, 0x5A, 0x8C,                  \
    0x4C, 0x4E, 0x95, 0x9D,                                          \
    (uint8_t)((id) & 0xFF), (uint8_t)((id) >> 8), 0xC4, 0xA5 }

const uint8_t UUID_SERVICE[16] = CHRONO_UUID(0x0001);
const uint8_t UUID_STATUS [16] = CHRONO_UUID(0x0002);
const uint8_t UUID_CONTROL[16] = CHRONO_UUID(0x0003);
const uint8_t UUID_RESULT [16] = CHRONO_UUID(0x0004);
const uint8_t UUID_TIME   [16] = CHRONO_UUID(0x0005);
const uint8_t UUID_CAL    [16] = CHRONO_UUID(0x0006);
const uint8_t UUID_INFO   [16] = CHRONO_UUID(0x0007);

BLEService        svc     (UUID_SERVICE);
BLECharacteristic chStatus (UUID_STATUS);   // read/notify: [state, pendingCount, timeValid]
BLECharacteristic chControl(UUID_CONTROL);  // write: commands
BLECharacteristic chResult (UUID_RESULT);   // read/notify: Result struct below
BLECharacteristic chTime   (UUID_TIME);     // write: uint32 LE unix seconds
BLECharacteristic chCal    (UUID_CAL);      // read/notify: CalResult struct below
BLECharacteristic chInfo   (UUID_INFO);     // read: HwInfo struct below

// One measurement on the wire. 11 bytes LE — parsed byte-for-byte by the app.
struct __attribute__((packed)) Result {
  uint16_t id;
  uint32_t splitNs;   // time between sensor 1 and sensor 2, NANOSECONDS
  uint32_t epoch;     // unix seconds at the stop trigger, 0 = clock never synced
  uint8_t  flags;     // bit0: epoch is valid
};

// Stored form: the timestamp is a boot-relative millis() reading — a
// "flywheel clock" that runs from power-on. The unix epoch is computed at
// SEND time, so a shot taken before the phone ever synced the clock still
// gets a correct timestamp as long as a sync happens at some point during
// this boot (it normally does, automatically, on every connect).
struct Pending {
  uint16_t id;
  uint32_t splitNs;
  uint32_t bootMs;
};

// Results wait here until the phone ACKs them, so nothing is lost if
// the BLE link drops while a test is in progress.
const uint8_t MAX_PENDING = 16;
Pending  pending[MAX_PENDING];
uint8_t  pendingCount = 0;
uint16_t nextId       = 1;

// One calibration sweep summary. 20 bytes LE — fits a default-MTU notification.
struct __attribute__((packed)) CalResult {
  uint8_t  channel;   // 1 or 2
  uint8_t  status;    // 0 ok, 1 some samples timed out, 2 no valid samples
  uint16_t samples;   // valid samples included in the stats
  uint32_t medianNs;  // median time-to-threshold
  uint32_t meanNs;
  uint32_t stddevNs;
  uint32_t minNs;
};

// Identifies this hardware/firmware to the app so it can apply the right
// accuracy model. A future revision with tighter timing (e.g. a TDC front
// end) reports different numbers here and the app's confidence estimate
// follows automatically — no app update needed.
const uint8_t HW_REV   = 1;
const uint8_t FW_MAJOR = 1;
const uint8_t FW_MINOR = 4;

struct __attribute__((packed)) HwInfo {
  uint8_t  hwRev;
  uint8_t  fwMajor;
  uint8_t  fwMinor;
  uint8_t  reserved;
  uint32_t tickPs;        // timer tick period in picoseconds (62500 = 62.5 ns)
  uint16_t clockPpm;      // crystal tolerance
  uint16_t edgeJitterNs;  // conservative per-edge front-end uncertainty
  uint32_t deviceId0;     // NRF_FICR->DEVICEID[0]
  uint32_t deviceId1;     // NRF_FICR->DEVICEID[1]
};

// ------------------------------------------------------- run-time state
uint8_t  state    = ST_IDLE;
bool     armed    = false;
bool     started  = false;
bool     finished = false;

bool     fetchRequested = false;
bool     hfxoOn         = false;
volatile uint8_t calRequested = 0;   // 1 or 2; handled in loop()

// ACK ids are queued here from the BLE callback and applied in loop(), so
// the pending[] buffer is only ever mutated from one context.
volatile uint16_t ackQueue[MAX_PENDING];
volatile uint8_t  ackHead = 0, ackTail = 0;

// Wall-clock: the phone writes unix time; we extrapolate with millis().
bool     timeValid  = false;
uint32_t epochBase  = 0;
uint32_t millisBase = 0;

// --------------------------------------------------- hardware timing core
void setupTiming() {
  uint32_t p1 = g_ADigitalPinMap[SENSOR1_PIN];   // Arduino pin -> nRF pin number
  uint32_t p2 = g_ADigitalPinMap[SENSOR2_PIN];

  nrf_gpio_cfg_input(p1, NRF_GPIO_PIN_PULLDOWN);
  nrf_gpio_cfg_input(p2, NRF_GPIO_PIN_PULLDOWN);

  // Charge pins idle in true hi-Z (input buffer disconnected) so they never
  // load the sensor nodes during live fire.
  nrf_gpio_cfg_default(g_ADigitalPinMap[CHARGE1_PIN]);
  nrf_gpio_cfg_default(g_ADigitalPinMap[CHARGE2_PIN]);

  // TIMER2: free-running 32-bit counter at 16 MHz -> 62.5 ns/tick.
  NRF_TIMER2->TASKS_STOP  = 1;
  NRF_TIMER2->MODE        = TIMER_MODE_MODE_Timer;
  NRF_TIMER2->BITMODE     = TIMER_BITMODE_BITMODE_32Bit << TIMER_BITMODE_BITMODE_Pos;
  NRF_TIMER2->PRESCALER   = 0;
  NRF_TIMER2->TASKS_CLEAR = 1;
  NRF_TIMER2->TASKS_START = 1;

  // GPIOTE event channels, rising edge on each sensor pin.
  NRF_GPIOTE->CONFIG[GPIOTE_S1] =
      (GPIOTE_CONFIG_MODE_Event      << GPIOTE_CONFIG_MODE_Pos)     |
      (p1                            << GPIOTE_CONFIG_PSEL_Pos)     |
      (GPIOTE_CONFIG_POLARITY_LoToHi << GPIOTE_CONFIG_POLARITY_Pos);
  NRF_GPIOTE->CONFIG[GPIOTE_S2] =
      (GPIOTE_CONFIG_MODE_Event      << GPIOTE_CONFIG_MODE_Pos)     |
      (p2                            << GPIOTE_CONFIG_PSEL_Pos)     |
      (GPIOTE_CONFIG_POLARITY_LoToHi << GPIOTE_CONFIG_POLARITY_Pos);

  // PPI: edge -> TIMER capture, and (fork) -> disable this channel's own
  // group so only the FIRST edge is captured (immune to piezo ringing).
  NRF_PPI->CH[PPI_S1].EEP   = (uint32_t)&NRF_GPIOTE->EVENTS_IN[GPIOTE_S1];
  NRF_PPI->CH[PPI_S1].TEP   = (uint32_t)&NRF_TIMER2->TASKS_CAPTURE[0];
  NRF_PPI->FORK[PPI_S1].TEP = (uint32_t)&NRF_PPI->TASKS_CHG[PPI_GRP_S1].DIS;

  NRF_PPI->CH[PPI_S2].EEP   = (uint32_t)&NRF_GPIOTE->EVENTS_IN[GPIOTE_S2];
  NRF_PPI->CH[PPI_S2].TEP   = (uint32_t)&NRF_TIMER2->TASKS_CAPTURE[1];
  NRF_PPI->FORK[PPI_S2].TEP = (uint32_t)&NRF_PPI->TASKS_CHG[PPI_GRP_S2].DIS;

  NRF_PPI->CHG[PPI_GRP_S1] = (1UL << PPI_S1);
  NRF_PPI->CHG[PPI_GRP_S2] = (1UL << PPI_S2);
}

void requestHfxo() {
  if (!hfxoOn) { sd_clock_hfclk_request(); hfxoOn = true; }
}
void releaseHfxo() {
  if (hfxoOn) { sd_clock_hfclk_release(); hfxoOn = false; }
}

// Prepare the capture hardware for a shot and enable both channels.
void armTiming() {
  requestHfxo();                              // accurate 16 MHz during the shot
  NRF_GPIOTE->EVENTS_IN[GPIOTE_S1] = 0;
  NRF_GPIOTE->EVENTS_IN[GPIOTE_S2] = 0;
  (void)NRF_GPIOTE->EVENTS_IN[GPIOTE_S2];     // flush the clears before continuing
  NRF_TIMER2->TASKS_CLEAR = 1;
  NRF_PPI->TASKS_CHG[PPI_GRP_S1].EN = 1;      // re-enable capture channels
  NRF_PPI->TASKS_CHG[PPI_GRP_S2].EN = 1;
}

void disarmTiming() {
  NRF_PPI->TASKS_CHG[PPI_GRP_S1].DIS = 1;
  NRF_PPI->TASKS_CHG[PPI_GRP_S2].DIS = 1;
  releaseHfxo();
}

// ----------------------------------------------------- calibration engine
// Measures a channel's time-to-threshold under a known stimulus: the charge
// pin steps HIGH through its 10 k 1% resistor and the node's RC rise is
// timed from launch to the GPIO threshold crossing — both ends in hardware.
// The launch is PPI-synchronized (TIMER2 compare -> GPIOTE SET task) so
// SoftDevice preemption cannot skew a sample; a preempted setup surfaces as
// a timeout and is excluded from the stats. The app runs this with the port
// bare (baseline) and again with the sensor attached; the difference
// isolates the cable+sensor load with the pin threshold and fixture
// capacitance cancelled.

const uint8_t  CAL_SAMPLES       = 64;
const uint32_t CAL_DISCHARGE_US  = 1000;            // ~5 tau for a 200 nF piezo via 1k
const uint32_t CAL_TIMEOUT_TICKS = 16UL * 20000UL;  // 20 ms per sample

static uint32_t isqrt64(uint64_t v) {
  uint64_t r = 0, bit = 1ULL << 62;
  while (bit > v) bit >>= 2;
  while (bit) {
    if (v >= r + bit) { v -= r + bit; r = (r >> 1) + bit; }
    else r >>= 1;
    bit >>= 2;
  }
  return (uint32_t)r;
}

void runCalibration(uint8_t channel) {
  uint32_t sensPin = g_ADigitalPinMap[(channel == 1) ? SENSOR1_PIN : SENSOR2_PIN];
  uint32_t chgPin  = g_ADigitalPinMap[(channel == 1) ? CHARGE1_PIN : CHARGE2_PIN];
  uint8_t  evCh    = (channel == 1) ? GPIOTE_S1 : GPIOTE_S2;
  uint8_t  grp     = (channel == 1) ? PPI_GRP_S1 : PPI_GRP_S2;
  uint8_t  ccIdx   = (channel == 1) ? 0 : 1;

  requestHfxo();   // absolute nanoseconds matter here: run off the crystal

  // Charge pin drives the node through 10k only during calibration. The
  // previous PPI/GPIOTE-task launch was elegant but brittle across board cores;
  // a direct GPIO step is plenty stable for setup calibration and much easier
  // to verify electrically.
  nrf_gpio_cfg_output(chgPin);
  nrf_gpio_pin_clear(chgPin);

  static uint32_t ticksBuf[CAL_SAMPLES];
  uint8_t valid = 0;
  uint8_t timeouts = 0;

  for (uint8_t i = 0; i <= CAL_SAMPLES; i++) {   // one extra: first sweep discarded
    // Discharge the node completely (sensor pin drives it low directly;
    // the charge pin pulls low through the 10k as well).
    nrf_gpio_pin_clear(chgPin);
    nrf_gpio_cfg(sensPin, NRF_GPIO_PIN_DIR_OUTPUT, NRF_GPIO_PIN_INPUT_CONNECT,
                 NRF_GPIO_PIN_NOPULL, NRF_GPIO_PIN_S0S1, NRF_GPIO_PIN_NOSENSE);
    nrf_gpio_pin_clear(sensPin);
    delayMicroseconds(CAL_DISCHARGE_US);

    // Release the node with NO pull — the internal pulldown would divide
    // the ramp against the 10k and sag the asymptote near the threshold.
    nrf_gpio_cfg(sensPin, NRF_GPIO_PIN_DIR_INPUT, NRF_GPIO_PIN_INPUT_CONNECT,
                 NRF_GPIO_PIN_NOPULL, NRF_GPIO_PIN_S0S1, NRF_GPIO_PIN_NOSENSE);

    NRF_GPIOTE->EVENTS_IN[evCh] = 0;
    (void)NRF_GPIOTE->EVENTS_IN[evCh];
    NRF_PPI->TASKS_CHG[grp].EN = 1;              // arm the threshold capture

    NRF_TIMER2->TASKS_CAPTURE[2] = 1;
    uint32_t t0 = NRF_TIMER2->CC[2];
    nrf_gpio_pin_set(chgPin);

    bool got = false;
    while (true) {
      if (NRF_GPIOTE->EVENTS_IN[evCh]) { got = true; break; }
      NRF_TIMER2->TASKS_CAPTURE[2] = 1;
      if ((uint32_t)(NRF_TIMER2->CC[2] - t0) > CAL_TIMEOUT_TICKS) break;
    }

    nrf_gpio_pin_clear(chgPin);
    NRF_PPI->TASKS_CHG[grp].DIS = 1;
    NRF_GPIOTE->EVENTS_IN[evCh] = 0;

    if (i == 0) continue;                        // residual-charge settling sweep
    if (got) ticksBuf[valid++] = NRF_TIMER2->CC[ccIdx] - t0;
    else     timeouts++;
  }

  // Restore live-fire pin configuration.
  nrf_gpio_pin_clear(chgPin);
  nrf_gpio_cfg_default(chgPin);                  // back to true hi-Z
  nrf_gpio_cfg_input(sensPin, NRF_GPIO_PIN_PULLDOWN);
  if (!armed) releaseHfxo();

  // Stats: insertion sort for the median, integer mean/stddev, all in ticks
  // then converted to ns (x62.5 = x125/2).
  for (uint8_t i = 1; i < valid; i++) {
    uint32_t key = ticksBuf[i];
    int8_t j = i - 1;
    while (j >= 0 && ticksBuf[j] > key) { ticksBuf[j + 1] = ticksBuf[j]; j--; }
    ticksBuf[j + 1] = key;
  }

  CalResult r = {};
  r.channel = channel;
  r.samples = valid;
  if (valid == 0) {
    r.status = 2;
  } else {
    r.status = (timeouts > 0) ? 1 : 0;
    uint64_t sum = 0, sumsq = 0;
    for (uint8_t i = 0; i < valid; i++) {
      sum += ticksBuf[i];
      sumsq += (uint64_t)ticksBuf[i] * ticksBuf[i];
    }
    uint32_t meanT = (uint32_t)(sum / valid);
    int64_t var = (int64_t)(sumsq / valid) - (int64_t)meanT * meanT;
    if (var < 0) var = 0;
    uint32_t medT = (valid & 1) ? ticksBuf[valid / 2]
                                : (ticksBuf[valid / 2 - 1] + ticksBuf[valid / 2]) / 2;
    r.medianNs = (uint32_t)(((uint64_t)medT * 125ULL) / 2ULL);
    r.meanNs   = (uint32_t)(((uint64_t)meanT * 125ULL) / 2ULL);
    r.stddevNs = (uint32_t)(((uint64_t)isqrt64((uint64_t)var) * 125ULL) / 2ULL);
    r.minNs    = (uint32_t)(((uint64_t)ticksBuf[0] * 125ULL) / 2ULL);
  }

  chCal.write((uint8_t*)&r, sizeof(r));
  chCal.notify((uint8_t*)&r, sizeof(r));
}

// -------------------------------------------------------------- helpers
// Flywheel conversion: boot-relative -> unix. The signed difference lets a
// sync applied AFTER a shot back-date it, and one applied before carry
// forward. Valid within ~24 days of the sync point (millis() wrap).
uint32_t epochForBootMs(uint32_t bootMs) {
  if (!timeValid) return 0;
  int32_t deltaMs = (int32_t)(bootMs - millisBase);
  return epochBase + deltaMs / 1000;
}

void notifyStatus() {
  uint8_t buf[3] = { state, pendingCount, (uint8_t)(timeValid ? 1 : 0) };
  chStatus.write(buf, sizeof(buf));
  chStatus.notify(buf, sizeof(buf));
}

void notifyPending(const Pending& p) {
  Result r;
  r.id      = p.id;
  r.splitNs = p.splitNs;
  r.epoch   = epochForBootMs(p.bootMs);   // flywheel: computed fresh each send
  r.flags   = (r.epoch != 0) ? 0x01 : 0x00;
  chResult.write((uint8_t*)&r, sizeof(r));
  chResult.notify((uint8_t*)&r, sizeof(r));
}

void storeResult(uint32_t splitNs) {
  if (pendingCount >= MAX_PENDING) {           // buffer full: drop the oldest
    memmove(&pending[0], &pending[1], sizeof(pending[0]) * (MAX_PENDING - 1));
    pendingCount = MAX_PENDING - 1;
  }
  Pending& p = pending[pendingCount++];
  p.id      = nextId++;
  p.splitNs = splitNs;
  p.bootMs  = millis();                        // flywheel timestamp
  notifyPending(p);  // no-op if nobody is connected/subscribed; FETCH re-sends
}

void ackResult(uint16_t id) {
  for (uint8_t i = 0; i < pendingCount; i++) {
    if (pending[i].id == id) {
      memmove(&pending[i], &pending[i + 1], sizeof(pending[0]) * (pendingCount - i - 1));
      pendingCount--;
      break;
    }
  }
}

void setState(uint8_t s) {
  state = s;
  notifyStatus();
}

// --------------------------------------------------------- BLE callbacks
void onControlWrite(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void)conn_hdl; (void)chr;
  if (len < 1) return;
  uint16_t arg = (len >= 3) ? (uint16_t)(data[1] | (data[2] << 8)) : 0;

  switch (data[0]) {
    case CMD_VERIFY1: armed = false; disarmTiming(); setState(ST_VERIFY1); break;
    case CMD_VERIFY2: armed = false; disarmTiming(); setState(ST_VERIFY2); break;
    case CMD_ARM:
      started  = false;
      finished = false;
      armed    = true;
      armTiming();
      setState(ST_ARMED);
      break;
    case CMD_DISARM:
    case CMD_CANCEL:
      armed    = false;
      started  = false;
      finished = false;
      disarmTiming();
      setState(ST_IDLE);
      break;
    case CMD_ACK: {
      // Defer the buffer edit to loop() — never mutate pending[] here.
      uint8_t nt = (uint8_t)((ackTail + 1) % MAX_PENDING);
      if (nt != ackHead) { ackQueue[ackTail] = arg; ackTail = nt; }
      break;
    }
    case CMD_FETCH:
      fetchRequested = true;   // handled in loop() — keeps this callback quick
      break;
    case CMD_CALIBRATE:
      // Deferred to loop() like FETCH; refused while a shot could arrive.
      if (state != ST_ARMED && state != ST_RUNNING && arg >= 1 && arg <= 2) {
        calRequested = (uint8_t)arg;
      }
      break;
  }
}

void onTimeWrite(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void)conn_hdl; (void)chr;
  if (len < 4) return;
  epochBase  = (uint32_t)data[0] | ((uint32_t)data[1] << 8) |
               ((uint32_t)data[2] << 16) | ((uint32_t)data[3] << 24);
  millisBase = millis();
  timeValid  = (epochBase != 0);
  notifyStatus();
}

// When the app subscribes to STATUS, push the current state right away.
void onStatusCccd(uint16_t conn_hdl, BLECharacteristic* chr, uint16_t value) {
  (void)conn_hdl; (void)chr;
  if (value & 0x0001) notifyStatus();
}

void onConnect(uint16_t conn_hdl) {
  (void)conn_hdl;
}

void onDisconnect(uint16_t conn_hdl, uint8_t reason) {
  (void)conn_hdl; (void)reason;
  // Deliberately do nothing: if we're ARMED or RUNNING we stay that way,
  // the hardware keeps capturing, the result is stored, and advertising
  // restarts automatically so the phone can reconnect and collect it.
}

// ------------------------------------------------------------------ setup
void setup() {
  STATUS_LED_BEGIN();

  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName("Chrono");
  Bluefruit.Periph.setConnectCallback(onConnect);
  Bluefruit.Periph.setDisconnectCallback(onDisconnect);

  // Set up the capture hardware after Bluefruit.begin() so the SoftDevice
  // is running when we touch PPI/HFXO.
  setupTiming();

  svc.begin();

  chStatus.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chStatus.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chStatus.setFixedLen(3);
  chStatus.setCccdWriteCallback(onStatusCccd);
  chStatus.begin();

  chControl.setProperties(CHR_PROPS_WRITE | CHR_PROPS_WRITE_WO_RESP);
  chControl.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chControl.setMaxLen(3);
  chControl.setWriteCallback(onControlWrite);
  chControl.begin();

  chResult.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chResult.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chResult.setFixedLen(sizeof(Result));
  chResult.begin();

  chTime.setProperties(CHR_PROPS_WRITE);
  chTime.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chTime.setMaxLen(4);
  chTime.setWriteCallback(onTimeWrite);
  chTime.begin();

  chCal.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chCal.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chCal.setFixedLen(sizeof(CalResult));
  chCal.begin();

  chInfo.setProperties(CHR_PROPS_READ);
  chInfo.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chInfo.setFixedLen(sizeof(HwInfo));
  chInfo.begin();
  // Rev 1: 16 MHz hardware capture, HFXO +/-30 ppm, piezo->GPIO front end
  // with a conservative 300 ns per-edge threshold-walk allowance.
  HwInfo info = {
    HW_REV, FW_MAJOR, FW_MINOR, 0, 62500UL, 30, 300,
    NRF_FICR->DEVICEID[0], NRF_FICR->DEVICEID[1]
  };
  chInfo.write((uint8_t*)&info, sizeof(info));

  notifyStatus();

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(svc);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);   // key to auto-reconnect
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);                    // advertise forever
}

// ------------------------------------------------------------------- loop
void loop() {
  switch (state) {
    case ST_VERIFY1:
      if (digitalRead(SENSOR1_PIN) == HIGH) setState(ST_VERIFY1_OK);
      break;

    case ST_VERIFY2:
      if (digitalRead(SENSOR2_PIN) == HIGH) setState(ST_VERIFY2_OK);
      break;

    case ST_ARMED:
    case ST_RUNNING:
      // Timestamps are already frozen in TIMER2->CC by hardware; here we
      // only notice that the edges happened and read the captured values.
      if (!started && NRF_GPIOTE->EVENTS_IN[GPIOTE_S1]) {
        NRF_GPIOTE->EVENTS_IN[GPIOTE_S1] = 0;
        started = true;
        setState(ST_RUNNING);
      }
      if (started && !finished && NRF_GPIOTE->EVENTS_IN[GPIOTE_S2]) {
        NRF_GPIOTE->EVENTS_IN[GPIOTE_S2] = 0;
        finished = true;
        uint32_t ticks = NRF_TIMER2->CC[1] - NRF_TIMER2->CC[0];  // wrap-safe
        uint32_t splitNs = (uint32_t)(((uint64_t)ticks * 1000000000ULL) / TIMER_HZ);
        armed = false;
        started = false;
        finished = false;
        disarmTiming();
        storeResult(splitNs);
        setState(ST_IDLE);
      }
      break;

    default:
      break;
  }

  // Apply any ACKs the phone sent (buffer is only mutated here).
  bool acked = false;
  while (ackHead != ackTail) {
    uint16_t id = ackQueue[ackHead];
    ackHead = (uint8_t)((ackHead + 1) % MAX_PENDING);
    ackResult(id);
    acked = true;
  }
  if (acked) notifyStatus();

  if (calRequested) {
    uint8_t ch = calRequested;
    calRequested = 0;
    uint8_t prev = state;
    setState(ST_CALIBRATING);
    runCalibration(ch);
    setState(prev);   // resume idle or the verify-ok state the wizard shows
  }

  if (fetchRequested) {
    fetchRequested = false;
    for (uint8_t i = 0; i < pendingCount; i++) {
      notifyPending(pending[i]);
      delay(15);   // give the BLE stack room between notifications
    }
  }

  // Status LED on P0.15: standby blinks 1s on / 1s off, timing solid on.
  if (state == ST_ARMED) {
    STATUS_LED_WRITE(((millis() / 1000UL) % 2UL) == 0UL);
  } else {
    STATUS_LED_WRITE(state == ST_RUNNING);
  }

  delay(2);
}
