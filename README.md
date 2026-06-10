# Acoustic Scene Classification 🎧

An Android app that listens through the microphone and guesses what kind of place you are in: at home, at work, in nature, in traffic, and so on.

<p align="center">
  <img src="docs/img/live-session.png" width="260" alt="Live recording screen with timer and charts">
</p>

## How it works

The app records a few seconds of sound. A model running on the phone then picks the scene that fits best, out of nine. The app does not even ask for internet access, so recordings never leave your device.

## Using the app

1. Pick a mode. Test mode is open for everyone. Configuration mode is locked and meant for the people running the study.
2. Tap "Start new session" on the home screen.
3. Set the gap between recordings and how often the app should ask you for a rating.
4. Pick how long the session runs. For a study over several days, choose an end date in the calendar.
5. Let it run. The live screen shows a timer and a few charts while the phone records. You can pause or stop anytime.
6. Sometimes the app asks which environment you were actually in. Answer honestly. The model's guess stays hidden until you do.

| 1. Pick a mode | 2. Start a session | 3. Set the pace |
|:---:|:---:|:---:|
| <img src="docs/img/mode-select.png" width="240" alt="Mode select screen"> | <img src="docs/img/home.png" width="240" alt="Home screen with start button"> | <img src="docs/img/wizard-interval.png" width="240" alt="Interval and rating sliders"> |

| 4. Choose an end date | 5. Let it run | 6. Rate what you heard |
|:---:|:---:|:---:|
| <img src="docs/img/end-date.png" width="240" alt="Calendar picker for the session end date"> | <img src="docs/img/live-session.png" width="240" alt="Live recording screen"> | <img src="docs/img/rating.png" width="240" alt="Rating screen with scene list"> |

## Looking back

Every session lands in the history, where you can see what the model heard most plus charts for volume, battery temperature, and CPU.

<p align="center">
  <img src="docs/img/history-charts.png" width="260" alt="Session detail with charts">
</p>

## For developers

Open the project in Android Studio or build with `./gradlew assembleDebug`.
Model files (`.pt`) go into `app/src/main/assets/`.
The detailed technical docs are maintained outside this repo.

Built at FZI Forschungszentrum Informatik, Karlsruhe.
