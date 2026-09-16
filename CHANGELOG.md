# Changelog

## 0.1.1 (2026-09-16)

First feedback from a Galaxy Z Fold8 Ultra (SM-F976B, One UI 9 / Android 17,
build CP2A.260605.016): the ADB link over loopback holds, device states are
read correctly, but the private hinge stream and live capture produce nothing,
and the public hinge sensor on this device is quantised to 90° (Samsung
reports `resolution 90.0°`), so basic mode only sees 0°, 90° and 180°.

- **Collect debug report** button: gathers, through the ADB link, the build's
  `IWallpaperManager` transaction table (to find where the wallpaper command
  moved), existing FoldInteractive log lines, the result of the probe command,
  hinge-related sensors, device/display state and the app's own logcat, then
  copies it and opens the share sheet. No PC needed.
- Vendor hinge sensors: every SensorManager sensor whose name mentions hinge,
  fold or angle is registered alongside the public one; the finest sensor that
  actually delivers plausible degrees is used. On builds that expose Samsung's
  internal angle sensor to apps this replaces the wallpaper-log hack entirely.
- Clear warning in the UI when only the 90°-step sensor is available.
- Angle log parsing accepts `mCurrentAngle=` / `:` / `(` variants and the
  logcat filter also matches a plain `FoldInteractive` tag.
- One UI freezes cached background processes, bound accessibility services
  included. On every (re)connect the app now exempts itself through the shell
  (`dumpsys deviceidle whitelist`, `appops RUN_ANY_IN_BACKGROUND`, active
  standby bucket) so the overlay keeps receiving angles while off screen.

## 0.1.0 (2026-09-16)

Focus: make the app usable day to day on the Galaxy Z Fold8 Ultra, where the
0.0.1 proof of concept kept dropping its on-device ADB link
(upstream issue #1).

### Connection stability
- The three shell streams (hinge probe, hinge log, live-capture bridge) are now
  supervised independently. When one of them exits it is restarted with
  backoff; the whole session is only torn down when adbd itself is gone.
  Previously any single stream ending disconnected everything, which is what
  showed up as "disconnects and reconnects" and "Live capture stopped".
- adbd is reached over `127.0.0.1` instead of the Wi-Fi address, so IP changes,
  Wi-Fi roaming and Android 17's local-network permission no longer sever the
  link. The discovered address remains a fallback.
- The last working port, and `getprop service.adb.tls.port` where readable,
  are tried when NsdManager silently loses the `_adb-tls-connect` service.
- Reconnects use exponential backoff and distinguish "pairing required" from
  transient failures instead of showing "pairing required" for every error.
- A heartbeat watchdog restarts a stalled hinge stream and only gives up on
  the session after repeated stalls.
- When a link comes back while the overlay is idle, the device state is reset
  and any shell display wake locks leaked by the lost session are released.

### Live capture
- The shell-side capture bridge no longer dies on a single failed frame,
  rejected buffer or Binder hiccup; failures are logged (rate limited) and the
  loop continues. Its output is now relayed into `adb logcat -s ZFoldDuoEngine`.
- Fallback between `android.window.ScreenCaptureInternal` and
  `android.window.ScreenCapture` for Android 16/17 point releases.
- The app-side frame sink catches unsupported buffer formats instead of
  propagating the exception back over Binder (which crashed the bridge).

### Device compatibility
- Concurrent-display state identifiers are read from
  `cmd device_state print-states` by name (`CONCURRENT_INNER_DEFAULT`,
  `CONCURRENT_OUTER_DEFAULT`, `CLOSED`) instead of assuming 4/5/0; the Fold7
  values remain the fallback. `dumpsys device_state` is parsed with tolerant
  regular expressions.
- The public `TYPE_HINGE_ANGLE` sensor drives the angle readout and a basic
  single-panel animation whenever the private ADB stream is unavailable, so
  the angle no longer vanishes when the link drops and the app does something
  useful before pairing. Dual-display transitions still require ADB and are
  skipped without it.

### Setup and UI
- English UI with Japanese translation (previously Japanese only).
- Setup checklist with prerequisite hints (Developer options, USB debugging,
  Wireless debugging) and deep links to Wireless debugging, the accessibility
  service page and App info (for "Allow restricted settings").
- Pairing code can be entered from a notification with inline reply while the
  Settings pairing dialog stays open.
- Diagnostics section (device, Android build, device states, sensor, link and
  stream status) with one-tap copy for bug reports.

### Build and distribution
- Release APKs are built locally, signed with the fork's own key (configured
  through an untracked `keystore.properties`) and published on the GitHub
  Releases page of the fork. Because that key differs from upstream's, the
  upstream 0.0.1 build has to be uninstalled before installing 0.1.0.
- CI: `sdkmanager` was not on PATH on the hosted runner and the SDK 37
  platform is published as `platforms;android-37.0`; both fixed. CI is a test
  and lint gate only; it does not produce release builds.
