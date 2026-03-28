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
| `model/` | Data classes: `SceneClass` (9 classes enum), `RecordingMode` (4 modes with FFT params), `ClassificationResult`, `PredictionRecord`, `ModelConfig` |
| `data/` | `PredictionRepository` — **must use singleton** via `getInstance(context)`, SharedPreferences-backed, 10k record limit |
| `service/` | `ClassificationService` — foreground service with WakeLock for background recording |
| `ui/` | All Activities (`MainActivity`, `WelcomeActivity`, `HistoryActivity`), all Fragments (`RecordingFragment`, `HistoryFragment`, `SettingsFragment`), `MainViewModel`, sealed state classes, custom views (`ConfidenceCircleView`, `RipplePulseView`, `VolumeLineChartView`), `ModernDialogHelper` |
| `util/` | `ThemeHelper`, `ModelDisplayNameHelper` |

## Audio Pipeline

`AudioRecorder` (32kHz, 16-bit PCM) → `MelSpectrogramProcessor` (FFT → Mel filterbank → log) → `ModelInference` (PyTorch `[1,1,nMels,nFrames]` → softmax)

Each `RecordingMode` defines its own FFT parameters (nFft, winLength, hopLength, nMels). FAST=1s, MEDIUM=5s, STANDARD=10s, LONG=10s recording + 30min pause cycle.

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
- `scripts/` — Python model export/test utilities and shell helpers
