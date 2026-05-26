# v2 Implementation Spec

This doc tells the next agent everything that's behind the v2 mockups but isn't visible in them. Open `dark.html` / `light.html` for the visual reference, this file for the rules.

## Quick map

| Slide | Name | Path entry |
|-------|------|------------|
| 01 | Mode Select | App start → always shown |
| 02 | Password | from Mode Select → Configuration tile |
| 03 | Config Welcome | from Password → correct password |
| 04 | Test Welcome | from Mode Select → Test tile |
| 05–08 | Wizard (Models, Mode, Duration, Summary) | from Config Welcome (Start, Quick start, or Configure test mode) |
| 09 | Live Recording | from Wizard Summary, also from Test Welcome → tap a slot |
| 10 | Session Results | after a recording ends |
| 11 | History | from any Welcome screen |
| 12 | Session Detail | from History → tap a session |
| 13 | Settings | from Config Welcome only |
| 14 | Evaluation | shown after sessions long enough to warrant feedback |

## Two-mode entry

The first thing the app shows is the Mode Select screen (slide 01). Two stacked tiles, no header chrome, no app logo more than the small mark:

- **Test mode** — open, no password. Goes to Test Welcome (slide 04).
- **Configuration mode** — gated. Goes to Password (slide 02). Tile shows a lock icon + `Locked` chip on the right so it's obvious why a tap doesn't just open it.

The mode picker is the start destination of the nav graph. It runs every app start. We do not remember the last mode — picking is part of the entry flow.

### Password gate (slide 02)

- Single text input, characters masked as dots by default.
- Eye icon on the right toggles plain-text display (tap once shows, tap again masks).
- **Password is hard-coded: `Welcome2fzi`.** Stored in code, not in a build-config secret — this is a developer access gate, not real auth.
- Wrong password → inline error under the field, field clears, eye toggles back to masked.
- Right password → navigate to Config Welcome (slide 03), pop Password + Mode Select off the back stack so back-button exits the app.
- Back chevron at the top returns to Mode Select.

## Config Welcome (slide 03)

This is the old Welcome screen with one addition. Five stacked buttons:

1. Start new session (primary)
2. Quick start
3. History
4. Settings
5. **Configure test mode** (new — dashed accent border so it reads as developer-only)

The small `Config mode` chip at the top is the only thing telling the user where they are. Footer is empty now (no `Developer · Configures test mode` line — we removed that).

Buttons 1–4 behave exactly like in v1. Button 5 opens the wizard in **save-as-quickstart mode** (see below).

## Test Welcome (slide 04)

Top chip `Test mode`. Header reads `Quick starts` and `Tap one to start a recording. 3 of 5 slots used`. Then the slot list:

- Up to 5 slots, numbered 01–05.
- Filled slots: tap-able, primary style for the suggested one (most recent, or the first one — pick one rule and stick with it), surface style for the rest.
- Empty slots: dashed border, label `Empty slot`, **not tap-able** in test mode. They exist only to communicate the cap.
- Each filled slot shows two lines: `Quick start <n>` and a short description like `30 min recording` or `1 hour, fast mode`. No model file names, no `M-CNN-...` strings — they confused the user.

A History button sits at the bottom of the list.

**No app logo, no app title on this screen.** The chip + header carry it.

### Tap behavior

- Tap a filled slot → immediately start Live Recording (slide 09) with that slot's saved `SessionConfig`. **No wizard, no confirmation.** The whole point of the Test mode is that the tester does not make any decision.
- Tap History → go to History (slide 11). History in Test mode shows only sessions recorded in Test mode (filtered).

## Quickstart slots

- Maximum **5 slots**, persisted in `PredictionRepository` (or a new `QuickstartStore` — see Open Questions).
- Each slot stores a full `SessionConfig`: model, recording mode, duration, volume threshold, anything else the wizard collects.
- Slot names are **auto-generated** — `Quick start 1`, `Quick start 2`, ... The user does not name them.
- When the user saves a new config in Configure-test-mode flow:
  - If there is an empty slot, take the lowest-numbered free slot.
  - If all 5 are full, show a picker dialog: "Pick a slot to overwrite" with the existing 5 + their short descriptions. Cancel cancels the save.
- Deleting a slot: long-press on the slot in Test Welcome? Or a "manage slots" screen inside Configure test mode? **Open Question** — pick whichever is simpler to ship first.
- The slot description shown in Test Welcome should be derived from the config, not stored separately. Examples:
  - Standard 10s + 30 min total → `30 min recording`
  - Fast 1s + 1 h total → `1 hour, fast mode`
  - Average 1s + 4 h total → `4 hours, averaging`

## Wizard reuse (slides 05–08)

The wizard runs from three entry points:

1. **Start new session** (Config Welcome) — exit via Live Recording, no slot saved.
2. **Quick start** (Config Welcome) — uses the last-used config, may skip the wizard entirely (existing v1 behavior).
3. **Configure test mode** (Config Welcome, new) — exit by **saving as quickstart slot**, not by starting a recording.

The third entry point is the only new piece. Suggested implementation: pass an `intent: WizardIntent` argument through the nav graph, `WizardIntent` is a sealed class with `StartRecording`, `QuickStart`, `SaveAsSlot`. The Summary screen's CTA changes based on intent:

- `StartRecording` → `Start session` (current)
- `SaveAsSlot` → `Save as Quick start`, returns to Test Welcome (or stays in Config Welcome — your call) and shows a snackbar `Saved to slot N`.

The wizard itself is identical in all three flows — same four steps (Models, Mode, Duration, Summary).

## Settings — model test accuracy from file

Each model in the wizard's Select Models step shows its test accuracy (e.g. `Test acc 92.4%`). v2 makes this load from a file, with an explicit missing-value state:

- **Source of truth:** a JSON file in `assets/` (suggested name: `model_metadata.json`).
- Schema per model:
  ```json
  {
    "model_filename": "dcase2025_10s_04_06_64bt.pt",
    "training_seconds": 10,
    "test_accuracy": 0.924
  }
  ```
- Loaded once into a `ModelMetadataRegistry` or attached to the existing `ModelInfoRegistry`.

**When a model has no entry, or the entry has no `test_accuracy`:**
- Replace the percent value with the red label `TEST ACC MISSING` (uppercase, accent color `#d6584f` dark / `#b1463f` light).
- Add a one-liner underneath: `No accuracy value provided for this model.`
- Replace the progress bar with a dashed empty track in the same red.
- The model **stays selectable** — missing accuracy doesn't disable it, it just flags it.

The same red treatment also belongs in Settings → Models (slide 13) if/when the user goes there to inspect what's installed.

## Method is derived from the model, not picked by the user

The Standard / Fast / Average method is **not** a wizard step in v2 and **not** a slot setting. It is a property of the model:

- A model trained on **10 s** clips → runs with **Standard** only. One card per cycle, one classification per cycle.
- A model trained on **1 s** clips → runs with **both Fast and Average in parallel**, in the same session. Two cards per cycle for that one model: Fast (one 1 s classification per cycle) + Average (ten 1 s classifications per cycle, then averaged).

So picking models in the wizard is the only method-related choice. Examples:

| User picks in Select Models | Cards shown live | Result rows saved per cycle |
|-----------------------------|------------------|-----------------------------|
| 1× 10 s model | 1 Standard | 1 |
| 1× 1 s model | 1 Fast + 1 Average | 2 |
| 1× 10 s + 1× 1 s | 1 Standard + 1 Fast + 1 Average | 3 |
| 2× 1 s | 2 Fast + 2 Average | 4 |

The mockup at slide 09 shows the third row: one 10 s model (Standard) and one 1 s model (running both Fast and Average — same model file in both cards C and B, only the Method label differs).

Implications:

- **Drop the LongSubMode user choice everywhere.** No wizard step for it, no toggle in the slot config, no compatibility-check dialog. The compatibility rule (10 s → Standard; 1 s → Fast + Average) becomes a static branch in the inference pipeline keyed off `modelTrainingSeconds`.
- The slot description in Test Welcome stays based on user-visible config only (mode, duration). It does not list methods — they are implied by the chosen model(s).
- `SessionConfig` no longer stores `longSubMode`. The session execution computes the method set from the selected models.
- CSV / `PredictionRecord` still carries the per-record method (Standard | Fast | Average) so historical data stays meaningful.

## Live Recording — Average model card (slide 09)

Each running model gets its own card. v1 already covered Standard (10 s model) and Fast (1 s model running once per cycle). v2 adds the **Average** card, which is what's now visible at slide 09 model card C.

Average always uses a 1 s model and runs it **10 times per 10 s cycle** — one classification per second. The cycle result is the per-class mean of those 10 slices.

The card has to show **both** the per-slice trace and the averaged result, because the trace is how the user understands where the average came from. Layout:

1. Header row: model file, `Method · Average`, badge with the averaged top class + averaged confidence.
2. Slice strip: a row of **10 circles**, one per second of the current cycle. Each circle is filled with the color of the class that slice classified as, and shows the class emoji. Left-to-right is oldest → newest. The newest circle gets a subtle accent border so the user can spot the live edge. Axis labels under the row: `−10 s`, `−5 s`, `now`.
3. Averaged distribution: same `dist-row` layout as Standard/Fast.

Display rules:

- Slice colors must come from the existing scene-class palette (the same constants used by `dist-fill`). Reuse them, don't redefine.
- Slice tooltips (`title` attr) should read like `t-7s · Nature` so the implementation can match the mockup hover behavior if it wants to.
- Before the first 10 slices are in, partially fill the row from the left and leave the remaining slots as empty dashed circles (same `--hairline` style as the empty Quickstart slots). Do not pre-fill with a default class.
- The averaged distribution updates every full cycle, not every slice — the slice row updates every second.

## State and persistence

- Picked mode (Test vs Config) lives in the ViewModel for the session only. App restart → Mode Select again. Do not auto-skip based on last choice.
- Quickstart slots: persistent across launches, scoped to device (SharedPreferences key like `quickstart_slots_v2`, JSON-encoded list of SessionConfig).
- History: same store as today, but filtered by mode tag on the session when shown from Test Welcome. Each new `Session` gets a `mode: "test" | "config"` field at write time.

## Removed (do not re-add)

- `FZI Karlsruhe · Field tool` footer on Welcome.
- `Test mode stays open without a password` footer on Password screen.
- `Developer side · sets up test mode` footer on Config Welcome.
- `Eye reveals the password` hint under the password field.
- Slot subtitles with model identifiers (`M-CNN-Office-10s` etc.) — too cryptic.
- Version row in Settings.

## What the mockups don't show

These are real states the implementation needs but no static screen could draw:

- Wrong-password error: inline red text under the input, field clears, focus stays in the field.
- Saving a slot when full: blocking dialog with the existing 5 slots, user picks one to overwrite, or cancels.
- Snackbar after slot save: `Saved to slot 3` with an `Undo` action (optional).
- Mode-select tile press states: subtle background lift, accent border briefly.
- The wizard's `Save as Quick start` button is only enabled when the config is valid (same rule as the current `Start session`).
- Test Welcome with 0 saved slots: show a single-line empty state like `No quick starts yet — ask the developer to set one up.` instead of the 5 empty rows.
- Test Welcome with > 5 slots: literally cannot happen; enforce the cap at save time, not at display time.

## Open questions for the implementation agent

1. **Slot deletion UX** — long-press on the slot row in Test Welcome (visible only in Config mode), or a separate Manage Slots screen reachable from Configure test mode? Pick whichever is shorter to build.
2. **Same model file twice?** Two slots can use the same model with different recording modes/durations — should that be allowed? (Yes by default.)
3. **What does `Quick start` (button 2 on Config Welcome) do now that slots exist?** Two options:
   a. Keep v1 behavior — use the last-used config, ignore slots entirely.
   b. Make it pick `Quick start 1` from the slot list.
   Pick (a) for the first cut, revisit if it confuses users.
4. **History filtering** — should the filter (Test vs Config) be a tab in History, or hidden behind a filter chip? Defaulting to "match the mode you opened it from" + a way to see all.

## Files likely to change

- `model/SessionConfig.kt` — add a `mode` tag.
- `model/QuickstartSlot.kt` — new.
- `data/QuickstartRepository.kt` — new, or add to `PredictionRepository`.
- `ui/ModeSelectFragment.kt` — new.
- `ui/PasswordFragment.kt` — new.
- `ui/TestWelcomeFragment.kt` — new.
- `ui/WelcomeFragment.kt` — add Configure test mode button.
- `ui/WizardFragment.kt` — accept `WizardIntent`, change Summary CTA.
- `ui/MainViewModel.kt` — add mode + intent state.
- `nav_graph.xml` — add Mode Select (start), Password, Test Welcome.
- `assets/model_metadata.json` — new.
- `model/ModelInfoRegistry.kt` (or new `ModelMetadataRegistry`) — load + expose test accuracy.

## Reference: developer password

`Welcome2fzi`
