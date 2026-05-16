# Acoustic Scene Classification

Android-App, die über das Mikrofon erkennt, in welcher Umgebung du dich gerade befindest: Park, Büro, Einkaufszentrum und sechs weitere. Mehrere KI-Modelle können parallel auf demselben Audio laufen; jedes Ergebnis landet in der History und im CSV-Export.

---

## Bedien-Flow

Eine Aufnahme läuft durch vier Schritte:

1. Welcome ist die Home-Page mit vier Buttons: neue Session, Quick Start (letzte Config), History, Settings.
2. Im Wizard wählst du in 5 bis 6 Schritten Modell, Continuous oder Interval, Aufnahme-Methode, optional ein Pausen-Intervall und die Session-Dauer. Am Schluss kommt eine Übersicht.
3. Live-Recording zeigt eine Stoppuhr, Bar-Distributions pro Modell und einen Volume-Graph. Pausieren und Stoppen geht jederzeit.
4. Results fasst die Ergebnisse pro Modell × Methode zusammen. Von dort geht's zurück zur Home-Page oder in die History.

---

## Berechtigungen

- Mikrofon (Pflicht)
- Notifications (Android 13+, für Foreground-Service und Evaluation-Prompts)
- Batterie-Optimierung deaktivieren (empfohlen für lange Sessions)

---

Projekt: FZI Forschungszentrum Informatik, Karlsruhe, DCASE 2025
Stack: Android (Kotlin), PyTorch Mobile, TarsosDSP

Mehr für Entwickler: `CLAUDE.md`, `docs/PROJEKT_DOKUMENTATION.md`, `docs/UI_REDESIGN_WIZARD.md`, `docs/MODEL_INTEGRATION_SPEC.md`.
