# Acoustic Scene Classification App - Projekt-Dokumentation

**FZI Karlsruhe | DCASE 2025 | Android App**
**Stand:** 2026-05-17

---

## Inhaltsverzeichnis

1. [Projekt-Übersicht](#1-projekt-übersicht)
2. [Technologie-Stack](#2-technologie-stack)
3. [Projekt-Struktur](#3-projekt-struktur)
4. [UI-Flow & Layout](#4-ui-flow--layout)
5. [Haupt-Features](#5-haupt-features)
6. [Audio-Pipeline & ML](#6-audio-pipeline--ml)
7. [Bekannte Probleme & Lösungen](#7-bekannte-probleme--lösungen)
8. [Anleitung für Benutzer](#8-anleitung-für-benutzer)
9. [Für Entwickler: Nächste Schritte](#9-für-entwickler-nächste-schritte)

---

## 1. Projekt-Übersicht

Eine Android-App für **Acoustic Scene Classification** (akustische Szenenklassifikation). Sie nimmt Umgebungsaudio über das Mikrofon auf, rechnet Log-Mel-Spektrogramme und schickt diese durch ein PyTorch-Mobile-Modell, das die Umgebung einer von 9 Szenenklassen zuordnet (DCASE 2025).

Der Bedien-Flow ist als geleiteter Wizard angelegt: Welcome → Wizard (5 oder 6 Schritte) → Live-Recording → Results-Summary. Es gibt keinen User/Dev-Modus mehr — alles läuft über denselben Pfad. Die App ist auf Modell-Vergleich ausgelegt: pro Aufnahme können mehrere Modelle parallel laufen, und jedes Ergebnis landet in History und CSV-Export.

### 9 Szenen-Klassen (DCASE 2025)

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
| 9 | Living_Room | 🏠 | Innen - Wohnbereich |

---

## 2. Technologie-Stack

| Komponente | Technologie |
|------------|-------------|
| **Platform** | Android (Min SDK 26, Target/Compile SDK 36) |
| **Sprache** | Kotlin 2.0.21 |
| **Build** | Gradle 8.13.2 (KTS, Version Catalog) |
| **ML Framework** | PyTorch Mobile 1.13.1 |
| **Architektur** | Single Activity + Navigation Component, MVVM |
| **UI** | Material Design 3, View Binding, Custom Views |
| **Audio** | Android AudioRecord, TarsosDSP (FFT) |
| **State Management** | StateFlow + sealed classes |

### Dependencies (Auszug aus `build.gradle.kts`)

```kotlin
// Android Core + Material 3
implementation("androidx.core:core-ktx:...")
implementation("com.google.android.material:material:...")

// Navigation Component
implementation("androidx.navigation:navigation-fragment-ktx:...")
implementation("androidx.navigation:navigation-ui-ktx:...")

// PyTorch Mobile
implementation("org.pytorch:pytorch_android:1.13.1")
implementation("org.pytorch:pytorch_android_torchvision:1.13.1")

// Lifecycle, ViewModel, Process-Lifecycle
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:...")
implementation("androidx.lifecycle:lifecycle-process:2.7.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:...")

// TarsosDSP (FFT)
implementation("be.tarsos.dsp:core:2.5")
implementation("be.tarsos.dsp:jvm:2.5")
```

TarsosDSP kommt aus einem Custom-Maven-Repo (`https://mvn.0110.be/releases`), konfiguriert in `settings.gradle.kts`.

---

## 3. Projekt-Struktur

```
app/src/main/
├── java/com/fzi/acousticscene/
│   ├── audio/
│   │   ├── AudioRecorder.kt              # 32 kHz Mono PCM + RMS-Volume-Aggregation pro Cycle
│   │   ├── MelSpectrogramProcessor.kt    # FFT → Mel-Filterbank → log
│   │   └── RecordingState.kt             # Recording States (sealed class)
│   │
│   ├── ml/
│   │   ├── ModelInference.kt             # PyTorch Mobile Loading + Inferenz
│   │   └── ComputationDispatcher.kt      # Background-Thread-Pool für ML
│   │
│   ├── model/
│   │   ├── SceneClass.kt                 # 9 Szenen-Klassen (deutsche Labels)
│   │   ├── RecordingMode.kt              # Continuous/Interval + Sub-Modes
│   │   ├── LongSubMode.kt                # Standard/Fast/Avg + isCompatibleWith()
│   │   ├── ModelTrainingDuration.kt      # 1s/10s aus Modell-Filename
│   │   ├── SessionDuration.kt            # 30min/1h/3h/6h/12h/MANUAL
│   │   ├── LongInterval.kt               # 10min/15min/30min/45min/1h/3h
│   │   ├── WizardStep.kt                 # Sealed class mit allen Wizard-Schritten
│   │   ├── SessionConfig.kt              # Vom Wizard erzeugte Session-Config
│   │   ├── ClassificationResult.kt
│   │   ├── PredictionRecord.kt           # inkl. volumeMean/volumePeak/isPause/pauseDurationSec
│   │   ├── ModelConfig.kt
│   │   ├── ModelInfo.kt + ModelInfoRegistry.kt   # Trainings-Metadaten pro Modell
│   │
│   ├── data/
│   │   ├── PredictionRepository.kt       # Singleton via getInstance(context), 10k-Limit
│   │   ├── ActiveSessionRegistry.kt      # Prozessweite Live-Session-Tracking
│   │   └── LastConfigStore.kt            # Letzte SessionConfig für "Use last config"
│   │
│   ├── service/
│   │   └── ClassificationService.kt      # Foreground Service mit WakeLock
│   │
│   ├── ui/
│   │   ├── MainActivity.kt               # NavHost mit nav_graph.xml
│   │   ├── HistoryActivity.kt
│   │   ├── EvaluationActivity.kt
│   │   ├── WelcomeFragment.kt            # Home-Page mit 4 Buttons
│   │   ├── WizardFragment.kt             # Alle Wizard-Schritte in einem Fragment
│   │   ├── WizardViewState.kt            # Helper für Wizard-State-Logik
│   │   ├── LiveRecordingFragment.kt      # Live-Bars, Stopp-Uhr, Volume-Graph, Pause-Picker
│   │   ├── ResultsSummaryFragment.kt     # Aggregat pro Modell × Methode nach Session-Ende
│   │   ├── SettingsFragment.kt
│   │   ├── MainViewModel.kt              # Wizard- + Session-State über StateFlow<UiState>
│   │   ├── AppState.kt                   # Sealed class: Idle/Loading/Ready/Recording/Paused/...
│   │   ├── ModernDialogHelper.kt         # Detail-Dialog inkl. Pause-Trennlinien
│   │   ├── EvaluationPromptBus.kt
│   │   ├── BarDistributionView.kt        # 9 horizontale Bars in Klassenfarben
│   │   ├── ConcentricStopwatchView.kt    # Äußerer/innerer Ring + AVG-10-Segmente
│   │   ├── ConfidenceCircleView.kt       # Per-Second-Mini-Kreise im AVG-Modus
│   │   └── VolumeLineChartView.kt        # Permanenter Volume-Graph
│   │
│   └── util/
│       ├── ThemeHelper.kt
│       └── ModelDisplayNameHelper.kt
│
├── res/
│   ├── layout/
│   │   ├── activity_main.xml             # Plain NavHostFragment, keine Bottom-Nav
│   │   ├── fragment_welcome.xml
│   │   ├── fragment_wizard.xml
│   │   ├── fragment_live_recording.xml
│   │   ├── fragment_results_summary.xml
│   │   ├── fragment_settings.xml
│   │   ├── activity_history.xml
│   │   ├── dialog_history_details.xml    # Distribution + Method Comparison + Pauses-Sektion
│   │   ├── dialog_pause_duration.xml
│   │   └── ...
│   ├── navigation/
│   │   └── nav_graph.xml                 # Welcome → Wizard → Live → Results
│   └── values/
│       ├── colors.xml
│       └── strings.xml                   # Englisch (außer SceneClass-Labels)
│
├── dev_models/                           # PyTorch-Modelle (.pt)
└── AndroidManifest.xml
```

---

## 4. UI-Flow & Layout

### 4.1 Farbschema (Dark Theme)

| Element | Farbe | Hex |
|---------|-------|-----|
| Hintergrund | Fast Schwarz | `#0D0D0D` |
| Karten | Dunkelgrau | `#1A1A1A` |
| Akzent | Grün | `#1B5E20` / `#4CAF50` |
| Text Primär | Weiß | `#FFFFFF` |
| Text Sekundär | Hellgrau | `#B0B0B0` |

Über den Theme-Switch auf der Welcome-Page lässt sich auf hellen Modus umstellen — der Switch persistiert die Wahl, alle Bildschirme passen sich an.

### 4.2 Top-Level-Flow

```
┌──────────┐    ┌──────────┐    ┌──────────────┐    ┌──────────────────┐
│ Welcome  │───▶│  Wizard  │───▶│ Live         │───▶│ Results Summary  │
│ (Home)   │    │ (5–6     │    │ Recording    │    │                  │
│          │    │  Schritte)│   │              │    │                  │
└──────────┘    └──────────┘    └──────────────┘    └──────────────────┘
     ▲                                                        │
     └────────────────────────────────────────────────────────┘
                  „Back to Home"

Zusätzlich von Welcome erreichbar: History · Settings
```

Keine Bottom-Nav. Navigation läuft über die Welcome-Buttons + Back-Pfeile.

### 4.3 Wizard-Pfade

- **Continuous (5 Schritte):** Modelle → Kategorie → Clip-Dauer → Session-Dauer → Übersicht.
- **Interval (6 Schritte):** Modelle → Kategorie → Pausen-Intervall → Methoden pro Modell → Session-Dauer → Übersicht.

Inkompatible Modell × Methoden-Combos werden ausgegraut. 10s-Modelle dürfen nur Standard, 1s-Modelle nur Fast und Avg. Bei Mixed-Duration-Auswahl im Continuous (10s + 1s gleichzeitig) erscheint ein Hinweis-Text. Auf der Übersichts-Seite ist jede Sektion klickbar und springt zum entsprechenden Schritt zurück, ohne den Wizard-State zu verlieren.

### 4.4 Live-Recording-Layout (Skizze)

```
┌─────────────────────────────────────────────────────────┐
│              Konzentrische Stopp-Uhr                    │
│              0:43:12 / 3:00:00                          │
│              Recording · 7s / 10s                       │
├─────────────────────────────────────────────────────────┤
│ 🧠 dcase2025_10s_04_06_64bt                             │
│   1 — Standard                                          │
│     🌳 Park              ████████████████ 84%           │
│     🚗 Transit           ███ 8%                         │
│     ...                                                 │
│   2 — Fast                                              │
│     ...                                                 │
│   3 — Avg                                               │
│     ...                                                 │
│     [ Show Live Data ▾ ]   ← per-Card-Toggle, default   │
│                              aus, aufgeklappt 10        │
│                              Per-Second-Mini-Zellen     │
│                                                         │
│ 🧠 dcase2025_1s_04_06_128bt   (zweite Card)             │
│   ... gleiche Struktur ...                              │
├─────────────────────────────────────────────────────────┤
│  Volume-Graph (permanent sichtbar, kein Toggle)         │
├─────────────────────────────────────────────────────────┤
│         [ Pause ]            [ Stop ]                   │
└─────────────────────────────────────────────────────────┘
```

Pro Modell × Methode 9 Bars in Klassenfarben, sortiert nach Wahrscheinlichkeit absteigend. AVG zeigt den laufenden Durchschnitt; aufgeklappt 10 Mini-Zellen mit Emoji oben (13 sp) und Konfidenz-Kreis darunter (28 dp), eines pro Sekunde.

---

## 5. Haupt-Features

### 5.1 Geleiteter Wizard
5 oder 6 Schritte je nach Aufnahme-Kategorie. Schrittweises Zurücknavigieren erhält den State. Die Übersicht ist klickbar — jede Zeile springt zum Schritt zurück.

### 5.2 Multi-Model parallel
Im Wizard beliebig viele Modelle wählbar. Pro Cycle laufen alle Modelle auf demselben Audio. Eine Card pro Modell mit eigener Bar-Distribution. Im Interval zusätzlich pro Modell wählbare Methoden (Standard / Fast / Avg).

### 5.3 Drei Auswertungs-Methoden
- **Standard** — füttert die ganzen 10 s ans Modell (passt nur zu 10s-Modellen).
- **Fast** — schickt einen 1 s-Slice (passt nur zu 1s-Modellen).
- **Avg** — zerteilt den 10 s-Buffer in 10 × 1 s-Clips, mittelt die Wahrscheinlichkeiten (passt nur zu 1s-Modellen).

### 5.4 Konzentrische Stopp-Uhr
Äußerer Ring = Session-Progress, innerer Ring = Cycle-Progress. AVG-Cycles werden in 10 Segmente unterteilt, die sich live einer pro Sekunde füllen. Bei „Stop manually" wird der äußere Ring grau und zählt nur hoch.

### 5.5 Pause/Resume mit Timer-Picker
Tap auf Pause öffnet einen Picker mit `No timer` · 5 min · 10 min · 30 min · 1 h. Bei Timer-Wahl resumed die Session automatisch nach Ablauf; manuell weiter geht jederzeit. Pausen werden clipgenau angewendet (laufender Cycle wird zu Ende geführt) und als eigene Pause-Records in History und CSV geschrieben.

### 5.6 Soft-Stop bei Session-Ende
Wenn die Session-Dauer abläuft, während ein Cycle läuft: der Cycle wird zu Ende geführt, dann gestoppt, kein neuer angefangen, automatischer Wechsel auf die Results-Summary.

### 5.7 Volume-Aggregation pro Cycle
`AudioRecorder` sammelt RMS-Samples mit ~30 Hz und liefert pro Cycle Mean + Peak (Skala 0.0–1.0). Beides landet in `PredictionRecord` und in den CSV-Spalten `volume_mean` / `volume_peak`. Bei AVG zusätzlich pro 1 s-Clip mitgeführt.

### 5.8 Results-Summary
Nach Stop oder Auto-Stop landet der User auf einem Screen, der pro Modell × Methode die finalen Bar-Distributions zeigt (aggregiert über alle Cycles). Pro Card: Anzahl Cycles, häufigste Klasse, Durchschnitts-Volume. Buttons: `Back to Home`, `Open History`.

### 5.9 History
Alle Sessions als Karten mit Config-Label (`🧠 model · Continuous · Standard 10s` o. ä.), Aufnahmezahl, Dauer, Batterie-Verbrauch. Tap auf eine Session öffnet einen Detail-Dialog mit Distribution, Method Comparison (bei mehreren Methoden), Per-Second Clips (bei AVG), User Evaluations (bei Interval) und einer Pause-Sektion mit grauen Trennlinien pro Pause. Long-Press aktiviert die Mehrfachauswahl für Bulk-Export oder -Delete.

### 5.10 CSV-Export
Pro Aufnahme-Cycle eine Zeile. Der Header gruppiert die Spalten in vier logische Blöcke:

1. **Session-Meta:** `id`, `timestamp`, `session_start_time`, `session_duration_planned` (z. B. `30 min` / `Stop manually`).
2. **Cycle + Wizard-Snapshot:** `battery_percent`, `class_display_name`, `confidence_percent`, `inference_time_sec`, `recording_mode`, `model_name` (Primary), `models_selected` (`m1.pt|m2.pt`), `category` (`Continuous`/`Interval`), `continuous_methods_by_model` (`m1.pt:STANDARD,AVERAGE|m2.pt:FAST`, leer im Interval), `interval_methods_by_model` (gleiche Serialisierung, leer im Continuous), `pause_auto_resume_min` (nur in PAUSE-Rows gesetzt).
3. **Klassifikation:** `top1..3_display_name` + `top1..3_confidence_percent`, `probabilities[…]`, `user_selected_class`, `per_second_clips` (bei AVG), `long_standard`/`long_fast`/`long_average`/`long_interval_min` (bei Interval).
4. **Volume + Pause:** `volume_mean`, `volume_peak`, `volume_s1`..`volume_s10` (Per-Sekunden-RMS-Mean über das 10 s-Frame; bei FAST nur `s1` gefüllt, Rest 0), `pause_duration_sec`. Bei Multi-Model-Continuous folgen dynamische `allinone_<model>`-Spalten am Ende.

Pause-Records (`recording_mode = "PAUSE"`) tragen denselben Session-Snapshot wie die regulären Records darum herum — so bleibt eine Session auch beim Filtern auf PAUSE-Rows greifbar. Volume-Spalten sind in PAUSE-Rows mit `0.000` gefüllt (nicht leer), damit eine numerische Spalte ihren Typ behält. Records von vor der 2026-05-17-Migration tragen leere Zellen in den neuen Spalten — Gson liest sie ohne Crash, der CSV-Export setzt sie auf `""`.

### 5.11 Evaluation (Interval-only)
Nach jeder Interval-Aufnahme bekommt der User eine Notification (Background) bzw. eine in-App-Card (Foreground), wo er die tatsächliche Szene angeben kann. Das Timing folgt dem im Wizard gewählten Pausen-Intervall. Im Continuous gibt es keine Evaluation-Card (würde alle paar Sekunden erscheinen).

### 5.12 Foreground Service
`ClassificationService` mit WakeLock + AlarmManager-Keep-Alive. Notification zeigt den Session-Status. Aufnahmen laufen durch, auch wenn der Bildschirm aus ist oder die App im Hintergrund liegt.

---

## 6. Audio-Pipeline & ML

### 6.1 Audio-Aufnahme

```
AudioRecorder.kt
├── Sample Rate: 32 000 Hz
├── Format: PCM 16-bit Mono
├── Cycle-Dauer: 1 s (Fast/Avg) bzw. 10 s (Standard)
├── Volume: RMS pro 33 ms-Buffer, Mean + Peak pro Cycle
└── Output: FloatArray (normalisiert -1.0 … 1.0)
```

### 6.2 Mel-Spektrogramm

```
MelSpectrogramProcessor.kt
├── nFft:      4 096 (FFT-Fenster)
├── winLength: 3 072
├── hopLength: 500
├── nMels:     256
├── fMin:      0 Hz
├── fMax:      16 000 Hz
└── Output:    [1, 1, 256, nFrames] Tensor
```

Schritte: STFT (TarsosDSP) → Power-Spektrum → Mel-Filterbank → `log(mel + 1e-5)` → Tensor.

Die Parameter sind für alle Modi zentral in `MelSpectrogramProcessor` definiert. Die Modi unterscheiden sich nur in der Aufnahme-Dauer — daraus ergibt sich die Breite des Spektrogramms (`nFrames`). Details siehe `docs/MODEL_INTEGRATION_SPEC.md`.

### 6.3 Modell-Inferenz

```
ModelInference.kt
├── Input:  FloatArray mit Cycle-Audio
├── Pre:    Mel-Spektrogramm auf Background-Thread (ComputationDispatcher)
├── Modell: PyTorch Mobile (.pt)
├── Output: Softmax → 9 Wahrscheinlichkeiten
└── Result: Top-Klasse + alle Probs in ClassificationResult
```

### 6.4 Modell × Methoden-Compat

`LongSubMode.isCompatibleWith(modelTrainingSeconds)` ist die Single Source of Truth: 10s-Modelle dürfen nur STANDARD, 1s-Modelle nur FAST und AVERAGE. Wizard graut Inkompatibles aus, ViewModel rejectet inkompatible Toggles silent. `ModelTrainingDuration.secondsForFilename(model)` parst die Trainings-Dauer aus dem Filename (Pattern `_1s_` / `_10s_`); fällt auf 10s zurück, wenn nichts passt.

---

## 7. Bekannte Probleme & Lösungen

### 7.1 ANR (App Not Responding)

**Problem:** App fror ein während Mel-Spektrogramm-Berechnung — Berechnung lief auf UI-Thread.

**Lösung:**
```kotlin
// In ModelInference.infer()
return withContext(Dispatchers.Default) {
    val logMelSpectrogram = melSpectrogramProcessor.computeLogMelSpectrogram(audio)
    // ...
}
```

### 7.2 Langsame FFT

Custom DFT war O(n²), zu langsam. TarsosDSP integriert (O(n log n)).

### 7.3 Datenlücken bei Nacht-Aufnahmen (68 % Verlust!)

Android Doze Mode ignoriert WakeLock. Lösung in vier Schichten:

1. **WakeLock:**
```kotlin
wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "AcousticScene::ClassificationWakeLock"
).apply { acquire(4 * 60 * 60 * 1000L) }
```

2. **AlarmManager Keep-Alive:**
```kotlin
alarmManager.setExactAndAllowWhileIdle(
    AlarmManager.ELAPSED_REALTIME_WAKEUP,
    SystemClock.elapsedRealtime() + 30_000L,
    keepAlivePendingIntent
)
```

3. **Höhere Notification-Priorität** (`PRIORITY_HIGH`, `IMPORTANCE_DEFAULT`).

4. **Batterie-Optimierung deaktivieren** — der wichtigste Schritt:
```kotlin
val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
intent.data = Uri.parse("package:$packageName")
startActivity(intent)
```

### 7.4 TarsosDSP-FFT-Format

ArrayIndexOutOfBoundsException, weil das Output-Format missverstanden war. TarsosDSP liefert:

```
[DC, Nyquist, Re1, Im1, Re2, Im2, ..., Re(N/2-1), Im(N/2-1)]
```

Korrekte Extraktion:

```kotlin
powerSpectrum[0]      = fftResult[0] * fftResult[0]   // DC
powerSpectrum[nFft/2] = fftResult[1] * fftResult[1]   // Nyquist
for (bin in 1 until nFft/2) {
    val real = fftResult[2 * bin]
    val imag = fftResult[2 * bin + 1]
    powerSpectrum[bin] = real * real + imag * imag
}
```

### 7.5 Pause-Records verfälschten Aggregate

Pause-Records werden mit einem `SceneClass.TRANSIT_VEHICLES`-Placeholder und `confidence = 0` synthetisch geschrieben. Bis 2026-05-07 wurden sie in `PredictionRepository.getStatistics()` und `HistoryActivity.calculatePackageStatistics()` mitgezählt — die Class-Distribution war damit verfälscht und die Recordings-Zahl in der History inflated. Fix: alle Aggregate-Pfade filtern jetzt `filterNot { it.isPause }` vorgeschaltet. Die Detail-Dialog-Mode/Model-Detection orientiert sich an `realRecords` statt `firstOrNull()`.

---

## 8. Anleitung für Benutzer

### 8.1 Erste Einrichtung

1. App installieren (APK oder Play Store).
2. Mikrofon-Berechtigung erlauben.
3. Notification-Berechtigung erlauben (Android 13+).
4. Batterie-Optimierung deaktivieren — Dialog erscheint automatisch.

### 8.2 Eine neue Session starten

1. Auf der Welcome-Page **Start new session** tippen.
2. Im Wizard Schritt für Schritt durchgehen:
   - Modelle auswählen (eines oder mehrere).
   - Continuous oder Interval.
   - Bei Continuous: Clip-Dauer (Fast / Standard / Avg).
   - Bei Interval: Pausen-Intervall + Methoden pro Modell.
   - Session-Dauer (30 min bis 12 h, oder Stop manually).
3. Auf der Übersichts-Seite **Start** tippen.
4. Die Live-Page zeigt die laufenden Vorhersagen pro Modell × Methode als Bar-Distribution.
5. **Stop** drücken oder warten, bis die Session-Dauer abläuft → Results-Summary.

### 8.3 Letzte Config wiederverwenden

Wenn schon einmal eine Session lief, erscheint auf der Welcome-Page der Button **Use last config**. Der überspringt den Wizard und startet direkt mit den zuletzt verwendeten Einstellungen.

### 8.4 Pausen während einer Session

Tap auf **Pause** öffnet einen Picker mit `No timer` · 5 min · 10 min · 30 min · 1 h. Bei Timer-Wahl resumed die Session automatisch nach Ablauf; manuell weiter geht jederzeit über **Resume**. Pausen werden clipgenau angewendet — der laufende Cycle wird komplett zu Ende geführt, dann pausiert die Schleife.

### 8.5 Für Langzeit-Aufnahmen (z. B. Nacht)

1. Im Wizard eine lange Session-Dauer wählen (3 h, 6 h, 12 h oder Stop manually).
2. Batterie-Optimierung muss deaktiviert sein.
3. Handy am Ladekabel lassen.
4. App im Vordergrund starten, dann Bildschirm aus.
5. Morgens: Results öffnen, History prüfen, CSV exportieren.

### 8.6 Hersteller-spezifische Einstellungen

| Hersteller | Einstellung |
|------------|-------------|
| **Xiaomi** | Sicherheit → Akku → Keine Einschränkungen + Autostart |
| **Huawei** | Telefonmanager → Geschützte Apps |
| **Samsung** | Einstellungen → Apps → Akku → Nicht optimieren |

### 8.7 History und Export

- **History** auf der Welcome-Page öffnet die Session-Liste.
- Tap auf eine Session öffnet den Detail-Dialog mit Distribution, Method Comparison, Per-Second Clips, User Evaluations und Pausen.
- **Export** im Detail-Dialog generiert eine CSV-Datei und öffnet das Android-Teilen-Menü.
- Long-Press auf eine Session aktiviert die Mehrfachauswahl für Bulk-Export oder -Delete.

---

## 9. Für Entwickler: Nächste Schritte

### Offene Aufgaben

- [ ] Unit-Tests für Mel-Spektrogramm (Vergleich mit Python-Referenz).
- [ ] Modell-Quantisierung (kleinere Dateigröße, schnellere Inferenz).
- [ ] Echtzeit-Streaming (keine feste Cycle-Dauer mehr).
- [ ] FZI-Branding/Logo.
- [ ] Mehrsprachigkeit (EN/DE) — aktuell ist die UI Englisch, nur Szenenklassen-Labels sind Deutsch.

### Wichtige Code-Stellen

| Feature | Datei | Methode |
|---------|-------|---------|
| Wizard-State | `MainViewModel.kt` | `wizardSetModels()`, `wizardGoToStep()`, `canAdvance()` |
| Recording starten | `MainViewModel.kt` | `startSession()` |
| Audio aufnehmen | `AudioRecorder.kt` | `startRecording()` |
| Mel-Spektrogramm | `MelSpectrogramProcessor.kt` | `computeLogMelSpectrogram()` |
| ML-Inferenz | `ModelInference.kt` | `infer()` |
| Pause/Resume | `MainViewModel.kt` | `pauseSession()`, `resumeSession()` |
| Pause-Record schreiben | `MainViewModel.kt` | `persistPauseRecord()` |
| Volume-Graph | `VolumeLineChartView.kt` | `addDataPoint()` |
| Live-Bars | `BarDistributionView.kt`, `LiveRecordingFragment.ensureCards()` |
| Results-Aggregat | `ResultsSummaryFragment.buildResults()` |
| History-Pause-Render | `ModernDialogHelper.renderPausesSection()` |
| Doze-Protection | `ClassificationService.kt` | `startKeepAliveAlarm()` |

### Git-Workflow

- Pro Aufgabe ein eigener Branch (`feature/...`, `fix/...`, `refactor/...`).
- Nie direkt auf `main` committen.
- Conventional Commits (`feat`, `fix`, `chore`, `refactor`, `docs`, `test`).
- Vor jedem wöchentlichen Treffen ein PR auf `main` (oder den aktuellen Meeting-Branch).

---

## Kontakt & Support

**Projekt:** FZI Acoustic Scene Classifier
**Institut:** FZI Forschungszentrum Informatik, Karlsruhe
**Wettbewerb:** DCASE 2025

---

*Letzte Aktualisierung: 2026-05-07*
