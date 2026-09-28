# Kiosk D2 Guardian — verified Android 17 release baseline

This document records the device configuration that passed five consecutive reboot tests. Preserve this architecture while preparing the release; expand privileged/system_server behavior only as isolated, reviewable changes.

## Verified device path
- Device: SM-S948U1
- Android: 17 / One UI 9 test environment
- D2 install: normal user-installed APK under `/data/app`
- D2 APK UID: normal application UID; the APK does **not** require UID 1000
- System Integrations: **KernelSU Root Mode ON**
- ReZygisk: enabled
- LSPosed: enabled
- D2 LSPosed scopes: System Framework (`system`), SystemUI, Samsung biometrics
- Xposed bridge mode: `ams_systemReady_only`
- Separate `samsunglock37_ksunext` / paired KernelSU bridge: **disabled during verification**
- Result: five consecutive successful reboots reported by the tester

## system_server rule
The release baseline hooks only `ActivityManagerService.systemReady()`. Android executes the real method first. D2 receives a single explicit `BOOT_READY` signal only after the real call returns. The hook uses protective/fail-open behavior and does not alter framework arguments or results.

D2 itself is not UID 1000. The Xposed code runs with the identity of the process into which LSPosed injects it; in `system_server`, that process is UID 1000.

## Packaging rule
The APK is delivered separately and installed normally. CI must not place D2 under `/system/priv-app`, request `android.uid.system`, or use a late `pm install` fallback.

The KernelSU ZIP is an **optional diagnostic/startup bridge**. It is not required for the verified stable path and should remain disabled unless that separate path is being tested intentionally.

## Release guardrails
- Preserve the minimal `ams_systemReady_only` implementation for this release.
- Do not add keyguard, ATMS, power, lock-settings, or additional early system_server hooks to the release baseline.
- Keep experimental behavioral Xposed switches opt-in and test them independently.
- Keep PIN/Pattern as the supported D2 Guardian credentials; D2-only fingerprint unlock remains experimental.
- Verify APK signer, package/version identity, build outputs, and SHA-256 sums before publishing.

## Historical anchors
- Earlier known-good workflow run: 36096182415
- Earlier baseline commit: c3a110c3bd10b2bff697045cba82eea2f6250db5
- Original branch: integration/guardian-watchdog-current

The current release candidate is intentionally derived through reviewable commits rather than wholesale replacement of the source tree.
