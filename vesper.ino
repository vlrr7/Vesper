// ─── Pin assignments ────────────────────────────────────────────────────────
#define S1_TRIG      9
#define S1_ECHO      10
#define S1_BUZZER    7

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

// ─── Sensor state ───────────────────────────────────────────────────────────
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

// ─── Functions ──────────────────────────────────────────────────────────────
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

// ─── Setup / Loop ───────────────────────────────────────────────────────────
void setup() {
  Serial.begin(9600);
  for (int i = 0; i < NUM_SENSORS; i++) {
    pinMode(sensors[i].trigPin, OUTPUT);
    pinMode(sensors[i].echoPin, INPUT);
    pinMode(sensors[i].buzzerPin, OUTPUT);
    digitalWrite(sensors[i].trigPin, LOW);
  }
}

void loop() {
  for (int i = 0; i < NUM_SENSORS; i++) {
    sensors[i].distance = ping(sensors[i]);
    printDistance(i, sensors[i].distance);
    updateBuzzer(sensors[i]);
  }
}
