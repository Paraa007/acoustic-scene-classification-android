# Beschreibung App Features – Acoustic Scene Classification

## Überblick

Die App **Acoustic Scene Classification** ist eine Android-App, die akustische Szenen in Echtzeit über ein KI-Modell erkennt. Sie nimmt Umgebungsgeräusche über das Mikrofon auf, rechnet sie zu Log-Mel-Spektrogrammen und schickt sie durch ein PyTorch-Mobile-Modell, das die Umgebung einer von neun Szenenklassen zuordnet (DCASE 2025). Die App ist auf Modell-Vergleich ausgelegt: pro Aufnahme können mehrere Modelle parallel laufen, und jedes Ergebnis landet in der History und im CSV-Export.

Der Bedien-Flow ist als geleiteter Wizard angelegt: Welcome → Wizard (5 oder 6 Schritte) → Live-Recording → Results-Summary. Es gibt keinen User/Dev-Modus mehr — alles läuft über denselben Pfad.

---

## 1. Welcome-Page (Home)

Beim Öffnen der App erscheint die Welcome-Page. Oben steht der App-Titel mit Untertitel, in der oberen rechten Ecke der Theme-Switch (Sonne/Mond) für Hell-/Dunkelmodus.

Vier Buttons stapeln sich darunter:

- **Start new session** — öffnet den Wizard ab Schritt 1.
- **Use last config** — überspringt den Wizard und startet sofort mit der zuletzt verwendeten Config. Nur sichtbar, wenn schon einmal eine Session lief.
- **History** — öffnet die Aufnahme-Historie.
- **Settings** — öffnet die App-Einstellungen.

Es gibt keine Bottom-Navigation. Alles wird von hier aus aufgerufen.

---

## 2. Hell- und Dunkelmodus

Über den Theme-Switch oben rechts auf der Welcome-Page wechselt man zwischen hellem und dunklem Farbschema. Die Wahl wird persistiert und gilt für alle Bildschirme. Im Dunkelmodus ist der Hintergrund nahezu schwarz mit heller Schrift, im Hellmodus hellgrau mit dunkler Schrift. Standard ist Dunkelmodus.

---

## 3. Wizard (Setup)

Der Wizard verzweigt nach der Aufnahme-Kategorie in zwei Pfade. Continuous hat 5 Schritte, Interval 6. Auf jeder Seite oben links ein Pfeil-Zurück (Hardware-Back tut dasselbe), unten ein „Next"-Button, der nur aktiv wird, wenn der Schritt vollständig ausgefüllt ist.

### 3.1 Schritt 1 — Modelle
Eine Liste der verfügbaren `.pt`-Modelle aus `dev_models/`. Ein oder mehrere Modelle wählen. Multi-Model bedeutet: in jedem Aufnahme-Cycle laufen alle Modelle parallel auf demselben Audio.

### 3.2 Schritt 2 — Aufnahme-Kategorie
Zwei Karten:
- **Continuous** — durchgehend Aufnahmen aneinander, ohne Pausen.
- **Interval** — Aufnahme, dann Pause, dann nächste Aufnahme.

### 3.3 Schritt 3 (Continuous) — Clip-Dauer
Drei Optionen, die zugleich die Auswertungs-Methode festlegen:
- **Fast (1s)** — 1-Sekunden-Aufnahme, schnellster Cycle.
- **Standard (10s)** — 10-Sekunden-Aufnahme.
- **Avg (10×1s)** — 10 mal 1-Sekunde aufnehmen, alle Resultate mitteln.

Inkompatible Optionen werden ausgegraut. Beispiel: bei einem 1s-Modell ist Standard nicht wählbar (das Modell wurde auf 1s-Clips trainiert). Bei Mixed-Duration-Auswahl im Modelle-Schritt (10s + 1s gleichzeitig) erscheint ein Hinweis-Text, der zur Modell-Auswahl zurück oder auf Interval verweist.

### 3.3 Schritt 3 (Interval) — Pausen-Intervall
Sechs Optionen für den Abstand zwischen Aufnahmen: 10 min · 15 min · 30 min · 45 min · 1 h · 3 h.

### 3.4 Schritt 4 (Interval) — Methoden pro Modell
Pro gewähltem Modell eine Zeile mit drei Checkboxen (Standard / Fast / Avg). Die zur Trainings-Dauer des Modells passende Methode ist locked-on (10s-Modell → Standard, 1s-Modell → Fast). Inkompatible Methoden sind ausgegraut mit Hinweis „not available (model trained on Xs clips)".

### 3.5 Schritt 4 (Continuous) / Schritt 5 (Interval) — Session-Dauer
Sechs Optionen, wie lang die Session insgesamt laufen soll: 30 min · 1 h · 3 h · 6 h · 12 h · Stop manually.

### 3.6 Letzter Schritt — Übersicht
Listet alle bisher getroffenen Entscheidungen kompakt auf. Jede Sektion ist klickbar und springt zum entsprechenden Schritt zurück, ohne den restlichen Wizard-State zu verlieren. Unten ein **Start**-Button, der auf den Live-Recording-Screen wechselt.

---

## 4. Live-Recording

Während eine Session läuft, zeigt der Bildschirm vier Bereiche von oben nach unten:

### 4.1 Stopp-Uhr (oben)
Zwei konzentrische Ringe:
- **Äußerer Ring** — Session-Progress (z. B. 43 min von 3 h gefüllt). Bei „Stop manually" wird der Ring grau und zählt nur hoch.
- **Innerer Ring** — Cycle-Progress (z. B. 7s von 10s). Bei AVG-Methode in 10 Segmente unterteilt, eines pro Sekunde.

Mittig die laufende Zeit; bei Pause wird der Hintergrund eingegraut, ein Pause-Icon erscheint, beide Ringe halten an.

### 4.2 Modell-Cards
Pro gewähltem Modell eine eigene Card, vertikal gestapelt:
- Card-Header: 🧠 Modell-Dateiname.
- Pro aktiver Methode (Standard / Fast / Avg) eine Sub-Sektion mit Nummer + Methoden-Name.
- Pro Sub-Sektion 9 horizontale Bars für die 9 Klassen, sortiert nach Wahrscheinlichkeit absteigend. Bar-Farbe entspricht der Klassenfarbe (Park immer grün, Verkehr immer rot, etc.), Bar-Länge ist proportional zur Confidence.
- Bei AVG-Methode zeigt die Bar-Distribution den laufenden Durchschnitt über die bisher erfassten 1s-Clips. Aufgeklappt über „Show Live Data" erscheinen 10 kleine Mini-Zellen mit Emoji der vorhergesagten Klasse oben und Konfidenz-Kreis darunter — eine pro Sekunde, fängt sich live einer nach dem anderen.

### 4.3 Volume-Graph (permanent)
Liniendiagramm der RMS-Lautstärke über die Zeit. Permanent sichtbar, kein Toggle.

### 4.4 Buttons (unten)
- **Pause** — öffnet einen Picker mit Optionen `No timer` · 5 min · 10 min · 30 min · 1 h. Bei Timer-Wahl resumed die Session automatisch nach Ablauf; manuell weiter geht jederzeit. Während einer getimerten Pause zeigt der Status-Header oben `Paused · m:ss` (oder `h:mm:ss`).
- **Stop** — beendet die Session.

### 4.5 Pause-Semantik
Pausen werden clipgenau angewendet: ein laufender Cycle wird komplett zu Ende geführt (Aufnahme + Inferenz + History-Record), erst dann pausiert die Schleife. Bei Resume startet ein frischer Cycle. Während Pause hält der Session-Timer an. Jede Pause wird als eigener Pause-Record in History und CSV geschrieben.

### 4.6 Soft-Stop bei Session-Ende
Wenn die Session-Dauer abläuft, während gerade ein Cycle läuft: der Cycle wird zu Ende geführt, History-Record geschrieben, im Continuous keine neue Schleife, im Interval keine neue Pause — die App wechselt automatisch auf Results.

### 4.7 Kein Reconfigure mid-Session
Während eine Session läuft, gibt es nur Stop. Konfigurations-Änderungen gehen über Stop → Results → Welcome → Wizard.

---

## 5. Results-Summary

Nach Stop oder Auto-Stop landet der User auf dem Results-Screen:
- Pro Modell × Methode eine Card mit der finalen Bar-Distribution (aggregiert über alle Cycles, nicht der letzte).
- Pro Card: Anzahl Cycles, häufigste Klasse über die Session, Durchschnitts-Volume.
- Buttons:
  - **Back to Home** — zurück zur Welcome-Page.
  - **Open History** — öffnet die History mit der gerade beendeten Session vorausgewählt.

---

## 6. Die 9 Szenenklassen

Das Modell erkennt folgende akustische Szenen (alle Labels sind auf Deutsch):

| Nr. | Emoji | Klasse | Beschreibung |
|-----|-------|--------|-------------|
| 1 | 🚗 | Transit - Fahrzeuge/draußen | Verkehr, Autos, Busse, Züge im Freien |
| 2 | 🏙️ | Außen - urban & Transit-Gebäude | Städtische Umgebung, Bahnhöfe, Haltestellen |
| 3 | 🌲 | Außen - naturbetont | Parks, Wälder, Natur |
| 4 | 👥 | Innen - Soziale Umgebung | Restaurants, Cafés, Gespräche |
| 5 | 💼 | Innen - Arbeitsumgebung | Büros, Arbeitsbereiche |
| 6 | 🛒 | Innen - Kommerzielle/belebte Umgebung | Einkaufszentren, belebte Geschäfte |
| 7 | ⚽ | Innen - Freizeit/Sport | Sporthallen, Fitnessstudios |
| 8 | 🎭 | Innen - Kultur/Freizeit ruhig | Museen, Bibliotheken, ruhige Orte |
| 9 | 🏠 | Innen - Wohnbereich | Wohnzimmer, häusliche Umgebung |

Jede Klasse hat eine eigene Farbe, die in den Live-Bars, der Results-Summary und allen Verteilungs-Diagrammen konsistent verwendet wird.

---

## 7. History

Über **History** auf der Welcome-Page erreichbar. Listet alle gespeicherten Sessions auf.

### 7.1 Session-Kachel
Jede Session als Karte mit:
- **Session-Name** — automatisch nummeriert (Session 1, Session 2, …, Session 1 ist die älteste). Umbenennbar.
- **Config-Label** — kompakt, z. B. `🧠 model_xyz · Continuous · Standard 10s` oder `🧠 3 Modelle · Interval 30min · 3 Methoden`.
- **Aufnahmen + Dauer** — z. B. „42 recordings · 3:12 h".
- **Batterie-Verbrauch** über die Session.

### 7.2 Detail-Dialog
Tap auf eine Session öffnet einen Dialog mit:
- Session-Name + Umbenennen-Button.
- Start- und Endzeit, Modell, Modus, Anzahl Aufnahmen, Durchschnitts-Confidence.
- **Distribution** — farbiges Balkendiagramm pro Szenenklasse über alle Aufnahmen der Session.
- **Method comparison** (bei Interval-Sessions mit mehreren Methoden) — pro Modell × Methode ein eigener Distribution-Stack, sodass man den Modell-Vergleich auf identischem Audio sieht.
- **Per-Second Clips** (bei AVG) — Verteilung der 1s-Vorhersagen über alle Cycles.
- **User Evaluations** (bei Interval) — wie oft welche Klasse vom User bestätigt wurde.
- **Pauses** (wenn die Session Pausen enthält) — Header `Pauses (N · Gesamtdauer)`, jede Pause als kompakte graue Trennlinie mit „Pause: 14 min".
- Buttons: Delete, Export (CSV), Close.

### 7.3 Multi-Selection
Long-Press auf eine Session aktiviert die Mehrfachauswahl:
- Checkboxen neben jeder Session.
- Toolbar oben: Schließen, Counter, Select All, Export, Delete.
- Schließt sich automatisch, wenn keine Auswahl mehr aktiv ist.

### 7.4 CSV-Export
Sessions exportierbar als CSV, einzeln oder im Bulk. Pro Aufnahme-Cycle eine Zeile mit Klasse, Konfidenz, Top-3, Inferenzzeit, Modell, Modus, Batterie, Volume (Mean + Peak), Per-Second-Clips bei AVG. Pause-Records erscheinen als eigene Zeilen mit `mode_label = "PAUSE"` und `pause_duration_sec`. Multi-Model-Cycles bekommen pro Modell eine zusätzliche Spalte. Die CSV lässt sich über das Android-Teilen-Menü versenden.

---

## 8. Datenpersistenz

Alle Vorhersagen werden automatisch in SharedPreferences gespeichert, sobald ein Cycle abschließt. Beim nächsten App-Start sind alle Sessions sofort wieder da. Custom-Session-Namen, Theme-Wahl und die letzte SessionConfig (für „Use last config") werden separat persistiert. Speicherlimit: 10.000 Records — bei Überschreitung werden die ältesten gelöscht.

---

## 9. Hintergrund-Betrieb

Aufnahmen laufen über einen Foreground Service mit WakeLock weiter, auch wenn das Display aus ist oder die App im Hintergrund läuft. Im Interval-Modus wird das Gerät zwischen den Aufnahmen geweckt. Die App fragt einmalig nach, ob die Batterieoptimierung deaktiviert werden darf, damit die Hintergrundaufnahmen keine Lücken bekommen.

---

## 10. Berechtigungen

- **Mikrofon** — wird beim ersten Start einer Aufnahme abgefragt, mit kurzer Begründung.
- **Batterie-Optimierung** — optional. Die App fragt einmal, ob sie ausgenommen werden darf.
- **Notifications** (Android 13+) — für die Foreground-Service-Notification und die Evaluation-Prompts im Interval-Modus.

---

## 11. Evaluation (Interval-only)

Im Interval-Modus bekommt der User nach jeder Aufnahme eine Notification (Background) bzw. eine in-App-Card (Foreground), wo er die tatsächliche Szene angeben kann. Das Timing folgt dem im Wizard gewählten Pausen-Intervall — bei 1 h kommt die Aufforderung jede Stunde, bei 10 min alle 10 Minuten. Im Continuous-Modus gibt es keine Evaluation-Card (würde alle paar Sekunden erscheinen, unbedienbar).

---

## 12. Design

- **Material Design 3** mit abgerundeten Cards, Buttons und Dialogen (28 dp Corner-Radius).
- **Edge-to-Edge** — Status- und Navigationsleiste werden mitgenutzt.
- **Konsistente Klassenfarben** — Park immer grün, Verkehr immer rot, etc., über Live-UI, Results und History hinweg.
- **Emojis** — jede Klasse mit eigenem Emoji.
- **Animationen** — Konzentrische Stopp-Uhr, animierte Bars, Per-Second-Kreise füllen sich live.

---

## Zusammenfassung der Hauptfeatures

1. **Akustische Szenen-Klassifikation** in Echtzeit, 9 Klassen.
2. **Wizard-Setup** mit 5 oder 6 Schritten — kein vergessener Konfigurations-Punkt mehr.
3. **Multi-Model parallel** — N Modelle gleichzeitig auf demselben Audio, eine Card pro Modell.
4. **Continuous- und Interval-Pfad** — kurze Cycles oder lange Pausen-Intervalle bis 3 h.
5. **Drei Auswertungs-Methoden pro Modell** (Standard / Fast / Avg) im Interval-Modus.
6. **Bar-Distribution live** — alle 9 Klassen pro Cycle, sortiert nach Wahrscheinlichkeit.
7. **Konzentrische Stopp-Uhr** — Session- und Cycle-Progress auf einen Blick, AVG in 10 Segmenten.
8. **Permanenter Volume-Graph** — RMS-Verlauf über die Session.
9. **Results-Summary** — finale Distribution pro Modell × Methode nach Session-Ende.
10. **Pause/Resume mit Timer-Picker** — clipgenaue Pausen, Auto-Resume optional.
11. **Hell-/Dunkelmodus**, Edge-to-Edge, Material Design 3.
12. **Session-Verwaltung** — automatische Benennung, Umbenennen, Multi-Selection, Detail-Dialog mit Method Comparison + Pausen-Übersicht.
13. **CSV-Export** — pro Cycle eine Zeile inkl. Volume-Mean/Peak, Pause-Records als eigene Zeilen, dynamische Multi-Model-Spalten.
14. **Hintergrund-Aufnahme** über Foreground Service + WakeLock.
15. **Last config wiederverwenden** — eine Tap-Abkürzung am Welcome-Screen.
