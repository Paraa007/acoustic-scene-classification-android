# Projekt-Prompt: Android Acoustic Scene Classification App

## 1. PROJEKT-ÜBERSICHT

### Was wurde entwickelt?
Eine Android-App für **Acoustic Scene Classification** (akustische Szenenklassifikation) mit PyTorch Mobile. Die App nimmt Audio auf, extrahiert Mel-Spektrogramme, und klassifiziert die akustische Umgebung in 8 Kategorien.

### Technologie-Stack:
- **Platform:** Android (Min SDK 26, Target SDK 36)
- **Sprache:** Kotlin
- **ML Framework:** PyTorch Mobile 1.13.1
- **Architektur:** MVVM (Model-View-ViewModel) mit Kotlin Coroutines
- **UI:** Material Design 3, Custom Views
- **Audio Processing:** Android AudioRecord, TarsosDSP (FFT), Custom MelSpectrogram Processor

---

## 2. PROJEKT-STRUKTUR

### Verzeichnisstruktur:
```
app/src/main/
├── java/com/fzi/acousticscene/
│   ├── MainActivity.kt                    # Haupt-UI (Activity)
│   ├── audio/
│   │   ├── AudioRecorder.kt              # Audio-Aufnahme (Android AudioRecord)
│   │   └── MelSpectrogramProcessor.kt    # Mel-Spectrogram Berechnung
│   ├── ml/
│   │   └── ModelInference.kt             # PyTorch Model Loading & Inference
│   ├── model/
│   │   ├── ClassificationResult.kt       # Ergebnis-Datenklasse
│   │   ├── RecordingMode.kt              # Enum: STANDARD (10s) / FAST (1s)
│   │   └── SceneClass.kt                 # Enum: 8 Acoustic Scene Klassen
│   └── ui/
│       ├── AppState.kt                   # Sealed Class für App-Zustände
│       ├── ConfidenceCircleView.kt       # Custom View für Circular Progress
│       └── MainViewModel.kt              # ViewModel (State Management)
├── res/
│   ├── layout/activity_main.xml          # UI Layout (Dark Theme)
│   ├── values/
│   │   ├── colors.xml                    # Dark Theme Farben
│   │   └── strings.xml                   # String-Ressourcen
└── AndroidManifest.xml                   # App-Konfiguration

app/build.gradle.kts                      # Dependencies (PyTorch, TarsosDSP, etc.)
settings.gradle.kts                       # Maven Repositories
```

---

## 3. HAUPT-FEATURES & IMPLEMENTIERUNGEN

### 3.1 Audio-Aufnahme (`AudioRecorder.kt`)
- **Zweck:** Nimmt Audio in konfigurierbarer Dauer auf
- **Parameter:**
  - Sample Rate: 32 kHz
  - Format: PCM 16-bit Mono
  - Dauer: Konfigurierbar (10s Standard, 1s Fast Mode)
- **Implementierung:**
  - Verwendet `AudioRecord` API
  - Konvertiert `ShortArray` (PCM) zu `FloatArray` (normalisiert [-1, 1])
  - Gibt Flow zurück für Kotlin Coroutines

### 3.2 Mel-Spectrogram Berechnung (`MelSpectrogramProcessor.kt`)
**KRITISCH:** Dies war der komplizierteste Teil des Projekts.

- **Zweck:** Konvertiert Audio-Samples zu Mel-Spectrogram für PyTorch Model
- **Parameter:**
  - `sampleRate = 32000`
  - `nFft = 4096` (FFT Fenster-Größe)
  - `winLength = 3072` (Window-Länge)
  - `hopLength = 500` (Hop-Länge für STFT)
  - `nMels = 256` (Anzahl Mel-Bins)
  - `fMin = 0f, fMax = 16000f`

- **Verarbeitungsschritte:**
  1. **STFT (Short-Time Fourier Transform):**
     - Extrahiert Frames aus Audio
     - Wendet Hann-Window an
     - Zero-Padding auf `nFft` Größe
     - FFT für jeden Frame
  
  2. **Power Spectrum:**
     - Extrahiert Power aus FFT-Ergebnis
     - Format: `[DC, Nyquist, Re1, Im1, Re2, Im2, ...]` (TarsosDSP)
  
  3. **Mel-Filterbank:**
     - Konvertiert lineare Frequenz-Bins zu Mel-Skala
     - Wendet Filterbank an
  
  4. **Log-Transformation:**
     - `log(mel + 1e-5)` für numerische Stabilität
  
  5. **Tensor-Format:**
     - Flattet zu 1D Array für PyTorch
     - Shape: `[1, 1, 256, 641]` (batch, channel, mels, time)

### 3.3 PyTorch Model Inference (`ModelInference.kt`)
- **Zweck:** Lädt PyTorch Model und führt Inferenz durch
- **Model Loading:**
  - Kopiert `.pt` Datei aus `assets/` zu `cacheDir`
  - Lädt mit `Module.load()` (PyTorch Mobile)
- **Inference:**
  - Input: `FloatArray` mit Audio-Samples (320000 für 10s)
  - Verarbeitung:
    1. Mel-Spectrogram Berechnung (auf Background-Thread!)
    2. Tensor-Erstellung: `[1, 1, 256, 641]`
    3. Model Forward Pass
    4. Softmax auf Output (Logits → Wahrscheinlichkeiten)
    5. Argmax für Top-Klasse
- **WICHTIG:** Mel-Spectrogram Berechnung läuft auf `Dispatchers.Default` um ANR zu vermeiden

### 3.4 ViewModel (`MainViewModel.kt`)
- **Zweck:** State Management und Business Logic
- **Features:**
  - Model Loading (asynchron)
  - Kontinuierliche Klassifikation (Loop mit Recording)
  - History Management (letzte 5 Ergebnisse)
  - Statistiken (Anzahl, durchschnittliche Inferenz-Zeit)
  - Recording Mode Management (STANDARD/FAST)
- **State Flow:**
  - `UiState`: Aktueller Zustand (AppState, Result, History, etc.)
  - `AppState`: Sealed Class (Idle, Loading, Ready, Recording, Processing, Error)

### 3.5 UI (`MainActivity.kt` + `activity_main.xml`)
- **Design:** Dark Theme (Schwarz, Dunkelgrau, Akzent-Grün)
- **Komponenten:**
  1. **Circular Confidence Indicator** (`ConfidenceCircleView.kt`):
     - Custom View mit Canvas-Zeichnung
     - Zeigt Prozent-Wert im Kreis
     - Animierter Progress
  2. **Mode Buttons:**
     - "Standard (10s)" / "Fast (1s)" Toggle
  3. **Predictions Card:**
     - Top 3 Predictions mit Progress Bars
  4. **History Card:**
     - Scrollable Liste der letzten 5 Ergebnisse
     - Timestamps, Confidence, Scene-Namen
     - "Save History" Button für CSV-Export
  5. **Statistics Card:**
     - Gesamtanzahl Klassifikationen
     - Durchschnittliche Inferenz-Zeit

### 3.6 Scene Classes (`SceneClass.kt`)
**8 Klassen** (DCASE 2025 kompatibel):
1. `TRANSIT_VEHICLES` - "Transit - Fahrzeuge/draußen" 🚗
2. `URBAN_WAITING` - "Außen-urban & Transit-Gebäude/Wartezonen" 🏙️
3. `NATURE` - "Außen - naturbetont" 🌲
4. `SOCIAL` - "Innen - Soziale Umgebung" 👥
5. `WORK` - "Innen - Arbeitsumgebung" 💼
6. `COMMERCIAL` - "Innen - Kommerzielle/belebte Umgebung" 🛒
7. `LEISURE_SPORT` - "Innen - Freizeit/Sport" ⚽
8. `CULTURE_QUIET` - "Innen - Kultur/Freizeit ruhig" 🎭

---

## 4. WICHTIGE FEHLER & LÖSUNGEN

### Fehler #1: ANR (Application Not Responding) durch langsame Mel-Spectrogram Berechnung

**Problem:**
- App friert ein während Mel-Spectrogram Berechnung
- Ursache: Langsame DFT-Implementierung (O(n²)) lief auf UI-Thread
- User-Feedback: "Ich warte aber jetzt seit ewig und bekomme kein Ergebnis!"

**Ursprüngliche Implementierung:**
```kotlin
// In ModelInference.infer() - Lief auf UI-Thread
val logMelSpectrogram = melSpectrogramProcessor.computeLogMelSpectrogram(processedAudio)
```

**Lösung:**
1. **Schritt 1 - Threading:** Mel-Spectrogram Berechnung auf Background-Thread verschoben
```kotlin
// In ModelInference.infer()
return withContext(Dispatchers.Default) {
    val logMelSpectrogram = melSpectrogramProcessor.computeLogMelSpectrogram(processedAudio)
    // ...
}
```

2. **Schritt 2 - FFT Optimierung:** Integration von TarsosDSP für optimierte FFT (O(n log n))

---

### Fehler #2: Langsame DFT-Implementierung

**Problem:**
- Custom DFT-Implementierung war O(n²) → extrem langsam
- `nFft = 4096` führte zu sehr langen Berechnungszeiten
- Temporärer Workaround: `nFft` auf 2048, dann 1024 reduziert (verlustbehaftet)

**Lösung:**
- **TarsosDSP Library** integriert für optimierte FFT
- **Dependencies** (`app/build.gradle.kts`):
```kotlin
implementation("be.tarsos.dsp:core:2.5")
implementation("be.tarsos.dsp:jvm:2.5")
```
- **Repository** (`settings.gradle.kts`):
```kotlin
maven {
    url = uri("https://mvn.0110.be/releases")
}
```
- **Implementation** (`MelSpectrogramProcessor.kt`):
```kotlin
private val fft = FFT(nFft)  // TarsosDSP FFT

private fun computeFFT(input: FloatArray): FloatArray {
    val fftBuffer = input.copyOf()
    fft.forwardTransform(fftBuffer)  // In-place FFT
    return fftBuffer
}
```
- **Ergebnis:** `nFft = 4096` wiederhergestellt, Berechnung ~10x schneller

---

### Fehler #3: ArrayIndexOutOfBoundsException in MelSpectrogramProcessor

**Problem:**
```
ArrayIndexOutOfBoundsException: length=2048; index=2048
at MelSpectrogramProcessor.extractPowerSpectrum (line 65)
```

**Ursache:**
- Falsche Interpretation des TarsosDSP FFT-Output-Formats
- TarsosDSP `forwardTransform()` gibt kompaktes Format zurück:
  - `[DC, Nyquist, Re1, Im1, Re2, Im2, ..., Re(N/2-1), Im(N/2-1)]`
  - Array-Größe: `nFft` (nicht `nFft/2+1`!)
  - Index 0: DC (nur Real)
  - Index 1: Nyquist (nur Real)
  - Index 2,3: Bin 1 (Real, Imag)
  - Index 4,5: Bin 2 (Real, Imag)
  - etc.

**Fehlerhafte Implementierung:**
```kotlin
// FALSCH: Annahme, dass Index k direkt Real/Imag enthält
for (bin in 1 until nFft / 2) {
    val real = fftResult[bin]  // ❌ FALSCH!
    val imag = fftResult[bin + 1]  // ❌ FALSCH!
}
```

**Korrekte Lösung:**
```kotlin
private fun extractPowerSpectrum(fftResult: FloatArray): FloatArray {
    val nFreqBins = nFft / 2 + 1  // 2049 für nFft=4096
    val powerSpectrum = FloatArray(nFreqBins)
    
    // DC Component (Bin 0): Index 0, nur Real
    powerSpectrum[0] = fftResult[0] * fftResult[0]
    
    // Nyquist Component (Bin nFft/2): Index 1, nur Real
    powerSpectrum[nFft / 2] = fftResult[1] * fftResult[1]
    
    // Alle anderen Bins (1 bis nFft/2-1)
    // Bin k → Real bei Index 2*k, Imag bei Index 2*k+1
    for (bin in 1 until nFft / 2) {
        val realIdx = 2 * bin  // ✅ Korrekt: Bin k → Index 2*k
        val imagIdx = 2 * bin + 1  // ✅ Korrekt: Bin k → Index 2*k+1
        
        val real = fftResult[realIdx]
        val imag = fftResult[imagIdx]
        
        powerSpectrum[bin] = real * real + imag * imag
    }
    
    return powerSpectrum
}
```

---

### Fehler #4: Gradle Build Fehler - TarsosDSP Version 2.4 nicht gefunden

**Problem:**
```
Could not find be.tarsos.dsp:core:2.4
Could not find be.tarsos.dsp:jvm:2.4
```

**Ursache:**
- Version 2.4 existiert nicht im Maven Repository
- Verfügbare Version: 2.5

**Lösung:**
- **Dependencies aktualisiert** (`app/build.gradle.kts`):
```kotlin
// Vorher: implementation("be.tarsos.dsp:core:2.4")  // ❌ Existiert nicht
// Nachher:
implementation("be.tarsos.dsp:core:2.5")  // ✅ Korrekt
implementation("be.tarsos.dsp:jvm:2.5")   // ✅ Korrekt
```

---

### Fehler #5: Unresolved reference 'RECORDING_DURATION_SECONDS'

**Problem:**
```
Unresolved reference 'RECORDING_DURATION_SECONDS'
at MainViewModel.kt (line 123)
```

**Ursache:**
- Konstante `RECORDING_DURATION_SECONDS` wurde während Refactoring entfernt
- Durch `RecordingMode` Enum ersetzt
- Alte Referenzen nicht aktualisiert

**Lösung:**
- **Alle Referenzen aktualisiert** (`MainViewModel.kt`):
```kotlin
// Vorher:
val durationSeconds = RECORDING_DURATION_SECONDS  // ❌ Nicht definiert

// Nachher:
val durationSeconds = currentMode.durationSeconds  // ✅ Aus RecordingMode
```

---

### Fehler #6: Android Resource Linking Fehler - indicatorCornerRadius

**Problem:**
```
Android resource linking failed
error: attribute indicatorCornerRadius (attr) not found
at LinearProgressIndicator in activity_main.xml
```

**Ursache:**
- `indicatorCornerRadius` Attribut wird von `LinearProgressIndicator` nicht unterstützt
- Möglicherweise Material Design 3 Änderung

**Lösung:**
- **Attribut entfernt** (`activity_main.xml`):
```xml
<!-- Vorher: -->
<LinearProgressIndicator
    android:id="@+id/timerProgress"
    app:indicatorCornerRadius="4dp"  <!-- ❌ Nicht unterstützt -->
    ... />

<!-- Nachher: -->
<LinearProgressIndicator
    android:id="@+id/timerProgress"
    ... />
```

---

### Fehler #7: Gradle Build Timeout - settings.gradle.kts

**Problem:**
```
Could not read settings file '.../settings.gradle.kts'
> Operation timed out
```

**Ursache:**
- Temporäres Netzwerkproblem beim Zugriff auf Maven Repository
- Möglicherweise TarsosDSP Repository (`https://mvn.0110.be/releases`) nicht erreichbar

**Lösung:**
- **Diagnose:** Datei selbst war korrekt, Problem war extern (Netzwerk)
- **Empfehlung:** Retry, Android Studio verwenden, Internetverbindung prüfen
- **Keine Code-Änderung nötig**

---

## 5. UI-REDESIGN & NEUE FEATURES

### 5.1 Dark Theme Implementierung

**Ziel:** Modernes, ästhetisches Dark Theme

**Farben** (`colors.xml`):
- Background: `#000000` (Schwarz)
- Surface: `#1E1E1E` (Dunkelgrau)
- Surface Variant: `#2E2E2E` (Hellgrau)
- Accent: `#4CAF50` (Grün)
- Text Primary: `#FFFFFF` (Weiß)
- Text Secondary: `#CCCCCC` (Grau)
- Individuelle Farben für jede Scene-Klasse

### 5.2 Circular Confidence Indicator

**Implementierung:** Custom View (`ConfidenceCircleView.kt`)
- Canvas-Zeichnung
- Hintergrund-Kreis (grau)
- Progress-Kreis (animiert, grün)
- Zentrierter Text (Prozent-Wert + "%" Symbol)
- Fix: "%" Symbol Position angepasst (nach unten verschoben) um Überschneidung zu vermeiden

### 5.3 Recording Modes

**Feature:** Zwei Aufnahme-Modi
- **STANDARD:** 10 Sekunden (Standard)
- **FAST:** 1 Sekunde (schnelle Vorhersage)

**Implementierung:**
- `RecordingMode` Enum (`model/RecordingMode.kt`)
- UI Toggle-Buttons in `MainActivity`
- ViewModel verwaltet aktiven Modus
- `AudioRecorder` akzeptiert `durationSeconds` Parameter

### 5.4 History Export (CSV)

**Feature:** Speichere Klassifikations-Verlauf als CSV

**Implementierung:**
- `saveHistoryToFile()` in `MainActivity.kt`
- CSV-Format: `Timestamp, Scene, Confidence, Inference Time (s)`
- FileProvider für File-Sharing
- Share-Intent für Datei-Weitergabe

### 5.5 UI-Verbesserungen

**Änderungen:**
- Lange Klassennamen statt kurze (kleinere Schrift)
- Emojis für jede Scene-Klasse
- Card-basiertes Layout (MaterialCardView)
- Scrollable History (NestedScrollView)
- Timestamps in History-Items
- Bessere Typografie und Spacing

---

## 6. ABHÄNGIGKEITEN (build.gradle.kts)

```kotlin
// Android Core
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.appcompat:appcompat:1.6.1")
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")

// PyTorch Mobile
implementation("org.pytorch:pytorch_android:1.13.1")
implementation("org.pytorch:pytorch_android_torchvision:1.13.1")

// Lifecycle & ViewModel
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

// TarsosDSP (FFT)
implementation("be.tarsos.dsp:core:2.5")
implementation("be.tarsos.dsp:jvm:2.5")
```

---

## 7. WICHTIGE TECHNISCHE DETAILS

### 7.1 Audio-Pipeline
1. `AudioRecorder` → PCM 16-bit (ShortArray)
2. Konvertierung zu FloatArray (normalisiert [-1, 1])
3. `MelSpectrogramProcessor` → Log-Mel-Spectrogram
4. Tensor-Erstellung: `[1, 1, 256, 641]`
5. PyTorch Model Forward Pass
6. Softmax → Wahrscheinlichkeiten
7. Argmax → Top-Klasse

### 7.2 Threading & Coroutines
- **UI-Thread:** Nur UI-Updates (`MainActivity`, `ConfidenceCircleView`)
- **Background-Thread:** Mel-Spectrogram Berechnung (`Dispatchers.Default`)
- **IO-Thread:** Model Loading (`Dispatchers.IO`)
- **Kotlin Flows:** Audio Recording (`AudioRecorder.startRecording()`)

### 7.3 State Management
- **MVVM Pattern:** ViewModel hält Business Logic
- **StateFlow:** Reaktive UI-Updates
- **Sealed Classes:** Type-safe State (`AppState`)

### 7.4 Mel-Spectrogram Parameter
- **nFft = 4096:** FFT Fenster-Größe
- **winLength = 3072:** Window-Länge (75% overlap)
- **hopLength = 500:** Hop-Länge (Frame-Advance)
- **nMels = 256:** Anzahl Mel-Bins
- **fMax = 16000:** Max Frequenz (Nyquist bei 32kHz = 16kHz)

---

## 8. LESSONS LEARNED & BEST PRACTICES

### Was gut funktioniert hat:
1. ✅ **TarsosDSP Integration:** Deutliche Performance-Verbesserung
2. ✅ **Background Threading:** ANR vermieden
3. ✅ **MVVM Architektur:** Saubere Trennung von UI und Business Logic
4. ✅ **Custom Views:** Flexible UI-Komponenten
5. ✅ **Material Design 3:** Modernes, konsistentes Design

### Was schwierig war:
1. ❌ **TarsosDSP FFT Format:** Unklar dokumentiert, viel Trial & Error
2. ❌ **Gradle Dependency Management:** TarsosDSP Repository finden
3. ❌ **Threading:** Initial ANR-Probleme durch UI-Thread Blockierung
4. ❌ **Mel-Spectrogram Implementierung:** Komplexe Mathematik, fehleranfällig

### Empfehlungen für zukünftige Projekte:
1. **FFT Library:** Immer dokumentierte, etablierte Library verwenden (z.B. TarsosDSP, FFTW)
2. **Performance:** Schwere Berechnungen IMMER auf Background-Thread
3. **Testing:** Unit Tests für Mel-Spectrogram (Vergleich mit Referenz-Implementierung)
4. **Dependencies:** Immer aktuelle Versionen prüfen, nicht blind Versionen verwenden
5. **Error Handling:** Robuste Fehlerbehandlung bei Audio/ML-Pipeline

---

## 9. ZUSAMMENFASSUNG DER ÄNDERUNGEN

### Initial → Final State:

**Performance:**
- ❌ Custom DFT (O(n²)) → ✅ TarsosDSP FFT (O(n log n))
- ❌ UI-Thread Blockierung → ✅ Background Threading
- ❌ nFft=1024 (Workaround) → ✅ nFft=4096 (optimiert)

**Features:**
- ❌ Keine UI → ✅ Dark Theme mit Custom Views
- ❌ Keine History → ✅ History mit CSV-Export
- ❌ Nur 10s Recording → ✅ 2 Modi (10s / 1s)
- ❌ Kurze Klassennamen → ✅ Lange Namen mit Emojis

**Stabilität:**
- ❌ ANR Fehler → ✅ Keine ANRs
- ❌ ArrayIndexOutOfBounds → ✅ Korrektes FFT-Format
- ❌ Gradle Build Fehler → ✅ Alle Dependencies korrekt

---

## 10. NÄCHSTE SCHRITTE (Optional)

Mögliche Verbesserungen:
- [ ] Unit Tests für Mel-Spectrogram
- [ ] Model Quantization (kleinere Dateigröße)
- [ ] Real-time Streaming (keine feste Dauer)
- [ ] Offline-Mode (ohne Model-Download)
- [ ] Mehr Recording-Modi (z.B. 5s)
- [ ] FZI Logo Integration
- [ ] Analytics/Telemetry

---

## 11. KONTAKT & SUPPORT

**Projekt:** FZI Acoustic Scene Classifier  
**Platform:** Android  
**ML Framework:** PyTorch Mobile 1.13.1  
**Entwickelt:** 2024-2025

---

**Ende des Prompts**
