# Acoustic Scene Classification

Android-App, die über das Mikrofon erkennt, in welcher Art von Umgebung du dich gerade befindest — Park, Büro, Einkaufszentrum und sechs weitere. Mehrere KI-Modelle können parallel auf demselben Audio laufen; jedes Ergebnis landet in der History und im CSV-Export.

---

## Bedien-Flow

Ein geleiteter Wizard führt durch jede Aufnahme:

1. **Welcome** — Einstieg mit vier Buttons: neue Session, Quick Start (letzte Config), History, Settings.
2. **Wizard** — 5 bis 6 Schritte: Modell-Wahl, Continuous oder Interval, Aufnahme-Methode, optional Pausen-Intervall, Session-Dauer, Übersicht.
3. **Live-Recording** — Stoppuhr, Bar-Distribution pro Modell, Volume-Graph. Pause und Stop jederzeit.
4. **Results** — Aggregierte Ergebnisse pro Modell × Methode. Zurück zur Home-Page oder direkt in die History.

---

## Berechtigungen

- **Mikrofon** — Pflicht
- **Notifications** (Android 13+) — Foreground-Service und Evaluation-Prompts
- **Batterie-Optimierung deaktivieren** — empfohlen für lange Sessions

---

Projekt: FZI Forschungszentrum Informatik · Karlsruhe · DCASE 2025
Stack: Android (Kotlin) · PyTorch Mobile · TarsosDSP

Mehr für Entwickler: `CLAUDE.md`, `docs/PROJEKT_DOKUMENTATION.md`, `docs/UI_REDESIGN_WIZARD.md`, `docs/MODEL_INTEGRATION_SPEC.md`.
