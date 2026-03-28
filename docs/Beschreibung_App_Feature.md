# Beschreibung App Features – Acoustic Scene Classification

## Überblick

Die App **Acoustic Scene Classification** ist eine Android-Anwendung, die mithilfe eines KI-Modells akustische Szenen in Echtzeit erkennt. Sie nimmt über das Mikrofon des Smartphones Umgebungsgeräusche auf, analysiert diese und ordnet sie einer von neun Szenenklassen zu. Die Ergebnisse werden visuell dargestellt und dauerhaft gespeichert, sodass man sie später einsehen und exportieren kann.

---

## 1. Willkommensseite (Welcome Screen)

Beim Öffnen der App erscheint die Willkommensseite. Oben steht der App-Titel „Acoustic Scene Classification" und darunter ein kurzer Untertitel. In der oberen rechten Ecke befindet sich ein Schiebeswitch (Schieberegler), mit dem man zwischen hellem und dunklem Modus wechseln kann – links daneben ist ein Sonnen-Symbol, rechts ein Mond-Symbol.

Darunter gibt es zwei auswählbare Karten:

- **User Mode** – Startet die Aufnahme mit dem Standardmodell (model1.pt, 9 Klassen). Für den normalen Gebrauch gedacht.
- **Development Mode** – Öffnet einen Auswahl-Dialog, in dem man aus verschiedenen Modellen wählen kann, die im Ordner `dev_models/` liegen. Für Entwickler und Forscher gedacht.

Am unteren Rand gibt es einen Button **„View History"**, der zur Aufnahmehistorie führt.

---

## 2. Hell- und Dunkelmodus (Light/Dark Mode)

Über den Schiebeswitch auf der Willkommensseite kann man zwischen einem hellen und einem dunklen Farbschema wechseln. Die Einstellung wird dauerhaft gespeichert und gilt für alle Bildschirme der App. Im Dunkelmodus ist der Hintergrund nahezu schwarz mit heller Schrift, im Hellmodus ist der Hintergrund hellgrau mit dunkler Schrift. Alle Farben (Buttons, Karten, Texte, Dialoge) passen sich automatisch an den jeweiligen Modus an. Standardmäßig startet die App im Dunkelmodus.

---

## 3. Aufnahmeseite (Recording Screen)

Nach dem Auswählen eines Modus gelangt man zur Hauptseite der App. Hier befinden sich folgende Elemente von oben nach unten:

### 3.1 Konfidenz-Anzeige (Confidence Circle)
Ein großer kreisförmiger Fortschrittsanzeiger in der Mitte zeigt den Prozentwert der Vorhersage-Sicherheit (Konfidenz) an – zum Beispiel „87%". Darunter steht der Name der erkannten Szene mit einem Emoji, z. B. „🌲 Außen - naturbetont". Während einer Aufnahme pulsiert ein Ripple-Effekt (Wellenanimation) um den Kreis herum, dessen Intensität sich an die aktuelle Lautstärke anpasst.

### 3.2 Top 3 Vorhersagen (Top Predictions)
Eine Karte zeigt die drei wahrscheinlichsten Szenenklassen für die aktuelle Aufnahme. Jede Vorhersage zeigt:
- Die Rangposition (1., 2., 3.)
- Das Emoji und den deutschen Namen der Klasse
- Den Konfidenzwert in Prozent
- Einen farbigen Fortschrittsbalken

### 3.3 Lautstärke-Analyse (Volume Analysis)
Eine Karte mit einem Switch „Show Live Data". Wenn man diesen aktiviert **bevor** man die Aufnahme startet, erscheint ein Liniendiagramm, das in Echtzeit die Lautstärke des aufgenommenen Audios über die Zeit darstellt. Die X-Achse passt sich automatisch an den gewählten Aufnahmemodus an. Der Switch lässt sich während einer laufenden Aufnahme nicht an- oder ausschalten, um die Datenintegrität zu gewährleisten.

### 3.4 Aufnahmemodus (Recording Mode)
Vier Buttons zur Auswahl des Aufnahmemodus:
- **Fast (1s)** – 1-Sekunden-Schnellaufnahme, optimiert für Geschwindigkeit
- **Medium (5s)** – 5-Sekunden-Aufnahme, ein Kompromiss zwischen Geschwindigkeit und Genauigkeit
- **Standard (10s)** – 10-Sekunden-Aufnahme mit voller Qualität
- **Long (30min)** – 10-Sekunden-Aufnahmen mit automatischer 30-Minuten-Wiederholung für Langzeit-Monitoring

### 3.5 Start/Stop Button
Ein großer grüner Button zum Starten und Stoppen der Aufnahme. Beim Aufnehmen zeigt ein Fortschrittsbalken den aktuellen Fortschritt und ein Timer die verbleibenden Sekunden an.

### 3.6 Status-Anzeige
Zeigt den aktuellen Status der App:
- **Ready** – Bereit zum Aufnehmen (grau)
- **Recording** – Aufnahme läuft (rot)
- **Processing** – Verarbeitung läuft (orange)
- **Paused** – Pause im Long-Modus mit verbleibender Zeit

### 3.7 Session-Statistiken
Eine Karte, die nach der ersten Vorhersage erscheint und folgendes anzeigt:
- Gesamtanzahl der Klassifikationen in dieser Sitzung
- Durchschnittliche Inferenzzeit (wie lange das Modell pro Vorhersage braucht)

### 3.8 Letzte Vorhersagen (Last Predictions)
Eine Karte, die die letzten 5 Vorhersagen der aktuellen Sitzung anzeigt, jeweils mit dem Emoji, dem Szenenamen und dem Konfidenzwert.

---

## 4. Die 9 Szenenklassen

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

Jede Klasse hat eine eigene Farbe, die in den Vorhersagen, Statistiken und Verteilungsdiagrammen konsistent verwendet wird.

---

## 5. Aufnahme-Historie (History Screen)

Über den „View History"-Button auf der Willkommensseite oder den Zurück-Button auf der Aufnahmeseite gelangt man zur Aufnahme-Historie. Hier werden alle jemals gespeicherten Aufnahme-Sessions aufgelistet.

### 5.1 Session-Darstellung
Jede Session wird als Karte angezeigt mit:
- **Session-Name** – Standardmäßig automatisch nummeriert (Session 1, Session 2, ..., wobei Session 1 die älteste ist). Man kann jeden Session-Namen individuell umbenennen.
- **Anzahl der Aufnahmen und Dauer** – z. B. „12 Aufnahmen · 5 min 30 s"

### 5.2 Session umbenennen
Durch Tippen auf eine Session öffnet sich ein Detail-Dialog. Dort gibt es einen Stift-Button, über den man der Session einen eigenen Namen geben kann (z. B. „Morgens im Büro" oder „Spaziergang im Park").

### 5.3 Detail-Dialog
Beim Tippen auf eine Session erscheint ein umfangreicher Dialog mit:
- Session-Name und Umbenennen-Button
- Startzeit und Endzeit der Aufnahme
- Verwendetes Modell (z. B. model1.pt, 9 Classes)
- Aufnahmemodus (User/Dev + Standard/Fast/Medium/Long)
- Anzahl der Einzelaufnahmen
- Durchschnittliche Konfidenz
- **Szenen-Verteilung** – Ein farbiges Balkendiagramm, das zeigt, wie oft jede Szenenklasse erkannt wurde
- Buttons zum **Löschen**, **Exportieren** (als CSV) und **Schließen**

### 5.4 Multi-Selection-Modus
Durch langes Drücken auf eine Session aktiviert man den Mehrfachauswahl-Modus:
- Neben jeder Session erscheint eine Checkbox
- Eine Auswahl-Toolbar erscheint oben mit:
  - Schließen-Button (X)
  - Anzeige der Anzahl ausgewählter Sessions (z. B. „3 selected")
  - „Select All"-Button
  - Export-Button (mehrere Sessions als CSV exportieren)
  - Löschen-Button (ausgewählte Sessions mit Bestätigungsdialog löschen)
- Man kann einzelne Sessions durch Tippen aus- und abwählen
- Der Modus schließt sich automatisch, wenn keine Session mehr ausgewählt ist

### 5.5 CSV-Export
Sessions können als CSV-Datei exportiert werden, entweder einzeln oder in der Mehrfachauswahl. Die Datei enthält alle Vorhersagedaten und kann über das Android-Teilen-Menü per E-Mail, Cloud-Speicher oder andere Apps gesendet werden.

---

## 6. Datenpersistenz

Alle Vorhersagen werden automatisch in den SharedPreferences des Geräts gespeichert, sobald eine Aufnahme beendet wird. Beim nächsten Start der App sind alle bisherigen Sessions sofort wieder verfügbar. Benutzerdefinierte Session-Namen werden separat gespeichert und bleiben erhalten. Es gibt ein Speicherlimit von 10.000 Vorhersagen – werden mehr erreicht, werden die ältesten automatisch gelöscht.

---

## 7. Hintergrund-Betrieb

Die App verwendet einen Foreground Service, damit Aufnahmen auch im Hintergrund weiterlaufen. Im Long-Modus (30 Minuten) wird das Gerät regelmäßig geweckt, um die nächste Aufnahme zu starten. Die App kann den Benutzer bitten, die Batterieoptimierung zu deaktivieren, damit die Hintergrundaufnahmen nicht unterbrochen werden.

---

## 8. Berechtigungen

- **Mikrofon-Zugriff** – Wird beim ersten Starten einer Aufnahme angefordert. Die App erklärt in einem Dialog, warum die Berechtigung nötig ist.
- **Batterie-Optimierung** – Die App bittet optional, die Batterieoptimierung für sich zu deaktivieren, um lückenlose Hintergrundaufnahmen sicherzustellen.

---

## 9. Design und Benutzeroberfläche

- **Material Design 3** – Moderne, abgerundete Karten, Buttons und Dialoge
- **Edge-to-Edge-Layout** – Die App nutzt den gesamten Bildschirm und passt sich an Status- und Navigationsleisten an
- **Farbcodierung** – Jede Szenenklasse hat eine eigene Farbe, die sich durch die gesamte App zieht
- **Emoji-Integration** – Szenenklassen werden mit passenden Emojis dargestellt
- **Animationen** – Pulsierender Ripple-Effekt bei Aufnahmen, animierte Fortschrittsbalken
- **Responsive** – Passt sich an verschiedene Bildschirmgrößen an

---

## Zusammenfassung der Hauptfeatures

1. **Akustische Szenen-Klassifikation** in Echtzeit mit 9 Klassen
2. **4 Aufnahmemodi** (Fast, Medium, Standard, Long)
3. **Konfidenz-Visualisierung** mit kreisförmiger Anzeige und Top-3-Vorhersagen
4. **Echtzeit-Lautstärke-Analyse** mit Liniendiagramm
5. **Hell-/Dunkelmodus** mit Schiebeswitch
6. **Session-Verwaltung** mit automatischer Benennung und Umbenennung
7. **Mehrfachauswahl** zum Löschen und Exportieren mehrerer Sessions
8. **CSV-Export** für einzelne oder mehrere Sessions
9. **Detail-Dialoge** mit Szenenverteilung und Statistiken
10. **Hintergrund-Aufnahme** mit Foreground Service
11. **Automatische Datenpersistenz** in SharedPreferences
12. **Material Design 3** mit modernem, ansprechendem UI
