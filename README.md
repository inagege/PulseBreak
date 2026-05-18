# PulseBreak

Wear OS app that detects possible stress and suggests break sessions.

## What Changed

- Replaced the old two-sample heart-rate delta trigger with a windowed stress scoring engine.
- Added optional multi-sensor inputs for classification (when available):
  - heart rate (required)
  - accelerometer-derived motion magnitude (optional)
  - ambient temperature variation (optional)
- Added user feedback prompt after stress detections:
  - `Do you feel stressed?` -> `Yes` / `No`
- Added lightweight on-device personalization using feedback-driven threshold bias updates.
- Added settings flags for:
  - feedback prompt enable/disable
  - personalization enable/disable

## Main Files

- `app/src/main/java/com/example/breakreminder/HeartRateReader.kt`
- `app/src/main/java/com/example/breakreminder/stress/StressInferenceEngine.kt`
- `app/src/main/java/com/example/breakreminder/stress/StressFeedbackStore.kt`
- `app/src/main/java/com/example/breakreminder/screens/DefaultScreen.kt`
- `app/src/main/java/com/example/breakreminder/screens/SettingsScreen.kt`
- `commonLibrary/src/main/java/com/example/commonlibrary/SettingsData.kt`
- `commonLibrary/src/main/proto/settings.proto`

## Notes

- The in-app classifier is currently a lightweight engineered model for on-device use.
- This structure is ready to swap to a trained TFLite model from Bemotion in a next step.

## Manual Verification

1. Open watch settings and verify stress options are visible.
2. Enable feedback prompts and personalization.
3. Keep app on `DefaultScreen` and simulate normal/stress activity.
4. Confirm prompt appears: `Do you feel stressed?`
5. Respond `Yes` and verify break navigation triggers.
6. Respond `No` in later prompts and verify repeated false-positive behavior reduces over time.
