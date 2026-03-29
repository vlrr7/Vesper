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
#define MAX_ECHO_US  96000  // µs — max echo wait time before "out of range"
#define ECHO_WAIT_MS 2000   // ms — max wait for echo to start arriving
#define SOUND_SPEED  0.0343 // cm/µs

// ─── Buzzer tuning ──────────────────────────────────────────────────────────
#define BEEP_ON_MS      30   // ms — how long each beep lasts
#define BEEP_NEAR_MS    50   // ms — beep interval at closest distance
#define BEEP_FAR_MS     1000 // ms — beep interval at farthest distance
#define DIST_NEAR_CM    5    // cm — distance mapped to BEEP_NEAR_MS
#define DIST_FAR_CM     100  // cm — distance mapped to BEEP_FAR_MS

// ─── Fall detection ─────────────────────────────────────────────────────────
#define MPU_ADDR           0x68
#define ACC_FULL_SCALE_16G 0x18

#define FREEFALL_THRESHOLD 0.4
#define IMPACT_THRESHOLD   3.0
#define STILL_THRESHOLD    0.3
#define STILL_DURATION_MS  1000
#define COOLDOWN_MS        5000

enum FallState { WATCHING, FREEFALL_DETECTED, IMPACT_DETECTED };

FallState fallState = WATCHING;
unsigned long freefallTime  = 0;
unsigned long impactTime    = 0;
unsigned long lastAlertTime = 0;

// ─── Ultrasonic sensor state ─────────────────────────────────────────────────
struct Sensor {
  int trigPin, echoPin, buzzerPin;
  long distance;
  bool buzzerState;
  unsigned long lastToggle;
};

Sensor sensors[] = {
  {S1_TRIG, S1_ECHO, S1_BUZZER, 0, false, 0},
  {S2_TRIG, S2_ECHO, S2_BUZZER, 0, false, 0},
};
const int NUM_SENSORS = sizeof(sensors) / sizeof(sensors[0]);

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

  float gx = ax / 2048.0;
  float gy = ay / 2048.0;
  float gz = az / 2048.0;

  return sqrt(gx * gx + gy * gy + gz * gz);
}

// ─── BLE alert ──────────────────────────────────────────────────────────────
void sendFallAlert() {
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
      } else if (now - freefallTime > 500) {
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

// ─── Ultrasonic ping ─────────────────────────────────────────────────────────
long ping(Sensor &s) {
  digitalWrite(s.trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(s.trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(s.trigPin, LOW);

  unsigned long waitStart = millis();
  while (digitalRead(s.echoPin) == LOW) {
    if (millis() - waitStart > ECHO_WAIT_MS) return 0;
  }

  unsigned long echoStart = micros();
  while (digitalRead(s.echoPin) == HIGH) {
    if (micros() - echoStart > MAX_ECHO_US) return 0;
  }

  return (micros() - echoStart) * SOUND_SPEED / 2.0;
}

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

  // MPU-9250 init
  I2CwriteByte(MPU_ADDR, 0x6B, 0x00); // wake up
  delay(100);
  I2CwriteByte(MPU_ADDR, 28, ACC_FULL_SCALE_16G); // ±16g

  pinMode(LED_BUILTIN, OUTPUT);

  // Ultrasonic sensors init
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
  updateFallDetection();

  for (int i = 0; i < NUM_SENSORS; i++) {
    sensors[i].distance = ping(sensors[i]);
    printDistance(i, sensors[i].distance);
    updateBuzzer(sensors[i]);
  }

  delay(20);
}
