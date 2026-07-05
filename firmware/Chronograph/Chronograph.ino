/*
 * Chrono — BLE chronograph firmware for the Seeed Studio XIAO nRF52840
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
 * Board package: "Seeed nRF52 Boards" (Adafruit-based core). The
 * Bluefruit BLE library ships with that core — nothing extra to install.
 */

#include <bluefruit.h>
#include <nrf_gpio.h>
#include <nrf_soc.h>

// ---------------------------------------------------------------- pins
const uint8_t SENSOR1_PIN = 0;   // XIAO pin labeled "0" (D0) — START
const uint8_t SENSOR2_PIN = 1;   // XIAO pin labeled "1" (D1) — STOP

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

BLEService        svc     (UUID_SERVICE);
BLECharacteristic chStatus (UUID_STATUS);   // read/notify: [state, pendingCount, timeValid]
BLECharacteristic chControl(UUID_CONTROL);  // write: commands
BLECharacteristic chResult (UUID_RESULT);   // read/notify: Result struct below
BLECharacteristic chTime   (UUID_TIME);     // write: uint32 LE unix seconds

// One measurement. 11 bytes, little-endian — parsed byte-for-byte by the app.
struct __attribute__((packed)) Result {
  uint16_t id;
  uint32_t splitNs;   // time between sensor 1 and sensor 2, NANOSECONDS
  uint32_t epoch;     // unix seconds at the stop trigger, 0 = clock never synced
  uint8_t  flags;     // bit0: epoch is valid
};

// Results wait here until the phone ACKs them, so nothing is lost if
// the BLE link drops while a test is in progress.
const uint8_t MAX_PENDING = 16;
Result   pending[MAX_PENDING];
uint8_t  pendingCount = 0;
uint16_t nextId       = 1;

// ------------------------------------------------------- run-time state
uint8_t  state    = ST_IDLE;
bool     armed    = false;
bool     started  = false;
bool     finished = false;

bool     fetchRequested = false;
bool     hfxoOn         = false;

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

// -------------------------------------------------------------- helpers
uint32_t currentEpoch() {
  if (!timeValid) return 0;
  return epochBase + (millis() - millisBase) / 1000UL;
}

void notifyStatus() {
  uint8_t buf[3] = { state, pendingCount, (uint8_t)(timeValid ? 1 : 0) };
  chStatus.write(buf, sizeof(buf));
  chStatus.notify(buf, sizeof(buf));
}

void notifyResult(const Result& r) {
  chResult.write((uint8_t*)&r, sizeof(r));
  chResult.notify((uint8_t*)&r, sizeof(r));
}

void storeResult(uint32_t splitNs) {
  if (pendingCount >= MAX_PENDING) {           // buffer full: drop the oldest
    memmove(&pending[0], &pending[1], sizeof(Result) * (MAX_PENDING - 1));
    pendingCount = MAX_PENDING - 1;
  }
  Result& r = pending[pendingCount++];
  r.id      = nextId++;
  r.splitNs = splitNs;
  r.epoch   = currentEpoch();
  r.flags   = timeValid ? 0x01 : 0x00;
  notifyResult(r);   // no-op if nobody is connected/subscribed; FETCH re-sends
}

void ackResult(uint16_t id) {
  for (uint8_t i = 0; i < pendingCount; i++) {
    if (pending[i].id == id) {
      memmove(&pending[i], &pending[i + 1], sizeof(Result) * (pendingCount - i - 1));
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
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);            // XIAO LEDs are active-LOW

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

  if (fetchRequested) {
    fetchRequested = false;
    for (uint8_t i = 0; i < pendingCount; i++) {
      notifyResult(pending[i]);
      delay(15);   // give the BLE stack room between notifications
    }
  }

  // LED: on while armed/running, off otherwise (active-LOW)
  digitalWrite(LED_BUILTIN, (state == ST_ARMED || state == ST_RUNNING) ? LOW : HIGH);

  delay(2);
}
