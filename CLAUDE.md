# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app (Kotlin) for real-time **Acoustic Scene Classification** using PyTorch Mobile. Records audio, computes Log-Mel-Spectrograms via TarsosDSP FFT, runs on-device ML inference, and classifies into 9 acoustic scene classes (DCASE 2025). Built for FZI Karlsruhe.

- **Package:** `com.fzi.acousticscene`
- **Min SDK:** 26 | **Target/Compile SDK:** 36
- **Kotlin:** 2.0.21 | **Gradle:** 8.13.2 (KTS with version catalog)

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew test                   # Unit tests (JUnit 4)
./gradlew connectedAndroidTest   # Instrumented tests (Espresso)
./gradlew clean                  # Clean build
```

## Architecture

**Single Activity + Navigation Component (MVVM)**

- `MainActivity` hosts 3 fragments via Bottom Navigation + `nav_graph.xml`
- `WelcomeActivity` is the landing/mode-selection page (currently not in manifest as launcher)
- `MainViewModel` manages all state via `StateFlow<UiState>` sealed classes
- Fragments share `MainViewModel` and `PredictionRepository` singleton

**Key packages under `app/src/main/java/com/fzi/acousticscene/`:**

| Package | Purpose |
|---------|---------|
| `audio/` | `AudioRecorder` (32kHz mono PCM), `MelSpectrogramProcessor` (TarsosDSP FFT), `RecordingState` |
| `ml/` | `ModelInference` (PyTorch Mobile 1.13.1), `ComputationDispatcher` (thread pool) |
| `model/` | Data classes: `SceneClass` (9 classes enum), `RecordingMode` (5 modes with FFT params), `ClassificationResult`, `PredictionRecord`, `ModelConfig` |
| `data/` | `PredictionRepository` — **must use singleton** via `getInstance(context)`, SharedPreferences-backed, 10k record limit |
| `service/` | `ClassificationService` — foreground service with WakeLock for background recording |
| `ui/` | All Activities (`MainActivity`, `WelcomeActivity`, `HistoryActivity`, `EvaluationActivity`), all Fragments (`RecordingFragment`, `HistoryFragment`, `SettingsFragment`), `MainViewModel`, sealed state classes, custom views (`ConfidenceCircleView`, `RipplePulseView`, `VolumeLineChartView`), `ModernDialogHelper` |
| `util/` | `ThemeHelper`, `ModelDisplayNameHelper` |

## Audio Pipeline

`AudioRecorder` (32kHz, 16-bit PCM) → `MelSpectrogramProcessor` (FFT → Mel filterbank → log) → `ModelInference` (PyTorch `[1,1,nMels,nFrames]` → softmax)

Each `RecordingMode` defines its own FFT parameters (nFft, winLength, hopLength, nMels). FAST=1s, MEDIUM=5s, STANDARD=10s, LONG=10s recording + 30min pause cycle, AVERAGE=10s split into 10x1s clips with averaged inference (Dev Mode only).

## Critical Patterns

- **PredictionRepository is a singleton.** Always use `PredictionRepository.getInstance(context)`. Creating separate instances causes data loss (stale overwrites).
- **Scene class labels are in German — do not translate.** Defined in `SceneClass.kt`.
- **Two modes:** User Mode (single production model at `user_model/model1.pt`) and Development Mode (multiple models from `dev_models/`).
- **All audio/ML work runs on background dispatchers** (`Dispatchers.IO`, `ComputationDispatcher`). UI updates flow through `StateFlow` collected in `viewLifecycleOwner.lifecycleScope`.
- **TarsosDSP** is fetched from a custom Maven repo (`https://mvn.0110.be/releases`) configured in `settings.gradle.kts`.
- **View Binding** is enabled (`buildFeatures.viewBinding = true`).

## Documentation

- `docs/PROJEKT_DOKUMENTATION.md` — detailed feature docs (German)
- `docs/Beschreibung_App_Feature.md` — feature overview with UI descriptions (German)
- `docs/AVERAGE_MODUS_ERKLAERUNG.md` — Erklaerung der Durchschnittsberechnung im AVERAGE-Modus (praesentationstauglich)
- `scripts/` — Python model export/test utilities and shell helpers

## Changelog

**2026-03-31 — Projektstruktur-Cleanup & Modell-Update**
- Activities (`MainActivity`, `WelcomeActivity`, `HistoryActivity`) und Fragments aus Root-Package nach `ui/` verschoben; alle Imports, `AndroidManifest.xml` und `nav_graph.xml` angepasst
- Lose Dateien organisiert: Python/Shell-Skripte → `scripts/`, Doku → `docs/`
- 3 ungenutzte XML-Ressourcen gelöscht (`item_history.xml`, `history_menu.xml`, `menu_history_selection.xml`)
- Alle alten Modelle (`model2.pt`, `model3.pt`, altes `model1.pt`) entfernt und durch ein einziges neues Modell ersetzt — jetzt liegt `model1.pt` (420 KB) in `user_model/` und `dev_models/`

**2026-03-31 — Evaluation-Feature, AVERAGE-Modus, History-Badges**
- **Evaluation (LONG-Modus):** Nach jeder 10s-Aufnahme im LONG-Modus wird eine Push-Notification gesendet. Nutzer hat 5 min Zeit, eine der 9 Klassen zu waehlen + optionalen Kommentar. Neue `EvaluationActivity` mit Countdown-Timer. Daten in `PredictionRecord.userSelectedClass` / `userComment` gespeichert, CSV um 2 Spalten erweitert.
- **AVERAGE-Modus (nur Dev Mode):** Neuer `RecordingMode.AVERAGE` — nimmt 10s auf, schneidet in 10x 1s-Clips, inferiert jeden Clip einzeln (FAST-FFT-Params), mittelt die 10 Probability-Verteilungen.
- **Mode-Badge:** Jede Session-Karte in History zeigt "Dev" (blau) oder "User" (gruen) Badge.
- **Distribution-Vergleich:** Session-Detail-Dialog zeigt "Modell-Vorhersagen" + "Nutzer-Bewertungen" Sektionen (letztere nur bei LONG-Modus).
- **Code-Quality:** `@Synchronized` auf alle PredictionRepository-Methoden, Gson custom Deserializer fuer nullable SceneClass, EvaluationActivity ueberlebt Rotation.
- **Doku:** `docs/MODEL_INTEGRATION_SPEC.md` — exakte Spec wie die App Mel-Spectrograms berechnet (FFT, Window, Mel-Skala, Log-Transformation), Python-Referenzimplementierung fuer kompatiblen Modell-Export.

**2026-03-31 — Per-Second Circles (AVERAGE-Modus Live-Visualisierung)**
- **Per-Second Circles:** Im AVERAGE-Modus (nur Dev Mode) werden 10 kleine Confidence-Kreise unter dem grossen Kreis angezeigt — einer pro 1-Sekunden-Clip. Kreise fuellen sich live waehrend der Inferenz.
- **Running Average:** Der grosse Kreis zeigt waehrend der Verarbeitung den laufenden Durchschnitt, der sich nach jedem Clip aktualisiert.
- **Toggle:** MaterialSwitch zum Ein-/Ausblenden der Per-Second-Kreise (Muster wie Volume-Analysis-Toggle). Karte nur sichtbar wenn AVERAGE-Modus aktiv.
- **ConfidenceCircleView:** Neue `setTargetSize(dp)` Methode — View ist jetzt groessenflexibel (Standard 200dp, kleine Kreise 52dp).
- **UiState:** Neue Felder `perSecondResults` (10 Slots) und `runningAverageResult` fuer live Updates.
- **Doku:** `docs/AVERAGE_MODUS_ERKLAERUNG.md` — Erklaerung der Durchschnittsberechnung, praesentationstauglich aufbereitet.

**2026-03-31 — AVERAGE-Modus: Echtzeit-Aufnahme, History-Details, Settings-Legende, UI-Fixes**
- **Echtzeit-Aufnahme:** AVERAGE-Modus nimmt jetzt Sekunde fuer Sekunde auf (nicht mehr 10s am Stueck). Jede Sekunde wird einzeln aufgenommen, sofort inferiert und im kleinen Kreis angezeigt. Eigener Flow `startAverageClassification()` in `MainViewModel`, unabhaengig vom normalen Recording-Flow.
- **Endlos-Schleife:** AVERAGE-Modus laeuft nach den 10 Sekunden automatisch weiter (naechste Runde), bis der User auf Stop drueckt — wie alle anderen Modi.
- **Per-Second Clips in History:** Session-Detail-Dialog zeigt bei AVERAGE-Sessions eine zusaetzliche Sektion "Per-Second Clips (1s)" mit Verteilung der 1s-Einzelvorhersagen. Neues Datenmodell `PerSecondClip` in `PredictionRecord.perSecondClips`.
- **CSV-Export erweitert:** Neue Spalte `per_second_clips` — zeigt pro Clip Nummer, Klasse und Konfidenz (Format: `1:Natur:79%|2:Natur:85%|...`). Leer bei Nicht-AVERAGE-Modi.
- **Processing-Indikator verschoben:** "Processing" erscheint jetzt im Header (neben Modell-Label), nicht mehr in der Status-Karte unten — verhindert Layout-Springen.
- **AVERAGE-Modus kein UI-Wackeln:** Zwischen den 1s-Clips wird nicht mehr auf `AppState.Processing` gewechselt, dadurch kein Flackern der Timer/Progressbar.
- **Scene-Legende in Settings:** Neue Karte "Scene Classes - Legende" ganz unten in Settings — zeigt alle 9 Klassen mit Emoji und deutschem Label. Wird automatisch aus `SceneClass` Enum generiert.
- **Show Live Data Label:** Per-Second-Analyse-Toggle zeigt jetzt einheitlich "Show Live Data" wie der Volume-Analysis-Toggle.
- **Doku erweitert:** `docs/AVERAGE_MODUS_ERKLAERUNG.md` erklaert jetzt Probability Averaging vs. Majority Voting vs. Gewichtetes Averaging mit Vergleichstabelle.
