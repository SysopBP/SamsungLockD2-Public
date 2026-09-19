# Samsung Lock D2

An independent Android app privacy screen with its own six-digit PIN. Version 0.3.0 adds optional root-assisted Android kiosk mode. It does not create or modify system credentials, force the screen off, or change firmware, Knox, Gatekeeper, or boot partitions. Kiosk mode interacts with the Android keyguard internally; this is not a guarantee against Samsung D2 boot errors.

**Public experimental preview.** Download the APK and optional KernelSU module from [Releases](https://github.com/SysopBP/SamsungLockD2-Public/releases).

**Device testing:** The v0.3.0 kiosk version was user-tested on a Samsung Galaxy S26 Ultra running the latest One UI 9 beta available at the time, including kiosk activation and PIN unlock. The exact beta build was not recorded. Version 0.3.1 adds licensing notices and PIN-gated weather credits; it has not yet been physically retested on that phone.

## Screenshots

Captured from D2 v0.3.0 in an Android 16 emulator. Samsung/One UI appearance may differ.

<table>
  <tr>
    <td align="center"><strong>Lock-screen preview</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/02-preview.png" width="270" alt="D2 lock-screen preview with its default background"></td>
    <td align="center"><strong>Active root kiosk</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/03-kiosk-lock.png" width="270" alt="D2 lock screen showing that kiosk mode is active and a PIN is required to leave"></td>
  </tr>
  <tr>
    <td align="center"><strong>Independent D2 PIN</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/04-pin-prompt.png" width="270" alt="The six-digit D2 PIN prompt"></td>
    <td align="center"><strong>App settings</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/01-settings.png" width="270" alt="D2 settings with double-tap activation and the optional root kiosk switch"></td>
  </tr>
</table>

## Set up

1. Install SamsungLockD2-debug.apk. If an older debug build has a different signing certificate, uninstall it first; uninstalling clears app data.
2. Open Samsung Lock D2 and create and confirm your six-digit PIN. This PIN belongs only to D2.
3. Choose a wallpaper. Preview is available after entering your PIN in settings.
4. Double-tap **Double-tap to lock D2** in settings or Preview to open the D2 PIN screen.
5. Tap **Add home-screen double-tap widget**, or add **D2 double-tap lock** from your launcher's widget list. Double-tap that widget within 0.9 seconds. D2 cannot intercept taps on other parts of the launcher. A single widget tap briefly opens a transparent activity to record the tap; it does not lock.
6. Enable **Require PIN to leave D2 (root kiosk)** in authenticated settings, if wanted. Grant D2 root access in KernelSU. Lock D2 and wait for **Kiosk active • PIN required to leave**. Until that appears, Home/Recents are not protected. Preview never starts kiosk mode.
7. To dismiss D2 and release kiosk mode, tap **PIN** and enter your six-digit D2 PIN. An incorrect PIN or Cancel leaves it locked.

**Show D2 when the screen wakes** is optional. Android may block background launches. Optional KernelSU root mode only requests this app's Activity launch. The bridge module can restart the opted-in service after boot; it does not provide pre-boot protection. Widget/manual activation does not require root.

Notification access, camera, and approximate location are optional. Locked D2 screens hide notification identities/content and media metadata; camera and media controls are available only in authenticated Preview. Settings require the PIN again when returning from the background. PIN changes require the existing PIN.

## Security boundary

D2 is an app privacy screen, **not a secure replacement for Android's device lock or encryption**. Without active kiosk mode, Home, Recents, notification shade, force-stop, clearing data, uninstall, root access, and reboot can bypass it. Active kiosk mode blocks ordinary Home/Recents and requires the D2 PIN for normal exit. Root, reboot, and recovery remain bypasses. Back requests the app PIN. Do not rely on it to protect a stolen phone or encrypted data.

The PIN verifier uses PBKDF2-HMAC-SHA256 (210,000 iterations, random 128-bit salt, 256-bit result), constant-time comparison, and a private no-backup file. The raw PIN is not stored. After five wrong PIN attempts, verification is delayed for 30 seconds; repeated failures increase the delay up to 16 minutes. Attempts persist across process restarts. An attacker with root or control of app data/device time can bypass these software controls. Screenshots/recents capture and third-party overlays are blocked in D2 screens; credentials are excluded from backup.

If the PIN is forgotten, clearing D2 app data resets it and all app settings. There is no default, master, or remotely recoverable PIN.

The D2 name refers to the user's Samsung download-mode concern. This app cannot repair firmware integrity or guarantee that a D2 boot/download-mode error will be prevented. It does not remove any existing Samsung credential. No Samsung S26 Ultra beta or KernelSU hardware behavior has been verified by emulator tests.

## Experimental root kiosk and recovery

Kiosk mode is off by default and supports the primary, unmanaged user only. It refuses existing task allowlists, device owners, and profile owners. A separate root `app_process` temporarily allowlists only D2 in ActivityTaskManager. No device owner is provisioned, and no system credential or persistent device-policy file is written. The app checks for full `LOCK_TASK_MODE_LOCKED`; ordinary screen pinning is not accepted as success.

The helper enables the kiosk power menu and existing keyguard behavior while Home/Recents remain blocked. On exit it clears its temporary allowlist and returns idle task-feature flags to the unmanaged-device power-menu default. Devices using other root tools to customize runtime kiosk policy are unsupported. Android task APIs are internal and Samsung beta compatibility requires physical testing.

A root watchdog releases the restriction when the app connection closes or UI heartbeats stop for about 20 seconds. This intentionally favors recovery over keeping a crashed app locked. Screen-off process suspension may also release it. If recovery fails, restart the phone; no kiosk restriction is persisted across reboot. Keep the hardware restart method available for testing. There is no master PIN. Do not use this prerelease as theft protection or unattended kiosk security.

## Build and tests

AGP 9.1.1, Gradle 9.3.1, Java 17, compile/target API 37; minimum API 31. GitHub Actions builds and lints the debug APK, runs Android 16/API 36 instrumentation tests, and packages it with the optional KernelSU bridge. Tests cover the wallpaper/startup regression, independent PIN verification/change/throttling, settings gating, PIN-screen recreation/unlock, and double-tap timing.

The app now uses the neutral package ID `app.d2lock`; source namespaces and module author metadata contain no personal name. This installs as a separate app from earlier builds: disable/uninstall the old app, create a new D2 PIN, grant root again, and replace the old widget. The KernelSU module ID is retained so installing its new ZIP updates the existing bridge.

## Data

Notification content stays in memory. Wallpaper selection stores a persistent image URI. Weather uses Open-Meteo with approximate last-known location only when permission is granted; no location history is stored by D2.

## Rights and credits

Copyright (c) 2026 D2 Project. **All rights reserved.** D2's original code is publicly viewable but is not released under an open-source license. See [LICENSE](LICENSE). Third-party components retain their own licenses; see [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) and **Licenses and credits** in the app.

Weather data by [Open-Meteo](https://open-meteo.com/), under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). D2 truncates temperatures and maps weather codes to short labels. No affiliation with or endorsement by Samsung, KernelSU, or Open-Meteo is implied.
