# Kiosk D2 Boot Guardian

An independent Android app privacy screen with its own six-digit PIN, designed for rooted Samsung devices where the user intentionally runs without Android's standard screen lock or biometrics. Version 0.3.0 adds optional root-assisted Android kiosk mode. It does not create or modify system credentials, force the screen off, or change firmware, Knox, Gatekeeper, or boot partitions.

> **Root / D2 recovery use case:** The project is intended to be used as the phone's app-level lock screen while rooted **after the user has deliberately removed the standard Android/Samsung screen lock and biometrics**. The project author has physically tested recovery from Samsung D2 Download Mode in this configuration and was able to restore firmware with Odin/firmware flashing tools. The purpose is to avoid having an Android credential/biometric configuration become an additional recovery obstacle after a root-related D2 condition. **D2 itself does not repair Download Mode, remove D2 errors, modify firmware integrity checks, or guarantee recovery on every Samsung model/firmware/root configuration.** Keep firmware backups and the correct Odin/firmware files available before experimenting with root.

**Public experimental preview.** The current source is **D2 0.4.4**, with optional KernelSU Next bridge **0.5.6**. The newest CI-verified D2 build is published as [v0.4.4-preview.2](https://github.com/SysopBP/SamsungLockD2-Public/releases/tag/v0.4.4-preview.2), built and verified in [Actions run #16](https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/35520482675). The existing paired Galaxy Island preview remains available from [v0.4.4-preview.1](https://github.com/SysopBP/SamsungLockD2-Public/releases/tag/v0.4.4-preview.1).

- [**Latest CI-verified D2 APK (Preview 2)**](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.2/SamsungLockD2-debug.apk) — includes wake-listener recovery and verified original signing. [Release notes and all files](https://github.com/SysopBP/SamsungLockD2-Public/releases/tag/v0.4.4-preview.2).
- [Previous D2 0.4.4 paired APK](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.1/SamsungLockD2-v0.4.4-paired-themes.apk)
- [Galaxy Island themed APK](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.1/Galaxy-Island-themed.apk)
- [Optional KernelSU Next bridge 0.5.6](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.2/SamsungLockD2-KSUNext-v0.5.6-paired.zip)
- [Theme and update guide](docs/THEMES.md), [build verification](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.1/Build-verification.txt), [current D2 checksums](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.2/SHA256SUMS.txt), and [runtime dependencies](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.2/runtime-dependencies.txt)
- [D2 source](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.2/SamsungLockD2-source.zip) and [Galaxy Island source](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.1/Galaxy-Island-themed-source.zip)

D2's **THEME & COLORS** settings add system/light/dark/AMOLED modes, wallpaper colors, soft accent presets and custom hex colors. Notifications support per-app, accent or neutral colors, adjustable opacity and corner radius. The stack stays centered between the clock/weather and lower controls; longer lists scroll. The floating PIN/shortcut bar has adjustable width, opacity and bottom spacing.

Galaxy Island's **Profile** now includes AMOLED and matching accent choices, plus **Copy current accent to island**. Each app saves its theme independently. Existing island layout controls remain available. Galaxy Island retains version label 0.2.0-beta; use this release's filename and checksum to identify the themed build.

Exit kiosk with your PIN before updating both APKs. Install the D2 APK even when updating the module: the bridge preserves an already-installed app. Notification privacy, phone controls and PIN requirements are retained. [Call and live notification setup](docs/CALLS-AND-LIVE-NOTIFICATIONS.md).

**Device testing:** The user reported testing the earlier combo on a **Samsung Galaxy S26 Ultra running Android 17**. This update has separate Android 16/API 36 emulator checks; it has not been physically tested on that handset. Theme options were inspired by [KernelSU Next's Material 3/dynamic-color/AMOLED approach](https://github.com/KernelSU-Next/KernelSU-Next/blob/dev/manager/app/src/main/java/com/rifsxd/ksunext/ui/theme/Theme.kt), with an independent implementation.

## Theme screenshots

Real emulator captures with sample notifications, not Samsung handset photos. D2 captures use app-only mode; they do not demonstrate a real incoming call. Galaxy Island settings are captured separately.

| Centered D2 notifications | D2 AMOLED and floating bar | Galaxy Island theme controls |
| --- | --- | --- |
| <img src="https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.1/13-d2-044-centered-notifications.png" width="240" alt="Centered sample D2 notifications"> | <img src="https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.1/15-d2-044-amoled-floating-bar.png" width="240" alt="D2 AMOLED theme and adjustable floating bar"> | <img src="https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.4.4-preview.1/18-galaxy-amoled-teal.png" width="240" alt="Galaxy Island AMOLED theme controls"> |

Earlier galleries: [0.4.3 notification colors](https://github.com/SysopBP/SamsungLockD2-Public/releases/tag/v0.4.3-preview.1) · [paired-app captures](https://github.com/SysopBP/SamsungLockD2-Public/releases/tag/v0.4.1-preview.1).

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

## What's new on the Guardian fingerprint branch

- **Guardian fingerprint unlock (experimental):** D2 now requests genuine Android/Samsung `BIOMETRIC_STRONG` authentication when the enhanced rooted path is available.
- **Unique glass fingerprint surface:** the D2 lock screen adds a glass biometric card with scan feedback, success/failure states, and direct PIN/Pattern fallback. The secure fingerprint decision still comes from Android/Samsung; the glass surface is D2 presentation.
- **Safe non-root fallback:** if temporary root is unavailable, D2 does not start its enhanced fingerprint session and falls back to the configured Guardian PIN or Pattern.
- **Root-loss reboot recovery:** `LOCKED_BOOT_COMPLETED` records only a non-secret pending-restore marker in device-protected storage. Samsung/Android remains authoritative for the first device unlock; Guardian restores its existing service path afterward.
- **Expanded Xposed fingerprint diagnostics:** listener/start/update method signatures are logged to identify the exact Samsung/SystemUI fingerprint-listening path before any narrow suppression hook is enabled.
- **Single-scan integration is still experimental:** the current diagnostics are groundwork for preventing duplicate Samsung-keyguard + D2 scans. Do not treat the one-scan SystemUI integration as complete until it is validated on-device.
- **Passkeys remain separate:** D2 passkeys are not the Guardian lock-screen unlock method and are not a substitute for PIN/Pattern fallback.

## Set up

1. Exit active kiosk using your PIN and install the current paired D2 APK linked above. If Android reports a signature mismatch, stop: uninstalling clears your PIN and settings. For the optional combo, install the Galaxy Island themed APK linked above from Preview 1.
2. Open Kiosk D2 Boot Guardian and create and confirm your six-digit PIN. This PIN belongs only to D2.
3. Choose a wallpaper. Preview is available after entering your PIN in settings.
4. Double-tap **Double-tap to lock D2** in settings or Preview to open the D2 PIN screen.
5. Tap **Add home-screen double-tap widget**, or add **D2 double-tap lock** from your launcher's widget list. Double-tap that widget within 0.9 seconds. D2 cannot intercept taps on other parts of the launcher. A single widget tap briefly opens a transparent activity to record the tap; it does not lock.
6. Enable **Require PIN to leave D2 (root kiosk)** in authenticated settings, if wanted. Grant D2 root access in KernelSU. Lock D2 and wait for **Kiosk active • PIN required to leave**. Until that appears, Home/Recents are not protected. Preview never starts kiosk mode.
7. To dismiss D2 and release kiosk mode, use your configured Guardian unlock method. PIN/Pattern remains the recovery path; an incorrect credential or Cancel leaves D2 locked.
8. **Fingerprint testing (experimental):** with root and the Guardian fingerprint integration available, lock D2 and use the glass fingerprint surface. Authentication remains Android/Samsung `BIOMETRIC_STRONG`; **PIN / Pattern** on the glass surface cancels fingerprint and opens the normal Guardian credential UI.
9. **No-root behavior:** after a normal reboot that removes temporary root, expect fingerprint-enhanced Guardian behavior to be unavailable. Use the configured Guardian PIN/Pattern. D2 does not change fingerprint enrollment or weaken Samsung's device credential.
10. **Full-reboot behavior:** before Android credential storage is unlocked, Samsung/Android keyguard remains authoritative. Guardian records only a pending restore state and resumes its existing service/fallback path after Android reports the user unlocked. Restore root/LSPosed afterward to return to enhanced integration.

### Fingerprint test states

**Root + integration available:** Guardian glass fingerprint UI + genuine biometric authentication.  
**Root unavailable:** Guardian PIN/Pattern fallback; the enhanced fingerprint session is not started.  
**Normal reboot with temporary root lost:** Samsung/Android handles the first device unlock, then Guardian restores; regain root/LSPosed for enhanced fingerprint integration.

> The single-scan SystemUI work is under active testing. Current builds may still expose both Samsung keyguard authentication and D2 authentication until the exact Samsung/SystemUI fingerprint-listener hook is validated. Do not disable your known-working recovery path while testing.

**Show D2 when the screen wakes** is optional. Android may block background launches. Optional KernelSU root mode only requests this app's Activity launch. The bridge module can restart the opted-in service after boot; it does not provide pre-boot protection. Widget/manual activation does not require root.

Notification access, camera, and approximate location are optional. Locked media metadata and playback controls can be shown through Settings when notification access is granted. Notification privacy can hide all notifications, show a count or app names, show public-only content, or explicitly show private content too. Notifications marked secret remain hidden on the locked D2 screen. The camera action requires the D2 PIN while locked; the flashlight can work without it. Settings require the PIN again when returning from the background. PIN changes require the existing PIN.

## Security boundary

D2 is an app privacy screen, **not a secure replacement for Android's device lock or encryption**. Without active kiosk mode, Home, Recents, notification shade, force-stop, clearing data, uninstall, root access, and reboot can bypass it. Active kiosk mode blocks ordinary Home/Recents and requires the D2 PIN for normal exit. Root, reboot, and recovery remain bypasses. Back requests the app PIN. Do not rely on it to protect a stolen phone or encrypted data.

The PIN verifier uses PBKDF2-HMAC-SHA256 (210,000 iterations, random 128-bit salt, 256-bit result), constant-time comparison, and a private no-backup file. The raw PIN is not stored. After five wrong PIN attempts, verification is delayed for 30 seconds; repeated failures increase the delay up to 16 minutes. Attempts persist across process restarts. An attacker with root or control of app data/device time can bypass these software controls. Screenshots/recents capture and third-party overlays are blocked in D2 screens; credentials are excluded from backup.

If the PIN is forgotten, clearing D2 app data resets it and all app settings. There is no default, master, or remotely recoverable PIN.

The D2 name refers to the project's Samsung Download Mode recovery use case. The author has physically tested a rooted-device setup with the standard Samsung/Android screen lock and biometrics removed and successfully restored firmware after encountering D2 in Download Mode. D2 provides the independent app PIN/kiosk layer for that setup. It does **not** remove an existing Samsung credential for you, repair firmware integrity, clear a D2 condition by itself, or establish that every Samsung device/firmware/root combination will behave identically. Emulator tests cannot validate Samsung bootloader or Download Mode behavior.

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
