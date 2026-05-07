# Was hier gerade passiert ist (für Anfänger)

Stand: 2026-05-07

## Worum geht's überhaupt?

Wenn du mit Claude Code in einem Projekt arbeitest, liest Claude bei **jeder neuen Session automatisch** zwei Dateien, ohne dass du sie explizit erwähnen musst:

1. **`<projekt>/CLAUDE.md`** — projektspezifische Anweisungen (gilt nur in diesem Repo).
2. **`~/.claude/CLAUDE.md`** — persönliche Anweisungen (gilt für *alle* deine Projekte).

Diese Dateien sind Claudes „Spickzettel". Sie landen automatisch im Kontextfenster und beeinflussen, wie Claude antwortet.

**Das Problem:** Je länger diese Dateien werden, desto weniger Platz bleibt für deine eigentliche Frage. Außerdem werden sie unübersichtlich. Deine `CLAUDE.md` war auf 273 Zeilen angewachsen, davon ~180 Zeilen reine Historie (Changelog).

## Was wir aufgeräumt haben

Die alte `CLAUDE.md` wurde in vier Eimer sortiert:

| Eimer | Was war drin | Wohin verschoben |
|-------|-------------|------------------|
| **Projekt-Regeln** (bleibt) | Architektur, Build-Befehle, Code-Regeln, Pointer auf Doku | `CLAUDE.md` (gestrafft auf 69 Zeilen) |
| **Persönliche Vorlieben** | „Skills/MCP nur projektweit", „kein AI-Schreibstil" | `~/.claude/CLAUDE.md` (neu, 12 Zeilen) |
| **Historie** | Der komplette Changelog (180 Zeilen) | `docs/CHANGELOG.md` (neu) |
| **Veraltet** | „Version 2"-Hinweis, User-Mode-Beschreibung (wird eh gestrichen), Theme-Toggle-Details | gelöscht |

### Was die drei Dateien jetzt tun

**`CLAUDE.md`** (Projekt) — 69 Zeilen. Enthält:
- Hinweis auf den Wizard-Umbau (Spec hat Vorrang vor Code)
- Package-Name, SDK-Versionen
- Build-Befehle
- Architektur-Tabelle (welcher Ordner enthält was)
- Audio-Pipeline kurz erklärt
- 6 Hartline-Regeln (Singleton, deutsche Labels, Background-Dispatchers, …)
- Pointer auf alle Doku-Dateien

**`~/.claude/CLAUDE.md`** (User-weit) — 12 Zeilen. Enthält:
- „Skills und MCP Server immer auf Projektebene installieren"
- „Keine AI-typischen Patterns in Texten"

**`docs/CHANGELOG.md`** (Historie) — 180 Zeilen. Enthält den kompletten alten Changelog. Wird **nicht** automatisch geladen, sondern nur wenn Claude oder du es explizit liest.

## Was bedeutet das für deine nächste Session?

### Kurze Antwort: nichts.

Du musst **nichts Besonderes** sagen, wenn du eine neue Claude-Session startest. Die `CLAUDE.md` und `~/.claude/CLAUDE.md` werden automatisch geladen. Sag einfach was du willst:

> „Bau mir den Wizard-Step 3 fertig."
> „Warum crasht die App beim Start?"
> „Refactor MainViewModel."

Claude weiß dann automatisch:
- welche Sprache (Kotlin), welches Framework (Android/MVVM)
- wo welcher Code lebt (siehe Architektur-Tabelle)
- die 6 Hartlines (Singleton nicht umgehen, deutsche Labels nicht übersetzen, …)
- dass ein Wizard-Umbau läuft und die Spec Vorrang hat

### Wann du *doch* was sagen solltest

- **Wenn der Kontext aus dem Changelog wichtig ist:** „Lies den Changelog-Eintrag vom 2026-05-06 zu Multi-Model Evaluation, dann …"
- **Wenn die Spec-Details relevant sind:** „Schau in `docs/UI_REDESIGN_WIZARD.md`, dann implementier Step 4."
- **Wenn du einen Skill triggern willst** (Skills sind vorgefertigte Playbooks):
  - „teste meine App" → startet den `android-test`-Skill (Maestro-UI-Test)
  - „grill mich zu …" → startet den `grill-me`-Skill (interaktive Plan-Diskussion)
  - „humanize diesen Text" → startet den `humanizer`-Skill

### Anti-Beispiele (musst du *nicht* sagen)

- ❌ „Lies erstmal CLAUDE.md" — passiert eh automatisch
- ❌ „Wir benutzen Kotlin und Android" — steht in `CLAUDE.md`
- ❌ „Vergiss nicht, dass `PredictionRepository` ein Singleton ist" — steht in den Hartlines
- ❌ „Antworte auf Deutsch" — geht aus deinen Nachrichten hervor

## Wie du die Setup-Dateien selbst pflegst

Wenn dir auffällt, dass Claude eine bestimmte Anweisung *immer wieder* von dir hören muss, gehört sie in eine der Setup-Dateien:

- **Gilt nur in diesem Projekt?** → `CLAUDE.md` (Projekt)
- **Gilt für alle deine Projekte?** → `~/.claude/CLAUDE.md`
- **Reine Historie / „warum haben wir X so gemacht"?** → `docs/CHANGELOG.md`

Du kannst Claude einfach sagen: *„Merk dir das in CLAUDE.md"* — dann fügt Claude den Eintrag selbst ein.

## Schnellreferenz: drei Dateien, drei Rollen

```
~/.claude/CLAUDE.md             ← du (in jedem Projekt)
<projekt>/CLAUDE.md             ← dieses Projekt (Regeln, Architektur)
<projekt>/docs/CHANGELOG.md     ← Historie (nur bei Bedarf gelesen)
```
