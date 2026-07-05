/*
 * Chrono — BLE chronograph firmware for the Seeed Studio XIAO nRF52840
 * ---------------------------------------------------------------------
 * Two digital inputs act as START and STOP triggers. When armed, the
 * clock starts on a rising edge on SENSOR 1 and stops on a rising edge
 * on SENSOR 2. The split time (microseconds) is stored on the device
 * and delivered to the phone over BLE — including after a disconnect
 * and automatic reconnect.
 *
 * Wiring (see README.md):
 *   SENSOR 1 (start): one lead to pin D0, other lead to 3V3
 *   SENSOR 2 (stop):  one lead to pin D1, other lead to 3V3
 *   Inputs use the internal pull-DOWN, so a sensor is anything that
 *   momentarily connects the pin to 3.3 V (switch, make-screen, etc.).
 *
 * Board package: "Seeed nRF52 Boards" (Adafruit-based core). The
 * Bluefruit BLE library ships with that core — nothing extra to install.
 */

#include <bluefruit.h>

// ---------------------------------------------------------------- pins
const uint8_t SENSOR1_PIN = 0;   // XIAO pin labeled "0" (D0) — START
const uint8_t SENSOR2_PIN = 1;   // XIAO pin labeled "1" (D1) — STOP

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
  uint32_t splitUs;   // time between sensor 1 and sensor 2, microseconds
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
volatile uint8_t  state    = ST_IDLE;
volatile bool     armed    = false;
volatile bool     started  = false;
volatile uint32_t tStart   = 0;
volatile bool     finished = false;
volatile uint32_t tStop    = 0;

volatile bool fetchRequested = false;

// Wall-clock: the phone writes unix time; we extrapolate with millis().
bool     timeValid  = false;
uint32_t epochBase  = 0;
uint32_t millisBase = 0;

// ------------------------------------------------------------ interrupts
// Kept minimal for timing accuracy: grab micros() and set a flag.
void isrSensor1() {
  if (armed && !started) {
    tStart  = micros();
    started = true;
  }
}

void isrSensor2() {
  if (armed && started && !finished) {
    tStop    = micros();
    finished = true;
    armed    = false;   // one-shot: re-arm from the app for the next test
  }
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

void storeResult(uint32_t splitUs) {
  if (pendingCount >= MAX_PENDING) {           // buffer full: drop the oldest
    memmove(&pending[0], &pending[1], sizeof(Result) * (MAX_PENDING - 1));
    pendingCount = MAX_PENDING - 1;
  }
  Result& r = pending[pendingCount++];
  r.id      = nextId++;
  r.splitUs = splitUs;
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
    case CMD_VERIFY1: armed = false; setState(ST_VERIFY1); break;
    case CMD_VERIFY2: armed = false; setState(ST_VERIFY2); break;
    case CMD_ARM:
      started  = false;
      finished = false;
      armed    = true;
      setState(ST_ARMED);
      break;
    case CMD_DISARM:
    case CMD_CANCEL:
      armed    = false;
      started  = false;
      finished = false;
      setState(ST_IDLE);
      break;
    case CMD_ACK:
      ackResult(arg);
      notifyStatus();
      break;
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
  // keep timing, store the result, and advertising restarts automatically
  // so the phone can reconnect and collect it.
}

// ------------------------------------------------------------------ setup
void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);            // XIAO LEDs are active-LOW

  pinMode(SENSOR1_PIN, INPUT_PULLDOWN);
  pinMode(SENSOR2_PIN, INPUT_PULLDOWN);
  attachInterrupt(digitalPinToInterrupt(SENSOR1_PIN), isrSensor1, RISING);
  attachInterrupt(digitalPinToInterrupt(SENSOR2_PIN), isrSensor2, RISING);

  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName("Chrono");
  Bluefruit.Periph.setConnectCallback(onConnect);
  Bluefruit.Periph.setDisconnectCallback(onDisconnect);

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
      if (started) setState(ST_RUNNING);   // lets the app show "shot started"
      // fall through to check for completion in the same pass
    case ST_RUNNING:
      if (finished) {
        uint32_t split = tStop - tStart;   // unsigned math survives micros() wrap
        started  = false;
        finished = false;
        storeResult(split);
        setState(ST_IDLE);
      }
      break;

    default:
      break;
  }

  if (fetchRequested) {
    fetchRequested = false;
    for (uint8_t i = 0; i < pendingCount; i++) {
      notifyResult(pending[i]);
      delay(15);   // give the BLE stack room between notifications
    }
  }

  // LED: on while armed/running, off otherwise (active-LOW)
  digitalWrite(LED_BUILTIN, (state == ST_ARMED || state == ST_RUNNING) ? LOW : HIGH);

  delay(5);
}
