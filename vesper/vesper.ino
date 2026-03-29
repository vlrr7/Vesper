// =============================================
// Vesper — Détection de chute + Alerte BLE
// Arduino Mega 2560 + MPU-9250 + HM-10
// =============================================
//
// Câblage HM-10:
//   HM-10 TX  → Mega pin 19 (RX1)
//   HM-10 RX  → Mega pin 18 (TX1) via diviseur de tension
//   HM-10 VCC → 3.3V
//   HM-10 GND → GND
//
// Le module communique sur Serial1 à 9600 baud (défaut HM-10)

#include <Wire.h>

#define MPU_ADDR 0x68
#define ACC_FULL_SCALE_16G 0x18

// ---- Seuils (en g) ----
#define FREEFALL_THRESHOLD 0.4
#define IMPACT_THRESHOLD   3.0
#define STILL_THRESHOLD    0.3
#define STILL_DURATION_MS  1000
#define COOLDOWN_MS        5000

// ---- Machine à états ----
enum FallState {
  WATCHING,
  FREEFALL_DETECTED,
  IMPACT_DETECTED
};

FallState state = WATCHING;
unsigned long freefallTime = 0;
unsigned long impactTime = 0;
unsigned long lastAlertTime = 0;

// ---- I2C ----
void I2CwriteByte(uint8_t addr, uint8_t reg, uint8_t data) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(data);
  Wire.endTransmission();
}

// ---- Lecture accéléromètre ----
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

// ---- Envoyer alerte BLE ----
void sendFallAlert() {
  // Envoyer sur Serial (debug USB) et Serial1 (HM-10 BLE)
  Serial.println(">>>  CHUTE DETECTEE!  <<<");
  Serial1.println("FALL");

  // Clignoter la LED
  for (int i = 0; i < 10; i++) {
    digitalWrite(LED_BUILTIN, HIGH);
    delay(100);
    digitalWrite(LED_BUILTIN, LOW);
    delay(100);
  }
}

void setup() {
  Serial.begin(115200);   // Debug USB
  Serial1.begin(9600);    // HM-10 (défaut 9600)
  Wire.begin();

  // Réveiller le MPU
  I2CwriteByte(MPU_ADDR, 0x6B, 0x00);
  delay(100);

  // ±16g
  I2CwriteByte(MPU_ADDR, 28, ACC_FULL_SCALE_16G);

  pinMode(LED_BUILTIN, OUTPUT);

  Serial.println("=== Vesper Fall Detection ===");
  Serial.println("BLE: HM-10 sur Serial1");
  Serial.println("Pret.");

  // Petit test: envoyer un message BLE au démarrage
  Serial1.println("VESPER_READY");

  delay(1000);
}

void loop() {
  float mag = readAccMagnitude();
  unsigned long now = millis();

  switch (state) {

    case WATCHING:
      if (mag < FREEFALL_THRESHOLD) {
        state = FREEFALL_DETECTED;
        freefallTime = now;
        Serial.print("! Chute libre: ");
        Serial.print(mag, 2);
        Serial.println("g");
      }
      break;

    case FREEFALL_DETECTED:
      if (mag > IMPACT_THRESHOLD) {
        state = IMPACT_DETECTED;
        impactTime = now;
        Serial.print("! Impact: ");
        Serial.print(mag, 2);
        Serial.println("g");
      }
      else if (now - freefallTime > 500) {
        state = WATCHING;
      }
      break;

    case IMPACT_DETECTED:
      if (abs(mag - 1.0) < STILL_THRESHOLD) {
        if (now - impactTime > STILL_DURATION_MS) {
          if (now - lastAlertTime > COOLDOWN_MS) {
            sendFallAlert();
            lastAlertTime = now;
          }
          state = WATCHING;
        }
      }
      else if (now - impactTime > 3000) {
        Serial.println("  -> Fausse alerte");
        state = WATCHING;
      }
      break;
  }

  // Écouter les commandes BLE entrantes (optionnel)
  if (Serial1.available()) {
    String cmd = Serial1.readStringUntil('\n');
    cmd.trim();

    if (cmd == "PING") {
      Serial1.println("PONG");
      Serial.println("BLE: PING recu, PONG envoye");
    }
    else if (cmd == "STATUS") {
      Serial1.println("OK");
      Serial.println("BLE: STATUS demande");
    }
  }

  delay(20); // ~50Hz
}
