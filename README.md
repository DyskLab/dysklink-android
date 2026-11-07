# DyskLink for Android

Connects your Android phone and Mac over local Wi-Fi, with no cloud and no account.

**This is the phone app. The Mac app is [DyskLink for Mac](https://github.com/DyskLab/dysklink-mac).**
You need both.

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-fannydevz-ffdd00?logo=buymeacoffee&logoColor=000)](https://buymeacoffee.com/fannydevz)

> DyskLink is in early development. There are no prebuilt downloads yet, so you build it from source.

## Features
- **Connection:** encrypted pairing, multiple Macs, automatic reconnection, and connectivity reports (carrier,
  network type, signal strength).
- **Files:** send files both ways.
- **Clipboard:** copies on the Mac arrive on the phone, and the phone can send its copies back.
- **Remote control:** touchpad, keyboard, media remote, and presentation remote for the Mac, and Mac commands
  listed in advance on the Mac.
- **Phone data for the Mac:** notifications, reading and sending SMS, call alerts, contacts, and read-only file
  access.
- **Screen:** share the phone's screen to the Mac, with optional live mouse and keyboard control.

## Phone data
Notifications, SMS, calls, contacts, files, screen requests, and control from the Mac are **off by default**.
Turn them on one by one under **choose a Mac > Phone data for Mac**. The phone rechecks every request from a Mac
against the switch and the Android permission, and your choice applies to every paired Mac.

- File access is limited to internal storage and is read-only. Files whose names start with a dot are hidden unless
  **Show hidden** is ticked on the Mac. Several files can be downloaded together, and the phone sends two at a time.
- Notifications their app marks as secret are not sent. Other notifications are sent as they are, so verification
  codes from banking apps or SMS can appear on the Mac.
- SMS is sent with the phone's number and credit, through the default SMS SIM. RCS is not supported.

## Screen and control
- Screen sharing always needs approval in the Android dialog for each session, even when requested from the Mac.
  While it runs, a phone notification offers a Stop button.
- Control from the Mac has its own switch and needs the Accessibility permission. It is refused while the phone is
  locked, so a PIN can't be entered from the Mac, and text is never typed into password fields.
- Apps that protect their screen, such as banking apps, appear black on the Mac.
- Mouse drags and scrolling are sent as one live touch on the phone. If the phone cancels that touch, the movement is
  replayed as one gesture when the button is released. A touch is lifted automatically after 10 seconds without news
  from the Mac, so a lost connection can't leave it pressed.

## Permissions
- **Clipboard:** since Android 10, background apps can't read the clipboard. Text leaves the phone through the
  **Send clipboard** button in the DyskLink notification, when DyskLink is opened, or from the Share menu, or
  automatically if you turn on **Send phone copies automatically**. That option is off by default. It needs the
  Accessibility service, which then checks the labels of tapped buttons and toasts for words like Copy, without
  storing or sending them, and opens a brief transparent screen to read the clipboard. Copied passwords can be sent
  unless the app marks them private, and icon-only copy buttons may not be noticed. The Mac confirms each text.
  Without a confirmation within 4 seconds, the phone reconnects and sends it again. Mac to phone is automatic.
- **Phone data:** requested when the feature is turned on. Notification access and all-files access are granted on
  Settings pages. If the option is grayed out, open DyskLink's App info, tap the three-dot menu, then allow restricted
  settings.

The SMS, call log, and all-files permissions make this app unsuitable for Google Play without a special application,
so it is installed directly.

## Build and install
You need Android 8 or later on the phone, JDK 17, and the Android SDK. Set `ANDROID_HOME` to the SDK, or put `sdk.dir`
in `local.properties`.

```bash
git clone https://github.com/DyskLab/dysklink-android.git
cd dysklink-android
```

```bash
./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If your default Java isn't 17, set `JAVA_HOME` to JDK 17 first.

Then [build the Mac app](https://github.com/DyskLab/dysklink-mac#build-and-run) and start it.

## How connecting works
- The phone finds the Mac through mDNS, a /24 subnet scan, or a manual IP address.
- A new Mac brings up the code screen right away while the app is open. The first pairing still needs confirmation on
  both devices. After that, connecting is automatic.
- The app uses a foreground service and a 25-second heartbeat, and reconnects when the network changes. The service
  also starts again after the phone reboots.

To stay connected, allow DyskLink to run in the background from the card in the app. On vivo, Oppo, and Xiaomi, also
turn on autostart.

## Tests
```bash
./gradlew :app:testDebugUnitTest
```

The tests that need a running Mac app are skipped here. To run them too, use `scripts/integration-test.sh` in
[dysklink-mac](https://github.com/DyskLab/dysklink-mac#tests), with this repository cloned next to it.

Both apps speak the protocol in
[docs/PROTOCOL.md](https://github.com/DyskLab/dysklink-mac/blob/main/docs/PROTOCOL.md), kept in the Mac repository. A
change to it needs a matching change in the Mac app.

## Current security limits
Private keys are stored as files with restricted permissions in the app data folder, not yet in Android Keystore. The
APK is a debug build, so its app data can be read through `adb run-as`.

## Internal names
The app's display name is DyskLink. The old name PhoneLink remains in the package ID, the `_phonelink._tcp` network
service, and the data folder, because changing them would erase existing pairings.

## Support
DyskLink is free and open source under the [MIT License](LICENSE). If it helps you, you can
[buy me a coffee](https://buymeacoffee.com/fannydevz).
