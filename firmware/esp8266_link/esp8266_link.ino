/*
 * EyeBot Wi-Fi link - ESP8266 firmware (Arduino IDE)            [EyeBot v4]
 * -----------------------------------------------------------------------
 * Lets EyeBot talk to an ESP8266 board over Wi-Fi (UDP port 4210). Use it for:
 *   - a Wi-Fi pan-tilt stand (2 servos), or
 *   - as the starting point for the LAFVIN quadruped spider (ESP8266, Wi-Fi only - no Bluetooth).
 *
 * Network: connects to WIFI_SSID (e.g. your phone's hotspot). If that fails within 15 s it makes
 * its own access point "EyeBot-Link" (password eyebot123) - join it from the phone instead.
 *
 * Protocol (one ASCII line per UDP packet, same as the ESP32 BLE/USB stand):
 *   phone -> board   "EYEBOT?"          discovery  -> board replies "EYEBOT:<name>"
 *                    "PING"             keep-alive -> "OK"
 *                    "S<pan>,<tilt>"    servo angles in degrees, e.g. "S97,84"
 *                    "MOVE:F|B|L|R|STOP"   reserved for the spider's walking gait (see TODO)
 *   board -> phone   "BTN:2" / "BTN:3"  FLASH button short / long press -> boop / poke
 *                    "DIST:<cm>"        optional HC-SR04 distance (EyeBot reacts when you come close)
 *                    any QR-style command, e.g. "PARTY", "SAY:Hello"
 *
 * Board: NodeMCU / Wemos D1 mini (esp8266 board package).  Libraries: built-in Servo, ESP8266WiFi.
 * Wiring: pan servo D5 (GPIO14), tilt servo D6 (GPIO12), HC-SR04 TRIG D7 (GPIO13),
 *         ECHO D1 (GPIO5) through a 1k/2k divider (ECHO is 5 V!). Servos on a separate 5 V supply.
 */

#include <ESP8266WiFi.h>
#include <WiFiUdp.h>
#include <Servo.h>

// ---------------------------------------------------------------- configuration
static const char *WIFI_SSID = "YourHotspotName";
static const char *WIFI_PASS = "YourHotspotPassword";
static const char *AP_SSID = "EyeBot-Link";
static const char *AP_PASS = "eyebot123";
static const char *BOARD_NAME = "EyeBot-Link";
static const uint16_t PORT = 4210;

static const int PAN_PIN = 14;     // D5
static const int TILT_PIN = 12;    // D6
static const int TRIG_PIN = 13;    // D7
static const int ECHO_PIN = 5;     // D1
static const int BUTTON_PIN = 0;   // FLASH button
static const bool USE_DISTANCE = false;   // set true if an HC-SR04 is wired

static const int PAN_MIN = 15, PAN_MAX = 165, TILT_MIN = 55, TILT_MAX = 125;
static const float MAX_DEG_PER_SEC = 180.0f;

// ---------------------------------------------------------------- state
WiFiUDP udp;
IPAddress phoneIp;
uint16_t phonePort = 0;
Servo panServo, tiltServo;
float panPos = 90, tiltPos = 90;
int panTarget = 90, tiltTarget = 90;
char packet[128];
int lastDist = -1;
uint32_t lastDistAt = 0, buttonDownAt = 0;
bool buttonDown = false;

void sendLine(const String &s) {
  Serial.println(s);
  if (phonePort == 0) return;
  udp.beginPacket(phoneIp, phonePort);
  udp.print(s);
  udp.print('\n');
  udp.endPacket();
}

void handleLine(String line, IPAddress from, uint16_t fromPort) {
  line.trim();
  if (line.length() == 0) return;
  if (line == "EYEBOT?") {
    phoneIp = from; phonePort = fromPort;
    sendLine(String("EYEBOT:") + BOARD_NAME);
  } else if (line == "PING") {
    phoneIp = from; phonePort = fromPort;
    sendLine("OK");
  } else if (line[0] == 'S') {
    int comma = line.indexOf(',');
    if (comma > 1) {
      panTarget = constrain(line.substring(1, comma).toInt(), PAN_MIN, PAN_MAX);
      tiltTarget = constrain(line.substring(comma + 1).toInt(), TILT_MIN, TILT_MAX);
    }
  } else if (line.startsWith("MOVE:")) {
    // TODO (spider): call the LAFVIN gait functions here, e.g. forward()/back()/turnLeft()/stop().
    sendLine("OK:" + line);
  }
}

void setupWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 15000) { delay(250); Serial.print('.'); }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\nConnected, IP %s\n", WiFi.localIP().toString().c_str());
  } else {
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASS);
    Serial.printf("\nAccess point %s, IP %s\n", AP_SSID, WiFi.softAPIP().toString().c_str());
  }
  udp.begin(PORT);
}

int readDistanceCm() {
  digitalWrite(TRIG_PIN, LOW); delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH); delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);
  unsigned long us = pulseIn(ECHO_PIN, HIGH, 25000);
  return us == 0 ? -1 : (int)(us / 58);
}

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
  if (USE_DISTANCE) { pinMode(TRIG_PIN, OUTPUT); pinMode(ECHO_PIN, INPUT); }
  panServo.attach(PAN_PIN, 500, 2400);
  tiltServo.attach(TILT_PIN, 500, 2400);
  panServo.write(90);
  tiltServo.write(90);
  setupWifi();
}

void loop() {
  int size = udp.parsePacket();
  if (size > 0) {
    int n = udp.read(packet, sizeof(packet) - 1);
    packet[n > 0 ? n : 0] = 0;
    String all(packet);
    int start = 0;
    while (start < (int)all.length()) {
      int nl = all.indexOf('\n', start);
      if (nl < 0) nl = all.length();
      handleLine(all.substring(start, nl), udp.remoteIP(), udp.remotePort());
      start = nl + 1;
    }
  }

  // Serial works too (USB cable)
  static String serialLine;
  while (Serial.available()) {
    char ch = (char)Serial.read();
    if (ch == '\n' || ch == '\r') { handleLine(serialLine, IPAddress(0, 0, 0, 0), 0); serialLine = ""; }
    else if (serialLine.length() < 64) serialLine += ch;
  }

  // Smooth servo slewing
  static uint32_t lastStep = millis();
  uint32_t t = millis();
  float maxStep = MAX_DEG_PER_SEC * (t - lastStep) / 1000.0f;
  lastStep = t;
  panPos += constrain(panTarget - panPos, -maxStep, maxStep);
  tiltPos += constrain(tiltTarget - tiltPos, -maxStep, maxStep);
  panServo.write((int)(panPos + 0.5f));
  tiltServo.write((int)(tiltPos + 0.5f));

  // FLASH button: short = boop, long = poke
  bool pressed = digitalRead(BUTTON_PIN) == LOW;
  if (pressed && !buttonDown) { buttonDown = true; buttonDownAt = t; }
  if (!pressed && buttonDown) {
    buttonDown = false;
    uint32_t held = t - buttonDownAt;
    if (held > 30) sendLine(held > 600 ? "BTN:3" : "BTN:2");
  }

  // Optional distance sensor, max 5 readings/s, only when it changes
  if (USE_DISTANCE && t - lastDistAt > 200) {
    lastDistAt = t;
    int d = readDistanceCm();
    if (d > 0 && abs(d - lastDist) >= 3) { lastDist = d; sendLine("DIST:" + String(d)); }
  }
  delay(10);
}
