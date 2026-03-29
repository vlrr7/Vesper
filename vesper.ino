#define TRIG_PIN     9
#define ECHO_PIN     10
#define BUZZER_PIN   7
#define MAX_DURATION 50000  // µs, max echo time (~2m range)
#define ECHO_TIMEOUT 2000   // ms, wait for echo before giving up

long ping() {
  // Send trigger
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  // Wait for ECHO to go HIGH (echo arriving)
  // If nothing within 2s, give up and return 0
  unsigned long waitStart = millis();
  while (digitalRead(ECHO_PIN) == LOW) {
    if (millis() - waitStart > ECHO_TIMEOUT) return 0;
  }

  // Echo arrived — measure how long it stays HIGH
  unsigned long echoStart = micros();
  while (digitalRead(ECHO_PIN) == HIGH) {
    if (micros() - echoStart > MAX_DURATION) return 0; // beyond max range
  }

  // Only returns after echo is fully received
  return micros() - echoStart;
}

void setup() {
  Serial.begin(9600);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(TRIG_PIN, LOW);
}

void loop() {
  long duration = ping();

  if (duration == 0) {
    Serial.println("Out of range");
    digitalWrite(BUZZER_PIN, LOW);
    return;
  }

  long distance = duration * 0.0343 / 2.0;

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
