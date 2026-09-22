# On-device acceptance checks

Use the debug APK on the Fold with at least one USB MIDI device and a MIDI-capable music app. These checks require actual devices; they are not claims of completed testing.

1. **Launch and permissions:** Install and launch. Allow notifications; confirm an ongoing routing notification. Repeat with notifications denied: the screen should still work and routing should start.
2. **Virtual sources:** In another app, send notes/controllers to `My MIDI Hub / Hub In 1`. Connect that source to a USB MIDI destination. Check notes, velocities, sustain, program changes, and pitch bend.
3. **Virtual destinations:** Route a USB MIDI source to `Hub Out 1`. Have a synth app receive from that output. Confirm note-on and note-off behavior.
4. **Fan-out:** Connect one source to two destinations. Both should receive each event once. Add the same route again; it should not duplicate delivery.
5. **Merge:** Route two sources to one destination. Send interleaved notes and controllers from different channels. Neither stream should alter the other's message types. Removing either source may silence notes on the shared destination, as documented.
6. **Gestures:** Drag horizontally to connect; cancel over empty space; tap source then destination; scroll vertically; hold a port to rename. Connect ports several rows apart using scrolling or the Routes dialog. Check Routes removal and accessible native Add route controls.
7. **Background:** Play continuous MIDI, switch apps, lock the screen, and leave it running for at least 15 minutes. Unlock and verify notes still route with acceptable latency. If needed, repeat with Samsung battery usage set to Unrestricted.
8. **Fold/theme:** While routing, fold/unfold and rotate the phone; toggle system dark mode. The activity should redraw without restarting or interrupting the routing engine. Repeat while explicitly stopped: configuration should remain visible and stopped.
9. **Reconnect:** Disconnect a routed USB device. Its nodes and dashed routes should remain. Reconnect; routes should resume without editing. Repeat with an app MIDI service closing/reopening.
10. **Busy destination:** Have another app own a destination input before connecting it here. Expect a waiting/busy status. Release it in that app; within a few seconds the route should become active.
11. **Persistence:** Rename ports, show several virtual ports, and create routes. Stop and reopen. Force-stop and reopen. Reboot and open. Configuration should survive each case; routing should not start automatically on boot. The numbered port names in other apps should remain stable.
12. **Stop:** Stop from the app and notification. Verify MIDI ceases, notification disappears, and notes do not hang. Start again and verify saved routes resume. Swiping the activity away while running should leave the foreground service operating.
13. **Panic:** Hold notes/sustain and use More → All notes off. Notes should release on connected destinations.
14. **Port bank:** Show all eight inputs and outputs. Verify their numbering matches other apps. Remove a virtual port from the board and add it again. Other apps should continue to see the fixed bank; removed routes should not reappear.
15. **Duplicate devices:** Where possible, connect two identical devices without unique serials. Expect ambiguous/unavailable nodes rather than a guessed connection. Remove one and check reconnection.

Optional ADB diagnostics:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mymidihub/.MainActivity
adb shell dumpsys midi
adb shell dumpsys activity services com.mymidihub
adb logcat -s AndroidRuntime MidiManager MidiDeviceService
```

Use a MIDI monitor in a separate app for byte-level delivery checks. Avoid creating a feedback loop while constructing loopback tests.
