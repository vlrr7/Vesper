#define MAX_DURATION 96000  // µs, max echo time

struct Sensor {
  int trigPin, echoPin, buzzerPin;
  long distance;
  bool buzzerState;
  unsigned long lastToggle;
};

Sensor sensors[2] = {
  {9,  10, 7,  0, false, 0},
  {22, 24, 26, 0, false, 0}
};

long ping(Sensor &s) {
  digitalWrite(s.trigPin, LOW);
  delayMicroseconds(2);
  digitalWrite(s.trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(s.trigPin, LOW);

  // Wait for ECHO to go HIGH, timeout 2s
  unsigned long waitStart = millis();
  while (digitalRead(s.echoPin) == LOW) {
    if (millis() - waitStart > 2000) return 0;
  }

  // Measure how long ECHO stays HIGH
  unsigned long echoStart = micros();
  while (digitalRead(s.echoPin) == HIGH) {
    if (micros() - echoStart > MAX_DURATION) return 0;
  }

  long duration = micros() - echoStart;
  return duration * 0.0343 / 2.0;
}

void updateBuzzer(Sensor &s) {
  if (s.distance == 0) {
    digitalWrite(s.buzzerPin, LOW);
    s.buzzerState = false;
    return;
  }

  long interval = map(s.distance, 5, 100, 50, 1000);
  interval = constrain(interval, 50, 1000);

  unsigned long now = millis();
  if (s.buzzerState && now - s.lastToggle >= 30) {
    digitalWrite(s.buzzerPin, LOW);
    s.buzzerState = false;
    s.lastToggle = now;
  } else if (!s.buzzerState && now - s.lastToggle >= interval) {
    digitalWrite(s.buzzerPin, HIGH);
    s.buzzerState = true;
    s.lastToggle = now;
  }
}

void setup() {
  Serial.begin(9600);
  for (auto &s : sensors) {
    pinMode(s.trigPin, OUTPUT);
    pinMode(s.echoPin, INPUT);
    pinMode(s.buzzerPin, OUTPUT);
    digitalWrite(s.trigPin, LOW);
  }
}

void loop() {
  for (auto &s : sensors) {
    s.distance = ping(s);
    Serial.print("Sensor ");
    Serial.print(&s == &sensors[0] ? 1 : 2);
    Serial.print(": ");
    Serial.println(s.distance == 0 ? "Out of range" : String(s.distance) + " cm");
    updateBuzzer(s);
  }
}
