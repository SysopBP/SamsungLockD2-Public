# Kiosk D2 Guardian — verified Android 17 release baseline

> **Frozen application reference (October 2, 2026):** GitHub Actions **run #985 / 36825757183**, commit `811888c8ab9a45ec05ff0dfc542cc59475806908`, is the current known-good D2 application build. Do not change D2 application code, lock-screen behavior, UI, or the proven boot/token sequence merely to accommodate an external privilege provider. Any TokenX work belongs outside the frozen app unless a future D2 app change is explicitly opened as a separate test.

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
- Xposed bridge mode: `ams_systemReady_plus_wake_observe` (minimal boot handoff plus post-call Android 17 wake observation)
- Separate `samsunglock37_ksunext` / paired KernelSU bridge: **disabled during verification**
- Result: five consecutive successful reboots reported by the tester

## system_server rule
The stable boot handoff remains `ActivityManagerService.systemReady()`. Android executes the real method first. D2 receives a single explicit `BOOT_READY` signal only after the real call returns. The hook uses protective/fail-open behavior and does not alter framework arguments or results.

On September 28, Android 17 boot captures showed that the older `PowerManagerService#wakeUpInternal` target is not present on the SM-S948U1 framework, while `PowerManagerService$BinderService.wakeUp(...)` and `wakeUpWithDisplayId(...)` are present. D2 therefore adds a narrow **post-call observational** wake hook for those actual Android 17 entry points. It emits a deduplicated `WAKE` framework signal after Android completes the wake call; it does not replace, suppress, or modify Android power behavior.

D2 itself is not UID 1000. The Xposed code runs with the identity of the process into which LSPosed injects it; in `system_server`, that process is UID 1000.

## Packaging rule
The APK is delivered separately and installed normally. CI must not place D2 under `/system/priv-app`, request `android.uid.system`, or use a late `pm install` fallback.

The KernelSU ZIP is an **optional diagnostic/startup bridge**. It is not required for the verified stable path and should remain disabled unless that separate path is being tested intentionally.

## Release guardrails
- Preserve the proven `ActivityManagerService.systemReady()` boot handoff.
- Keep the Android 17 wake observer post-call, fail-open, deduplicated, and limited to the verified BinderService wake entry points.
- Do not add keyguard, ATMS, lock-settings, or additional early system_server enforcement hooks to the release baseline.
- Keep experimental behavioral Xposed switches opt-in and test them independently.
- Keep PIN/Pattern as the supported D2 Guardian credentials; D2-only fingerprint unlock remains experimental.
- Verify APK signer, package/version identity, build outputs, and SHA-256 sums before publishing.

## Historical anchors
- Earlier known-good workflow run: 36096182415
- Earlier baseline commit: c3a110c3bd10b2bff697045cba82eea2f6250db5
- Original branch: integration/guardian-watchdog-current

The current release candidate is intentionally derived through reviewable commits rather than wholesale replacement of the source tree.


## September 28, 2026 development update
- Added KernelSU Root Mode and Shizuku checks to initial setup based on the verified rooted-device configuration.
- Fixed Android 17 media-session fallback and added Notification Access to setup so media/live notification features are not silently starved.
- Added the lock-screen idle display-off timer.
- Stopped accidental foreground starts of bound D2 services.
- Reworked the optional KernelSU bridge documentation/packaging so the normal APK + KernelSU Root Mode + LSPosed path remains the primary verified configuration.
- Expanded boot/system-server diagnostics and crash capture while keeping diagnostic hooks separate from release enforcement.
- New boot captures confirmed `ActivityManagerService#systemReady` continues to install and execute successfully.
- Two late-evening captures showed `BOOT_READY` preceding D2's normal locked-boot receiver by roughly four seconds, identifying the remaining handoff window to investigate.
- Android 17 framework enumeration confirmed `PowerManagerService#wakeUpInternal` is unavailable on this build and exposed `PowerManagerService$BinderService.wakeUp(...)` / `wakeUpWithDisplayId(...)` instead.
- Added the narrow BinderService wake observer in commit `73d717b`; the next device build should validate that the signal fires reliably without changing the already-stable boot path.


## September 29, 2026 — token-integrated fast-boot reference

A physical-device boot capture from the current build establishes a new known-good reference for the D2 boot path. Preserve this ordering and token behavior unless a change is being tested specifically against this baseline.

### Measured successful boot
- 11:51:44.788 — system_server emits `BOOT_READY`.
- 11:51:44.799 — D2 requests the early lock-screen launch through the root path.
- 11:51:44.903 — boot launch token enters `PENDING`.
- 11:51:44.904 — early launch is accepted.
- 11:51:45.043 — `LockScreenActivity` is created and the token becomes `CONFIRMED`.
- 11:51:45.047 — D2 reports the lock surface `VISIBLE`.
- 11:51:45.093 — `GUARDIAN_FIRST_DRAW`.
- Measured `BOOT_READY` → first draw: approximately **305 ms**.
- Measured early-launch acceptance → first draw: approximately **189 ms**.

### Late receiver deduplication
The normal Android boot receiver arrived later at 11:51:48.937. Instead of launching a second lock screen, D2 recognized the already-claimed token:

```text
GUARDIAN_BOOT_TOKEN_RECOGNIZED source=boot confirmed=true
GUARDIAN_BOOT_LATE_LAUNCH_SKIPPED token=claimed source=boot
```

This confirms the intended architecture: **system_server BOOT_READY → early root launch → token pending → activity created → token confirmed → visible/first draw → later Android boot receiver recognizes the claimed token and skips its duplicate launch.**

The later activity recreation observed around 11:51:55 was marked `changingConfig=true` and is treated as a configuration recreation, not a duplicate boot launch.

### Baseline rule
Treat this token-integrated fast-boot path as the current known-good boot baseline. Keep current application features and UI; do not roll the application back to older builds. Future boot changes should be compared against this trace and should preserve the early `BOOT_READY` handoff, token confirmation, and late-launch suppression.
