# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Projekt auf einen Blick

Android-App (Kotlin) für **Acoustic Scene Classification** in Echtzeit. Die App nimmt über das Mikrofon Audio auf, rechnet daraus Log-Mel-Spektrogramme (TarsosDSP FFT) und schickt diese durch ein PyTorch-Mobile-Modell. Das Modell ordnet die Umgebung einer von 9 akustischen Szenen zu (DCASE 2025). Entwickelt für das FZI Karlsruhe.

- **Package:** `com.fzi.acousticscene`
- **Min SDK:** 26 · **Target/Compile SDK:** 36
- **Kotlin:** 2.0.21 · **Gradle:** 8.13.2 (KTS, Version Catalog)

## Was die App kann (User-Sicht)

**Zwei Betriebsmodi**, die über den Welcome-Screen ausgewählt werden:
- **User Mode** — Produktionsmodus mit einem einzigen, festen Modell (`user_model/model1.pt`).
- **Development Mode** — Modellwahl aus mehreren `.pt`-Dateien in `dev_models/`. Für Tests und Vergleiche.

**Aufnahme-Modi** (auswählbar auf dem Recording-Screen):
- **Fast (1s)** — kurze Aufnahme, schnelles Feedback.
- **Standard (10s)** — Normalfall, gute Balance.
- **every 30min** — nimmt 10s auf, pausiert dann 30min, wiederholt. Für Langzeit-Monitoring. Nach jeder Aufnahme bekommt der Nutzer eine Push-Notification und kann die tatsächliche Szene + Kommentar angeben (Evaluation-Feature).
- **Avg (10s, nur Dev Mode)** — nimmt 10 × 1s nacheinander auf, inferiert jeden Clip einzeln, und mittelt die 10 Wahrscheinlichkeitsverteilungen. Alle Einzelergebnisse sind live im UI sichtbar (10 kleine Kreise + großer Kreis mit laufendem Durchschnitt).

**History / Auswertung:**
- Jede Aufnahme-Session wird persistent gespeichert (SharedPreferences, max. 10k Records).
- Session-Kacheln tragen ein Badge „Dev" (blau) oder „User" (grün).
- Session-Detail-Dialog zeigt Verteilung der Modell-Vorhersagen, bei LONG zusätzlich Nutzer-Bewertungen, bei AVERAGE zusätzlich die 1-Sekunden-Einzelclips.
- CSV-Export inkl. Nutzer-Bewertung und Per-Second-Clips.

**Settings:**
- Theme-Umschaltung (light/dark via `ThemeHelper`).
- Legende aller 9 Szenenklassen mit Emoji und deutschem Label (automatisch aus `SceneClass`-Enum generiert).

## Build

```bash
./gradlew assembleDebug          # Debug-APK
./gradlew assembleRelease        # Release-APK
./gradlew test                   # Unit-Tests (JUnit 4)
./gradlew connectedAndroidTest   # Instrumented-Tests (Espresso)
./gradlew clean
```

## Architektur

**Single Activity + Navigation Component, MVVM.**

- `MainActivity` hostet 3 Fragments via Bottom Navigation (`nav_graph.xml`).
- `WelcomeActivity` ist der Einstieg (Modus-Auswahl).
- `MainViewModel` verwaltet den gesamten UI-Zustand über `StateFlow<UiState>` (sealed classes).
- Fragments teilen sich `MainViewModel` und den `PredictionRepository`-Singleton.

**Package-Struktur unter `app/src/main/java/com/fzi/acousticscene/`:**

| Package | Inhalt |
|---------|--------|
| `audio/` | `AudioRecorder` (32 kHz mono PCM), `MelSpectrogramProcessor` (TarsosDSP FFT), `RecordingState` |
| `ml/` | `ModelInference` (PyTorch Mobile 1.13.1), `ComputationDispatcher` (Thread-Pool) |
| `model/` | `SceneClass` (Enum, 9 Klassen), `RecordingMode` (4 Modi), `ClassificationResult`, `PredictionRecord`, `ModelConfig` |
| `data/` | `PredictionRepository` — **Singleton via `getInstance(context)`**, SharedPreferences, 10k-Limit |
| `service/` | `ClassificationService` — Foreground-Service mit WakeLock für Hintergrund-Aufnahme |
| `ui/` | Activities (`MainActivity`, `WelcomeActivity`, `HistoryActivity`, `EvaluationActivity`), Fragments (`RecordingFragment`, `HistoryFragment`, `SettingsFragment`), `MainViewModel`, Custom Views (`ConfidenceCircleView`, `RipplePulseView`, `VolumeLineChartView`), `ModernDialogHelper` |
| `util/` | `ThemeHelper`, `ModelDisplayNameHelper` |

## Audio-Pipeline

`AudioRecorder` (32 kHz, 16-bit PCM) → `MelSpectrogramProcessor` (FFT → Mel-Filterbank → log) → `ModelInference` (PyTorch-Input `[1, 1, nMels, nFrames]` → Softmax über 9 Klassen).

Die FFT-Parameter (nFft, winLength, hopLength, nMels, fMin, fMax) sind für alle Modi **zentral** im `MelSpectrogramProcessor` definiert. Die Modi unterscheiden sich nur in der Aufnahme-Dauer — daraus ergibt sich die Breite des Spektrogramms. Details: `docs/MODEL_INTEGRATION_SPEC.md`.

## Wichtige Regeln beim Arbeiten im Code

- **`PredictionRepository` ist ein Singleton.** Immer `getInstance(context)` verwenden. Eigene Instanzen führen zu Datenverlust (Stale-Overwrites).
- **Szenenklassen-Labels sind auf Deutsch — nicht übersetzen.** Definiert in `SceneClass.kt`.
- **Audio/ML läuft auf Background-Dispatchers** (`Dispatchers.IO`, `ComputationDispatcher`). UI-Updates fließen via `StateFlow`, kollekt im `viewLifecycleOwner.lifecycleScope`.
- **TarsosDSP** kommt aus einem Custom-Maven-Repo (`https://mvn.0110.be/releases`), konfiguriert in `settings.gradle.kts`.
- **View Binding** ist aktiv (`buildFeatures.viewBinding = true`).

## Weiterführende Dokumentation

- `docs/PROJEKT_DOKUMENTATION.md` — detaillierte Feature-Doku
- `docs/Beschreibung_App_Feature.md` — Feature-Übersicht mit UI-Beschreibungen
- `docs/AVERAGE_MODUS_ERKLAERUNG.md` — Erklärung der Durchschnittsberechnung im AVERAGE-Modus (präsentationstauglich)
- `docs/MODEL_INTEGRATION_SPEC.md` — exakte Spec der Mel-Spektrogramm-Berechnung + Python-Referenzcode für kompatiblen Modell-Export
- `scripts/` — Python-Utilities (Modell-Export, Tests) und Shell-Helper

## Changelog

**2026-04-15 — LONG sub-mode polish: triangle layout, per-second pacing, session lifecycle**
- Triangle circles reworked: main Standard circle 150 dp (inside a 170 dp frame when triangle is active, 240 dp otherwise) with the Standard sub-label sitting right under the %; Fast + Avg circles 70 dp each (internal `setTargetSize(60)`), both wraps `layout_weight=1` so they're geometrically symmetric. Small circles now show `<Label>\n<Emoji> <ClassShort> <%>` colored by class — same compact style across all three sub-modes.
- When ≥ 2 sub-modes are selected, the big `currentSceneLabel` (the large "Emoji Class") is hidden — its role is taken over by the compact colored label under the main circle, so all three circles are labeled consistently. With only Standard selected, the original big label behavior is preserved.
- Top Predictions card is hidden in triangle mode — it was ambiguous which circle its Top 3 belonged to.
- LONG + Avg sub-mode: 350 ms pacing between the 10 per-second inferences on the in-memory 10 s buffer, so the "Show Live Data" circles visibly fill one by one instead of flashing through in < 1 s.
- `stopClassification()` now also clears `pendingEvaluation`, `currentResult`, `history`, `totalClassifications`, `averageInferenceTime`, `recordingProgress`, `currentVolume` — pressing Stop wipes every transient session view (big circle, Top Predictions, Last Predictions, Session Statistics, pending Rate card) back to the Ready state, as if the app had just launched. The persistent History-tab repository is untouched.
- `startClassification()` bumps `sessionStartTime = now` on every press, so every Start = a fresh History session and every Stop ends it. Re-pressing Start never re-opens the previous session — it opens a new one. `ActiveSessionRegistry` picks this up automatically.
- Evaluation-methods checkboxes (Fast / Avg) are disabled while a session is running; selection is locked until Stop. Standard stays locked-on as before.
- History session-detail dialog wrapped in a `NestedScrollView` (`maxHeight=600dp`) so long method-comparison + user-evaluation sections scroll instead of overlapping. When `longSubResults` exists on any record, the redundant top-level "Modell-Vorhersagen" distribution is hidden — the per-sub-mode "Method comparison" stacks already convey the same info.

**2026-04-15 — LONG sub-mode evaluation (Dev Mode): Standard / Fast / Avg on the same 10 s sample**
- LONG mode now acts as a comparison harness. In Dev Mode, below the Interval / LONG button row there's an "Evaluation methods" chooser with three checkboxes: Standard (locked on, default), Fast (middle 1 s), Avg (10 × 1 s averaged). User can pick 1, 2, or all 3.
- On every 30-min tick a single 10 s recording is made, then inference runs once per selected sub-mode on the same buffer: Standard → full 10 s; Fast → `samples[4.5s..5.5s]`; Avg → 10 × 1 s slices fed back through the existing AVERAGE live pipeline (`perSecondResults` + `runningAverageResult`).
- UI: when ≥ 2 sub-modes are selected, the big confidence circle shrinks to 180 dp (Standard) and two smaller 90 dp circles appear underneath in a triangle layout — bottom-left = Fast, bottom-right = Avg, each with a per-sub label. With only Standard selected, UI is unchanged.
- `ModeTimelineView` for LONG appends `evaluated as: Standard + Fast + Avg` (reflecting the current selection) to the bottom summary.
- Per-Second card is also shown when LONG + Avg sub-mode is active, so the "Show Live Data" switch still gates the 1 s circles.
- Data model: new `LongSubMode` enum and `LongSubResult` (`model/PredictionRecord.kt`). `PredictionRecord` gains `longSubResults: List<LongSubResult>?`. When Avg ran, its 10 × 1 s clips populate the existing `perSecondClips` field.
- CSV: 3 new columns `long_standard`, `long_fast`, `long_average` (one row per recording), each `"<Class>:<xx>%"` or empty.
- History session-detail dialog gains a "Method comparison" section rendering a distribution stack per sub-mode so you can compare evaluation strategies on identical audio.
- Sub-mode selection persisted in `mode_prefs` under new keys `long_sub_modes_dev` / `long_sub_modes_user` (latter reserved for a future User-Mode port). Dev-Mode only today.

**2026-04-15 — LONG-mode label + second-accurate countdown**
- Renamed the LONG mode label from "Long (30min)" to "every 30min" (both `RecordingMode.LONG.label` and string `mode_long`).
- The 30-min pause countdown now ticks every second instead of every minute. `AppState.Paused` / `UserPaused` now carry `secondsRemaining: Int` (was `minutesRemaining`). UI shows `mm:ss` (e.g. `29:47`) in both the status label and the timer. New strings: `pause_mmss`, `user_paused_with_mmss`.

**2026-04-15 — LONG-mode Pause/Resume + persistent active session + History "active" badge**
- LONG mode now has a Pause/Resume button under Start/Stop, visible only while a LONG session is active. It halts both the recording loop (at the next iteration boundary) and the 30-min countdown; Resume continues from where Pause was hit. Stop still ends the session entirely. New `AppState.UserPaused(minutesRemaining)` and `UiState.isPaused` drive the UI.
- New `ActiveSessionRegistry` (`data/`) tracks which modes (User/Dev) have a live session, process-wide. `MainViewModel.startClassification()` registers, `stopClassification()` unregisters. Survives activity/fragment destruction as long as the foreground service keeps the process alive.
- `RecordingFragment.configureModel()` checks the registry: if a Dev-mode session is active but the ViewModel is empty (process revival edge case), it restores the model config from the registry entry and skips the picker. The normal in-process case (ViewModel alive) continues to use `isClassifying()`.
- `HistoryFragment` observes `ActiveSessionRegistry.active` and pushes the set of active `sessionStartTime`s into `PackageAdapter`. Matching rows show a red "● ACTIVE" badge next to the Dev/User mode badge; it appears/disappears live as recordings start and stop.

**2026-04-14 — Removed optional comment from evaluation**
- `EvaluationActivity` no longer has a comment `EditText`. Users now pick one of the 9 classes and tap Send — no more text input, no keyboard issues.
- `PredictionRecord.userComment` field removed. `PredictionRepository.updatePredictionEvaluation(id, userSelectedClass)` now takes a single parameter.
- CSV export: `user_comment` column removed from both the header and each row.
- Layout cleanup in `activity_evaluation.xml` (removed `TextInputLayout` + divider); string `evaluation_comment_hint` deleted.

**2026-04-14 — Persistent in-app evaluation card + sticky Send/Skip**
- `activity_evaluation.xml` restructured: the Send/Skip button row now sits **outside** the `ScrollView` as a sticky bottom bar inside the card, so it's always reachable when the soft keyboard opens. Comment `EditText` also gets `imeOptions="actionDone"`.
- In-app evaluation prompt is now a **persistent card** below the Start/Stop button in `RecordingFragment` with a live 5-min countdown (previously a short-lived Snackbar). `UiState.pendingEvaluation: PendingEvaluation?` drives visibility; `MainViewModel` sets it for 5 min when a LONG recording finishes with the app in foreground and auto-clears it on expiry.
- `EvaluationPromptBus` simplified to a dismissal-only bus: `EvaluationActivity.onDestroy()` posts the prediction id and the ViewModel clears matching `pendingEvaluation`. The previous Snackbar in `MainActivity` was removed.

**2026-04-14 — Foreground evaluation routing + keyboard manifest fix**
- `EvaluationActivity` sets `windowSoftInputMode="adjustResize|stateHidden"` in the manifest.
- LONG-mode evaluation no longer posts a system notification when the app is in the foreground. `MainViewModel.sendEvaluationNotification` checks `ProcessLifecycleOwner` state: backgrounded → system notification as before; foregrounded → in-app card (see entry above).
- New dependency: `androidx.lifecycle:lifecycle-process:2.7.0`.

**2026-04-14 — Two-step mode picker + timeline schema**
- Mode picker restructured into two steps: first category **"Continuous"** (FAST, STANDARD, AVERAGE) vs. **"Interval"** (LONG), then the specific mode. Sub-buttons are generated dynamically in Kotlin.
- New `RecordingCategory` enum and `category` / `devOnly` properties on `RecordingMode`; helper `forCategory(cat, isDevMode)`.
- New `ModeTimelineView` (Canvas-based) under the picker: static schema per mode — filled blocks for recording, gaps for pauses, labels like "10s" / "30 min pause". AVERAGE additionally draws a bracket under all 10 × 1 s blocks with the label "10 × 1s → 1 averaged result" to make the aggregation explicit. One-line description below (`mode_desc_*` strings).
- Last selected mode is persisted in `SharedPreferences` ("mode_prefs") with separate keys for User Mode and Dev Mode.
- All newly added UI copy is in English (app-wide convention).

**2026-04-14 — MEDIUM-Modus entfernt**
- Der ungenutzte `MEDIUM (5s)`-Aufnahmemodus wurde komplett aus App entfernt: Enum-Eintrag, Button im Recording-Screen, String-Ressource und alle Referenzen in `RecordingFragment`. Damit bleiben 4 Modi: FAST, STANDARD, LONG, AVERAGE (Dev-only).

**2026-03-31 — AVERAGE-Modus: Echtzeit-Aufnahme, History-Details, Settings-Legende, UI-Fixes**
- AVERAGE-Modus nimmt sekundenweise auf (nicht mehr 10s am Stück), eigener Flow `startAverageClassification()` im ViewModel, läuft in Endlos-Schleife wie die anderen Modi.
- History-Session-Detail zeigt bei AVERAGE-Sessions eine Sektion „Per-Second Clips (1s)" mit den 10 Einzelvorhersagen. Neues Datenmodell `PerSecondClip` in `PredictionRecord.perSecondClips`.
- CSV-Export um Spalte `per_second_clips` erweitert (Format: `1:Natur:79%|2:Natur:85%|...`).
- Processing-Indikator in den Header verschoben (kein Layout-Springen mehr).
- Scene-Klassen-Legende als Karte in Settings (automatisch aus `SceneClass`-Enum).
- „Show Live Data"-Label vereinheitlicht (Per-Second-Toggle + Volume-Toggle).

**2026-03-31 — Per-Second Circles (AVERAGE-Modus Live-Visualisierung)**
- 10 kleine Confidence-Kreise unter dem großen Kreis, füllen sich live während der Inferenz.
- Großer Kreis zeigt laufenden Durchschnitt, der nach jedem 1s-Clip aktualisiert wird.
- Toggle zum Ein-/Ausblenden der Per-Second-Kreise, Karte nur sichtbar wenn AVERAGE aktiv.
- `ConfidenceCircleView` ist jetzt größenflexibel (`setTargetSize(dp)`).
- Neue `UiState`-Felder: `perSecondResults` (10 Slots) und `runningAverageResult`.

**2026-03-31 — Evaluation-Feature, AVERAGE-Modus, History-Badges**
- **Evaluation (LONG-Modus):** Nach jeder 10s-Aufnahme eine Push-Notification; Nutzer hat 5 min Zeit, eine der 9 Klassen zu wählen + optionalen Kommentar. Neue `EvaluationActivity` mit Countdown-Timer, überlebt Rotation. Daten in `PredictionRecord.userSelectedClass`/`userComment`, CSV um 2 Spalten erweitert.
- **AVERAGE-Modus (Dev-only):** Neuer `RecordingMode.AVERAGE` — 10s aufnehmen, in 10×1s schneiden, je Clip inferieren, Wahrscheinlichkeiten mitteln.
- **Mode-Badge:** Session-Karten in History zeigen „Dev" (blau) / „User" (grün).
- **Distribution-Vergleich:** Session-Detail-Dialog zeigt „Modell-Vorhersagen" + „Nutzer-Bewertungen" (letztere nur LONG).
- **Code-Quality:** `@Synchronized` auf alle `PredictionRepository`-Methoden, Gson-Custom-Deserializer für nullable `SceneClass`.

**2026-03-31 — Projektstruktur-Cleanup & Modell-Update**
- Activities (`MainActivity`, `WelcomeActivity`, `HistoryActivity`) und Fragments von Root-Package nach `ui/` verschoben; Imports, `AndroidManifest.xml` und `nav_graph.xml` angepasst.
- Lose Dateien organisiert: Python/Shell-Skripte → `scripts/`, Doku → `docs/`.
- 3 ungenutzte XML-Ressourcen gelöscht (`item_history.xml`, `history_menu.xml`, `menu_history_selection.xml`).
- Alte Modelle (`model2.pt`, `model3.pt`, altes `model1.pt`) entfernt; ein neues `model1.pt` (420 KB) in `user_model/` und `dev_models/`.
