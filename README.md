# Demeter Speech Android

Demeter Speech is the Android client used for recording, transcription, transcript review, and meeting report generation in the Transcode workspace.

## Prerequisites

- A locally installed JDK
- Android SDK `platforms;android-36`
- Android Build Tools `36.0.0`
- CMake `3.22.1`
- NDK `28.2.13676358`

## Configuration

The app reads its backend URL from `.env.production` at the repository root.

To start from the tracked example:

```bash
cp .env.production.example .env.production
```

Update the value if you want to point the app at another backend instance.

## Build and test

```bash
./gradlew test
```

Useful Android Gradle commands:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Mobile launcher

`run-mobile.sh` starts the app on a connected device or on a local emulator.

Examples:

```bash
./run-mobile.sh --device
./run-mobile.sh --emulator
./run-mobile.sh --emulator-window
```

The launcher reads optional local defaults from `./.tooling/run-mobile.env` and expects `JAVA_HOME` and the Android SDK to be available locally when you override the defaults.

## License

GPL-3.0-or-later.
