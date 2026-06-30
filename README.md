# TrueCapture

A plain camera app for Android. It shows the camera, takes a photo, and saves it
to your phone. Nothing else.

## What it does

- Live camera preview
- Take a photo with one button
- Switch between the back and front camera
- Flash off, on, or auto
- Tap the screen to focus
- Pinch to zoom
- Photos are saved to your gallery in the `Pictures/TrueCapture` folder

## Installing it on your phone

You do not need a computer or any developer tools. GitHub builds the app for you.

1. Go to the **Actions** tab of this repository on GitHub.
2. Open the most recent **Build APK** run that has a green check mark.
3. Scroll down to **Artifacts** and download **TrueCapture-apk**.
4. Unzip it on your phone. Inside is a file called `app-debug.apk`.
5. Tap the file to install it. The first time, Android will ask you to allow
   installing apps from this source. Turn that on, then go back and install.
6. Open TrueCapture and allow the camera permission when it asks.

## Building it yourself

If you have Android Studio or the Android command line tools:

```
./gradlew assembleDebug
```

The finished app is at `app/build/outputs/apk/debug/app-debug.apk`.
