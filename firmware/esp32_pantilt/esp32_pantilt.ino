/*
 * EyeBot pan-tilt stand - ESP32 firmware (Arduino IDE)
 * ----------------------------------------------------
 * Receives "S<pan>,<tilt>\n" lines from the EyeBot Android app and drives two hobby servos so
 * the phone physically turns to keep your face centred. Works over:
 *   - Bluetooth Low Energy (Nordic UART Service, advertised as "EyeBot-PanTilt"), or
 *   - USB serial at 115200 baud (USB OTG cable from the phone).
 *
 * It also sends touch / button events back to the phone, so physical taps trigger reactions:
 *   "TOUCH:HEAD\n"  capacitive pad (pet)      -> EyeBot squints and purrs
 *   "BTN:2\n"       BOOT button (short press) -> boop (happy chirp)
 *   "BTN:3\n"       BOOT button (long press)  -> poke (startled)
 * Any other text line (e.g. "PARTY", "SAY:Hello") is treated like a QR card by the app.
 *
 * Board:     any ESP32 / ESP32-S3 dev board (Arduino-ESP32 core 2.x or 3.x)
 * Library:   "ESP32Servo" by Kevin Harrington (Library Manager)
 *
 * Wiring (power the servos from a separate 5 V supply, NOT the ESP32's 3.3 V pin; join grounds):
 *   Pan servo signal  -> GPIO 18
 *   Tilt servo signal -> GPIO 19
 *   Touch pad         -> GPIO 4 (T0) : a coin / foil / copper tape on the "head", plain wire
 *   BOOT button       -> GPIO 0 (already on the board)
 */

#include <Arduino.h>
#include <ESP32Servo.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---------------------------------------------------------------- configuration
static const int PAN_PIN = 18;
static const int TILT_PIN = 19;
static const int TOUCH_PIN = 4;     // T0 on classic ESP32, TOUCH4 on S2/S3
static const int BUTTON_PIN = 0;    // BOOT button

static const int PAN_MIN = 15, PAN_MAX = 165;
static const int TILT_MIN = 55, TILT_MAX = 125;
static const float MAX_DEG_PER_SEC = 180.0f;   // servo slew limit (smooth motion)
static const uint32_t IDLE_DETACH_MS = 0;      // >0: release servos after this long still (0 = always hold the phone)

static const char *DEVICE_NAME = "EyeBot-PanTilt";
#define NUS_SERVICE "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"   // phone writes here
#define NUS_TX      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"   // we notify here

// ---------------------------------------------------------------- state
Servo panServo, tiltServo;
float panPos = 90, tiltPos = 90;       // current (slewed) angle
int panTarget = 90, tiltTarget = 90;   // last commanded angle
uint32_t lastMoveAt = 0;
bool attached = false;

BLECharacteristic *txChar = nullptr;
volatile bool bleConnected = false;
bool wasConnected = false;

String serialLine;
String bleLine;
portMUX_TYPE bleMux = portMUX_INITIALIZER_UNLOCKED;
String blePending;                     // filled by BLE callback, consumed in loop()

// ---------------------------------------------------------------- helpers
void sendLine(const String &s) {
  Serial.println(s);
  if (bleConnected && txChar) {
    String out = s + "\n";
    txChar->setValue((uint8_t *)out.c_str(), out.length());
    txChar->notify();
  }
}

void attachServos() {
  if (attached) return;
  panServo.attach(PAN_PIN, 500, 2400);
  tiltServo.attach(TILT_PIN, 500, 2400);
  attached = true;
}

void detachServos() {
  if (!attached) return;
  panServo.detach();
  tiltServo.detach();
  attached = false;
}

void handleLine(String line) {
  line.trim();
  if (line.length() == 0) return;
  if (line[0] == 'S' || line[0] == 's') {          // S<pan>,<tilt>
    int comma = line.indexOf(',');
    if (comma > 1) {
      panTarget = constrain(line.substring(1, comma).toInt(), PAN_MIN, PAN_MAX);
      tiltTarget = constrain(line.substring(comma + 1).toInt(), TILT_MIN, TILT_MAX);
      lastMoveAt = millis();
      attachServos();
    }
  } else if (line == "PING") {
    sendLine("OK");
  } else if (line == "CENTER") {
    panTarget = 90; tiltTarget = 90; lastMoveAt = millis(); attachServos();
  }
}

// ---------------------------------------------------------------- BLE
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override { bleConnected = true; }
  void onDisconnect(BLEServer *s) override { bleConnected = false; }
};

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    // getValue() is std::string on core 2.x and String on core 3.x; c_str() works for both.
    String v = String(c->getValue().c_str());
    portENTER_CRITICAL(&bleMux);
    blePending += v;
    portEXIT_CRITICAL(&bleMux);
  }
};

void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *svc = server->createService(NUS_SERVICE);

  txChar = svc->createCharacteristic(NUS_TX, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());

  BLECharacteristic *rx = svc->createCharacteristic(
      NUS_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCallbacks());

  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// ---------------------------------------------------------------- touch + button
int touchBaseline = 0;
bool touching = false;
uint32_t touchChangedAt = 0;
uint32_t buttonDownAt = 0;
bool buttonDown = false;

void setupTouch() {
  long sum = 0;
  for (int i = 0; i < 16; i++) { sum += touchRead(TOUCH_PIN); delay(5); }
  touchBaseline = sum / 16;
}

void pollTouch() {
  int v = touchRead(TOUCH_PIN);
  // Classic ESP32 readings DROP when touched; S2/S3 readings RISE. Use the relative change.
  int diff = abs(v - touchBaseline);
  bool now = diff > touchBaseline * 0.3f;
  uint32_t t = millis();
  if (now != touching && t - touchChangedAt > 80) {
    touching = now;
    touchChangedAt = t;
    if (touching) sendLine("TOUCH:HEAD");
  }
  // While being stroked, repeat so the robot keeps purring.
  if (touching && t - touchChangedAt > 1500) { touchChangedAt = t; sendLine("TOUCH:HEAD"); }
  // Slowly track the baseline while not touched (humidity / temperature drift).
  if (!touching) touchBaseline = (touchBaseline * 63 + v) / 64;
}

void pollButton() {
  bool pressed = digitalRead(BUTTON_PIN) == LOW;
  uint32_t t = millis();
  if (pressed && !buttonDown) { buttonDown = true; buttonDownAt = t; }
  if (!pressed && buttonDown) {
    buttonDown = false;
    uint32_t held = t - buttonDownAt;
    if (held > 30) sendLine(held > 600 ? "BTN:3" : "BTN:2");
  }
}

// ---------------------------------------------------------------- main
void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  ESP32PWM::allocateTimer(0);
  ESP32PWM::allocateTimer(1);
  panServo.setPeriodHertz(50);
  tiltServo.setPeriodHertz(50);
  attachServos();
  panServo.write(90);
  tiltServo.write(90);
  lastMoveAt = millis();

  setupBle();
  setupTouch();
  sendLine("HELLO:EyeBot-PanTilt");
}

void loop() {
  // USB serial input
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == '\n' || ch == '\r') { handleLine(serialLine); serialLine = ""; }
    else if (serialLine.length() < 64) serialLine += ch;
  }

  // BLE input (copied out of the callback buffer)
  String chunk;
  portENTER_CRITICAL(&bleMux);
  chunk = blePending;
  blePending = "";
  portEXIT_CRITICAL(&bleMux);
  for (size_t i = 0; i < chunk.length(); i++) {
    char ch = chunk[i];
    if (ch == '\n' || ch == '\r') { handleLine(bleLine); bleLine = ""; }
    else if (bleLine.length() < 64) bleLine += ch;
  }

  // Re-advertise after the phone disconnects
  if (wasConnected && !bleConnected) { delay(200); BLEDevice::startAdvertising(); }
  if (!wasConnected && bleConnected) sendLine("HELLO:EyeBot-PanTilt");
  wasConnected = bleConnected;

  // Smoothly slew towards the targets
  static uint32_t lastStep = millis();
  uint32_t t = millis();
  float dt = (t - lastStep) / 1000.0f;
  lastStep = t;
  float maxStep = MAX_DEG_PER_SEC * dt;
  float dp = constrain(panTarget - panPos, -maxStep, maxStep);
  float dtl = constrain(tiltTarget - tiltPos, -maxStep, maxStep);
  if (fabsf(dp) > 0.01f || fabsf(dtl) > 0.01f) {
    attachServos();
    panPos += dp;
    tiltPos += dtl;
    panServo.write((int)roundf(panPos));
    tiltServo.write((int)roundf(tiltPos));
    lastMoveAt = t;
  } else if (IDLE_DETACH_MS > 0 && t - lastMoveAt > IDLE_DETACH_MS) {
    detachServos();
  }

  pollTouch();
  pollButton();
  delay(10);
}
