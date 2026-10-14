# Samsung Lock D2

## 0.6.4 prerelease preparation

- [Installation and fingerprint setup](docs/INSTALLATION-0.6.4-PREVIEW.md)
- [Release write-up, findings and credits](docs/RELEASE-0.6.4-PREVIEW.md)

**Fingerprint setup:** configure Samsung PIN and enroll a fingerprint first; then turn Samsung **Fingerprint unlock OFF**, retaining the PIN and enrollment. Configure D2’s recovery credential and select **Login with fingerprint**. Samsung PIN is still required after D2 fingerprint in this preview, including the first device unlock after reboot.


## Standard D2 information dialogs

New information and choice dialogs use the reusable `GuardianDialogs` surface: rounded dark glass, a leading icon, readable text, large action buttons, and a transparent window without the square Android panel. Fingerprint setup, unavailable-fingerprint guidance, the unlock-method chooser and launcher-icon help now use this style. Fingerprint setup includes an **Open settings** shortcut and keeps the D2 PIN fallback guidance. Long information text scrolls while action buttons remain visible. Existing specialized dialogs are migrated separately; the secure Samsung biometric prompt remains system-rendered.

## In development — additional Xposed controls

The framework and Galaxy Island control grids include disabled placeholders. Safe diagnostics, configurable screen/wake awareness, immediate relock, Home/Recents, gesture coordination, notifications, media, call-state integration, SystemUI recovery, QS/status guards, native lifecycle, fallback controls, and Galaxy Island event/positioning/sync controls require their individual hooks to be implemented and tested before those switches become available. Development is deferred to a later update; this release does not enable them.

The current boot/wake observers, security-setup refresh, D2 SRP checks and power-menu hooks run separately. The disabled grid switches do not control those existing hooks. **Accept framework signals** is a separate app-side control.

## Feature naming

**D2 SRP** is the short name for **Samsung Recovery Protection**. Compact buttons and messages use D2 SRP; setup instructions and explanations spell out the full name. SRP is D2's feature name, not a guarantee of successful firmware recovery. The underlying Samsung vault remains named `DMC`; code identifiers, broadcast actions and diagnostic log tags retain that technical name. Credit to **@diablOw** remains for the original DMC discovery.

## D2 power-menu integration (experimental)

Enable D2 in **both SystemUI and System Framework** LSPosed scopes. Reload those processes using your established recovery procedure; installing an APK alone does not guarantee the hooks are active.

Samsung's power menu presents **D2 power off** and **D2 restart** using the original Samsung action models, with native confirmation and long-press behavior. Emergency, lockdown and unrelated actions are retained. D2 adds **D2 soft restart** (with Samsung confirmation) and **D2 SRP status** (a rounded dark live-status dialog). Power off and Restart use the System Server guard described below; soft restart requests are accepted only from the trusted primary-user SystemUI or D2 app UID and refused if the soft-restart guard is unavailable. The live dialog checks System Server's sender identity and times out after six seconds.

This is an action-list integration inspired by [OneUIX](https://github.com/SoClear/OneUIX), implemented independently using reflection and libxposed. It keeps Samsung's menu presentation; a complete replacement of the menu's visual layout is not included. It requires Samsung's `SamsungGlobalActionsPresenter#createActions` API. Missing APIs leave the standard menu available and are logged as `D2_POWER_MENU_HOOK_UNAVAILABLE` or `D2_POWER_MENU_ACTIONS_UNAVAILABLE`. Test interaction with other power-menu modules, including OneUIX, before relying on combined behavior.

## D2 SRP checks before power actions (0.6.4 preview)

With D2 enabled in LSPosed's **System Framework** scope, the new guard checks the actual DMC vault before the confirmed normal restart or power-off sequence. Safe-mode, recovery and download restarts are covered only when they enter the same framework path. Android's orderly `ActivityManagerService.restart()` soft restart has a separate guard.

- Fresh vault validation, existing target enforcement, and a second read before continuing.
- Five-second timeout; verification failure cancels the requested action. The phone stays on; open D2's DMC status and retry. A late result cannot initiate a delayed reboot.
- Duplicate requests are suppressed; a late result cannot cause a delayed reboot.
- Battery/thermal emergency shutdown remains immediate.
- Direct root process kills, init/property restarts, forced hardware resets and power loss are outside these hooks.

Check `DMC_POWER_HOOK_INSTALLED` logs before relying on coverage. Firmware-specific missing hooks are reported and cannot provide protection. A verified read is not a guarantee that no Samsung service writes the settings later or that firmware has persisted them durably. Local policy/request tests and targeted hook compilation pass; full APK build and device reboot/power-off tests remain pending. See [validation and limits](docs/AUTOMATIC-DMC-RECOVERY.md#power-action-guard-experimental).

### When protection does not pass

For an active, supported guard, failed or unknown DMC verification cancels the requested power off, restart, or supported soft restart. The phone stays on and shows a message directing you to D2's DMC status. A five-second timeout also cancels the request; a late successful result cannot restart the phone. Fix the reported issue and explicitly retry the action.

Opening **D2 SRP status** performs the recovery/check and displays its result; it never requests shutdown or restart by itself. A failed status dialog does not globally disable every reboot route.

**A missing guard is different from a failed check:** if the shutdown hook is not loaded, unsupported, or falls back through protective Xposed exception handling, Samsung's normal power action may proceed without D2 verification. D2's own soft-restart request is refused when its guard is unavailable. Direct root-level zygote restarts (including `ctl.restart zygote`) and process kills bypass the orderly ActivityManager soft-restart path and can restart Android without a D2 SRP check. D2’s Recovery Center Soft restart button now uses the guarded System Server path instead of restarting zygote directly; Restart SystemUI requires a fresh SRP check before its root action. These controls do not block another root app, terminal, module, or external process from restarting zygote directly. Forced hardware resets, power loss, and battery/thermal emergency shutdown are also outside enforcement. Hook installation and coverage must be verified on the actual firmware.

## Samsung Recovery Protection (D2 SRP) preview (0.6.4)

D2 applies and verifies the primary-user DMC vault targets inside Xposed System
Server (UID 1000): screen-lock flag disabled, Maintenance Mode flag disabled,
Download Mode AT-command flag enabled, and derived Download Mode eligibility
allowed. Enable D2 for System Framework in LSPosed and reboot. Recovery has no
root shell or app_process fallback and no manual Fix Now step.

Completed PIN/password/pattern changes and fingerprint/face additions or removals
trigger a refresh after 2 seconds, followed by a check at 12 seconds for delayed
Samsung updates. Setup detection needs validation on the target firmware.
The live glass status dialog shows target checks, System Server identity, and
last-check time. Successful vault read-back is required to report verification.
DMC flags are separate from Android credentials and biometric enrollment.

**DMC discovery credit: @diablOw**, for originally discovering the DMC behavior
behind this recovery feature.

See [automatic DMC recovery behavior and limits](docs/AUTOMATIC-DMC-RECOVERY.md).

> **September 29, 2026 development status:** Physical-device testing now confirms the token-integrated fast-boot path as the new known-good reference. `ActivityManagerService.systemReady()` delivers `BOOT_READY`, D2 performs the early root launch, confirms the boot token when `LockScreenActivity` is created, and reaches `GUARDIAN_FIRST_DRAW` about **305 ms after BOOT_READY** in the captured successful boot. When the normal Android boot receiver arrives later, it recognizes the claimed token and skips the duplicate launch. Preserve this ordering as the boot baseline. See [the verified baseline and measured boot timeline](.github/KIOSK_D2_BASELINE.md).

## October 1, 2026 — New features & hotfixes

### Hotfixes
- **Voicemail / call-state relock:** fixes the case where an incoming call transitioning to voicemail could incorrectly cause D2 to lock. Normal Guardian relock behavior after an actual call is retained.
- **Double-tap screen-off:** double-tapping an empty area of the D2 lock screen can turn the display off while interactive controls remain excluded from the gesture.
- **Notification/call-card gesture handling:** the presence of a notification or call card no longer disables double-tap sleep for the entire lock screen; D2 checks the actual touch target instead.

### Double-tap sleep fallback chain
- **Root → Shizuku → Device Admin.** Root remains the privileged path, Shizuku is attempted when available and authorized, and Android Device Admin lockNow() provides an opt-in fallback when root/Shizuku cannot perform the action.
- Device Admin is never silently enabled; Android's approval screen is shown when activation is required.

### Guardian Dock / Floating Bar
- The floating Guardian Dock and center **CORE/CHECK** control now hide together when scrolling down and return together when scrolling up.
- Added smooth slide/fade behavior and retained bottom inset clearance for gesture navigation.
- Added **Floating Bar scroll behavior** under **Settings → Shortcuts & Bar → Floating Bar**:
  1. **Always visible** — keep the dock pinned.
  2. **Hide on scroll** — hide on downward scroll and restore on upward scroll. This is the default.
  3. **Exit after scrolling** — after scrolling begins, keep the dock hidden for the remainder of the current settings view.

These UI and gesture changes are intentionally isolated from the token-integrated early-boot path described above.

An independent Android app privacy screen with its own six-digit PIN, designed for rooted Samsung devices where the user intentionally runs without Android's standard screen lock or biometrics. Version 0.3.0 adds optional root-assisted Android kiosk mode. It does not create or modify system credentials, force the screen off, or change firmware, Knox, Gatekeeper, or boot partitions.

> **Root / D2 recovery use case:** The project is intended to be used as the phone's app-level lock screen while rooted **after the user has deliberately removed the standard Android/Samsung screen lock and biometrics**. The project author has physically tested recovery from Samsung D2 Download Mode in this configuration and was able to restore firmware with Odin/firmware flashing tools. The purpose is to avoid having an Android credential/biometric configuration become an additional recovery obstacle after a root-related D2 condition. **D2 itself does not repair Download Mode, remove D2 errors, modify firmware integrity checks, or guarantee recovery on every Samsung model/firmware/root configuration.** Keep firmware backups and the correct Odin/firmware files available before experimenting with root.

## Verified known-good build

The current **frozen known-good D2 application baseline** is **GitHub Actions run #985 / run 36825757183**, commit `811888c8ab9a45ec05ff0dfc542cc59475806908` (October 1, 2026). That workflow completed successfully and produced the `Kiosk-D2-Guardian-deliverables`, preview test reports, and lint reports.

- [**Verified Actions run #985**](https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/36825757183)
- Baseline commit: [`811888c8`](https://github.com/SysopBP/SamsungLockD2-Public/commit/811888c8ab9a45ec05ff0dfc542cc59475806908)
- [Verified boot architecture and measured timeline](.github/KIOSK_D2_BASELINE.md)
- [TokenX integration boundary](docs/TOKENX-INTEGRATION.md)

**Freeze rule:** preserve the application, lock-screen UI, kiosk behavior, boot handoff, boot-token confirmation, late-launch suppression, and October 1 feature/hotfix set from run #985. Infrastructure, documentation, release metadata, and optional external integrations may evolve independently, but they must not silently replace this application baseline.

Older 0.4.x preview releases remain historical downloads and screenshot references; they are no longer described as the current verified D2 baseline.

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

## Login with fingerprint — third unlock option

Settings → Security Center → Unlock method now offers **PIN**, **Pattern**, and **Login with fingerprint**.

1. Keep a fingerprint enrolled in Samsung Settings → Fingerprints.
2. Turn **Fingerprint unlock OFF** on that Samsung settings page. Keep the enrolled fingerprint; do not delete it for this mode.
3. Select **Login with fingerprint** in D2. D2 checks Android's strong-biometric availability before saving this choice. If unavailable, the existing unlock method is kept and a shortcut to security settings is offered.
4. Lock D2 and authenticate through the secure Android/Samsung biometric prompt. If Samsung's PIN screen appears first, complete it before D2 fingerprint login. Selecting this option does not bypass Samsung's PIN or the first unlock after reboot.
5. Cancel or use your **D2 PIN** when biometrics are unavailable. Fingerprint mode retains the independent D2 PIN as its fallback.

The explicit fingerprint option can request the platform biometric prompt without a successful root probe. Root-dependent kiosk and framework integration still require their own setup. Android decides whether strong biometrics are available; the API requests `BIOMETRIC_STRONG`, rather than a fingerprint-only authenticator. Enrollment and Samsung settings are changed manually; D2 does not change them automatically.

This mode keeps biometrics enrolled and differs from the original credential-free recovery configuration described above. Compilation and device testing of the new chooser remain pending.

## What's new on the Guardian fingerprint branch

- **Guardian fingerprint unlock (experimental):** D2 now requests genuine Android/Samsung `BIOMETRIC_STRONG` authentication when the enhanced rooted path is available.
- **Unique glass fingerprint surface:** the D2 lock screen adds a glass biometric card with scan feedback, success/failure states, and direct PIN/Pattern fallback. The secure fingerprint decision still comes from Android/Samsung; the glass surface is D2 presentation.
- **Safe non-root fallback:** if temporary root is unavailable, the enhanced rooted path remains unavailable. Selecting Login with fingerprint explicitly still requests the platform prompt, with D2 PIN fallback.
- **Root-loss reboot recovery:** `LOCKED_BOOT_COMPLETED` records only a non-secret pending-restore marker in device-protected storage. Samsung/Android remains authoritative for the first device unlock; Guardian restores its existing service path afterward.
- **Expanded Xposed fingerprint diagnostics:** listener/start/update method signatures are logged to identify the exact Samsung/SystemUI fingerprint-listening path before any narrow suppression hook is enabled.
- **Single-scan integration is still experimental:** the current diagnostics are groundwork for preventing duplicate Samsung-keyguard + D2 scans. Do not treat the one-scan SystemUI integration as complete until it is validated on-device.
- **Passkeys remain separate:** D2 passkeys are not the Guardian lock-screen unlock method and are not a substitute for PIN/Pattern fallback.

## Set up

1. Exit active kiosk using your PIN and install the current paired D2 APK linked above. If Android reports a signature mismatch, stop: uninstalling clears your PIN and settings. For the optional combo, install the Galaxy Island themed APK linked above from Preview 1.
2. Open Samsung Lock D2 and create and confirm your six-digit PIN. This PIN belongs only to D2.
3. Choose a wallpaper. Preview is available after entering your PIN in settings.
4. Double-tap **Double-tap to lock D2** in settings or Preview to open the D2 PIN screen.
5. Tap **Add home-screen double-tap widget**, or add **D2 double-tap lock** from your launcher's widget list. Double-tap that widget within 0.9 seconds. D2 cannot intercept taps on other parts of the launcher. A single widget tap briefly opens a transparent activity to record the tap; it does not lock.
6. Enable **Require PIN to leave D2 (root kiosk)** in authenticated settings, if wanted. Grant D2 root access in KernelSU. Lock D2 and wait for **Kiosk active • PIN required to leave**. Until that appears, Home/Recents are not protected. Preview never starts kiosk mode.
7. To dismiss D2 and release kiosk mode, use your configured Guardian unlock method. PIN/Pattern remains the recovery path; an incorrect credential or Cancel leaves D2 locked.
8. **Fingerprint login:** follow the third-option setup above, then lock D2. Authentication uses Android/Samsung `BIOMETRIC_STRONG`. Canceling the prompt opens the D2 credential fallback; fingerprint mode uses your D2 PIN.
9. **No-root behavior:** after a normal reboot that removes temporary root, expect fingerprint-enhanced Guardian behavior to be unavailable. The explicit Login with fingerprint choice can still request the platform prompt when Android allows it; use your D2 PIN if unavailable. D2 does not change fingerprint enrollment or Samsung's device credential.
10. **Full-reboot behavior:** before Android credential storage is unlocked, Samsung/Android keyguard remains authoritative. Guardian records only a pending restore state and resumes its existing service/fallback path after Android reports the user unlocked. Restore root/LSPosed afterward to return to enhanced integration.

### Fingerprint test states

**Root + integration available:** Guardian glass fingerprint UI + genuine biometric authentication.
**Root unavailable:** the explicit fingerprint option requests the platform biometric prompt when available; otherwise use the D2 PIN. The enhanced rooted integration is unavailable.
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

**@diablOw** — original discovery of the DMC behavior used by D2’s recovery feature.

Copyright (c) 2026 D2 Project and contributors. D2's original software and accompanying project documentation in this revision are released under **AGPL-3.0-only**. See [LICENSE](LICENSE). Third-party components retain their own licenses and notices. The APK includes the full AGPL text, credits and source access under **Legal, privacy & licenses**.

**OneUIX — SoClear and contributors:** [upstream project](https://github.com/SoClear/OneUIX), licensed under AGPL-3.0. The requested Samsung customization work will preserve its upstream notices and record adaptations. The experimental periodic PIN/pattern timeout scheduler control and the requested OneUI feature categories are adapted. See [integration status](docs/ONEUIX-INTEGRATION.md) for scope, unsupported firmware paths and testing requirements.

See [source and build instructions](docs/SOURCE-DISTRIBUTION.md). Distribute a source archive matching each APK revision alongside that APK; source from a later branch head is not a substitute. The license transition applies to this revision and future covered releases, not a claim about older binaries.

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


<!-- rebuild: 2026-09-28 fresh 918 early-boot validation -->


### Fingerprint Center test versus login

**Test Fingerprint** checks whether Android accepts an enrolled strong biometric. It does not unlock D2 or change the selected unlock method. Samsung renders the secure authentication prompt; its visual layout is separate from D2's glass dialogs. Success and non-cancellation error results use the shared D2 glass style. **Login with fingerprint** authenticates on D2's actual lock screen and retains the D2 PIN fallback.


### Unlock the D2 app with fingerprint

When **Login with fingerprint** is selected and a D2 PIN is configured, opening the app requests Samsung's secure **Unlock D2** biometric prompt before showing protected settings. Successful strong-biometric authentication opens the same app session as a correct D2 PIN. **Use D2 PIN**, cancellation, unavailable biometrics and authentication errors use the D2 PIN fallback. A non-matching scan never authorizes settings. Leaving the app revokes authorization, cancels a pending prompt and invalidates late callbacks. An interrupted/cancelled attempt falls back to PIN on return; re-entering after a completed session can request biometrics again.

The Fingerprint Center sensor test stays diagnostic and does not authorize settings. Samsung renders the secure authentication prompt; D2 styles its own guidance and result dialogs.


## Experimental D2-only PIN-panel presentation

With Guardian and kiosk enabled, D2 sends a short-lived visible-surface signal to SystemUI. Supported `StatusBarKeyguardViewManager` PIN-panel show methods defer their presentation only while this signal is fresh and Android reports the user unlocked. Before the first device-credential unlock after reboot, Samsung presentation remains allowed. This hook does not dismiss Android keyguard, satisfy Samsung credentials, or change biometric authentication results.

D2 stops the signal when paused, destroyed, unlocking, or handing off to its biometric prompt. A suppressed Samsung PIN-panel request resumes when the surface is released or its three-second lease expires. Unsupported firmware methods leave stock behavior intact. The heartbeat requires Android 14 or newer and LSPosed SystemUI scope. This is an experimental hook: full lock-screen coverage, wake ordering, crash recovery and compatibility on One UI 9 require device validation. Samsung's underlying keyguard can still appear after D2 unlock; full credential integration is separate.

Diagnostics: `D2_KEYGUARD_PRESENTATION_HOOK_INSTALLED`, `D2_KEYGUARD_PIN_DEFERRED`, `D2_KEYGUARD_PIN_RESTORED`, and `D2_KEYGUARD_PRESENTATION_UNAVAILABLE`.


### Post-boot Samsung lock-screen overlap fix

In enabled Guardian kiosk mode, the real D2 lock activity now uses Android's
`setShowWhenLocked` presentation flag after `UserManager.isUserUnlocked` reports
the first device unlock. The flag is refreshed on resume, screen-on, and user-unlock
events so Samsung keyguard can remain underneath D2 on subsequent lock/wake cycles.
Before that first unlock, and in Preview or disabled/non-kiosk mode, this flag is
off. This does not dismiss keyguard, change Samsung credentials, or authenticate
D2. The genuine biometric prompt and D2 credential fallback remain in use.

Device validation pending: first reboot PIN, repeated lock/wake, D2 fingerprint
and PIN fallback, incoming calls, and leaving D2.

## Experimental periodic Samsung credential check

Tools → System Integration → **Experimental: periodic PIN / pattern check**.
This opt-in/off-by-default option requires root and the D2 System Framework
LSPosed scope. It suppresses only the candidate periodic timeout scheduler for
an unmanaged primary user with Samsung’s normal 72-hour policy. The app reports
this boot’s hook status and suppressed schedule count; installation is not proof
of PIN/pattern behavior on a device. Keep Samsung’s backup credential.

After enabling or disabling, perform one soft reboot and authenticate Samsung
once. Already active timeout requirements are not cleared. Reboot/first-unlock,
lockdown, failure and administrator requirements retain their normal paths.
See [validation priorities](docs/ONEUIX-PORT-VALIDATION.md). This is experimental
and is not yet a recommended alternative to Samsung face recognition.

### Experimental OneUI feature port

Tools → Experimental: OneUI feature port provides categorized configuration for the requested OneUIX hooks. All imported feature switches default off; Apply publishes through LSPosed's private remote-file service and verifies read-back. Select each affected app in D2's LSPosed scope and soft reboot after framework changes. Configuration is distinct from hook installation or device verification. D2 retains the 1074 accessibility and watchdog behavior. See [integration and test matrix](docs/ONEUIX-INTEGRATION.md).
