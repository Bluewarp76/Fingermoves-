# Tab Gestures

iPad-style multi-finger gestures for Android tablets (built for the Galaxy Tab S10 Ultra),
without root, using [Shizuku](https://shizuku.rikka.app/).

| Default gesture             | Action        |
|-----------------------------|---------------|
| 4 or 5 finger pinch in      | Home          |
| 4 or 5 finger swipe up      | Recent apps   |
| 4 or 5 finger swipe right   | Previous app  |
| 4 or 5 finger swipe left    | Next app      |

Every 3, 4 and 5-finger swipe (up/down/left/right), pinch in/out and tap can be mapped to:
Home, Recents, Back, Previous/Next app, Toggle last app, Notifications, Quick settings,
Screenshot, Lock screen, Play/pause, Assistant, or Nothing.

## How it works

Android doesn't let normal apps see touches outside their own window. Shizuku runs a small
helper process with ADB ("shell") permissions, which may read the raw touchscreen device
(`/dev/input/eventN`, the same data `adb shell getevent` shows).

```
touchscreen ─▶ GestureUserService (shell, via Shizuku)
                 MultiTouchParser  → finger frames
                 GestureDetector   → swipe / pinch / tap
                     │  binder callback
                     ▼
               GestureService (app, foreground)
                 maps gesture → action (with screen rotation)
                     │  binder call
                     ▼
               GestureUserService injects KEYCODE_HOME / APP_SWITCH / a nav-handle swipe …
```

Touches aren't intercepted, so the app underneath still receives them (same as on iPad).

## Install

1. **Install Shizuku** from the Play Store.
2. Enable **Developer options** (Settings › About tablet › Software information › tap
   *Build number* 7 times), then turn on **Wireless debugging**.
3. Open Shizuku › *Start via Wireless debugging* › pair (follow its notification), then *Start*.
4. Install `TabGestures-0.1.0.apk` (allow installing from your browser/file manager).
5. Open **Tab Gestures** › *Grant Shizuku permission* › *Start gestures*.
6. Tap *Battery: unrestricted* and set battery usage to **Unrestricted**.
7. For Previous/Next app: Settings › Display › Navigation bar › **Swipe gestures**
   (these actions replay the system's swipe along the bottom handle).

After a reboot, start Shizuku again; Tab Gestures restarts itself and reconnects.

### Testing / tuning

The *Last gesture* line shows what was detected and which action ran.
- Swipes go the wrong way → change **Direction correction**.
- Swipes trigger too easily / not at all → adjust **Swipe distance**.
- Pinches not detected → lower **Pinch amount**.

## Building

With Android Studio / the Android Gradle Plugin: `./gradlew assembleDebug` (open the folder
in Android Studio to get the wrapper).

Without AGP (what was used here, since Google's Maven repo wasn't reachable):
`tools/build-apk.sh` — uses aapt, kotlinc, D8, zipalign and apksigner directly. See the
script header for paths.

Unit tests for the parser and detector are in `app/src/test` (`./gradlew testDebugUnitTest`).

## Known limitations

- Shizuku must be restarted after each reboot (an Android restriction without root).
- Actions fire when the fingers lift, not continuously like iPadOS's interactive animations.
- Multi-finger touches also reach the foreground app (e.g. a 4-finger swipe in a drawing app).
- *Toggle last app* sends Recents twice; how One UI handles that may vary.
