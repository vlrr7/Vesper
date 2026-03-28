#define TRIG_PIN   9
#define ECHO_PIN   10
#define BUZZER_PIN 7
#define MAX_DIST_CM 200

void setup() {
  Serial.begin(9600);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(TRIG_PIN, LOW);
}

long getDistance() {
  // Lock: wait for ECHO to be LOW before triggering
  // If stuck HIGH for more than 2 seconds, give up
  unsigned long lockStart = millis();
  while (digitalRead(ECHO_PIN) == HIGH) {
    if (millis() - lockStart > 2000) return -1; // stuck
  }

  // Send trigger
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  // Wait for echo to start (ECHO goes HIGH)
  unsigned long start = micros();
  while (digitalRead(ECHO_PIN) == LOW) {
    if (micros() - start > 30000) return 0; // no echo = out of range
  }

  // Measure how long ECHO stays HIGH
  unsigned long echoStart = micros();
  long maxDuration = (long)MAX_DIST_CM * 2 / 0.0343; // µs for max distance
  while (digitalRead(ECHO_PIN) == HIGH) {
    if (micros() - echoStart > maxDuration) return 0; // beyond max range
  }

  long duration = micros() - echoStart;
  return duration * 0.0343 / 2.0;
}

void loop() {
  long distance = getDistance();

  if (distance <= 0) {
    Serial.println(distance == -1 ? "Sensor stuck!" : "Out of range");
    digitalWrite(BUZZER_PIN, LOW);
  } else {
    Serial.print("Distance: ");
    Serial.print(distance);
    Serial.println(" cm");

    long interval = map(distance, 5, 100, 50, 1000);
    interval = constrain(interval, 50, 1000);

    digitalWrite(BUZZER_PIN, HIGH);
    delay(30);
    digitalWrite(BUZZER_PIN, LOW);
    delay(interval);
  }
}
