# Acoustic Scene Classification

Eine Android-App, die über das Mikrofon des Smartphones Umgebungsgeräusche aufnimmt und mit einem KI-Modell erkennt, in welcher Art von Umgebung du dich gerade befindest – zum Beispiel „Park", „Büro" oder „Einkaufszentrum".

---

## Was macht die App?

Die App ist auf Modell-Vergleich ausgelegt: pro Aufnahme können mehrere KI-Modelle parallel laufen, und jedes Ergebnis landet in der History und im CSV-Export. Der Bedien-Flow ist als geleiteter Wizard angelegt: Welcome → Wizard (5 oder 6 Schritte) → Live-Recording → Results-Summary.

Insgesamt kennt die App **9 verschiedene Umgebungen**:

| Emoji | Umgebung |
|-------|----------|
| 🚗 | Transit – Fahrzeuge draußen (Autos, Busse, Züge) |
| 🏙️ | Außen – Stadt & Bahnhöfe/Haltestellen |
| 🌲 | Außen – Natur (Park, Wald) |
| 👥 | Innen – Soziale Umgebung (Café, Restaurant) |
| 💼 | Innen – Arbeitsumgebung (Büro) |
| 🛒 | Innen – Kommerziell (Einkaufszentrum, Geschäft) |
| ⚽ | Innen – Freizeit/Sport (Sporthalle, Fitnessstudio) |
| 🎭 | Innen – Kultur/Ruhig (Museum, Bibliothek) |
| 🏠 | Innen – Wohnbereich (Zuhause) |

---

## Features im Überblick

### 1. Welcome-Page
Auf dem Startbildschirm gibt es vier Buttons:
- **Start new session** – öffnet den Wizard, um eine neue Aufnahme einzurichten.
- **Use last config** – startet mit der zuletzt verwendeten Config, ohne den Wizard. Erscheint erst, wenn schon einmal eine Session lief.
- **History** – öffnet die Liste aller bisherigen Aufnahmen.
- **Settings** – öffnet die App-Einstellungen.

Oben rechts ein Schalter (Sonne/Mond) für Hell- und Dunkelmodus. Die Wahl wird gespeichert.

### 2. Wizard (Setup)
Schritt für Schritt durch die Konfiguration:
1. **Modelle** – ein oder mehrere KI-Modelle auswählen.
2. **Aufnahme-Kategorie** – Continuous (durchgehend) oder Interval (mit Pausen).
3. **Bei Continuous:** Clip-Dauer (Fast 1 s, Standard 10 s, oder Avg = 10 × 1 s gemittelt).
   **Bei Interval:** Pausen-Intervall (10 min bis 3 h).
4. **Bei Interval:** Methoden pro Modell (Standard / Fast / Avg, je nach Trainings-Dauer des Modells).
5. **Session-Dauer** – 30 min, 1 h, 3 h, 6 h, 12 h oder Stop manually.
6. **Übersicht** – jede Sektion klickbar, springt zum entsprechenden Schritt zurück.

Inkompatible Kombinationen werden automatisch ausgegraut. 10s-Modelle dürfen nur Standard, 1s-Modelle nur Fast und Avg.

### 3. Live-Recording
Während der Session zeigt die App:
- **Konzentrische Stopp-Uhr** – äußerer Ring = Session-Progress, innerer Ring = Cycle-Progress. Bei Avg in 10 Segmente unterteilt.
- **Eine Card pro Modell** mit 9 horizontalen Bars (eine pro Klasse) für jede aktive Methode. Bar-Farbe entspricht der Klassenfarbe (Park grün, Verkehr rot, etc.).
- **Volume-Graph** unten – Lautstärke über die Zeit, permanent sichtbar.
- **Pause** – Tap öffnet einen Picker mit `No timer` · 5 min · 10 min · 30 min · 1 h. Bei Timer-Wahl resumed die Session automatisch.
- **Stop** – beendet die Session, wechselt auf Results.

### 4. Results-Summary
Nach Stop oder Auto-Stop landet man auf einem Screen, der pro Modell × Methode die finalen Bar-Distributions zeigt (aggregiert über alle Cycles). Pro Card: Anzahl Cycles, häufigste Klasse, Durchschnitts-Volume. Zwei Buttons: **Back to Home** und **Open History**.

### 5. History
Alle Sessions als Karten mit kompaktem Config-Label (`🧠 model · Continuous · Standard 10s` o. ä.). Tap auf eine Session öffnet einen Detail-Dialog mit Distribution, Method Comparison (bei mehreren Methoden), Per-Second Clips (bei Avg), User Evaluations (bei Interval) und einer Pausen-Sektion mit grauen Trennlinien für jede Pause.

Long-Press aktiviert die Mehrfachauswahl, um Sessions im Bulk zu exportieren oder zu löschen.

### 6. CSV-Export
Sessions als CSV speichern und per Android-Teilen-Menü versenden. Pro Aufnahme-Cycle eine Zeile mit Klasse, Konfidenz, Top-3, Inferenzzeit, Modell, Modus, Batterie und Volume (Mean + Peak). Pause-Records erscheinen als eigene Zeilen mit `mode_label = "PAUSE"` und `pause_duration_sec` — in Pandas einfach mit `.filter(mode_label == "PAUSE")` herauszuziehen.

### 7. Evaluation (Interval-only)
Nach jeder Interval-Aufnahme bekommt der User eine Notification (Background) bzw. eine in-App-Card (Foreground), wo er die tatsächliche Szene angeben kann. Das Timing folgt dem im Wizard gewählten Pausen-Intervall. Im Continuous gibt es keine Evaluation-Card.

### 8. Aufnahme im Hintergrund
Auch wenn die App minimiert oder der Bildschirm aus ist, läuft die Aufnahme weiter. Ein Foreground-Service mit WakeLock hält das Gerät wach. Für lange Sessions bitte die Batterie-Optimierung deaktivieren.

### 9. Automatisches Speichern
Alle Ergebnisse werden automatisch gespeichert. Beim nächsten App-Start sind sie sofort wieder da. Speicherlimit: 10.000 Records — danach werden die ältesten automatisch gelöscht.

---

## Berechtigungen

- **Mikrofon** – Pflicht, sonst kann die App nichts hören.
- **Notifications** (Android 13+) – für die Foreground-Service-Notification und Evaluation-Prompts.
- **Batterie-Optimierung deaktivieren** – optional, aber empfohlen für lange Sessions.

---

## Für wen ist die App?

- **Forscher & Entwickler** – um eigene KI-Modelle zu testen oder Daten für Auswertungen zu sammeln.
- **Modell-Vergleich** – um mehrere Modelle parallel auf demselben Audio laufen zu lassen.
- **Langzeit-Beobachtung** – z. B. für Geräusch- oder Aktivitätsmuster über mehrere Stunden.

---

## Technik im Hintergrund (kurz)

- **Plattform:** Android (ab Version 8.0 / SDK 26, Target SDK 36)
- **Sprache:** Kotlin 2.0.21
- **KI-Framework:** PyTorch Mobile 1.13.1
- **Audio:** TarsosDSP für FFT, Android AudioRecord
- **Architektur:** Single Activity + Navigation Component, MVVM mit StateFlow
- **Design:** Material Design 3
- **Projekt:** FZI Forschungszentrum Informatik, Karlsruhe – DCASE 2025

Mehr Details für Entwickler: `CLAUDE.md`, `docs/PROJEKT_DOKUMENTATION.md`, `docs/UI_REDESIGN_WIZARD.md`, `docs/MODEL_INTEGRATION_SPEC.md`.

---

*Die App wird laufend weiterentwickelt.*
