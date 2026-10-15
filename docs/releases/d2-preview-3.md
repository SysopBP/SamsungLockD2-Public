# D2 Guardian 0.6.4 Preview 3 — Guardian UI, SRP & Setup Refresh

Prepared from successful [Build 1131](https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38727163183), source `b1edbb9fdf1b6734c3398db05683e4b51f59f59f`, on the existing development branch and PR #71. APK: `app.d2lock`, version `0.6.4-d2-guardian`, versionCode 30. This is a draft preview, not a stable release.

![D2 Guardian Home](https://github.com/SysopBP/SamsungLockD2-Public/releases/download/v0.6.4-preview.3/D2-Preview-3-Home.jpg)

## Changes since Preview 2

- Reorganized Guardian Home with full-width Guardian and Kiosk rows, protection/connection status, readiness, security, and consolidated diagnostics.
- Fixed the widget's SRP tap action and layout; removed its 72-hour UI.
- Hidden the 72-hour observer screen and summary from navigation. Internal read-only observations remain; mandatory Samsung authentication policy is unchanged.
- Aligned Guardian Core markers and wrapped text; long status content scrolls in a bounded dialog.
- Added fingerprint retry from the D2 app PIN login and corrected fingerprint action padding, button separation, and icon inset.
- Removed OneUIX feature-port code, compile stubs, settings, and routing. Preserved D2's independent power menu, SRP checks, boot/wake hooks, credential observers, lock screen, watchdog/call policy, widgets, and optional screen-off fallback.
- Reduced the Xposed scope to System Framework, SystemUI, and Samsung Biometrics.
- Retained AMOLED security icons, PIN/pattern/fingerprint choices, theme/wallpaper controls, optional accessibility screen-off, and bounded watchdog reporting.

## D2 power menu and Samsung Recovery Protection

The retained menu includes D2 power off, restart, soft restart, SRP status, Lock D2, and mobile data where supported; Samsung emergency actions remain. Supported orderly power actions use live SRP validation before proceeding. SRP vault read-back and Android authentication are different checks.

Firmware hook availability must be verified on the device. Direct root process kills/property restarts, forced resets, power loss, and emergency shutdown do not gain protection merely because the D2 menu is present. Root-level zygote restart bypasses the orderly guarded framework path.

## Unlock and Samsung handoff

D2 supports its own PIN, pattern, and platform strong-biometric fingerprint login. Keep the independent D2 recovery PIN configured.

The owner reported Samsung face recognition and Extend Unlock / Trusted Places working on SM-S948U1, Android 17 / One UI 9. Samsung face unlock can provide a smoother Samsung-keyguard handoff after D2 PIN, fingerprint, or pattern. In Samsung Face recognition settings, enable Face unlock and turn off **Stay on Lock screen until swipe** if you want direct return to your last screen. This is Samsung authentication; D2-native face recognition is not implemented. Samsung may still require its PIN/pattern/password, especially after reboot or when strong-auth rules require it.

Fingerprint setup: enroll Samsung biometrics and configure Samsung credentials first; retain enrollment, turn Samsung Fingerprint unlock off for this D2 setup, then select D2 Login with fingerprint. Complete Samsung's required first unlock after reboot. Extend Unlock is optional and does not authenticate D2.

## Installation

1. Exit active kiosk using your configured D2 credential, then install the included APK over the existing `app.d2lock` installation.
2. Grant D2 root access and authorize Shizuku when using that route.
3. Enable D2 in the current Xposed scopes: **System Framework, SystemUI, Samsung Biometrics**. OneUIX and unrelated app targets are removed.
4. Reload the relevant processes using your established recovery procedure; do not assume APK installation activates hooks.
5. Check readiness and live SRP status. Test unlock cancellation, recovery PIN/pattern, fingerprint, lock/wake, calls, and widget SRP tap.
6. The optional KernelSU ZIP is a separate bridge test path. It contains no injected privileged APK and does not make D2 UID 1000. Install the APK normally; retain your working setup.

Accessibility screen-off and Device Admin are optional fallbacks. Device Admin requests only force-lock. Neither route unlocks Samsung keyguard.

## Included files

- Signed debug APK from Build 1131, retaining the original signing certificate.
- Optional KernelSU bridge and exact corresponding-source/dependency archives from that build.
- Runtime dependency report and checksums.
- This release/setup guide, the supplied Guardian Home screenshot, and the 5:26 setup video.
- Video admin-app content is blurred except the D2 entry. The release video corrects historical Xposed scope/integration instructions and includes Samsung face-unlock handoff guidance.

## Validation

Build 1131 completed build/lint, unit and emulator runtime checks, APK signing verification, and deliverable packaging. CI confirms those steps completed successfully; it does not establish Samsung firmware behavior or certify every physical-device path. The screenshot shows the owner's installed UI; **System Server observed this boot** is an observation, not proof that every hook or power route is protected. APK build identity and checksums identify this package.

## Credits and licenses

Thanks to **@diablOw** for the original Samsung recovery discovery behind D2 SRP, and the users testing Guardian, calls, fingerprint, boot/wake, and widgets. Historical OneUIX attribution to SoClear and contributors remains recorded where applicable; its feature port is removed. KernelSU, Shizuku, libxposed/LSPosed, Android/SESL, and other dependencies retain their upstream rights and notices.

This revision's D2 source is AGPL-3.0-only. Distribute the exact matching source and included third-party notices with the APK. No Samsung, KernelSU, or upstream-project endorsement is implied.
