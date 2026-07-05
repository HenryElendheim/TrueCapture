<p align="center">
  <img src="logo.svg" width="120" alt="Elendheim Capture logo">
</p>

<h1 align="center">Elendheim Capture</h1>

<p align="center">A plain camera for Android. Take photos and videos. Nothing else.</p>

<p align="center">
  <a href="https://github.com/HenryElendheim/Elendheim-Capture/releases/latest/download/ElendheimCapture.apk">
    <img src="https://img.shields.io/badge/Download-Elendheim%20Capture%20APK-2ea44f?style=for-the-badge&logo=android&logoColor=white" alt="Download the APK">
  </a>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-6D95ED" alt="License: MIT"></a>
</p>

---

## Why

I built Elendheim Capture for my Nothing Phone because I wanted a camera that
just takes pictures and videos, without the extra features and AI I did not ask
for.
It opens, it shows the camera, you tap the button, and your shot is saved to the
gallery. That is the whole idea.

## What it does

- Live camera preview
- Take a photo, or record video (switch with the Photo and Video tabs)
- Multi-camera zoom: tap a lens (such as 0.6x, 1x, or 3x) to switch between the
  ultra-wide, main, and telephoto cameras, or pinch to zoom
- Switch between the back and front camera, including while recording
- Flash for photos (off, on, auto) and a light for video
- Tap the screen to focus
- Record at 30 or 60 fps, chosen from the settings menu
- A timer shows how long you have been recording
- Photos and videos are saved to the `DCIM/Camera` folder, so they show up
  directly in your gallery

## Installing it on your phone

You do not need a computer or any developer tools.

1. Tap the **Download Elendheim Capture APK** button above (or
   [this link](https://github.com/HenryElendheim/Elendheim-Capture/releases/latest/download/ElendheimCapture.apk))
   on your phone. It always downloads the newest version.
2. Open the downloaded `ElendheimCapture.apk` and tap to install. The first time,
   Android will ask you to allow installing apps from this source. Turn that on,
   then install.
3. Open Elendheim Capture and allow the camera and microphone permissions when
   asked.

If Play Protect shows a warning, that is normal for an app installed outside the
Play Store. Tap **More details**, then **Install anyway**.

## Building it yourself

With Android Studio or the Android command line tools:

```
./gradlew assembleDebug
```

The finished app is at `app/build/outputs/apk/debug/app-debug.apk`.

## Built with

- Kotlin
- [CameraX](https://developer.android.com/media/camera/camerax) for the camera,
  photo capture, and video recording
- A single screen, saved to the gallery through `MediaStore`
- No third-party analytics, accounts, or network access

## License

Elendheim Capture is released under the [MIT License](LICENSE). You are free to
use, change, and share it, including in your own projects.
