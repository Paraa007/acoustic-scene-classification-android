# Acoustic Scene Classification App - Projekt-Dokumentation

**FZI Karlsruhe | DCASE 2025 | Android App**
**Stand:** 02.02.2026

---

## Inhaltsverzeichnis

1. [Projekt-Übersicht](#1-projekt-übersicht)
2. [Technologie-Stack](#2-technologie-stack)
3. [Projekt-Struktur](#3-projekt-struktur)
4. [UI-Design & Layout](#4-ui-design--layout)
5. [Haupt-Features](#5-haupt-features)
6. [Audio-Pipeline & ML](#6-audio-pipeline--ml)
7. [Bekannte Probleme & Lösungen](#7-bekannte-probleme--lösungen)
8. [Anleitung für Benutzer](#8-anleitung-für-benutzer)
9. [Für Entwickler: Nächste Schritte](#9-für-entwickler-nächste-schritte)

---

## 1. Projekt-Übersicht

### Was ist das?

Eine Android-App für **Acoustic Scene Classification** (akustische Szenenklassifikation). Die App:
- Nimmt Audio auf (1s, 5s, 10s oder 30min-Intervall)
- Extrahiert Mel-Spektrogramme
- Klassifiziert die akustische Umgebung mit PyTorch Mobile
- Zeigt Ergebnisse in Echtzeit mit animiertem UI

### 8 Szenen-Klassen (DCASE 2025)

| Nr. | Klasse | Emoji | Beschreibung |
|-----|--------|-------|--------------|
| 1 | Transit_Vehicles | 🚗 | Transit - Fahrzeuge/draußen |
| 2 | Urban_Waiting | 🏙️ | Außen-urban & Transit-Gebäude/Wartezonen |
| 3 | Nature | 🌲 | Außen - naturbetont |
| 4 | Social | 👥 | Innen - Soziale Umgebung |
| 5 | Work | 💼 | Innen - Arbeitsumgebung |
| 6 | Commercial | 🛒 | Innen - Kommerzielle/belebte Umgebung |
| 7 | Leisure_Sport | ⚽ | Innen - Freizeit/Sport |
| 8 | Culture_Quiet | 🎭 | Innen - Kultur/Freizeit ruhig |

---

## 2. Technologie-Stack

| Komponente | Technologie |
|------------|-------------|
| **Platform** | Android (Min SDK 26, Target SDK 36) |
| **Sprache** | Kotlin |
| **ML Framework** | PyTorch Mobile 1.13.1 |
| **Architektur** | MVVM mit Kotlin Coroutines |
| **UI** | Material Design 3, Custom Views |
| **Audio** | Android AudioRecord, TarsosDSP (FFT) |
| **State Management** | StateFlow, Sealed Classes |

### Dependencies (build.gradle.kts)

```kotlin
// Android Core
implementation("androidx.core:core-ktx:1.12.0")
implementation("com.google.android.material:material:1.11.0")

// PyTorch Mobile
implementation("org.pytorch:pytorch_android:1.13.1")
implementation("org.pytorch:pytorch_android_torchvision:1.13.1")

// Lifecycle & ViewModel
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// TarsosDSP (FFT)
implementation("be.tarsos.dsp:core:2.5")
implementation("be.tarsos.dsp:jvm:2.5")
```

---

## 3. Projekt-Struktur

```
app/src/main/
├── java/com/fzi/acousticscene/
│   ├── MainActivity.kt              # Haupt-Activity
│   ├── HistoryActivity.kt           # History-Screen
│   ├── WelcomeActivity.kt           # Model-Auswahl
│   │
│   ├── audio/
│   │   ├── AudioRecorder.kt         # Audio-Aufnahme + Echtzeit-Volume
│   │   ├── RecordingState.kt        # Recording States (Sealed Class)
│   │   └── MelSpectrogramProcessor.kt # Mel-Spectrogram Berechnung
│   │
│   ├── ml/
│   │   └── ModelInference.kt        # PyTorch Model Loading & Inference
│   │
│   ├── model/
│   │   ├── ClassificationResult.kt  # Ergebnis-Datenklasse
│   │   ├── RecordingMode.kt         # 4 Modi: FAST, MEDIUM, STANDARD, LONG
│   │   ├── SceneClass.kt            # 8 Szenen-Klassen
│   │   └── PredictionRecord.kt      # DB-Record
│   │
│   ├── ui/
│   │   ├── MainViewModel.kt         # ViewModel (State Management)
│   │   ├── AppState.kt              # App States (Sealed Class)
│   │   ├── ConfidenceCircleView.kt  # Circular Progress Custom View
│   │   ├── RipplePulseView.kt       # Sonar Animation
│   │   └── VolumeLineChartView.kt   # Echtzeit-Volumen-Graph (NEU!)
│   │
│   ├── data/
│   │   └── PredictionRepository.kt  # Datenpersistenz
│   │
│   └── service/
│       └── ClassificationService.kt # Foreground Service für Hintergrund
│
├── res/
│   ├── layout/
│   │   └── activity_main.xml        # Haupt-Layout (Dark Theme)
│   └── values/
│       ├── colors.xml               # Farben
│       └── strings.xml              # Texte
│
└── AndroidManifest.xml              # Permissions & Service-Deklaration
```

---

## 4. UI-Design & Layout

### 4.1 Farbschema (Dark Theme)

| Element | Farbe | Hex |
|---------|-------|-----|
| Hintergrund | Fast Schwarz | `#0D0D0D` |
| Karten | Dunkelgrau | `#1A1A1A` |
| Akzent | Grün | `#1B5E20` / `#4CAF50` |
| Text Primär | Weiß | `#FFFFFF` |
| Text Sekundär | Hellgrau | `#B0B0B0` |

### 4.2 Layout-Struktur (von oben nach unten)

```
┌─────────────────────────────────────────┐
│  Header: App-Name + Model Status        │
├─────────────────────────────────────────┤
│                                         │
│      ┌───────────────────────┐          │
│      │   Confidence Circle   │          │
│      │        87%            │          │
│      │   + Ripple Animation  │          │
│      └───────────────────────┘          │
│        "🌲 Außen - naturbetont"         │
│                                         │
├─────────────────────────────────────────┤
│  Top 3 Predictions Card (toggle)        │
├─────────────────────────────────────────┤
│  Volume Analysis Card (NEU!)            │
│  [Switch: Show Live Data]               │
│  ┌─────────────────────────────────┐    │
│  │  Echtzeit-Volumen-Graph         │    │
│  │  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~   │    │
│  └─────────────────────────────────┘    │
├─────────────────────────────────────────┤
│  Recording Mode:                        │
│  [Fast] [Medium] [Standard] [Long]      │
├─────────────────────────────────────────┤
│  ┌─────────────────────────────────┐    │
│  │      🎤 Start Recording         │    │
│  └─────────────────────────────────┘    │
├─────────────────────────────────────────┤
│  Progress Bar + Timer                   │
├─────────────────────────────────────────┤
│  Status Card: "Idle" / "Recording"      │
├─────────────────────────────────────────┤
│  Statistics Card                        │
├─────────────────────────────────────────┤
│  History Card + CSV Export              │
└─────────────────────────────────────────┘
```

### 4.3 Recording Modes

| Modus | Dauer | Use Case |
|-------|-------|----------|
| **FAST** | 1s | Schnelle Echtzeit-Klassifikation |
| **MEDIUM** | 5s | Balance zwischen Geschwindigkeit und Genauigkeit |
| **STANDARD** | 10s | Standardmodus, beste Genauigkeit |
| **LONG** | 10s + 30min Pause | Langzeit-Monitoring (spart Batterie) |

---

## 5. Haupt-Features

### 5.1 Echtzeit-Klassifikation

1. **Start Recording** drücken
2. App nimmt Audio auf (je nach Modus)
3. Mel-Spectrogram wird berechnet
4. PyTorch Model klassifiziert
5. Ergebnis wird angezeigt
6. Wiederholt sich automatisch

### 5.2 Volume Visualization (NEU!)

**Komponente:** `VolumeLineChartView.kt`

- **Zeitachse startet mit Recording** (nicht mit Switch!)
- **Switch "Show Live Data":** Bereitet Graph vor (View sichtbar)
- **Recording Start:** Graph füllt sich von links nach rechts
- **Recording Stop:** Graph wird geleert
- **X-Achse:** Passt sich an Recording Mode an (1s, 5s, 10s)
- **Y-Achse:** Volume 0-100%
- **Performance:** 50ms Intervall (20 FPS), Canvas-basiert

### 5.3 Ripple Animation

**Komponente:** `RipplePulseView.kt`

- Sonar-artige Animation
- Reagiert auf Lautstärke in Echtzeit
- Konzentrische Kreise breiten sich aus
- Je lauter, desto mehr Ringe

### 5.4 CSV Export

- Alle Vorhersagen werden gespeichert
- CSV enthält: Timestamp, Scene, Confidence, Inference Time, Battery, etc.
- Export via Share-Intent

### 5.5 Foreground Service

- App läuft im Hintergrund weiter
- Notification zeigt Status
- WakeLock hält CPU wach
- Doze-Mode Protection (siehe Abschnitt 7)

---

## 6. Audio-Pipeline & ML

### 6.1 Audio-Aufnahme

```
AudioRecorder.kt
├── Sample Rate: 32,000 Hz
├── Format: PCM 16-bit Mono
├── Dauer: Konfigurierbar (1s, 5s, 10s)
└── Output: FloatArray (normalisiert -1.0 bis 1.0)
```

### 6.2 Mel-Spectrogram Berechnung

```
MelSpectrogramProcessor.kt
├── nFft: 4096 (FFT Fenster)
├── winLength: 3072
├── hopLength: 500
├── nMels: 256
├── fMin: 0 Hz
├── fMax: 16,000 Hz
└── Output: [1, 1, 256, 641] Tensor
```

**Schritte:**
1. STFT (Short-Time Fourier Transform) mit TarsosDSP
2. Power Spectrum extrahieren
3. Mel-Filterbank anwenden
4. Log-Transformation: `log(mel + 1e-5)`
5. Flatten zu 1D Array für PyTorch

### 6.3 Model Inference

```
ModelInference.kt
├── Input: FloatArray (320,000 samples für 10s)
├── Processing: Mel-Spectrogram auf Background-Thread
├── Model: PyTorch Mobile (.pt Datei)
├── Output: Softmax → 8 Wahrscheinlichkeiten
└── Result: Argmax → Top-Klasse
```

---

## 7. Bekannte Probleme & Lösungen

### 7.1 ANR (App Not Responding)

**Problem:** App fror ein während Mel-Spectrogram Berechnung

**Ursache:** Berechnung lief auf UI-Thread

**Lösung:**
```kotlin
// In ModelInference.infer()
return withContext(Dispatchers.Default) {
    val logMelSpectrogram = melSpectrogramProcessor.computeLogMelSpectrogram(audio)
    // ...
}
```

### 7.2 Langsame FFT

**Problem:** Custom DFT war O(n²), zu langsam

**Lösung:** TarsosDSP Library integriert (O(n log n))

### 7.3 Datenlücken bei Nacht-Aufnahmen (68% Verlust!)

**Problem:** Android Doze Mode ignoriert WakeLock

**Lösung - 4-Schichten-Schutz:**

1. **WakeLock** (Basis)
```kotlin
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "AcousticScene::ClassificationWakeLock"
).apply { acquire(4 * 60 * 60 * 1000L) }
```

2. **AlarmManager Keep-Alive**
```kotlin
alarmManager.setExactAndAllowWhileIdle(
    AlarmManager.ELAPSED_REALTIME_WAKEUP,
    SystemClock.elapsedRealtime() + 30_000L,
    keepAlivePendingIntent
)
```

3. **Höhere Notification Priority**
```kotlin
NotificationCompat.PRIORITY_HIGH
NotificationManager.IMPORTANCE_DEFAULT
```

4. **Batterie-Optimierung deaktivieren (WICHTIGSTE!)**
```kotlin
val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
intent.data = Uri.parse("package:$packageName")
startActivity(intent)
```

### 7.4 TarsosDSP FFT Format

**Problem:** ArrayIndexOutOfBoundsException

**Ursache:** Falsches Verständnis des FFT-Output-Formats

**Format von TarsosDSP:**
```
[DC, Nyquist, Re1, Im1, Re2, Im2, ..., Re(N/2-1), Im(N/2-1)]
```

**Korrekte Extraktion:**
```kotlin
powerSpectrum[0] = fftResult[0] * fftResult[0]           // DC
powerSpectrum[nFft/2] = fftResult[1] * fftResult[1]      // Nyquist
for (bin in 1 until nFft/2) {
    val real = fftResult[2 * bin]
    val imag = fftResult[2 * bin + 1]
    powerSpectrum[bin] = real * real + imag * imag
}
```

---

## 8. Anleitung für Benutzer

### 8.1 Erste Einrichtung

1. **App installieren** (APK oder Play Store)
2. **Mikrofon-Berechtigung erlauben**
3. **Batterie-Optimierung deaktivieren** (Dialog erscheint automatisch)

### 8.2 Normale Nutzung

1. **Recording Mode wählen** (Standard empfohlen)
2. **"Start Recording" drücken**
3. **Ergebnis erscheint** nach Aufnahme-Dauer
4. **Wiederholt sich automatisch**
5. **"Stop Recording"** zum Beenden

### 8.3 Volume Graph nutzen (NEU!)

1. **"Show Live Data" Switch aktivieren** → Graph wird sichtbar
2. **"Start Recording" drücken** → Graph füllt sich
3. **Echtzeit-Visualisierung** der Lautstärke
4. **Stop oder Mode-Wechsel** → Graph wird geleert

### 8.4 Für Langzeit-Aufnahmen (Nacht)

1. **STANDARD-Modus wählen** (nicht LONG für kontinuierliche Daten)
2. **Batterie-Optimierung muss deaktiviert sein!**
3. **Handy am Ladekabel lassen**
4. **App im Vordergrund starten, dann Bildschirm aus**
5. **Morgens: CSV exportieren und prüfen**

### 8.5 Hersteller-spezifische Einstellungen

| Hersteller | Einstellung |
|------------|-------------|
| **Xiaomi** | Sicherheit → Akku → Keine Einschränkungen + Autostart |
| **Huawei** | Telefonmanager → Geschützte Apps |
| **Samsung** | Einstellungen → Apps → Akku → Nicht optimieren |

---

## 9. Für Entwickler: Nächste Schritte

### Offene Aufgaben

- [ ] Unit Tests für Mel-Spectrogram (Vergleich mit Python-Referenz)
- [ ] Model Quantization (kleinere Dateigröße, schneller)
- [ ] Real-time Streaming (keine feste Aufnahme-Dauer)
- [ ] Offline-Mode Verbesserungen
- [ ] FZI Branding/Logo
- [ ] Mehrsprachige Unterstützung (EN/DE)

### Wichtige Code-Stellen

| Feature | Datei | Zeile/Methode |
|---------|-------|---------------|
| Recording starten | `MainViewModel.kt` | `startClassification()` |
| Audio aufnehmen | `AudioRecorder.kt` | `startRecording()` |
| Mel-Spectrogram | `MelSpectrogramProcessor.kt` | `computeLogMelSpectrogram()` |
| ML Inferenz | `ModelInference.kt` | `infer()` |
| Volume Graph | `VolumeLineChartView.kt` | `addDataPoint()` |
| Doze Protection | `ClassificationService.kt` | `startKeepAliveAlarm()` |

### Git Branch für Features

- Feature-Branches: `claude/feature-name-*`
- Main Development: Auf `main` mergen nach Review

---

## Kontakt & Support

**Projekt:** FZI Acoustic Scene Classifier
**Institut:** FZI Forschungszentrum Informatik, Karlsruhe
**Wettbewerb:** DCASE 2025

---

*Letzte Aktualisierung: 02.02.2026*
