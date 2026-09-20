# Samsung Lock D2

An independent Android app privacy screen with its own six-digit PIN. Version 0.3.0 adds optional root-assisted Android kiosk mode. It does not create or modify system credentials, force the screen off, or change firmware, Knox, Gatekeeper, or boot partitions. Kiosk mode interacts with the Android keyguard internally; this is not a guarantee against Samsung D2 boot errors.

**Public experimental preview.** The latest downloadable paired update is **D2 0.4.2 with call support and live notification banners**, with optional KernelSU Next bridge **0.5.4**. [Release notes and downloads](https://github.com/SysopBP/SamsungLockD2-Public/releases/tag/v0.4.2-preview.1). This branch contains the current v0.4.2 paired source; a downloadable source archive is attached as [SamsungLockD2-v0.4.2-source.zip](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/SamsungLockD2-v0.4.2-source.zip).

- [Install D2 0.4.2 paired APK](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/SamsungLockD2-v0.4.2-paired-calls.apk)
- [Optional KernelSU Next bridge 0.5.4 ZIP](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/SamsungLockD2-KSUNext-v0.5.4-paired.zip)
- [Setup and changes](docs/CALLS-AND-LIVE-NOTIFICATIONS.md), [verification](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/D2-v0.4.2-verification.txt), and [checksums](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/Combo-v0.4.2-SHA256SUMS.txt)

Install the APK even when updating the module; the module preserves an existing D2 APK. Enable D2 notification access for call controls and live banners. All 19 Android 16/API 36 emulator tests passed. The new call fix still needs a real incoming-call test on the Galaxy S26 Ultra running Android 17.

**Device testing:** User-reported physical-device testing of the Galaxy Island + Samsung Lock D2 combo was performed on a **Samsung Galaxy S26 Ultra running Android 17** (confirmed September 19, 2026). Earlier v0.3.0 testing included kiosk activation and PIN unlock on One UI 9 beta; the exact beta build was not recorded. Automated tests and the combo screenshot gallery use a separate Android 16 / API 36 emulator.

## New 0.4.2 screenshots

Real D2 captures with kiosk active on an Android 16/API 36 emulator. The incoming-call notification is simulated; the message is sample content. These are not Samsung handset captures or proof of a real call. Galaxy Island is hidden during D2 lock and was not installed for this D2-only capture.

| Call controls (simulation) | Live notification banner (sample) |
| --- | --- |
| <img src="https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/08-d2-042-call-controls-demo.png" width="240" alt="Simulated call controls during D2 kiosk"> | <img src="https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/09-d2-042-live-message-demo.png" width="240" alt="Sample message banner during D2 kiosk"> |

[Capture notes](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.2-preview.1/D2-v0.4.2-screenshot-notes.md) · [Earlier paired-app gallery](https://github.com/SysopBP/SamsungLockD2-Public/releases/tag/v0.4.1-preview.1).

## Earlier 0.4.0 screenshots

Captured from D2 v0.4.0 in an Android 16 emulator with sample media. These are not Samsung handset captures; One UI appearance may differ.

<table>
  <tr>
    <td align="center"><strong>Lock-screen preview</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/02-preview.png" width="270" alt="D2 lock-screen preview with its default background"></td>
    <td align="center"><strong>Active root kiosk</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/03-kiosk-lock.png" width="270" alt="D2 lock screen showing that kiosk mode is active and a PIN is required to leave"></td>
  </tr>
  <tr>
    <td align="center"><strong>Independent D2 PIN</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/04-pin-prompt.png" width="270" alt="The six-digit D2 PIN prompt"></td>
    <td align="center"><strong>App settings</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/01-settings.png" width="270" alt="D2 settings with double-tap activation and the optional root kiosk switch"></td>
  </tr>
  <tr>
    <td align="center"><strong>Appearance and media settings</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/05-customization.png" width="270" alt="Celsius and locked media settings"></td>
    <td align="center"><strong>Privacy and bottom shortcuts</strong><br><img src="https://raw.githubusercontent.com/SysopBP/SamsungLockD2-Public/main/docs/screenshots/06-shortcuts.png" width="270" alt="Notification privacy and configurable camera and flashlight actions"></td>
  </tr>
</table>

## Set up

1. Exit active kiosk using your PIN and install the current paired D2 APK linked above. If Android reports a signature mismatch, stop: uninstalling clears your PIN and settings. Install Galaxy-Island.apk from the same release for the optional combo.
2. Open Samsung Lock D2 and create and confirm your six-digit PIN. This PIN belongs only to D2.
3. Choose a wallpaper. Preview is available after entering your PIN in settings.
4. Double-tap **Double-tap to lock D2** in settings or Preview to open the D2 PIN screen.
5. Tap **Add home-screen double-tap widget**, or add **D2 double-tap lock** from your launcher's widget list. Double-tap that widget within 0.9 seconds. D2 cannot intercept taps on other parts of the launcher. A single widget tap briefly opens a transparent activity to record the tap; it does not lock.
6. Enable **Require PIN to leave D2 (root kiosk)** in authenticated settings, if wanted. Grant D2 root access in KernelSU. Lock D2 and wait for **Kiosk active • PIN required to leave**. Until that appears, Home/Recents are not protected. Preview never starts kiosk mode.
7. To dismiss D2 and release kiosk mode, tap **PIN** and enter your six-digit D2 PIN. An incorrect PIN or Cancel leaves it locked.

**Show D2 when the screen wakes** is optional. Android may block background launches. Optional KernelSU root mode only requests this app's Activity launch. The bridge module can restart the opted-in service after boot; it does not provide pre-boot protection. Widget/manual activation does not require root.

Notification access, camera, and approximate location are optional. Locked media metadata and playback controls can be shown through Settings when notification access is granted. Notification privacy can hide all notifications, show a count or app names, show public-only content, or explicitly show private content too. Notifications marked secret remain hidden on the locked D2 screen. The camera action requires the D2 PIN while locked; the flashlight can work without it. Settings require the PIN again when returning from the background. PIN changes require the existing PIN.

## Security boundary

D2 is an app privacy screen, **not a secure replacement for Android's device lock or encryption**. Without active kiosk mode, Home, Recents, notification shade, force-stop, clearing data, uninstall, root access, and reboot can bypass it. Active kiosk mode blocks ordinary Home/Recents and requires the D2 PIN for normal exit. Root, reboot, and recovery remain bypasses. Back requests the app PIN. Do not rely on it to protect a stolen phone or encrypted data.

The PIN verifier uses PBKDF2-HMAC-SHA256 (210,000 iterations, random 128-bit salt, 256-bit result), constant-time comparison, and a private no-backup file. The raw PIN is not stored. After five wrong PIN attempts, verification is delayed for 30 seconds; repeated failures increase the delay up to 16 minutes. Attempts persist across process restarts. An attacker with root or control of app data/device time can bypass these software controls. Screenshots/recents capture and third-party overlays are blocked in D2 screens; credentials are excluded from backup.

If the PIN is forgotten, clearing D2 app data resets it and all app settings. There is no default, master, or remotely recoverable PIN.

The D2 name refers to the user's Samsung download-mode concern. This app cannot repair firmware integrity or guarantee that a D2 boot/download-mode error will be prevented. It does not remove any existing Samsung credential. No Samsung S26 Ultra beta or KernelSU hardware behavior has been verified by emulator tests.

## Experimental root kiosk and recovery

Kiosk mode is off by default and supports the primary, unmanaged user only. It refuses existing task allowlists, device owners, and profile owners. A separate root `app_process` temporarily allowlists D2, the selected Phone app, and system call UI in ActivityTaskManager. No device owner is provisioned, and no system credential or persistent device-policy file is written. The app checks for full `LOCK_TASK_MODE_LOCKED`; ordinary screen pinning is not accepted as success.

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
## Current source update (0.4.0)

Version 0.4.0 adds a floating action bar, media details and controls while D2 is locked,
Fahrenheit/Celsius selection, configurable bottom shortcuts, and notification privacy
choices. The locked media card can show track names and operate playback; choose
whether to display it in Settings. The Public only option shows content explicitly marked public by the sending app;
All previews also shows private notification text before the D2 PIN is entered.
Notifications marked secret stay hidden while D2 is locked. Camera requires the D2 PIN
while locked; flashlight stays accessible. D2 retains its independent six-digit PIN
and optional root kiosk behavior. The APK and module ZIP passed the build, lint, and emulator checks, and a user reported the update working on their device.

## Notification preview fix (0.4.1)

Most Android notifications are marked private by default. Select **All previews (private too)**
in D2 settings if you want their message text on the locked D2 screen. **Public only**
shows text only from notifications explicitly marked public by their apps.
Notification access must be granted for either option. Secret notifications stay hidden.
