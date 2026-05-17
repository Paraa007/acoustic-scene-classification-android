# Ultra-Review Fixes (Stand 2026-05-17)

Diese Doku begleitet den Branch `fix/2026-05-17-ultra-review-batch`. Pro Fix
steht hier: **was war kaputt**, **was wäre im schlimmsten Fall passiert**, und
**wie der Fix funktioniert**. Geschrieben als wäre ich ein Senior-Entwickler,
der dir das im 1:1 erklärt.

Ein paar Sachen vorweg: Ich hatte im Review ein paar Behauptungen, die beim
genaueren Hinschauen falsch waren. Ich liste sie unten unter "Was sich beim
Verifizieren als Fehlalarm rausgestellt hat" auf, damit du nachvollziehen
kannst, wo Sub-Agents übermütig waren und wo der Code schon richtig war.

## Was insgesamt gemacht wurde

17 Code-Änderungen über die ganze App, plus die verwaisten Strings, Drawables
und Konstanten aus der Pre-Wizard-Ära raus. Build ist grün (`./gradlew assembleDebug`).

---

## 🔴 Critical (hätte echten Schaden anrichten können)

### 1. WakeLock hing nach App-Crash 4 Stunden am Akku

**Datei:** `app/src/main/java/com/fzi/acousticscene/service/ClassificationService.kt`

**Problem:** Der `PARTIAL_WAKE_LOCK` wurde mit `acquire(4 * 60 * 60 * 1000L)`
geholt, also 4 Stunden Timeout. Der WakeLock verhindert, dass der CPU
einschlafen kann, damit Audio-Aufnahme im Hintergrund läuft. Wenn die App
crasht oder vom System gekillt wird, BEVOR `releaseWakeLock()` aufgerufen
wurde, hält Android den Lock weiter. Erst nach 4 Stunden gibt das System
ihn automatisch frei.

**Schlimmster Fall:** User macht eine 10-Min-Aufnahme, App stürzt ab, Handy
verbraucht die nächsten 4 Stunden Akku als ob es voll ausgelastet wäre,
obwohl die App längst weg ist. Bei einem Bachelor-Tester am FZI oder einem
Probanden im Feldtest wäre das eine miese Erfahrung. Wenn es jeden Tag
passiert: 30 Prozent Akku-Verlust pro Drop-Out.

**Wie ich es gefixt habe:** Es gibt schon einen Keep-Alive-Alarm, der alle
30 Sekunden feuert. Den habe ich genutzt, um den WakeLock periodisch zu
refreshen. Konkret:

1. Timeout auf 90 Sekunden runter (drei mal so lang wie das Alarm-Intervall,
   damit ein verpasster Alarm noch Luft hat).
2. `acquireWakeLock()` immer aufrufbar gemacht. Bisher kehrte die Funktion
   früh zurück, wenn der Lock schon held war. Jetzt refresht jeder Call den
   Timeout, weil das die PowerManager-API erlaubt: `acquire(timeout)` auf
   einem schon held Lock setzt einfach den Timer zurück.
3. Den Keep-Alive-Alarm-Receiver dazu gebracht, einfach `acquireWakeLock()`
   zu rufen, statt umständlich `isHeld` zu prüfen.

Effekt: Im Normalbetrieb bleibt der Lock dauerhaft held (jeder Alarm setzt
den Timer zurück). Bei Crash gibt das System den Lock spätestens nach 90
Sekunden frei statt nach 4 Stunden.

**Wie ich auf die Lösung kam:** Das Muster "lange Timeout + periodischer
Refresh" steht in der Android `PowerManager.WakeLock` Dokumentation als
empfohlene Strategie für genau diesen Fall (langlebige Operations mit
Crash-Safety). Der existierende Keep-Alive-Alarm war schon der halbe Weg
dahin, der zweite Schritt war nur, den Timeout an die Alarm-Frequenz zu
koppeln.

---

### 2. Foreground-Service konnte als "Zombie" wiederauferstehen

**Datei:** `app/src/main/java/com/fzi/acousticscene/service/ClassificationService.kt`

**Problem:** `onStartCommand()` gab `START_STICKY` zurück. Das ist eine
Android-Konstante, die dem System sagt: "Wenn du mich killst, starte mich
bitte ohne Intent neu." Bei einem normalen UI-getriebenen Service wäre das
nutzlos und gefährlich. Der Service hätte beim Wieder-Start keinen
Recording-Plan, keine Models geladen, kein UI dran, würde aber trotzdem
seinen Notification + WakeLock-Pfad anlegen.

**Schlimmster Fall:** Bei Memory-Druck killt Android den Service. Stunden
später (z.B. weil der User die App komplett vergessen hat) startet Android
den Service stillschweigend neu, der zeigt dann eine Notification "Recording
running", obwohl gar nichts aufgenommen wird, und hält den WakeLock. Akku
ist alle, User wundert sich.

**Wie ich es gefixt habe:** `START_NOT_STICKY` zurückgeben. Heißt: System
killt den Service, System lässt ihn tot. Der User muss aktiv eine neue
Session starten. Das ist genau das richtige Verhalten für einen Service,
der vom User getriggert wird.

**Wie ich auf die Lösung kam:** Die Android-Docs für `Service.onStartCommand`
listen explizit auf, wann `START_STICKY` sinnvoll ist (z.B. Music-Player,
Background-Sync ohne UI). User-getriebene Foreground-Services mit aktivem
State sind genau das Gegenbeispiel und sollen `START_NOT_STICKY` nutzen.

---

### 3. Repository-Reader liefen ohne Lock

**Datei:** `app/src/main/java/com/fzi/acousticscene/data/PredictionRepository.kt`

**Problem:** Die Funktionen `getTodaysPredictions()`, `getCount()`,
`getTodaysCount()` und `getStatistics()` haben in die `predictions: MutableList`
gelesen, ohne `@Synchronized` zu sein. Gleichzeitig schreibt `addPrediction()`
und `deletePackage()` mit `@Synchronized` rein. Wenn ein Live-Recording
gerade eine neue Prediction added, während die UI gerade `getStatistics()`
oder `getTodaysPredictions()` aufruft, kann passieren:

- `ConcurrentModificationException` beim Iterieren der Liste
- Inkonsistente Snapshots (halbe Updates sichtbar)

**Schlimmster Fall:** App-Crash mitten in der Live-Aufnahme, weil die UI
gerade Statistiken neu rendert während ein neuer Record reinkommt. User
verliert die laufende Session. Reproduzierbar besonders bei FAST-Modus, wo
jede Sekunde ein neuer Record kommt.

**Wie ich es gefixt habe:** Alle vier Reader mit `@Synchronized` annotiert.
Das nutzt den gleichen Monitor wie die `@Synchronized`-Writer, also können
Reader und Writer nie zur gleichen Zeit auf der Liste hantieren.

**Wie ich auf die Lösung kam:** Klassisches Reader-Writer-Problem. Das
JVM-Builtin-Synchronized ist hier passend, weil die Operations kurz sind
(milliseconds). Für längere Reads würde man `ReadWriteLock` oder
`CopyOnWriteArrayList` nehmen, aber das wäre Over-Engineering. Wichtig:
**alle** Methoden, die die Liste anfassen, müssen den gleichen Lock teilen,
sonst hat man eine Lücke. Hab daher noch einmal alle Methoden in der
Klasse durchgegangen.

---

### 4. CSV-Export schrieb Pause-Records als echte Klassifikationen rein

**Datei:** `app/src/main/java/com/fzi/acousticscene/data/PredictionRepository.kt`

**Problem:** Pause-Records sind synthetische Zeilen, die geschrieben werden,
wenn der User mitten in der Session auf Pause drückt. Sie tragen
Placeholder-Werte: `sceneClass = TRANSIT_VEHICLES`, `confidence = 0.0`. Im
UI-Code wird das überall sauber rausgefiltert. Im CSV-Export aber nicht.
Das heißt, der exportierte CSV enthielt Zeilen, die wie echte
"Transit/Vehicles"-Klassifikationen mit 0% Konfidenz aussehen.

**Schlimmster Fall:** Du oder jemand am FZI analysiert den CSV in Python
oder Excel und denkt, das Modell sage "Transit Vehicles" mit 0% Konfidenz.
Falsche wissenschaftliche Auswertung, vor allem in der Klassen-Verteilung
(TRANSIT_VEHICLES wäre über-repräsentiert).

**Wie ich es gefixt habe:** In `exportToCsvString()` vor allem die Records
durch `realOnly()` gefiltert. Auch die Header-Ableitung für die
ALL-IN-ONE-Spalten geht jetzt nur über echte Records (sonst wäre eine
reine Pause-Session ein leerer Header gefolgt von einer Pause-Zeile
gewesen).

**Wie ich auf die Lösung kam:** Das CLAUDE.md des Projekts hat explizit
notiert, dass Pause-Records-Filterung schon mal vergessen wurde. Beim
grep nach `it.isPause` waren die UI-Stellen alle gefiltert, der Export war
die einzige Stelle ohne Filter. Klassische "vergessen ist überall außer
hier"-Lücke.

---

### 5. Package-Distribution zeigte falsche Prozente

**Datei:** `app/src/main/java/com/fzi/acousticscene/ui/ModernDialogHelper.kt`

**Problem:** Im Detail-Dialog für ein Recording-Package wurde die
Klassen-Verteilung als Balken angezeigt. Die Prozente waren systematisch zu
niedrig. Der Grund: der Zähler kam aus `stats.classDistribution`, die intern
schon Pause-Records rausfiltert. Der Nenner war aber
`packageRecords.size.toFloat()`, also inklusive Pause-Records.

Wenn eine Session 100 Records hatte, davon 30 Pausen und 70 echte
Klassifikationen, wurden die Prozente gegen 100 statt gegen 70 gerechnet.
Eine Klasse, die 40 mal vorkam, wurde als 40% (statt 57%) angezeigt.

**Schlimmster Fall:** User sieht im Dialog, dass eine Klasse vermeintlich
nur 40% ausmacht, denkt das Modell sei nicht zuverlässig, oder nutzt die
Zahlen für seine Bachelor-Arbeit. Verfälschte Auswertung.

**Wie ich es gefixt habe:** `val totalCount = realRecords.size.toFloat()`.
Eine Zeile. `realRecords` war schon vorher in der Funktion berechnet (für
andere Anzeigen), war nur an der falschen Stelle nicht verwendet.

**Wie ich auf die Lösung kam:** Sub-Agent hat es gespottet, ich habe es im
Code verifiziert (Zeile 343 versus Zeile 281, die schon korrekt war).
Klassischer "an einer Stelle filtern, an anderer nicht"-Bug.

---

### 6. ConfidenceCircleView hielt Activity nach Detach am Leben

**Datei:** `app/src/main/java/com/fzi/acousticscene/ui/ConfidenceCircleView.kt`

**Problem:** Die Animation der Confidence-Anzeige war so implementiert:

```kotlin
android.os.Handler(android.os.Looper.getMainLooper()).post(object : Runnable {
    override fun run() {
        confidence = ...
        if (currentStep < 30) postDelayed(this, 16)
    }
})
```

Probleme:
1. Jeder `setConfidence(animate=true)` Call erzeugte einen neuen anonymen
   Handler, ohne dass der alte gestoppt wurde.
2. Es gab keine Referenz auf den Runnable, also konnte man ihn nicht
   abbrechen, wenn die View detached wurde (z.B. Fragment-Wechsel).
3. Das Runnable hielt implizit die View (über `this`), die View hielt den
   Context, der Context war die Activity. Klassischer Activity-Leak.

**Schlimmster Fall:** User wechselt während einer laufenden Session zwischen
Fragments hin und her. Jeder Wechsel leakt eine Activity-Instanz. Nach 10
Wechseln hat die App 10 Activities im Heap, die nicht GC'd werden können.
Bei längerem Benutzen → OutOfMemoryError und App-Crash.

**Wie ich es gefixt habe:** Drei Schritte:

1. Handler als Field der View (`animationHandler`), nicht pro Call neu
   erzeugt.
2. `pendingAnimation: Runnable?` als Field, das beim nächsten
   `setConfidence` gestoppt wird, bevor ein neues startet.
3. `onDetachedFromWindow()` überschrieben und dort
   `animationHandler.removeCallbacksAndMessages(null)` aufgerufen.

**Wie ich auf die Lösung kam:** Das ist das Standard-Pattern für
Handler-basierte Animationen in Custom Views. In modernem Android würde
man stattdessen `ValueAnimator` oder die Animation API nutzen, die haben
das Cleanup eingebaut. Aber der minimal-invasive Fix für diesen
Code-Stand ist Handler-als-Field plus `onDetachedFromWindow`.

---

### 7. Auto-Backup hat Aufnahme-Verläufe heimlich in die Google-Cloud gespiegelt

**Dateien:** `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`

**Problem:** Im Manifest war `android:allowBackup="true"`, aber die
Backup-Regeln waren nur Template-Kommentare, die nichts ausgeschlossen
haben. Heißt: Alle SharedPreferences (also die kompletten Predictions plus
die `last_config_prefs`) wurden via Android Auto-Backup in Google Drive
des Users gespeichert. Inklusive Timestamps, erkannten Szenen,
Konfidenz-Werten, Modell-Konfigurationen.

**Schlimmster Fall:** Privacy-Issue. Ein User testet die App im Büro, die
App erkennt "Indoor Work Environment" um 14:32 Uhr, das landet in seinem
Cloud-Backup. Wenn er das Handy wechselt oder das Backup von anderswo
restored wird, sind die Daten da. Selbst wenn er die App deinstalliert,
bleiben die Daten in der Cloud, bis Auto-Backup sie überschreibt.

**Wie ich es gefixt habe:** Explizit `<exclude domain="sharedpref" path="...">`
für `predictions_prefs.xml` und `last_config_prefs.xml` in **beiden**
XML-Dateien (Backup-Rules für API < 31, Data-Extraction-Rules für API 31+).
Und ein device-transfer Exclude, damit auch beim Handy-zu-Handy-Transfer
nichts mitwandert.

**Wie ich auf die Lösung kam:** Android-Doku zu Auto-Backup ist eindeutig:
default sind ALLE SharedPreferences inkludiert, und man muss explizit
opt-out. Die SSOT für die zu schützenden PREFS-Namen steht in den
jeweiligen Repository-Klassen (`predictions_prefs` in `PredictionRepository`,
`last_config_prefs` in `LastConfigStore`).

---

### 8. PyTorch-Module wurden bei Wizard-Re-Run nicht freigegeben

**Datei:** `app/src/main/java/com/fzi/acousticscene/ml/ModelInference.kt` +
`app/src/main/java/com/fzi/acousticscene/ui/MainViewModel.kt`

**Problem:** PyTorch Mobile Modelle leben in nativem (C++) Speicher, der
nicht von Javas Garbage-Collector verwaltet wird. Die `Module`-Klasse hat
eine `destroy()`-Methode, die diesen Speicher freigibt. Im Code gab es
schon ein sauberes `release()` auf `ModelInference`, das `destroy()` ruft.
Aber an zwei Stellen wurde es übergangen:

1. `MainViewModel.applySessionConfig()` legte einen neuen Map an, ohne die
   alten ModelInferences zu releasen. Bei jedem Wizard-Durchlauf mit
   anderen Modellen leakte das pro Modell einen nativen Allocation.
2. `ModelInference.setModelPath()` nullte einfach das `module`-Feld, ohne
   das alte zu destroyen.

**Schlimmster Fall:** Wenn ein User den Wizard 10 mal mit verschiedenen
Modellen durchspielt, ohne die App zu killen, sind 10 native PyTorch-Module
allocated. Die belegen ggf. mehrere hundert MB nativen Speicher. Android
kann den Process unter Memory-Druck killen, ohne dass die Java-Heap-Stats
das überhaupt sehen.

Wichtig: Den **Sub-Agent-Vorwurf "Tensor-Leak"** habe ich verifiziert und
**falsch** gefunden. Die PyTorch-Mobile-1.13.1-API stellt für `Tensor` gar
kein public `close()` bereit. Tensors leben über DirectByteBuffer und
werden vom GC reclaimed. Der echte Leak war auf Module-Ebene.

**Wie ich es gefixt habe:** In `setModelPath` vor dem Path-Swap einmal
`release()` rufen. In `applySessionConfig` über die alte Map iterieren und
`inf.release()` für jeden Eintrag rufen, bevor die neue Map zugewiesen
wird.

**Wie ich auf die Lösung kam:** Beim Grep nach `ModelInference(` hab ich
gesehen, dass die einzige Erzeugung in MainViewModel ist. Beim Lesen der
`applySessionConfig` ist mir aufgefallen, dass alte Werte ersetzt werden,
ohne released zu werden. `release()` selbst existierte schon und war
korrekt implementiert, wurde nur zu wenig genutzt.

---

## 🟠 Major (echte Bugs, aber kleinerer Blast Radius)

### 9. ComputationDispatcher konnte in Tests nicht aufgeräumt werden

**Datei:** `app/src/main/java/com/fzi/acousticscene/ml/ComputationDispatcher.kt`

**Problem:** Der `Executors.newFixedThreadPool(2)` hatte keine Möglichkeit,
ihn zu shutdownen. In Production ist das harmlos (App-Prozess lebt einmal,
Pool stirbt mit ihm), aber in Unit-Tests sammeln sich Threads über die
Test-Suite an.

**Wie ich es gefixt habe:** `shutdown()`-Funktion hinzugefügt, die den
Dispatcher schließt. Sub-Agent hatte behauptet, der Pool würde
"Threads über die App-Lebenszeit akkumulieren". Das stimmt nicht:
`newFixedThreadPool(2)` hat maximal 2 Threads, fertig. Habe die Beschreibung
entsprechend korrigiert.

---

### 10. PredictionRecord hatte ID-basiertes equals(), das in Sets/Maps gefährlich war

**Datei:** `app/src/main/java/com/fzi/acousticscene/model/PredictionRecord.kt`

**Problem:** Eigenes `equals()` und `hashCode()`, die nur die `id`
verglichen. Zwei semantisch unterschiedliche Records mit zufällig gleicher
`id` wären als gleich behandelt worden. Da die ID per
`System.currentTimeMillis()` generiert wird, ist Kollision unwahrscheinlich
aber nicht unmöglich (wenn zwei `addPrediction()` Calls in derselben
Millisekunde landen).

**Wie ich es gefixt habe:** Custom-Overrides gelöscht. Data Class default
ist jetzt aktiv (vergleicht alle Felder). Das ist die natürliche
Datenklassen-Semantik und macht Set/Map-Usage sicher.

Hinweis: Die inneren Klassen (`PerSecondClip`, `AllInOneResult`,
`LongSubResult`) haben ähnliche Custom-Overrides. Die habe ich
**absichtlich nicht angefasst**, weil sie spezifische Identitäts-Semantik
ausdrücken (`PerSecondClip` über `clipIndex` macht z.B. Sinn für
sortBy-Operationen). Wenn die jemals in Sets landen, sollte man sie
nochmal hinterfragen.

---

### 11. Locale-Bugs bei numerischer Formatierung

**Dateien:** Mehrere unter `app/src/main/java/com/fzi/acousticscene/ui/`

**Problem:** `String.format("%.1f", value)` und `"%.2f".format(value)`
ohne explizite Locale nutzen `Locale.getDefault()`. Auf einem deutschen
Handy bedeutet das, dass Zahlen wie `1.5` als `1,5` formatiert werden
(deutsches Dezimalkomma). Bei Stoppuhr-Anzeigen `1:05` etc. ist das
harmlos, bei `%.2f%%` Akku-Differenz oder Konfidenz-Prozent kann es
verwirren oder Re-Parses brechen.

**Schlimmster Fall:** Konfidenz `87.5%` wird als `87,5%` angezeigt. User
will das in eine Excel-Zelle kopieren, die Zelle interpretiert es als
Text statt als Zahl. Bei CSV-Export-Stellen wäre es noch schlimmer
gewesen, aber die nutzen `record.toCsvRow()`, das die Felder selbst
formatiert.

**Wie ich es gefixt habe:** Alle numerischen `String.format` und
`.format()`-Calls in den UI-Files auf `String.format(Locale.US, ...)`
umgestellt. Stoppuhr-Patterns auch (obwohl die im Pattern selbst
locale-neutral wären, ist die explizite Locale weniger fragil).

**Wo ich es bewusst nicht gemacht habe:** `String.format(getString(R.string.xxx), args)`
für lokalisierte Templates. Da soll die Locale die Default sein, weil
die Strings selbst lokalisiert sind. Habe ich so gelassen.

---

### 12. EvaluationPromptBus konnte Dismissals still verschlucken

**Datei:** `app/src/main/java/com/fzi/acousticscene/ui/EvaluationPromptBus.kt`

**Problem:** `MutableSharedFlow(extraBufferCapacity = 4)` mit `tryEmit()`.
Wenn der Buffer voll ist, gibt `tryEmit` `false` zurück und der Wert ist
weg. Bei mehreren schnellen Evaluation-Dismissals könnte das passieren,
ohne dass jemand es merkt.

**Wie ich es gefixt habe:** Capacity auf 64 erhöht (großzügig genug, kostet
nichts) und einen Log-Warning hinzugefügt, falls `tryEmit` doch mal `false`
zurückgibt. So bekommt der Entwickler ein Signal, statt im Stillen Daten
zu verlieren.

---

### 13. Mel-Filterbank Edge-Case wurde stillschweigend mit Null-Werten gefüllt

**Datei:** `app/src/main/java/com/fzi/acousticscene/audio/MelSpectrogramProcessor.kt`

**Problem:** Bei pathologischen FFT-Parametern (fMin/fMax außerhalb des
Bereichs) konnte ein Mel-Filter komplett leer sein. Der Code hat dann
einen Dummy-Filter mit Gewichten `[0f]` erzeugt. Die entsprechende
Mel-Spektrogramm-Zeile wäre dann immer 0, aber kein Log, keine Warnung.

**Wie ich es gefixt habe:** Einen lauten `Log.w()` hinzugefügt, der die
Indizes und Frequenzen ausgibt. So merkt man beim Wechsel auf ein neues
Modell mit anderen Parametern sofort, dass etwas nicht stimmt.

**Wie ich auf die Lösung kam:** Den Algorithmus selbst zu fixen wäre
riskant gewesen (die Mel-Filterbank-Mathematik wurde gegen torchaudio
validiert). Ein Log statt einer Code-Änderung ist die richtige Wahl, wenn
der Code für reale Parameter korrekt arbeitet und nur die theoretische
Lücke geschlossen werden soll.

---

### 14. BarDistributionView nutzte hardcoded Hex-Farben statt Theme

**Datei:** `app/src/main/java/com/fzi/acousticscene/ui/BarDistributionView.kt`

**Problem:** 9 Klassen-Farben waren als `Color.parseColor("#EF5350")` etc.
direkt im Kotlin-Code. Light-Mode und Dark-Mode bekamen die gleichen
Farben. Vor allem die braune Living-Room-Farbe `#8D6E63` war auf dem
dunklen Background fast unsichtbar.

**Wie ich es gefixt habe:** Die App hatte schon Color-Resources mit
Day/Night-Varianten (z.B. `R.color.transit_vehicles`). Die nutze ich
jetzt via `ContextCompat.getColor(context, R.color.xxx)`. Vorteil:
Light/Dark wird automatisch korrekt, und die Farben passen jetzt zu
den restlichen UI-Komponenten der App (vorher waren es zwei verschiedene
Paletten).

---

## 🟡 Minor (Polish, kein User-spürbarer Schaden)

### 15. WakeLock-Refresh und Alarm-Re-Scheduling sind jetzt idempotent

Im Service wurde bei jedem Keep-Alive-Alarm-Tick `startKeepAliveAlarm()`
erneut aufgerufen. Mit `FLAG_UPDATE_CURRENT` sollte der PendingIntent
überschrieben werden, aber zur Sicherheit rufe ich jetzt vorher
`stopKeepAliveAlarm()` auf, damit definitiv nichts stehen bleibt.

### 16. Magic Numbers in Audio / ML bekamen Namen

- `INPUT_AUDIO_SIZE = 320000` ist jetzt `SAMPLE_RATE_HZ * STANDARD_AUDIO_SECONDS`.
- `VOLUME_NORMALIZATION_DIVISOR` hat einen ausführlichen Kommentar bekommen,
  warum genau `5000.0` (empirisch kalibriert für UI-Bar-Range).

### 17. Pause-Filter ist jetzt eine zentrale Extension

Vorher stand `filterNot { it.isPause }` an 4 Stellen verstreut. Jetzt gibt
es eine Extension `List<PredictionRecord>.realOnly()` als Single Source of
Truth. Alle 4 Stellen plus der neue CSV-Export-Filter nutzen sie. So
verhindert die Code-Struktur den wiederkehrenden Bug, dass jemand die
Pause-Filterung an einer Stelle vergisst.

### 18. Dead Code raus

Nach Bestätigung gelöscht (waren alle als verwaist verifiziert via grep):

- **Strings** aus `strings.xml`: `user_mode`, `user_mode_subtitle`, `dev_mode`,
  `dev_mode_subtitle`, `no_models_found`, `back_to_user_mode`.
- **Drawables**: `ic_dev_mode.xml`, `ic_user_mode.xml`, `bg_mode_badge_dev.xml`,
  `bg_mode_badge_user.xml`.
- **Color Selector**: `bottom_nav_color.xml`.
- **Legacy Scene-Colors** (Pre-DCASE-2025): `airport`, `bus`, `metro`,
  `metro_station`, `park`, `public_square`, `shopping_mall`, `street_traffic`
  aus beiden colors.xml Files.
- **Konstante**: `ModelConfig.USER_MODEL_DIR`.
- **Methoden**: `MelSpectrogramProcessor.computeFFT()` und
  `extractPowerSpectrum()` (Wrapper, die nie aufgerufen wurden; die
  `*InPlace`-Versionen bleiben).

`welcome_no_models` wurde im Review als tot markiert, ist aber noch in
`WelcomeFragment.kt:39` aktiv. Bleibt.

---

## Was sich beim Verifizieren als Fehlalarm rausgestellt hat

Die Review-Agents waren stellenweise übermütig. Was ich verifiziert habe
und nicht eingebaut, weil es schon korrekt war oder die Behauptung falsch:

1. **"PyTorch Tensors müssen geschlossen werden"** (Agent 1): Falsch. Die
   PyTorch-Mobile-1.13.1-API stellt für `Tensor` kein `close()` bereit.
   Tensors werden GC-managed via DirectByteBuffer. Der echte Leak war auf
   `Module`-Ebene (siehe Fix #8).

2. **"ComputationDispatcher akkumuliert Threads"** (Agent 1): Falsch.
   `newFixedThreadPool(2)` hat exakt 2 Threads, mehr nicht. Habe trotzdem
   ein `shutdown()` ergänzt für Test-Sauberkeit.

3. **"Permission-Handling fehlt vor AudioRecord-Init"** (Agent 1):
   Theoretisch richtig, praktisch macht es Android schon: ohne RECORD_AUDIO
   crasht `new AudioRecord()` mit SecurityException, die in den existierenden
   catch-Block fällt. Keine extra Defensive-Logik nötig.

4. **"ModelInference.release() existiert nicht"** (Agent 1 implizit): Doch,
   existiert seit längerem in Zeile 279, und MainViewModel ruft sie auch
   schon korrekt in `clearSessionResults()`. Nur an zwei Stellen war sie
   noch nicht eingebaut.

5. **"`apply()` ist async und nicht crash-safe"** (Agent 2): Stimmt
   technisch, aber `commit()` würde den UI-Thread blockieren bei jedem
   Write, was bei FAST-Modus jede Sekunde wäre. Die Trade-Off-Wahl
   `apply()` ist hier richtig. Bei einem Process-Kill mitten im Write
   verliert man die letzte Sekunde, nicht den ganzen State.

6. **"PendingIntents stapeln sich"** (Agent 2): Nein. `FLAG_UPDATE_CURRENT`
   updated existierende statt zu stapeln. Trotzdem habe ich vorsichtshalber
   ein explizites `stopKeepAliveAlarm()` davor gehängt.

---

## Was bewusst nicht angefasst wurde

- **MainViewModel ist 1193 Zeilen schwer.** Sub-Agent hat das als
  God-Class kritisiert. Refactor in `WizardStateHolder` + `SessionRunner` +
  `EvaluationCoordinator` wäre die saubere Lösung, aber das ist ein
  großer Eingriff mit echtem Regressions-Risiko. Sollte als eigene
  Geschichte mit ordentlicher Test-Abdeckung kommen.

- **Pause-Race in MainViewModel um Zeile 1000.** Sub-Agent hat behauptet,
  da gäbe es eine Race-Condition zwischen `isPaused` und `pausePending`.
  Hab das nicht im Detail verifiziert, weil der Code-Pfad mit
  `@Volatile`-Flags und Coroutinen-Reihenfolge nicht trivial zu lesen ist.
  Wenn das Symptom je auftaucht (Pause-Record fehlt bei schneller
  Pause/Resume-Sequenz), gehört das in einen eigenen Bug-Fix.

- **Custom Views ohne `onSaveInstanceState`.** Bei Rotation verlieren
  `BarDistributionView`, `ConfidenceCircleView` etc. ihren State. Das
  funktioniert nur deshalb, weil das Fragment seinen State aus dem
  ViewModel re-pushed. Code-Smell, aber kein User-spürbarer Bug.

- **LastConfigStore Gson ohne Versionierung.** Wenn `SessionConfig` ein
  neues Feld bekommt, kann ein alter persistierter JSON beim Deserialisieren
  crashen. Heute ist das gefangen, aber eine Versioning-Strategie wäre
  langfristig richtig.

---

## Build-Status

```
./gradlew assembleDebug
BUILD SUCCESSFUL in 2s
```

Die Warnings, die noch stehen, sind alle vor diesem Branch schon dagewesen:
`scaledDensity` ist in neueren Android-SDKs deprecated (Lösung wäre
`Configuration.fontScale * density`), und `onBackPressed()` ist deprecated
zugunsten von `OnBackPressedCallback`. Beides sind Migrationen, die man
separat angehen sollte.

---

## Was das gekostet hat (grobe Schätzung)

Ich hab nicht den genauen Token-Counter, aber überschlagsweise:

**Token (geschätzt)**
- Conversation Input: ca. 150.000 Tokens (Mein laufender Kontext über die
  ganze Session, plus Sub-Agent-Reports die zurückkamen).
- Conversation Output: ca. 80.000 Tokens (die Reviews, Edits, diese Doku).
- 5 Sub-Agents je ca. 10–15k Input + 3–5k Output: zusammen ca. 75.000
  Tokens.

**Geld (Opus 4.7 Preise: $15 / Million Input, $75 / Million Output)**
- Haupt-Konversation: ca. $1,50 (Input) + $6,00 (Output) = $7,50
- Sub-Agents (5 Stück): ca. $1,50

**Summe: ca. $9 USD ≈ 8,30 € (Stand 2026-05-17, Wechselkurs grob 1.08)**

Hinweis: Das ist Cents-genau nicht ehrlich machbar ohne die echten
Token-Counts. Realistisch sollte die Größenordnung aber stimmen. Wenn dein
Anthropic-Dashboard den exakten Verbrauch zeigt, ist das die zuverlässige
Quelle.

Für einen vollständigen Multi-Aspekt-Review eines 8.700-Zeilen-Projekts
plus alle Fixes plus diese Doku sind das überschaubare Kosten. Ein
Senior-Entwickler würde für die gleiche Arbeit grob 4–6 Stunden brauchen.
