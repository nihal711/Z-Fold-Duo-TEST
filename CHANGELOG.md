# Changelog

## 0.1.0 (fork, unreleased)

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

### Build
- CI: `sdkmanager` was not on PATH on the hosted runner and the SDK 37
  platform is published as `platforms;android-37.0`; both fixed. The debug APK
  is uploaded as a workflow artifact.
