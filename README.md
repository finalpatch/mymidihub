# My MIDI Hub

A small native Kotlin Android MIDI 1.0 router for personal use. Sources appear on the left, destinations on the right. Drag a source onto a destination, or tap a source and then a destination. The layout follows the system light/dark theme and resizes for folded, unfolded, and landscape screens.

## Features

- Android MIDI discovery: USB devices and MIDI services exposed by other apps.
- Eight virtual inputs and eight virtual outputs.
- One-to-many and many-to-one routing, without channel rules or filters.
- A foreground routing service and partial wake lock for background/screen-off operation.
- Saved routes, visible virtual ports, and local port labels.
- Reconnection when missing devices return; busy ports retry every three seconds.
- Explicit Stop and an all-notes-off command.
- No internet, Bluetooth, audio-recording, or storage permissions; no analytics.

## Install and use

Build the debug APK (see below), copy it to the phone, and open it with Android's package installer. Launch **My MIDI Hub** to start routing. Allow notifications to get a visible background indicator and Stop action.

1. Connect USB MIDI devices or open the music apps whose ports you want to use.
2. Drag from a source on the left to a destination on the right. Repeat for additional routes. **Routes → Add route** provides the same operation through native dialogs.
3. Use **Ports** to add more virtual sources/destinations to the board. Hold a port to rename it or remove it from the board. **Ports → Edit a port** also exposes those controls without a long press.
4. Use **Routes** to remove a connection. Solid lines indicate active connections; dashed lines indicate saved routes waiting for a port.
5. Use another app or turn the screen off. Routing continues until **Stop**, Android force-stop, or reboot. Open the app again after reboot; there is no boot receiver.

In another music app, **send to Hub In 1** to feed the **Hub In 1 source** here. **Receive from Hub Out 1** to listen to the **Hub Out 1 destination** here. For example:

```text
USB keyboard Out  →  Hub Out 1  →  synth app listening to Hub Out 1
sequencer app sending to Hub In 1  →  Hub In 1  →  USB synth In
```

Android declares virtual MIDI devices/ports in application metadata. This app therefore publishes a **fixed bank of eight numbered inputs and eight numbered outputs**. Adding/removing a virtual port manages the routing board; it does not create/delete an Android system port. Renames are saved local labels; other apps keep seeing `Hub In N` and `Hub Out N`. The bank is available after installation and is not dynamically renamed. No virtual ports are initially shown on the board; add sources and destinations through Ports as needed. Updating from 0.1.0 removes unused, unrenamed default ports from the board while preserving connected or renamed ports.

If Samsung puts the app to sleep during use, set **Settings → Apps → My MIDI Hub → Battery → Unrestricted**. Android can still stop an app through force-stop or the system's active-apps controls; no background service can override that.

## Build

Requires JDK 17+, Android SDK platform 36, and Build Tools 35.0.0. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir=/path/to/android-sdk`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

The checked-in wrapper pins Gradle 8.13 and verifies its distribution checksum. The project uses AGP 8.13.2 and Kotlin 2.3.0, targets Android 16 / API 36, and supports Android 8 / API 26 and newer. On Termux, the SDK must have working ARM64 build tools; the existing user Gradle configuration supplies the Termux `aapt2` override. No Termux-specific absolute paths are committed.

## Behavior and limits

- The app routes MIDI bytes, not audio. It does not discover Bluetooth or network devices and does not implement MIDI 2.0 UMP.
- Android device IDs are temporary. Persisted endpoint identities use device type, service component, manufacturer, product, name, serial number (when provided), and port metadata. Devices that change those properties appear as new endpoints. Multiple indistinguishable devices are deliberately not auto-connected; attach one at a time or use hardware with distinct identifiers.
- A destination's Android input port is exclusive. If another app owns it, the route waits and retries. Multiple sources inside this app share a single destination handle.
- MIDI messages are reconstructed before merging streams, including expansion of running status. Real-time messages pass immediately. SysEx is buffered until its terminating byte so another source cannot interleave into it; incomplete or oversized SysEx (more than approximately 1 MiB) is discarded. This makes the app unsuitable for arbitrarily large streamed firmware dumps.
- Routing changes, lost sources, Stop, and the all-notes-off command may silence all sources sharing the affected destination. This prevents held notes from remaining after disconnects.
- Forwarding preserves source timestamps. It does not reorder events from different sources by timestamp, align their clocks, or prevent feedback through other apps/devices. Avoid unintended MIDI loops.
- Configuration is stored privately on the phone. Uninstalling or clearing app data removes it. There is no cloud backup or preset system.

## Implementation

`RoutingEngine` owns ports and configuration on a dedicated worker. Incoming MIDI buffers are copied before leaving Android callbacks; all sends and topology changes are serialized. `VirtualMidiService` exposes the static bank and never starts routing itself. `RoutingService` owns the foreground notification and wake lock independently of the activity. The `specialUse` foreground service type covers continuous local MIDI routing, including sessions with no external USB hardware; its purpose is declared in the manifest.

References: [Android MIDI API and virtual devices](https://developer.android.com/reference/android/media/midi/package-summary), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

## Verification

JVM tests cover message fragmentation, independent running status across merged sources, real-time interleaving, system-common message lengths, SysEx framing/limits, reset behavior, timestamps, and stable endpoint keys. Android lint checks the application and manifest. These checks cannot establish real MIDI latency or Samsung background behavior.

Hardware acceptance checks are in [TESTING.md](TESTING.md). No Android device was connected to ADB during initial development, so on-device behavior must still be verified.
