# Acoustic Scene Classification

Eine Android-App, die über das Mikrofon deines Smartphones Umgebungsgeräusche aufnimmt und mithilfe von künstlicher Intelligenz erkennt, in welcher Art von Umgebung du dich gerade befindest – zum Beispiel „Park", „Büro" oder „Einkaufszentrum".

---

## Was kann die App?

Die App hört sich die Geräusche um dich herum an und sagt dir, wo du wahrscheinlich bist. Das funktioniert in Echtzeit: Du drückst einen Knopf, die App nimmt ein paar Sekunden Audio auf, und kurz danach erscheint das Ergebnis auf dem Bildschirm.

Insgesamt kennt die App **9 verschiedene Arten von Umgebungen**:

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

### 1. Willkommensseite
Beim Öffnen der App siehst du einen freundlichen Startbildschirm. Dort kannst du auswählen:
- **User Mode** – Der normale Modus für die meisten Leute.
- **Development Mode** – Für Entwickler, die verschiedene KI-Modelle ausprobieren wollen.
- **View History** – Um alte Aufnahmen anzusehen.

### 2. Hell- und Dunkelmodus
Ganz oben rechts gibt es einen kleinen Schalter (Sonne/Mond). Damit kannst du zwischen einem **hellen** und einem **dunklen Design** wechseln. Die App merkt sich deine Auswahl.

### 3. Vier Aufnahme-Modi
Je nachdem, wie schnell oder genau du ein Ergebnis haben willst, kannst du wählen:
- **Fast (1 Sekunde)** – Ganz schnelles Ergebnis.
- **Medium (5 Sekunden)** – Mittelschnell und schon recht genau.
- **Standard (10 Sekunden)** – Volle Qualität, empfohlen für den Alltag.
- **Long (30 Minuten)** – Nimmt alle 30 Minuten kurz auf. Perfekt, wenn man über längere Zeit beobachten möchte (z. B. über Nacht).

### 4. Konfidenz-Anzeige (Wie sicher ist die App?)
In der Mitte des Bildschirms siehst du einen großen Kreis mit einer Prozentzahl, z. B. „87 %". Das zeigt, **wie sicher sich die App bei ihrer Antwort ist**. Darunter steht die erkannte Umgebung mit Emoji, z. B. „🌲 Außen – naturbetont".

Während der Aufnahme gibt es eine kleine **Wellenanimation** rund um den Kreis, die sich an die Lautstärke anpasst – je lauter, desto größer die Wellen.

### 5. Top-3-Vorhersagen
Die App zeigt dir nicht nur die beste Antwort, sondern die **drei wahrscheinlichsten Umgebungen**. So siehst du auf einen Blick, ob sich die App zwischen zwei Möglichkeiten schwer tut.

### 6. Echtzeit-Lautstärke-Diagramm
Wenn du vor einer Aufnahme den Schalter **„Show Live Data"** einschaltest, wird während der Aufnahme ein **Liniendiagramm** gezeichnet, das die Lautstärke über die Zeit zeigt – so siehst du live, wann es laut und wann leise war.

### 7. Sitzungs-Statistiken
Nach der ersten Aufnahme erscheint eine kleine Karte, die zeigt:
- Wie viele Aufnahmen du in dieser Sitzung schon gemacht hast.
- Wie lange die App im Durchschnitt für eine Vorhersage braucht.

### 8. Letzte Vorhersagen
Eine Liste mit den **letzten 5 Ergebnissen** deiner aktuellen Sitzung, damit du den Verlauf im Blick hast.

### 9. Aufnahme-Historie
Alle Aufnahme-Sitzungen werden **automatisch gespeichert**. Du kannst sie später jederzeit wieder anschauen:
- Jede Sitzung hat einen Namen (standardmäßig z. B. „Session 1", „Session 2" …).
- Du kannst jede Sitzung in einen **eigenen Namen umbenennen**, z. B. „Morgens im Büro" oder „Spaziergang im Park".
- Beim Antippen einer Sitzung öffnet sich ein Detail-Fenster mit Startzeit, Endzeit, verwendetem Modell, Durchschnittskonfidenz und einem **bunten Balkendiagramm**, das zeigt, welche Umgebungen wie oft erkannt wurden.

### 10. Mehrere Sitzungen gleichzeitig verwalten
Durch **langes Drücken** auf eine Sitzung kannst du mehrere auswählen und auf einmal löschen oder exportieren.

### 11. CSV-Export
Du kannst Sitzungen als **CSV-Datei exportieren** (einzeln oder mehrere zusammen) und z. B. per E-Mail, Cloud oder einer anderen App teilen. So kannst du deine Daten in Excel oder anderen Programmen weiterverwenden.

### 12. Aufnahme im Hintergrund
Auch wenn du die App minimierst oder den Bildschirm ausschaltest, **läuft die Aufnahme weiter**. Das ist besonders nützlich für den Long-Modus (30-Minuten-Intervall), z. B. für Langzeit-Beobachtungen über Nacht.

### 13. Automatisches Speichern
Du musst nichts manuell speichern – **alle Ergebnisse werden automatisch gesichert**. Beim nächsten Start der App sind sie direkt wieder da.

---

## Berechtigungen

Damit die App funktioniert, braucht sie ein paar Erlaubnisse von dir:
- **Mikrofon** – Ohne Mikrofon kann die App natürlich nichts hören.
- **Batterie-Optimierung deaktivieren** (optional) – Damit die App auch bei langen Aufnahmen nicht vom System gestoppt wird.

---

## Für wen ist die App?

- **Normale Nutzer** – Neugierige, die wissen wollen, welche Umgebungen die KI erkennt.
- **Forscher & Entwickler** – Die eigene KI-Modelle testen oder Daten exportieren wollen.
- **Langzeit-Beobachtung** – Z. B. für Lärm- oder Aktivitätsmuster über mehrere Stunden.

---

## Technik im Hintergrund (kurz)

- **Plattform:** Android (ab Version 8.0 / SDK 26)
- **Sprache:** Kotlin
- **KI-Framework:** PyTorch Mobile
- **Design:** Material Design 3
- **Projekt:** FZI Forschungszentrum Informatik, Karlsruhe – DCASE 2025

---

*Die App wird aktiv weiterentwickelt. Feedback und Ideen sind willkommen!*
