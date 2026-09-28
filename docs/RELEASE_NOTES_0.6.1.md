# Kiosk D2 Guardian 0.6.1 — Stable Boot RC

This release candidate promotes the Android 17 boot configuration that completed five consecutive reboot tests on the SM-S948U1 test device.

## Highlights
- Normal APK installation — no `/system/priv-app` injection and no UID 1000 requirement for the D2 app.
- KernelSU Root Mode is the verified root path from D2 → System Integrations.
- Minimal LSPosed `system_server` integration using only `ActivityManagerService.systemReady()`.
- Android completes the real `systemReady()` call before D2 receives `BOOT_READY`.
- Fail-open/protective Xposed behavior remains the release baseline.
- Separate KernelSU bridge is now clearly labeled **optional** and was disabled during the verified reboot run.
- System Integration, About, What's New, and What's Next text updated to match the verified architecture.
- Fingerprint integration remains experimental; PIN and Pattern remain the supported Guardian credentials.
- Galaxy Island integration remains in development.

## Verified configuration
Install the D2 APK normally. In D2 System Integrations, enable **KernelSU Root Mode**. Keep LSPosed enabled with D2 scoped to System Framework, SystemUI, and Samsung biometrics. The verified bridge mode is `ams_systemReady_only`.

The optional KernelSU bridge ZIP is not required for this configuration.

## Release caution
This is a root/LSPosed project that changes lock-screen and kiosk behavior. Test recovery access before enabling kiosk protection. Device/firmware differences can change boot behavior.

## Testing credit
Special thanks to **reckaH2281** for continued testing and bug reports.
