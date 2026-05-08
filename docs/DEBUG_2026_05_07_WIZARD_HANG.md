# Debug-Session 2026-05-07 — Wizard-Hang nach erster Aufnahme

Mit Hilfe von [Maestro](https://maestro.dev/) auf einem Pixel 9 (Android 16) reproduziert und behoben. Der eine echte Bug, der die App unbrauchbar machte, hatte drei Quellen, die zusammenwirkten — keine davon wäre durch reines Code-Lesen aufgefallen.

## Symptom

Nach einer beliebigen Session lässt sich die zweite Session nicht mehr starten. Der Wizard akzeptiert die Eingaben, der „Start"-Button reagiert, aber die App landet wieder auf der Welcome-Seite statt auf dem Live-Recording-Screen. Workaround bisher: App komplett schließen und neu starten.

Zusätzlich zeigte Bild 2 vom Briefing eine Konfiguration mit gemischten Trainings-Längen (1 s + 10 s Modelle), bei der **alle** Recording-Methoden in Step 3 ausgegraut waren — auch der Average-Modus, der eigentlich beide Längen abdecken sollte.

Drittens: der Zurück-Pfeil oben links sah „billig" aus, weil dafür das System-Material `@android:drawable/ic_menu_revert` verwendet wurde.

## Was Maestro ausgeholt hat

Ohne Maestro hätte ich die echte Ursache nicht gefunden. Erst die scriptbare End-to-End-Sequenz „erste Session → zweite Session" + paralleler `adb logcat` machte den Bug stabil reproduzierbar. Folgende Maestro-Tools waren entscheidend:

- **`mcp__maestro__inspect_screen`** vor jedem Tap — gab mir die echten View-IDs (`liveStopButton`, `wizardPrimary`) statt mich auf Texte zu verlassen, die mit anderen UI-Elementen kollidieren konnten („Stop" vs. „Stop manually" stand zur gleichen Zeit auf dem Bildschirm).
- **`mcp__maestro__run` mit Inline-YAML** — eine kompakte Liste aus `tapOn` + `waitForAnimationToEnd` reichte aus, um den Bug deterministisch zu reproduzieren. Pro Iteration ~80 Sekunden, statt mehreren Minuten manueller Klickerei.
- **`mcp__maestro__take_screenshot`** nach jedem Schritt — der erste Beweis dass die zweite Session in Welcome landet (Screenshot statt Live-Recording-View) hat den Bug greifbar gemacht.

Parallel dazu lief `adb -s 48300DLAQ0045C logcat` und filterte auf `MainViewModel|AudioRecorder`. Die Logs zeigten zwei Dinge, die aus dem Code allein nicht herauszulesen waren:

1. **`applySessionConfig called while running — ignored`** — die zweite Wizard-Start fand `isRunning == true` vor, obwohl der User schon Stop getippt hatte.
2. **Zwei parallele `Recording started, collecting 320000 samples`** mit ~30 ms Versatz — zwei `AudioRecord`-Instanzen liefen gleichzeitig auf demselben Mikrofon. Beim ersten Lauf des Tests vor dem Fix kamen sogar > 100 `Starting recording` / `Recording stopped`-Paare in derselben Millisekunde, ein klassischer Tight-Loop ohne Yield.

Der Code allein gab nirgends einen Hinweis darauf, dass zwei `runSessionLoop`-Coroutinen gleichzeitig liefen — der Wizard hat ja nur einmal „Start" gedrückt. Die Logs haben das aufgedeckt.

## Ursachen-Kette

Die drei Bugs hingen zusammen:

1. **`LiveRecordingFragment.render()` startete heimlich eine zweite Session.** Sobald der User Stop tippte, lief der laufende Recording-Loop noch ein paar Millisekunden weiter, bis er beim nächsten Suspension-Point seine Cancellation observieren konnte. In diesem Fenster setzte `onSessionLoopExit()` `appState = AppState.Ready` zurück, was ein letztes UiState-Update triggerte. Die noch lebende `uiState.collect` im LiveRecordingFragment rief render() auf, sah `isModelLoaded && !isClassifying() && state.appState !is AppState.Error` als true und rief erneut `viewModel.startSession()`. Damit waren plötzlich zwei `recordingJob`s aktiv, `isRunning` wurde reaktiviert, der gecancelte alte Loop konnte über sein `while (isRunning)` weiter durchlaufen, beide Loops kämpften ums Mikrofon, und beim nächsten Wizard-Start fand `applySessionConfig` `isRunning=true` vor und brach ab.

2. **`recordCycleAudio` und `runAverageCycle` schluckten `CancellationException`.** Im äußeren Recording-Loop hieß das: bei einer Cancellation kam `null` zurück, der Loop traf auf `?: continue`, sprang ohne Yield zum nächsten Iteration-Anfang, und konnte die Cancellation gar nicht beobachten. Genau das Tight-Loop-Verhalten, das in den Logs als `Starting recording` / `Recording stopped` ohne Pausen sichtbar war.

3. **AudioRecord wurde pro Cycle geleakt.** Das `audioRecorder`-Feld wurde im Loop überschrieben (`audioRecorder = AudioRecorder(...)`), ohne die alte Instanz zu releasen. Über mehrere Sessions hinweg sammeln sich Native-Handles, die das System irgendwann nicht mehr aufmacht.

Dazu noch zwei semantische Bugs, die nichts mit dem Hang zu tun hatten:

4. **`LongSubMode.AVERAGE.isCompatibleWith` gab `false` zurück** für 10 s-Modelle. Der Wizard graute Avg deshalb aus, sobald 1 s + 10 s im Spiel waren — obwohl Avg neutral genug ist, um pro Modell unterschiedlich zu inferieren.

5. **Der Wizard-Header benutzte `@android:drawable/ic_menu_revert`** — ein OS-System-Icon, kein Material-Drawable.

## Behebung

Alle Änderungen liegen auf dem Branch `fix/wizard-stability-and-avg`, in zwei Commits:

### Commit 1 — `fix(session)`

- `LiveRecordingFragment` bekommt einen One-Shot-Auto-Start (`hasAutoStarted`) plus ein `stopInFlight`-Flag, das render() neutralisiert, sobald der User Stop getippt hat. Damit kann eine späte UiState-Emission keine zweite Session mehr launchen.
- `recordCycleAudio` und der innere Recorder-Collect von `runAverageCycle` haben ihre `catch (_: CancellationException) { return null }`-Blöcke verloren. Cancellation propagiert jetzt direkt zur `launch { ... }.catch (_: CancellationException) { ... }`-Klammer in `startSession`.
- `runSessionLoop` ruft `currentCoroutineContext().ensureActive()` als allererstes pro Iteration. Falls ein Race `isRunning` doch noch einmal auf `true` zurücksetzt, wird ein gecancelter Job an dieser Stelle terminiert.
- Vor jedem Reassign von `audioRecorder` wird `audioRecorder.stopRecording()` aufgerufen. Native AudioRecord-Handles bleiben nicht hängen.
- `ModelInference` bekommt eine `release()`-Methode, die `Module.destroy()` ruft und die Referenz nullt. `clearSessionResults()` wendet sie auf alle parkenden Modelle an. Beim Verlassen vom LiveRecordingFragment ohne Stop wird `clearSessionResults()` ebenfalls aufgerufen, damit Wizard-Restarts den Speicher nicht vollpacken. `onCleared()` lässt die nativen Module bewusst in Ruhe, weil ein `forward()` mid-flight kein Cooperative-Cancellation kennt.

### Commit 2 — `feat(wizard)`

- `LongSubMode.AVERAGE.isCompatibleWith` returnt jetzt `true` für 1 s und 10 s. Der Avg-Cycle in `MainViewModel` partitioniert pro Modell: 1 s-Modelle bekommen weiterhin ihre 10 Per-Slice-Inferenzen plus Mittelwert. 10 s-Modelle bekommen die 10 × 1 s als concatenierten 10 s-Buffer in einem einzelnen Inference.
- Per-Second-Circles werden nur dann live aktualisiert, wenn das Primary-Modell 1 s-trainiert ist. Sonst ergeben die Circles während eines 10 s-Cycles keine zwischenzeitlichen Werte.
- Interval-AVG-Pfad ist genauso angepasst: 10 s-Modelle bekommen den vollen 10 s-Buffer, statt auf einem 1 s-Slice mit falscher Tensor-Shape zu crashen.
- Der Mixed-Hint im Wizard sagt jetzt warum Avg trotz Mixed funktioniert, statt „no option is valid".
- Back-Pfeil benutzt das saubere `@drawable/ic_arrow_back`, eingebettet in einen 44 dp Tap-Target. Der Step-Indicator ist eine Reihe Dots (8 dp aktiv, 6 dp inaktiv), gerendert in `WizardFragment.renderStepDots()`. Gleicher Pfeil im LiveRecording-Header.

## Verifikation am Pixel 9 (Maestro)

Drei Maestro-Flows decken die Reparatur ab:

1. **Erste Session.** Welcome → 4 × 10 s + Continuous + Standard + Stop manually → Recording läuft, Stop landet sauber im „Session ended"-Screen. Logcat zeigt `stopSession()` → `Recording loop cancelled` → `Session loop exited (auto=false)`.
2. **Zweite Session direkt im Anschluss.** Back to home → Use last config → Wizard durch → Start. Recording läuft, Stop endet sauber. Logcat zeigt nur einen `recordingJob` und keine parallelen `Recording started`-Zeilen mehr. Vor dem Fix: zwei parallele Loops und der besagte `applySessionConfig called while running — ignored`. Danach: einer.
3. **Avg mit gemischten Modellen.** Welcome → 1 × 10 s + 1 × 1 s → Continuous: Avg ist enabled, Fast und Standard sind ausgegraut, der Hint erklärt warum. Start läuft, Logcat zeigt `collecting 32000 samples` (1 s-Slices), Stop endet sauber.

`./gradlew assembleDebug` und `./gradlew test` sind grün.

## Was ich beim nächsten Mal anders machen würde

Vor dem ersten Lauf war meine Hypothese, dass nur der AudioRecord-Leak und ein nicht-propagierender CancellationException den Bug verursachen. Das stimmte nur teilweise — der eigentliche Trigger war der versteckte zweite `startSession()`-Aufruf aus `LiveRecordingFragment.render()`. Ohne Maestro-Reproduktion hätte ich den Hang erst beim Code-Review viel später gefunden — wenn überhaupt. Ein Smoke-Test mit Maestro pro Wizard-Anpassung wäre eine gute Default-Routine.

## Stolperfalle Maestro: `waitForAnimationToEnd` ist kein `sleep`

Mein erster Repro-Versuch sah aus, als würde der ResultsSummary-Screen seine Bar-Distributions verlieren — nur „Avg volume: 0.00" pro Modell, keine Top-Klasse. Tatsächlich war das ein Test-Artefakt: ich hatte `waitForAnimationToEnd: { timeout: 25000 }` zwischen Start und Stop gesetzt, in der Annahme dass das wie ein 25 s Sleep funktioniert. In Wahrheit ist `waitForAnimationToEnd` ein **Timeout**, der returnt sobald keine Animation mehr läuft — auf einer ruhigen Live-Recording-Page also fast sofort. Maestro hat den Stop-Button nach 6 s gedrückt, mitten im ersten Cycle, also gab es nie ein vollständiges `accumulateAggregate()`. Workaround: `extendedWaitUntil { notVisible: "Stop", timeout: 25000 }` als bewusster „warte bis das nie passiert"-Block, und die Stop-Sequenz danach in einem zweiten Maestro-Flow. Damit lief die Session lang genug für drei Cycles, ResultsSummary zeigte alle Bar-Distributions wie spec'd.

## Settings-Aufräumarbeiten

Beim Smoke-Test entdeckt: der Settings-Screen trug noch eine „User Model"-Sektion mit „—", obwohl das Mode-Konzept seit dem Wizard-Umbau weg ist (siehe CLAUDE.md vom 2026-05-07). Ein Überbleibsel, das die App als unfertig wirken lässt. Gleichzeitig fehlte der Back-Pfeil im Settings-Header — Wizard und LiveRecording haben einen, Settings hatte nur Hardware-Back. Beides in einem `chore(settings)`-Commit nachgezogen: tote Sektion entfernt, Header analog zum Wizard mit ic_arrow_back + 44 dp Tap-Target ausgestattet. Ohne Maestro-Smoke-Test wäre auch das untergegangen — der Code hatte keine offensichtlichen Hinweise, dass die User-Model-Sektion praktisch leer ist.
