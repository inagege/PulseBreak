# PulseBreak

PulseBreak is a Wear OS application that detects possible stress from wearable sensors and suggests short break sessions to the user. The app uses an on-device, lightweight stress scoring engine (optionally enhanced with multi-sensor inputs) and supports feedback-driven personalization.

This README explains what the project contains and how to set it up from git, build and run the Android app, and work with the ML components located in the `ml/` folder.

Table of contents
- Project overview
- Quick setup (clone from git)
- Android: prerequisites, build and run
- ML: Python environment and packaging assets
- Key files and architecture notes
- Development tips and troubleshooting

Project overview
----------------
- Purpose: Detect short-term stress episodes using heart rate and optional sensor signals, prompt the user for feedback, and optionally personalize detection thresholds based on that feedback.
- Inputs (supported):
  - Heart rate (required)
  - Motion magnitude from accelerometer (optional)
  - Ambient temperature variation (optional)
- Feedback: After a detection, the app can prompt: "Do you feel stressed?" — responses are used to adjust thresholds for personalization when enabled.

Quick setup (clone from git)
---------------------------
1. Clone the repository (HTTPS):

   git clone https://github.com/<your-org>/PulseBreak.git

   Or with SSH:

   git clone git@github.com:<your-org>/PulseBreak.git

2. Enter the project directory:

   cd PulseBreak

3. (Optional) Checkout a branch or tag you intend to work on:

   git checkout -b my-feature-branch

Android prerequisites
----------------------
- JDK 11 or later (project uses Gradle Kotlin DSL; confirm the required Java version in Gradle wrapper/gradle.properties)
- Android SDK and appropriate SDK platforms (match compileSdk/targetSdk configured in the app module)
- Android Studio (recommended) or command-line Gradle via the included wrapper (`./gradlew`)
- A Wear OS device/emulator for testing (install the Wear OS system image in AVD Manager)

Open the project in Android Studio
-------------------------------
1. Start Android Studio -> Open an existing project -> select the repository folder.
2. Let Android Studio sync the Gradle project. If prompted, install missing SDK components.
3. Build and run to install on a connected device or emulator.

Build and run from the command line
----------------------------------
Use the Gradle wrapper included with the repo so everyone builds with the same Gradle version.

Build APKs:

   ./gradlew assembleDebug

Install the debug build onto a connected device (or emulator):

   ./gradlew installDebug

Run unit tests:

   ./gradlew test

Run Android instrumented tests (on-device):

   ./gradlew connectedAndroidTest

Lint and static checks

   ./gradlew lint

ML and Python components
------------------------
The `ml/` directory contains scripts and model artifacts used during development and for packaging on-device ML assets.

1. Create and activate a Python virtual environment (macOS zsh example):

   python3 -m venv .venv
   source .venv/bin/activate

2. Install dependencies:

   pip install -r ml/requirements.txt

3. Useful scripts:
  - `ml/scripts/train_stress_classifier.py` (training pipeline)
  - `ml/scripts/export_tflite.py` (export/convert models to TFLite)
  - `ml/scripts/package_app_ml_assets.py` (prepare ML assets for the Android app)

Packaging ML assets for the app
------------------------------
After exporting a TFLite model and any metadata, run the packaging helper to place artifacts in the appropriate app module location so the Android build can include them.

   python ml/scripts/package_app_ml_assets.py --input <model_dir> --output app/src/main/assets/ml

(Adjust paths and arguments per the script's help output.)

Key files and where to look
--------------------------
- App code (Wear OS app): `app/src/main/java/com/example/breakreminder/`
  - `HeartRateReader.kt` — sensor reading and preprocessing
  - `stress/StressInferenceEngine.kt` — scoring/decision logic
  - `stress/StressFeedbackStore.kt` — stores user feedback and personalization state
  - `screens/DefaultScreen.kt`, `SettingsScreen.kt` — UI screens
- Common library: `commonLibrary/src/main/java/com/example/commonlibrary/` and proto definitions in `commonLibrary/src/main/proto/`
- ML code and artifacts: `ml/` (scripts, requirements, model checkpoints and export helpers)

Development notes
-----------------
- Use Android Studio to inspect layouts, resources and run the Wear OS emulator.
- The Gradle wrapper (`./gradlew`) ensures a consistent build toolchain across machines.
- Keep Python environments isolated for the `ml/` folder to avoid dependency conflicts.

Troubleshooting
---------------
- Gradle sync errors: install missing SDK components listed in the Event Log and confirm Java version.
- Emulator issues: create a dedicated Wear OS AVD with the latest Wear OS image and enable hardware acceleration.
- Missing ML artifacts at runtime: ensure `ml/scripts/package_app_ml_assets.py` has been run and assets are present under `app/src/main/assets/`.

Last updated: May 2026
