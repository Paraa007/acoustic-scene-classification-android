# UI-Redesign: Wizard-Flow & Live-Vergleich

Stand: 2026-05-06 · Spec für die kommende Umbau-Iteration der App.

## Änderungen seit der ersten Spec-Version (2026-05-06, abends)

- **SessionDuration-Picker:** `5min` und `15min` raus. Aktuelle Optionen: 30 min · 1 h · 3 h · 6 h · 12 h · Manuell stoppen. Default = 30 min. Begründung: für eine Vergleichs-Session unter 30 min reichen schon 1–2 Cycles, was statistisch keine Aussage erlaubt.
- **Pause mit Timer-Picker.** Tap auf Pause öffnet einen Modal-Picker mit Optionen `No timer (manual resume)` · 5 min · 10 min · 30 min · 1 h. Auto-Resume nach Ablauf des Timers; manuelles Resume geht jederzeit. Während eine Pause mit Timer läuft, zeigt der Status-Header oben `Paused · m:ss` (oder `h:mm:ss`). Verhalten der Pause-Semantik selbst (clipgenau, der laufende Cycle wird zu Ende geführt) bleibt wie unten beschrieben.
- **AVG-Live-Kreise mit Emoji.** Die zehn 28dp-Kreise unter „Show Live Data" haben jetzt jeweils oben das Emoji der vorhergesagten Klasse (z. B. 🌳 für Park, 💼 für Work). Vor dem ersten Tick ein „·" als Platzhalter. Damit liest sich die Sekunden-Sequenz auch ohne Toggle aufzuklappen — bzw. macht aufgeklappt sofort sichtbar, ob die Klassifikation stabil ist oder zwischen zwei Klassen springt.
- **Modell × Methoden-Kompatibilität als harte Constraint.** Eine Methode (`STANDARD` / `FAST` / `AVERAGE`) ist nur gültig, wenn ihre Slice-Länge zur Trainingsdauer des Modells passt. Konkret:
  - `_10s_`-Modelle: nur **STANDARD** (10s-Slice).
  - `_1s_`-Modelle: nur **FAST** (1s-Slice) und **AVERAGE** (10×1s-Slices).
  - Interval-Wizard graut inkompatible Methoden-Checkboxen pro Modell aus, mit Hinweis „not available (model trained on Xs clips)".
  - Continuous-Wizard graut beim Sub-Mode-Picker (Step 3) Methoden aus, die nicht zu **allen** ausgewählten Modellen passen. Bei Mixed-Duration-Auswahl (10s+1s gleichzeitig) erscheint ein Hinweis-Text, der zur Modell-Auswahl zurück oder auf Interval verweist.
- **`wizardSetModels()` seedet locked-Default.** Wenn Step 1 ein zusätzliches Modell aktiviert, bekommt das Modell automatisch seine locked-Default-Methode (`STANDARD` für 10s, `FAST` für 1s) im `intervalMethodsByModel`-Map. Sonst hängt der „Next"-Button im Interval-Methods-Step, weil `canAdvance()` für jedes Modell mindestens eine geticked-te Methode verlangt.

## Motivation

Vorher: User Mode + Dev Mode parallel, alle Konfigurations-Controls auch während laufender Aufnahme sichtbar, Multi-Model nur in Interval und nur ein Modell live in der Confidence-Anzeige sichtbar.

Nachher: ein einziger App-Pfad, Konfiguration als geleiteter Wizard, Live-UI zeigt nur Ergebnisse — und zwar pro Modell parallel als Bar-Distribution.

Begründung: User Mode wurde nie verwendet. Die App ist in Evaluations-Phase, der Fokus liegt auf Modell-Vergleich, nicht auf End-User-Komfort. Das Mode-Konzept wird komplett gestrichen.

## Top-Level-Screens

```
┌──────────┐    ┌──────────┐    ┌──────────────┐    ┌──────────────────┐
│ Welcome  │───▶│  Wizard  │───▶│ Live         │───▶│ Results Summary  │
│ (Home)   │    │ (5–6     │    │ Recording    │    │                  │
│          │    │  Schritte)│   │              │    │                  │
└──────────┘    └──────────┘    └──────────────┘    └──────────────────┘
     ▲                                                        │
     └────────────────────────────────────────────────────────┘
                  „Zurück zur Home Page"

Zusätzlich von Welcome erreichbar: History · Settings
(keine Bottom-Nav mehr — alles wird über Welcome-Buttons aufgerufen)
```

## Welcome / Home

```
        Acoustic Scene Classification

        [   Neue Session starten   ]   → Wizard Schritt 1
        [   Letzte Config nutzen   ]   → Live Recording (überspringt Wizard)
        [          History         ]
        [          Settings        ]
```

`Letzte Config nutzen` ist nur sichtbar, wenn schon mal eine Session lief. Die letzte Config wird in SharedPreferences gehalten.

## Wizard — Baumdiagramm

Der Wizard verzweigt nach der Continuous/Interval-Wahl in zwei Pfade unterschiedlicher Länge:

- Continuous: 4 Konfigurations-Schritte + Übersicht = 5 Schritte
- Interval: 5 Konfigurations-Schritte + Übersicht = 6 Schritte

```
                    Wizard Schritt 1: MODELLE
                    Header: „Wähle ein oder mehrere Modelle."
                    │
                    ├── 1 Modell gewählt
                    └── N Modelle gewählt (Multi-Model)
                                   │
                                   ▼
                    Wizard Schritt 2: KATEGORIE
                    Header: „Wie soll die App aufnehmen?"
                                   │
                       ┌───────────┴───────────┐
                       │                       │
                  Continuous                Interval
                       │                       │
                       ▼                       ▼
        Schritt 3: CLIP-DAUER         Schritt 3: PAUSEN-INTERVALL
        Header: „Wie lang soll        Header: „Wie oft soll eine
        eine Aufnahme sein?"          Aufnahme gemacht werden?"
                       │                       │
        ┌──────┬───────┼─────┐                 │
        │      │       │     │                 │
       Fast Standard  Avg   ...               10min · 15min · 30min
       (1s)  (10s)   (10×1s)                  45min · 1h · 3h
                       │                       │
                       │                       ▼
                       │           Schritt 4: METHODEN PRO MODELL
                       │           Header: „Welche Auswertungs-
                       │           Methoden pro Modell?"
                       │                       │
                       │           Pro Modell eine Zeile mit
                       │           Standard / Fast / Avg-Checkboxen.
                       │           Default-Methode (locked) richtet sich
                       │           nach der Trainingsdauer:
                       │             ─ 10s-Modell → Standard locked
                       │             ─ 1s-Modell  → Fast locked
                       │                       │
                       ▼                       ▼
        Schritt 4: SESSION-DAUER      Schritt 5: SESSION-DAUER
        Header: „Wie lange insgesamt aufnehmen?"
                       │                       │
        30min · 1h · 3h · 6h · 12h
        Manuell stoppen
                       │                       │
                       ▼                       ▼
        Schritt 5: ÜBERSICHT          Schritt 6: ÜBERSICHT
        Header: „Bereit zum Start."
                       │                       │
        Jede Sektion ist klickbar = springt
        zurück zum entsprechenden Schritt.
                       │                       │
                       ▼                       ▼
                  [ START ]                [ START ]
                       │                       │
                       └───────────┬───────────┘
                                   ▼
                            Live Recording
```

### Navigation im Wizard

- Top-Bar mit Pfeil-Zurück auf jeder Wizard-Seite. Auf Schritt 1 führt der Pfeil zur Welcome-Page.
- Hardware-Back-Button macht dasselbe wie der Pfeil.
- Auf der Übersichts-Seite ist jede angezeigte Konfigurations-Zeile klickbar und springt zum entsprechenden Schritt zurück. Bereits getroffene Entscheidungen bleiben dabei erhalten.
- Während der Konfiguration kann jederzeit schrittweise rückwärts navigiert werden, ohne dass der Wizard-State verloren geht.

## Live Recording

Das Layout während einer laufenden Session:

```
┌─────────────────────────────────────────────────────────┐
│              ╭──────────────────────────╮               │
│              │   ╭──── inner ring ────╮ │               │
│              │   │                    │ │               │
│              │   │    0:43:12         │ │   ← Stopp-Uhr │
│              │   │   / 3:00:00        │ │   (Frage 9a)  │
│              │   │                    │ │               │
│              │   ╰────────────────────╯ │               │
│              ╰──────────────────────────╯               │
│            Recording · 7s / 10s                         │
├─────────────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 🧠 dcase2025_10s_04_06_64bt                         │ │
│ │                                                     │ │
│ │ 1 — Standard                                        │ │
│ │   🌳 Park              ████████████████ 84%         │ │
│ │   🚗 Transit           ███ 8%                       │ │
│ │   🌲 Natur             ██ 4%                        │ │
│ │   ...                                               │ │
│ │                                                     │ │
│ │ 2 — Fast                                            │ │
│ │   🌳 Park              ███████████████ 76%          │ │
│ │   ...                                               │ │
│ │                                                     │ │
│ │ 3 — Avg                                             │ │
│ │   🌳 Park              ████████████████ 81%         │ │
│ │   ...                                               │ │
│ │   [ Show Live Data ▾ ]   ← per-Card-Toggle, default │ │
│ │                            aus, aufgeklappt 10      │ │
│ │                            Per-Second-Confidence-   │ │
│ │                            Kreise                   │ │
│ └─────────────────────────────────────────────────────┘ │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 🧠 dcase2025_10s_04_29_32bt                         │ │
│ │ ... gleiche Struktur ...                            │ │
│ └─────────────────────────────────────────────────────┘ │
│ ┌─────────────────────────────────────────────────────┐ │
│ │ 🧠 dcase2025_1s_04_06_128bt                         │ │
│ │ ... gleiche Struktur (Fast als Default-Methode) ... │ │
│ └─────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────┤
│  Volume-Graph (permanent sichtbar, kein Toggle)         │
├─────────────────────────────────────────────────────────┤
│         [ Pause ]            [ Stop ]                   │
└─────────────────────────────────────────────────────────┘
```

### Stopp-Uhr — konzentrische Ringe

- Äußerer Ring: Session-Progress (z. B. 43 min von 3 h gefüllt).
- Innerer Ring: Cycle-Progress (z. B. 7s von 10s).
- Bei AVG-Mode wird der innere Ring in 10 Segmente unterteilt — eines pro Sekunde, fängt sich live einer nach dem anderen.
- Bei Session-Dauer „Manuell stoppen" wird der äußere Ring grau (kein Ziel) und zählt nur hoch.
- Pause: Hintergrund leicht eingegraut, zentrales Pause-Icon, beide Ringe halten an.

### Bar-Diagramm — Layout pro Modell-Card

- Pro gewähltem Modell eine eigene Card, klar abgegrenzt, vertikal gestapelt.
- Card-Header oben: 🧠 Modell-Dateiname.
- Pro aktiver Methode (Standard / Fast / Avg) eine Sub-Sektion mit Nummer + Methoden-Name als Titel.
- Pro Sub-Sektion 9 horizontale Bars für die 9 Szene-Klassen, sortiert nach Wahrscheinlichkeit absteigend (Gewinner-Klasse zuerst).
- Bar-Farbe entspricht der Klassenfarbe aus `SceneClass.color` — Park immer grün, Verkehr immer rot, etc. Das gibt visuelle Wiedererkennung über alle Modelle hinweg.
- Bar-Länge proportional zur Confidence.
- Beschriftung: Emoji + Klassenname (Standard-Textfarbe) + Prozent (Standard-Textfarbe), nicht in Klassenfarbe.
- Im Continuous mit nur einer aktiven Methode pro Modell: nur eine Sub-Sektion pro Card.
- Im Interval mit z. B. 2 gewählten Methoden für ein Modell: 2 Sub-Sektionen pro Card.

### Was bei AVG live passiert

- Die Bar-Distribution einer AVG-Sub-Sektion zeigt den **laufenden Durchschnitt** über die bisher erfassten 1s-Clips. Aktualisiert sich nach jeder Sekunde.
- Unter der Bar-Distribution steht ein Toggle „Show Live Data" (default aus). Aufgeklappt erscheinen 10 kleine Confidence-Kreise (eine pro Sekunde), die sich live füllen. Beim nächsten Cycle resetten sie.
- Jeder dieser Kreise sitzt in einer vertikalen Mini-Zelle: oben das **Emoji der vorhergesagten Klasse** dieser einen Sekunde (13sp), darunter der 28dp-Kreis mit dem Konfidenz-Prozent. Vor dem ersten Tick steht ein „·" als Platzhalter. So lässt sich auf einen Blick lesen, ob die zehn 1s-Vorhersagen stabil dieselbe Klasse zeigen oder zwischen zwei Klassen springen.

### Pause/Resume

- Verfügbar in beiden Modi — Continuous **und** Interval.
- Pause wird **clipgenau** angewendet: ein laufender Aufnahme-Cycle wird komplett zu Ende geführt (inkl. Inferenz und History-Speicherung), erst dann pausiert die Schleife.
- Bei Resume startet ein frischer Cycle.
- Während Pause: Session-Timer hält an, kein neuer Cycle wird gestartet.
- In der CSV wird jede Pause als eigener „PAUSE-Record" zwischen den echten Aufnahmen geschrieben — siehe CSV-Format weiter unten.
- **Pause-Picker:** Tap auf den Pause-Button öffnet einen Picker mit Optionen `No timer (manual resume)`, `5 min`, `10 min`, `30 min`, `1 h`. Bei Timer-Wahl resumed die Session automatisch nach Ablauf; manuell weiter geht aber jederzeit. Der Status-Header oben zeigt während einer getimerten Pause `Paused · m:ss` und tickt jede Sekunde herunter.

### Was während Live Recording **nicht** mehr sichtbar ist

Aus dem alten UI entfernt:
- Mode-Auswahl-Buttons + Mode-Timeline-Schema (im Wizard konfiguriert)
- Modell-Anzeige + Edit-Stift (im Wizard konfiguriert)
- LongSubMode-Checkboxen + Multi-Model-Container (im Wizard konfiguriert)
- Continuous/Interval-Kategorie-Buttons
- Großer Confidence-Kreis (durch Bars ersetzt)
- Triangle-Layout mit Sub-Mode-Kreisen (durch per-Methode-Bar-Sektionen ersetzt)
- Top-Predictions-Card (jedes Modell hat seine Top-Klassen inline in der Bar-Liste)
- Last-Predictions-Card (in Multi-Model nicht eindeutig zuordbar — welches Modell?)
- Globaler Per-Second-Toggle (jetzt pro Card statt global)

### Was bleibt sichtbar

- Stopp-Uhr (oben)
- Modell-Cards (Mitte)
- Volume-Graph (permanent unten, kein Toggle)
- Pause/Resume + Stop (fix unten)

### Soft-Stop bei Session-Ende

Wenn die Session-Dauer abläuft, während gerade ein Cycle läuft:
1. Der laufende Cycle wird zu Ende geführt (Aufnahme + Inferenz).
2. History-Record wird geschrieben.
3. Im Continuous: keine neue Schleife wird gestartet.
4. Im Interval: nach Ende der laufenden Aufnahme + Auswertung wird gestoppt, keine neue Pause beginnt.
5. App wechselt automatisch auf den Results-Summary-Screen.

## Results Summary (neu)

Nach Session-Ende (Stop oder Auto-Stop) landet der User auf einem neuen Results-Summary-Screen:

- Zeigt für jedes Modell × Methode die **finalen** Bar-Distributions auf einen Blick (aggregiert über alle Cycles der Session, nicht der letzte Cycle).
- Pro Card zusätzlich: Anzahl Cycles, häufigste Klasse über die Session, Durchschnitts-Volume.
- Buttons:
  - `[ Zurück zur Home Page ]` — geht zurück zu Welcome.
  - `[ Zur History ]` — öffnet die History mit dieser Session vorausgewählt.

## Volume in der CSV (neu)

`PredictionRecord` bekommt zwei neue Felder, die in zwei neuen CSV-Spalten landen:

- `volume_mean` — Mittelwert des RMS-Audio-Pegels über die gesamte Aufnahmedauer dieses Cycles. Skala 0.0 – 1.0, 3 Nachkommastellen. Bei Standard 10s = Durchschnitt aller ~300 RMS-Samples (30 Hz × 10 s). Bei Fast 1s = Durchschnitt von ~30 Samples.
- `volume_peak` — lautester einzelner RMS-Wert innerhalb dieser Aufnahmedauer. Skala 0.0 – 1.0, 3 Nachkommastellen. Wenn jemand nach 7 Sekunden in die Hände klatscht, steht der Klatscher hier drin.

Bei AVG-Records wird Volume zusätzlich pro 1s-Clip mitgeführt. Das `perSecondClips`-Format wird erweitert:

```
1:Park:79%:mean=0.421:peak=0.583|2:Park:85%:mean=0.398:peak=0.502|...
```

Bei Multi-Model: das Audio ist identisch für alle Modelle in einem Cycle, also wird Volume nur einmal pro Cycle gespeichert (nicht pro Modell). Die Volume-Spalten gehören zum Cycle, nicht zur Modell-Inferenz.

## Pause-Records in der CSV

Pausen werden als eigene synthetische Records geschrieben — nicht als Spalte am vorigen Record. Aufbau:

- `mode_label = "PAUSE"` (oder ein dediziertes Feld, je nach Implementierung)
- alle Klassen-Spalten leer
- `pause_duration_sec` mit der Pausen-Länge in Sekunden
- `confidence = 0`

In der CSV-Auswertung lassen sich Pausen so per `.filter(mode_label == "PAUSE")` direkt herausziehen, ohne aus mehreren Spalten zusammenpuzzeln zu müssen.

In der History-UI werden Pause-Records als kleine graue Trennlinie zwischen den Recording-Kacheln angezeigt („Pause: 14 min"), nicht als eigene Kachel.

## Evaluation-Feature (Interval-only, unverändert in der Logik)

Bleibt funktional wie heute, nur das Timing folgt jetzt dem im Wizard gewählten Pausen-Intervall — nicht mehr hardcodiert 30 min.

- Nach jeder Interval-Aufnahme bekommt der User eine Notification (Background) bzw. eine in-App-Card (Foreground), wo er die tatsächliche Szene angeben kann.
- Bei Multi-Model: die User-Bewertung gilt für den ganzen Cycle, nicht pro Modell.
- Wenn z. B. das Pausen-Intervall = 1 h gewählt wurde, kommt die Evaluation-Aufforderung jede Stunde. Bei 10 min entsprechend alle 10 Minuten.
- Im Continuous-Modus gibt es **keine** Evaluation-Card (würde alle 10 s erscheinen → unbedienbar).

## History-Anpassungen

- Die alten Mode-Badges („Dev" / „User") werden ersatzlos gestrichen — das Mode-Konzept existiert nicht mehr.
- Stattdessen pro Session-Kachel ein kompaktes Config-Label, z. B.:
  - `🧠 model_xyz · Continuous · Standard 10s`
  - `🧠 3 Modelle · Interval 30min · 3 Methoden`
- Pause-Records erscheinen als graue Trennlinie zwischen Aufnahmen, nicht als eigene Kachel.
- Alte Records (vor dem Umbau) bleiben in den SharedPreferences. Ihr `mode: "DEV" / "USER"`-Feld wird einfach nicht mehr gerendert. Kein Daten-Migrations-Aufwand.

## Was wird gestrichen

- `WelcomeActivity` als Mode-Picker (wird durch die neue Welcome-Home-Page ersetzt).
- Bottom-Navigation komplett — Navigation läuft nur noch über Welcome-Buttons + Back-Pfeile in den Sub-Screens.
- `RecordingMode.devOnly` (wird sinnlos, AVG ist jetzt immer verfügbar).
- Pref-Keys `long_sub_modes_user` (nur `long_sub_modes_dev`-Logik bleibt, könnte umbenannt werden).
- Alle `isDevMode`-Verzweigungen in `MainViewModel`, `RecordingFragment`, `SettingsFragment`.

## Persistenz

- Letzte Config wird in SharedPreferences gespeichert und auf der Welcome-Page als „Letzte Config nutzen"-Button angeboten.
- Während des Wizards: jeder Schritt wird in einem ViewModel-State gehalten (`WizardStep` als Sealed Class). Schrittweises Zurücknavigieren behält alle bisherigen Antworten.
- Nach Session-Ende: Config wird als „letzte Config" persistiert, unabhängig davon ob die Session vom User gestoppt oder vom Timer beendet wurde.

## Kein Reconfigure mid-Session

- Während eine Session läuft, gibt es **keinen** Reconfigure-Button.
- Der einzige Weg zur Konfigurations-Änderung: Stop drücken → landet auf Results Summary → zurück zur Home Page → entweder „Letzte Config nutzen" mit kleinen Änderungen oder „Neue Session starten" (Wizard ab Schritt 1).
- Begründung: ein mid-session Reconfigure würde die Frage aufwerfen „bricht das die Session ab oder forkt sie?" — die Antwort ist immer „erst Stop, dann neu". Klare Hartline.
