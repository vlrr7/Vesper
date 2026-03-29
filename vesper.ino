// =============================================
// Vesper — Obstacle Detection + Fall Alert BLE
// Arduino Mega 2560 + HC-SR04 x2 + MPU-9250 + HM-10
// =============================================
//
// Câblage HM-10:
//   HM-10 TX  → Mega pin 19 (RX1)
//   HM-10 RX  → Mega pin 18 (TX1) via diviseur de tension
//   HM-10 VCC → 3.3V
//   HM-10 GND → GND

#include <Wire.h>

// ─── Pin assignments ────────────────────────────────────────────────────────
#define S1_TRIG      44
#define S1_ECHO      46
#define S1_BUZZER    48

#define S2_TRIG      22
#define S2_ECHO      24
#define S2_BUZZER    26

// ─── Sensor tuning ──────────────────────────────────────────────────────────
#define MAX_ECHO_US  96000  // µs — max echo wait before "out of range"
#define ECHO_WAIT_MS 2000   // ms — max wait for echo to start
#define SOUND_SPEED  0.0343 // cm/µs

// ─── Buzzer tuning ──────────────────────────────────────────────────────────
#define BEEP_ON_MS   30
#define BEEP_NEAR_MS 50
#define BEEP_FAR_MS  1000
#define DIST_NEAR_CM 5
#define DIST_FAR_CM  100

// ─── Fall detection ─────────────────────────────────────────────────────────
#define MPU_ADDR           0x68
#define ACC_FULL_SCALE_REG 0x08  // ±2g=0x00, ±4g=0x08, ±8g=0x10, ±16g=0x18
#define ACC_LSB_PER_G      (16384 >> ((ACC_FULL_SCALE_REG >> 3) & 0x03)) // auto-derived from scale

#define FREEFALL_THRESHOLD 0.6   // g — below this → freefall
#define IMPACT_THRESHOLD   2.0   // g — above this → impact
#define STILL_THRESHOLD    0.3   // g — within this of 1g → person is still
#define STILL_DURATION_MS  1000  // ms — must be still this long before alert
#define COOLDOWN_MS        5000  // ms — min time between alerts
#define FALL_SAMPLE_MS     20    // ms — fall detection sample rate (~50Hz)

enum FallState { WATCHING, FREEFALL_DETECTED, IMPACT_DETECTED };
FallState fallState    = WATCHING;
unsigned long freefallTime  = 0;
unsigned long impactTime    = 0;
unsigned long lastAlertTime = 0;
unsigned long lastFallSample = 0;

// ─── Ultrasonic sensor state machine ────────────────────────────────────────
enum PingState { IDLE, WAIT_ECHO_START, WAIT_ECHO_END };

struct Sensor {
  int trigPin, echoPin, buzzerPin;
  long distance;
  bool buzzerState;
  unsigned long lastToggle;
  PingState pingState;
  unsigned long stateStart; // millis() when entered current state
  unsigned long echoStart;  // micros() when echo went HIGH
};

Sensor sensors[] = {
  {S1_TRIG, S1_ECHO, S1_BUZZER, 0, false, 0, IDLE, 0, 0},
  {S2_TRIG, S2_ECHO, S2_BUZZER, 0, false, 0, IDLE, 0, 0},
};
const int NUM_SENSORS = sizeof(sensors) / sizeof(sensors[0]);
int currentSensor = 0;

// ─── I2C helper ─────────────────────────────────────────────────────────────
void I2CwriteByte(uint8_t addr, uint8_t reg, uint8_t data) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(data);
  Wire.endTransmission();
}

// ─── Accelerometer ──────────────────────────────────────────────────────────
float readAccMagnitude() {
  Wire.beginTransmission(MPU_ADDR);
  Wire.write(0x3B);
  Wire.endTransmission(false);
  Wire.requestFrom((uint8_t)MPU_ADDR, (uint8_t)6);

  int16_t ax = (Wire.read() << 8) | Wire.read();
  int16_t ay = (Wire.read() << 8) | Wire.read();
  int16_t az = (Wire.read() << 8) | Wire.read();

  float gx = ax / (float)ACC_LSB_PER_G;
  float gy = ay / (float)ACC_LSB_PER_G;
  float gz = az / (float)ACC_LSB_PER_G;

  return sqrt(gx * gx + gy * gy + gz * gz);
}

// ─── BLE alert ──────────────────────────────────────────────────────────────
void sendFallAlert() {
  for (int i = 0; i < NUM_SENSORS; i++) {
    digitalWrite(sensors[i].buzzerPin, LOW);
    sensors[i].buzzerState = false;
  }

  Serial.println(">>>  CHUTE DETECTEE!  <<<");
  Serial1.println("FALL");

  for (int i = 0; i < 10; i++) {
    digitalWrite(LED_BUILTIN, HIGH);
    delay(100);
    digitalWrite(LED_BUILTIN, LOW);
    delay(100);
  }
}

// ─── Fall detection state machine ───────────────────────────────────────────
void updateFallDetection() {
  float mag = readAccMagnitude();
  unsigned long now = millis();

  switch (fallState) {
    case WATCHING:
      if (mag < FREEFALL_THRESHOLD) {
        fallState = FREEFALL_DETECTED;
        freefallTime = now;
        Serial.print("! Chute libre: "); Serial.print(mag, 2); Serial.println("g");
      }
      break;

    case FREEFALL_DETECTED:
      if (mag > IMPACT_THRESHOLD) {
        fallState = IMPACT_DETECTED;
        impactTime = now;
        Serial.print("! Impact: "); Serial.print(mag, 2); Serial.println("g");
      } else if (now - freefallTime > 1000) {
        fallState = WATCHING;
      }
      break;

    case IMPACT_DETECTED:
      if (abs(mag - 1.0) < STILL_THRESHOLD) {
        if (now - impactTime > STILL_DURATION_MS) {
          if (now - lastAlertTime > COOLDOWN_MS) {
            sendFallAlert();
            lastAlertTime = now;
          }
          fallState = WATCHING;
        }
      } else if (now - impactTime > 3000) {
        Serial.println("  -> Fausse alerte");
        fallState = WATCHING;
      }
      break;
  }

  if (Serial1.available()) {
    String cmd = Serial1.readStringUntil('\n');
    cmd.trim();
    if (cmd == "PING") {
      Serial1.println("PONG");
      Serial.println("BLE: PING recu, PONG envoye");
    } else if (cmd == "STATUS") {
      Serial1.println("OK");
      Serial.println("BLE: STATUS demande");
    }
  }
}

// ─── Non-blocking ping ───────────────────────────────────────────────────────
// Returns true when a new reading is ready (sensors[].distance updated)
bool stepPing(Sensor &s) {
  switch (s.pingState) {

    case IDLE:
      digitalWrite(s.trigPin, LOW);
      delayMicroseconds(2);
      digitalWrite(s.trigPin, HIGH);
      delayMicroseconds(10);
      digitalWrite(s.trigPin, LOW);
      s.pingState = WAIT_ECHO_START;
      s.stateStart = millis();
      return false;

    case WAIT_ECHO_START:
      if (digitalRead(s.echoPin) == HIGH) {
        s.echoStart = micros();
        s.pingState = WAIT_ECHO_END;
      } else if (millis() - s.stateStart > ECHO_WAIT_MS) {
        s.distance = 0;
        s.pingState = IDLE;
        return true;
      }
      return false;

    case WAIT_ECHO_END:
      if (digitalRead(s.echoPin) == LOW) {
        s.distance = (micros() - s.echoStart) * SOUND_SPEED / 2.0;
        s.pingState = IDLE;
        return true;
      } else if (micros() - s.echoStart > MAX_ECHO_US) {
        s.distance = 0;
        s.pingState = IDLE;
        return true;
      }
      return false;
  }
  return false;
}

// ─── Buzzer ──────────────────────────────────────────────────────────────────
void updateBuzzer(Sensor &s) {
  if (s.distance == 0) {
    digitalWrite(s.buzzerPin, LOW);
    s.buzzerState = false;
    return;
  }

  long interval = map(s.distance, DIST_NEAR_CM, DIST_FAR_CM, BEEP_NEAR_MS, BEEP_FAR_MS);
  interval = constrain(interval, BEEP_NEAR_MS, BEEP_FAR_MS);

  unsigned long now = millis();
  if (s.buzzerState && now - s.lastToggle >= BEEP_ON_MS) {
    digitalWrite(s.buzzerPin, LOW);
    s.buzzerState = false;
    s.lastToggle = now;
  } else if (!s.buzzerState && now - s.lastToggle >= interval) {
    digitalWrite(s.buzzerPin, HIGH);
    s.buzzerState = true;
    s.lastToggle = now;
  }
}

void printDistance(int index, long distance) {
  Serial.print("Sensor ");
  Serial.print(index + 1);
  Serial.print(": ");
  Serial.println(distance == 0 ? "Out of range" : String(distance) + " cm");
}

// ─── Setup / Loop ────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);
  Serial1.begin(9600);
  Wire.begin();

  I2CwriteByte(MPU_ADDR, 0x6B, 0x00);
  delay(100);
  I2CwriteByte(MPU_ADDR, 28, ACC_FULL_SCALE_REG);

  pinMode(LED_BUILTIN, OUTPUT);

  for (int i = 0; i < NUM_SENSORS; i++) {
    pinMode(sensors[i].trigPin, OUTPUT);
    pinMode(sensors[i].echoPin, INPUT);
    pinMode(sensors[i].buzzerPin, OUTPUT);
    digitalWrite(sensors[i].trigPin, LOW);
  }

  Serial.println("=== Vesper ===");
  Serial1.println("VESPER_READY");
  delay(1000);
}

void loop() {
  // Fall detection runs at ~50Hz via millis() — never blocked by sensors
  unsigned long now = millis();
  if (now - lastFallSample >= FALL_SAMPLE_MS) {
    updateFallDetection();
    lastFallSample = now;
  }

  // Advance current sensor one step — returns immediately, never blocks
  if (stepPing(sensors[currentSensor])) {
    printDistance(currentSensor, sensors[currentSensor].distance);
    currentSensor = (currentSensor + 1) % NUM_SENSORS;
  }

  // Buzzers update every iteration — fully independent of sensor timing
  for (int i = 0; i < NUM_SENSORS; i++) updateBuzzer(sensors[i]);
}
