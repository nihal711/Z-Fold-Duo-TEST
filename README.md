# ZFoldDuo

An experimental Android app that **recreates the iPhone Duo opening and closing animation on Galaxy Z Fold devices**. It follows the hinge angle of the Galaxy Z Fold7, Fold8 and Fold8 Ultra and overlays an iPhone Duo-inspired 3D folding effect across the entire screen.

**This is not a staged animation inside a demo app or a static-screenshot mockup.** ZFoldDuo runs as a system-wide overlay across Android and other apps, responds continuously to the physical hinge, and coordinates the real inner and cover displays. Live screen content remains part of the rendered transition.

> [!NOTE]
> This is a fork of [nnnnnnn0090/Z-Fold-Duo-TEST](https://github.com/nnnnnnn0090/Z-Fold-Duo-TEST) focused on making the app reliable on the Galaxy Z Fold8 Ultra. The upstream 0.0.1 proof of concept kept losing its on-device ADB link on that device ([upstream issue #1](https://github.com/nnnnnnn0090/Z-Fold-Duo-TEST/issues/1)); this fork rewrites the connection layer, adds a no-ADB basic mode, and turns the setup screen into an English checklist. See [CHANGELOG.md](CHANGELOG.md) for everything that changed.

> [!IMPORTANT]
> ZFoldDuo relies on private Samsung interfaces and the Fold series' device-specific display architecture. It is still experimental: the fork's changes have been verified by unit tests and builds, not yet by a long soak on every One UI build. Bug reports with the **Copy diagnostics** output and `adb logcat -s ZFoldDuoEngine` are very welcome.

## Demo

![ZFoldDuo running as a system-wide fold animation on a Galaxy Z Fold](docs/assets/zfoldduo-demo.gif)

## Features

- System-wide operation across the Android UI and other apps
- Continuous animation driven by Samsung's internal hinge angle (or the public hinge sensor in basic mode)
- Display transitions spanning the inner and cover screens
- Live frames that reflect changes in the content behind the overlay
- World-space projection based on a pinhole camera model
- Automatic cleanup when the hinge angle remains nearly unchanged for one second
- ADB connection that runs entirely on the device, over loopback, with automatic recovery

## Two modes

| | Basic mode | Full mode |
| --- | --- | --- |
| Needs | Accessibility service only | Accessibility service + one-time Wireless debugging pairing |
| Hinge angle | Public Android hinge sensor (about 1° steps) | Samsung's internal sensor, 40 Hz, three decimals |
| Frames | Frozen screenshot per transition | Live frames while the panel is moving |
| Both panels lit during a transition | No (Samsung switches panels itself) | Yes (concurrent display states) |

The app starts in basic mode as soon as the overlay service is enabled and upgrades itself to full mode whenever the ADB link is up. If the link drops mid-session it falls back to basic mode instead of stopping.

## Supported environment

- Galaxy Z Fold7, Fold8, Fold8 Ultra (SM-F976x / SM-F9760)
- Android 16 or 17 (One UI 8.x / 9.x)
- Wireless debugging (full mode)
- Accessibility service

The app may also work on the Galaxy Z Fold6 and earlier models, but these devices have not been tested. Chinese-market builds (CHC) are handled the same way as global ones; please report results.

## Setup

1. Install `zfoldduo-<version>.apk` from the [Releases](../../releases) page. It is signed with this fork's key, so uninstall the upstream 0.0.1 build first if you have it.
2. Open ZFoldDuo and allow notifications and, on Android 17, local network access when asked.
3. **Overlay service** – tap *Open Accessibility settings* and enable *ZFoldDuo fold animation*. If Android shows *Restricted setting*, tap *Open App info*, open the ⋮ menu, choose *Allow restricted settings*, then try again. Basic mode works from here on.
4. **Full mode** – tap *Open Developer options*, enable Developer options and USB debugging if needed, then *Open Wireless debugging*, turn it on and choose **Pair device with pairing code**.
5. Enter the six-digit code either in the notification ZFoldDuo posts (inline reply, no app switching needed) or in the app itself.

The pairing key is stored in the app's private storage. A PC is never required. Wireless debugging turns itself off after a reboot on most builds; turning it back on is enough, no re-pairing is needed.

### If the link keeps dropping

- Open the app and read the **1 · Wireless debugging link** section. It names the missing prerequisite (Developer options, USB debugging, Wireless debugging) and shows whether the hinge stream and live capture are alive.
- Tap **Copy diagnostics** and include the text, together with `adb logcat -s ZFoldDuoEngine`, in a bug report. The capture bridge's own output is relayed into that log, so a failing screen-capture API on a new One UI build is visible there.

## How it works

ZFoldDuo reads the internal hinge angle used by Samsung's system wallpaper component through an on-device ADB session. Its accessibility overlay is attached at the system level rather than to one app activity, so the effect follows whatever is currently visible on the device. During a display transition, it tracks Android's logical displays and the physical panels separately, temporarily keeping only the required displays powered at the same time. The identifiers of the concurrent-display device states are read from `cmd device_state print-states` at connection time so models that number them differently still work.

The rendering engine treats each captured frame as a virtual glass surface. It calculates the projected position from the distance to the hinge, distance to the viewer, and rotation of the surface, then uses AGSL to apply depth-dependent frosting and dimming.

Screen capture is used only as the live texture source for the system-wide effect; the animation is not a prerecorded or frozen screenshot trick. The frames are refreshed from the active display while the geometry continues to follow the physical hinge. The overlay itself does not receive touch input and is excluded from capture, preventing it from recursively appearing inside its own live frames.

### The ADB link

The link is made to `127.0.0.1` on the port Wireless debugging announces over mDNS. Loopback survives Wi-Fi address changes and Android 17's local-network permission, both of which killed connections made to the Wi-Fi address. The last working port is remembered and `service.adb.tls.port` is consulted so a flaky NsdManager does not block reconnection. Three shell streams run over the session (hinge probe, hinge log, live-capture bridge); each restarts on its own with backoff, and only an unreachable adbd tears the session down. A heartbeat watchdog restarts a stalled hinge stream.

## Privacy

- Screen frames are processed in memory only while an animation is active.
- Frames are never saved to files.
- Frames and hinge-angle data are never sent to external servers.
- Network permission is used only for the on-device wireless debugging connection and mDNS discovery.
- Notifications are used only for entering the pairing code.
- Secure screens protected by Android cannot be captured.

## Building

Requirements:

- JDK 17
- Android SDK platform 37.0 (`sdkmanager "platforms;android-37.0"`)

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed from an untracked `keystore.properties` in the project root:

```properties
storeFile=zfoldduo-release.jks
storePassword=...
keyAlias=zfoldduo
keyPassword=...
```

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Never commit the keystore or its properties; `.gitignore` already excludes `*.jks` and `keystore.properties`. Published releases are built this way and uploaded to the fork's Releases page by hand; CI only runs tests and lint.

## Project structure

```text
app/src/main/java/com/foldduo/hinge/
├── AngleRuntime.kt                 Link supervisor, endpoint discovery, device-state commands
├── EmbeddedAdbAngleClient.kt      ADB session with independently supervised shell streams
├── MainActivity.kt                Setup checklist and diagnostics
├── link/                          Link status model, endpoint candidates, device-state catalog,
│                                  public hinge sensor fallback, notification pairing
├── capture/                       Live-frame transport over Binder and the shell-side bridge
├── effect/                        Projection model, motion tracking, and frame smoothing
└── overlay/                       Display state machine and GPU-rendered views

app/src/main/res/raw/
└── spatial_projection.agsl        World-space projection and frosting shader
```

## Caveats

- This is an unofficial project that imitates the iPhone Duo opening and closing effect. It is not provided, endorsed, or supported by Apple or Samsung.
- Changes to private APIs may break the app without notice.
- Accessibility permission is used to display a touch-through overlay across the entire screen.
- If the app is force-stopped during a display transition, fold or unfold the device—or restart it—to restore the standard display state. The app also resets the display state itself the next time its ADB link comes up while idle.

## Contributing

Upstream discussion happens on the original project's [Discord server](https://discord.gg/3ZgZKwJhKz). For code contributions, read [CONTRIBUTING.md](CONTRIBUTING.md). Bug reports should include the device model, Android build number, reproduction steps, the app's **Copy diagnostics** output, and relevant `ZFoldDuoEngine` logs. Do not attach personal information or screen content.

## License

This project is available under the [MIT License](LICENSE). Third-party libraries are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
