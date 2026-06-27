# VCam — Virtual Camera for Rooted Android Emulators

Inject any image or video into the camera feed of any app on a rooted Android emulator — **no Xposed required**.

## Features

- 🎥 Inject **image or video** as camera source
- 🎯 **Target specific apps** or inject globally
- 🔐 Root-based injection via V4L2 / system commands
- 📱 Works on **rooted Android emulators** (Genymotion, LDPlayer, MuMu, BlueStacks with root, etc.)
- 🚀 Clean Material Design 3 UI
- 🔔 Foreground service with notification controls

## Requirements

- Android 8.0+ (API 26+)
- **Rooted** Android emulator
- Root manager (Magisk recommended)

## How It Works

1. App requests root permission from the system
2. Locates the virtual camera device (`/dev/video0` or v4l2loopback)
3. Injects your image/video frames directly into the camera stream via V4L2 ioctls
4. When a target app opens the camera, it receives the injected feed instead of the real one

## Download APK

See [Releases](../../releases) — built automatically by GitHub Actions.

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Usage

1. Install APK on your rooted emulator
2. Open VCam — grant root permission when prompted
3. Tap **Pick Image** or **Pick Video** to load your media
4. (Optional) Select a specific target app from the list
5. Tap **Start VCam**
6. Open the target app and access its camera — it will show your image/video

## Architecture

```
VCam
├── MainActivity          — UI: media picker, app selector, start/stop
├── MainViewModel         — State management
├── VCamService           — Foreground service (camera injection lifecycle)
├── CameraInjector        — Root + V4L2 injection logic
├── RootManager           — libsu root shell wrapper
├── AppLoader             — Installed app enumeration
└── vcam_native.so        — Native V4L2 frame writer (C++)
```
