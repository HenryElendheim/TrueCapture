<p align="center">
  <img src="logo.svg" width="120" alt="TrueCapture logo">
</p>

<h1 align="center">TrueCapture</h1>

<p align="center">A plain camera for Android. Take photos and videos. Nothing else.</p>

---

## Why

I built TrueCapture for my Nothing Phone because I wanted a camera that just
takes pictures and videos, without the extra features and AI I did not ask for.
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

You do not need a computer or any developer tools. GitHub builds the app for you.

1. Go to the **Actions** tab of this repository on GitHub.
2. Open the most recent **Build APK** run with a green check mark.
3. Under **Artifacts**, download **TrueCapture-apk**.
4. Unzip it on your phone. Inside is a file called `app-debug.apk`.
5. Tap the file to install it. The first time, Android will ask you to allow
   installing apps from this source. Turn that on, then install.
6. Open TrueCapture and allow the camera and microphone permissions when asked.

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
