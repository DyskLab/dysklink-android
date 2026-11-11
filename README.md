<p align="center">
  <img src="docs/icon.png" width="88" alt="DyskLink">
</p>

<h1 align="center">DyskLink for Android</h1>

<p align="center">Connects your Android phone and Mac over local Wi-Fi, with no cloud and no account.</p>

<p align="center">
  <a href="https://github.com/DyskLab/dysklink-android/releases/latest"><img src="https://img.shields.io/github/v/release/DyskLab/dysklink-android?include_prereleases" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/DyskLab/dysklink-android" alt="MIT License"></a>
  <a href="https://dysklab.fanny.dev"><img src="https://img.shields.io/badge/part%20of-DyskLab-2DD4A7" alt="Part of DyskLab"></a>
  <a href="https://buymeacoffee.com/fannydevz"><img src="https://img.shields.io/badge/Buy%20Me%20a%20Coffee-support-FFDD00?logo=buymeacoffee&logoColor=000" alt="Buy Me a Coffee"></a>
</p>

| Looking for your Mac | Pairing |
| --- | --- |
| <img src="docs/screenshots/01-home.png" width="280" alt="Looking for your Mac"> | <img src="docs/screenshots/02-pairing.png" width="280" alt="Matching the pairing code"> |

**This is the phone app. The Mac app is [DyskLink for Mac](https://github.com/DyskLab/dysklink-mac).**
You need both.

> DyskLink is in early development, so expect rough edges.

## Download
You need Android 8 or later.

1. Download `DyskLink-android-0.1.1.apk` from the
   [latest release](https://github.com/DyskLab/dysklink-android/releases/latest) and open it on your phone. Android asks
   you to allow installing from that source.
2. [Install the Mac app](https://github.com/DyskLab/dysklink-mac#download) and open it on the same network.
3. Type the 6-digit code the Mac shows into the phone. After that, they connect by themselves.

This APK is a debug build signed with a development key, so read [Current security limits](#current-security-limits)
first. A later release signed with a different key needs you to uninstall this one first, which erases your pairings.

**Updates:** once a day, DyskLink reads the public list of releases on GitHub. When a newer version is out, it sends
one notification and shows **Download** at the top of the home screen, which opens the new APK in your browser. It
sends nothing about your phone and never installs anything by itself. You can turn this off or check now under
**Check for updates** at the bottom of the home screen.

## Features
- **Connection:** encrypted pairing, multiple Macs, automatic reconnection, and connectivity reports (carrier,
  network type, signal strength).
- **Files:** send files both ways. Files from the Mac go to Download/DyskLink, and tapping one in the transfer list
  opens it. On Android 9 and older they stay in the app's own folder.
- **Clipboard:** copies on the Mac arrive on the phone, and the phone can send its copies back.
- **Remote control:** touchpad, keyboard, media remote, and presentation remote for the Mac, and Mac commands
  listed in advance on the Mac.
- **Phone data for the Mac:** notifications, reading and sending SMS, call alerts, contacts, and read-only file
  access.
- **Screen:** share the phone's screen to the Mac, with optional live mouse and keyboard control.
- **Todo, water & Pomodoro:** the Mac notch's todo list, water count, and Pomodoro, which can be changed from the
  phone. Optionally, the phone turns on priority-only Do Not Disturb during focus rounds. That needs Do Not Disturb
  access, and leaves a Do Not Disturb you set yourself alone.
- **Find my Mac / find my phone:** ring the Mac from the phone, or the phone from the Mac. The phone rings on the
  alarm stream at full volume for up to 30 seconds, even on silent, then puts the volume back.
- **Quick Settings tiles and a widget:** Send clipboard, Lock Mac, and Keep Mac awake tiles, and a home screen
  widget with the Mac's battery and buttons to send the clipboard and lock the Mac. Keep Awake only works while it is
  turned on in the Mac's Advanced page.
- **Stream Deck:** big buttons that run keyboard shortcuts, media keys, typed text, or Mac commands on the Mac.
  Edit names, colors, actions, and order, with 2 to 4 buttons per row. OBS buttons talk to OBS's built-in WebSocket
  server directly (OBS 28 and newer), so they work with OBS on any computer: scenes, streaming, recording, replay,
  virtual camera, mute, and showing or hiding sources, lit up to match OBS.
- **Scan barcodes to Mac:** scans a barcode or QR code with Google's code scanner and types its text on the Mac
  where the cursor is, then presses Enter, Tab, or nothing. Keep scanning reopens the camera after each code.
- **Voice typing to Mac:** speak in Indonesian or English and the words are typed on the Mac, with spoken
  punctuation ("titik", "koma", "baris baru", "comma", "new line") and capitals at the start of sentences. Uses the
  phone's speech recognizer and needs microphone access.
- **File history:** every file sent and received, by day, with filters, search, open, share, and remove from list.
- **Network tools:** works without the Mac. Shows this connection's details, runs a download and upload speed test
  against speed.cloudflare.com, finds devices on the same Wi-Fi by checking a few common ports and guessing what they
  are, and pings an address. The speed test uses test data, so it warns on metered connections.
- **Tabs:** the home screen has Devices (your Macs) and Tools (the phone's own tools) at the bottom.
- **Scan documents to Mac:** on the Mac page. Google's document scanner (through Play Services) finds the paper's edges and
  lets you crop and clean up pages, ML Kit reads the text, and the PDF goes to the Mac. Scans stay in DyskLink's own folder.
- **Other phones:** phones with DyskLink on the same Wi-Fi show up under Devices. Tap one, then type the code it shows,
  then send files, send the clipboard, ring it, and see its battery. Turn it off with Connect with other phones.
- **Terminal:** on the Mac page. A real shell on the Mac with colors and full-screen programs (vim, top, htop),
  an extra keys row, paste, and pinch to zoom. The Mac has to allow it first, and the phone asks for your
  fingerprint or screen lock every time.
- **Phone tools:** in Tools. Storage clean-up (large files, old downloads, leftover APKs, empty folders; nothing is
  removed until you pick and confirm), apps with size and last use (needs Usage access), and which apps hold
  sensitive permissions.
- **Phone files in a browser:** in Tools, turns on a small read-only web server so any laptop on the same Wi-Fi
  can browse, open, and download the phone's files after entering a PIN shown on the phone. It stops after 30 minutes
  without use and needs All files access. The connection isn't encrypted, so use it on a trusted Wi-Fi.
- **Prayer times & weather:** works without the Mac. Choose a city for today's prayer times from equran.id, with a
  reminder at each time or 10 minutes before, and the weather and next 3 days from Open-Meteo. A month of times is
  kept on the phone so they work offline, and a home screen widget shows the next prayer. Only the chosen city goes to
  equran.id, and only its coordinates to Open-Meteo. On-time reminders need **Alarms & reminders** access, otherwise
  Android may send them a few minutes late.
- **Charge reminder:** once per charge, a notification when the battery reaches a limit you choose (80% by default),
  and a list of recent charges. Android doesn't let apps stop charging, so it only reminds. Off by default.

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
- A new Mac brings up pairing right away while the app is open: the Mac shows a random code and you type it on the phone.
  After that, connecting is automatic.
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
The app's display name and application ID (`id.dysklink.android`, since 0.2.0) use DyskLink. The old name PhoneLink
remains in the Kotlin package, the `_phonelink._tcp` network service, and the data folder.

**Moving from 0.1:** the new application ID makes 0.2.0 a separate app. Uninstall the old DyskLink, install 0.2.0,
and pair again, because Android doesn't let one app read another's data.

## Support
DyskLink is free and open source under the [MIT License](LICENSE). If it helps you, you can
[buy me a coffee](https://buymeacoffee.com/fannydevz).

## Part of DyskLab
DyskLink is part of [DyskLab](https://dysklab.fanny.dev), alongside
[DyskFlow](https://github.com/DyskLab/dyskflow), [DyskFiles](https://github.com/DyskLab/dyskfiles), and
[DyskFocus](https://github.com/DyskLab/dyskfocus).

## Widgets

Besides the Mac and prayer times widgets: Quick note, Todo (tap a task to tick it), Pomodoro & water (the timer counts
down on the home screen), Mac media, Battery (this phone, Macs, and other phones), and Buttons (pick up to four:
Send clipboard, Lock Mac, Keep awake, Ring Mac, Play / pause, Touchpad, Terminal, Commands, Stream Deck). They follow
light and dark mode, and use the wallpaper colors on Android 12 and later.

## Wake Mac

When a paired Mac is offline, its page has Wake Mac, which sends Wake-on-LAN. The Mac has to be asleep (not shut down),
on power, on the same network, with "Wake for network access" on.

## Mac screen

On a Mac's page, Mac screen shows its screen live. Tap to click, tap twice to double-click, hold to right-click, drag
to drag, two fingers to scroll, and pinch to zoom. Type opens the phone keyboard. Turn on "View this screen from the
phone" on the Mac first; it needs Screen Recording, and Accessibility for control.

## Photo sync

On a Mac's page, PHOTOS › Send new photos sends photos and screenshots taken from then on to
Pictures › DyskLink on that Mac whenever it's connected. Photos already on the phone aren't sent.

## Camera to Mac

On a Mac's page, Camera to Mac streams the back or front camera to a window on the Mac. A Mac can also ask; the phone
shows a notification and starts only after a tap. Switch lenses, turn on the light, or stop it from the phone or the Mac.

## USB

Turn on USB debugging and plug the phone into the Mac. DyskLink moves the connection to the cable within a few seconds and
shows Connected over USB. Unplug to go back to Wi-Fi.

## Second display

On a Mac's page, Second display makes this phone an extra display of the Mac, turned sideways. Tap, drag, and type work as
in Mac screen.

## Sound sharing

On a Mac's page, Sound sharing plays the Mac's sound on this phone (turn on "Let phones hear this Mac" on the Mac
first), or plays this phone's sound on the Mac (Android 10 or later). Android asks before recording, and apps that
block recording stay silent. Mac screen has a Sound button, and the phone's sound goes with its screen to the Mac
unless "Sound with this phone's screen" is off.

## Gallery

Turn on Gallery in Phone data for Mac, and the Mac can browse this phone's photos and videos and open or save them.

## Air mouse

In Presentation, hold the purple Hold to point pad and turn the phone to move the Mac's pointer. A quick tap clicks.
