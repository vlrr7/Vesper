#include "SR04.h"

#define BUZZER_PIN 7

SR04 sr04(10, 9); // echoPin, triggerPin

void setup() {
  Serial.begin(9600);
  pinMode(BUZZER_PIN, OUTPUT);
}

void loop() {
  long distance = sr04.Distance();

  if (distance == 0) {
    Serial.println("Out of range");
    digitalWrite(BUZZER_PIN, LOW);
    delay(1000);
  } else {
    Serial.print("Distance: ");
    Serial.print(distance);
    Serial.println(" cm");

    // Closer = shorter interval. Range: 5cm (50ms) to 100cm (1000ms)
    long interval = map(distance, 5, 100, 50, 1000);
    interval = constrain(interval, 50, 1000);

    digitalWrite(BUZZER_PIN, HIGH);
    delay(30);
    digitalWrite(BUZZER_PIN, LOW);
    delay(interval);
  }
}
