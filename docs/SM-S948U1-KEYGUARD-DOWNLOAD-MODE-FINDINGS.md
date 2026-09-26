# SM-S948U1 D2 / Keyguard / Download Mode Findings

_Last updated: 2026-09-26_

## Test device

- Samsung SM-S948U1
- Android 17 / One UI 9 test environment
- Kiosk D2 Guardian development branch: `build/743-biometric-handoff-diagnostics`
- Temporary KernelSU/root workflow with LSPosed integration under active development

## Lock / synthetic-password state observed

Post-test shell inspection reported:

```text
lockscreen.disabled = 0
cmd lock_settings get-disabled 0 = false
LSKF-based SP protector ID = af7b763839d529e5
Secure Mode = 0
SID = 0000000000000000
Quality = 0
CredentialType = NONE
fingerprint_screen_lock = 0
Fingerprint sensorId = 0
```

### Interpretation

Android/Samsung reports the lock screen itself as enabled, while the current user has no framework credential (`CredentialType: NONE`, `Quality: 0`) and no active SID. This is the important distinction for D2-only lock-screen testing: framework keyguard state can remain enabled even when no Samsung PIN/pattern credential is enrolled.

The fingerprint HAL/provider remains present independently of `fingerprint_screen_lock`, so sensor/provider discovery must not be treated as proof that Samsung fingerprint screen-lock authentication is currently enabled.

## Download Mode recovery test — PASS

A real Download Mode recovery cycle was tested successfully on 2026-09-26:

1. Device began in the current rooted/D2 development configuration.
2. Device successfully entered Samsung Download Mode.
3. Device successfully exited Download Mode and booted normally.
4. Temporary root was lost as expected by the test workflow.
5. Root was subsequently restored successfully.
6. Device returned to the development environment without losing the recovery path.

### Result

**PASS:** Download Mode remains reachable and normal boot/root restoration remains viable with the current D2 development configuration.

This confirms a practical recovery path on the SM-S948U1 test device. It does not imply that every future D2/LSPosed/root change is safe; Download Mode should be revalidated after changes that modify boot, keyguard, SystemUI, or low-level recovery behavior.

## Fingerprint / keyguard investigation notes

- Samsung's biometric framework and fingerprint provider can remain discoverable while Samsung fingerprint screen-lock state is disabled.
- D2 biometric work should distinguish:
  - fingerprint HAL/provider availability,
  - enrolled biometric templates,
  - Samsung keyguard credential state,
  - `fingerprint_screen_lock`,
  - D2's own authentication presentation/handoff.
- Avoid using one of those signals as a proxy for all the others.
- The end goal remains D2 as the user-facing lock surface while retaining a reliable recovery path.

## Build 801 / 802

### Run 801 — failed

- Run: 801
- Commit message: `766: make Samsung biometric prompt safely dismissible`
- Commit: `6c1be5c0f3d373f8cd2f249ba420a61448d09c6d`
- Result: **FAILURE**

Run 801 should remain recorded as the failed build rather than being rewritten as successful.

### Run 802 — fix is green

- Run: 802
- Commit message: `767: fix biometric dismissal compile error`
- Commit: `ec6c0cf9c0adc27bc812d6ed0aa4c6ee71998c91`
- Result: **SUCCESS**

Run 802 is the build-level fix for the compile failure introduced in 801. Runtime behavior of the biometric dismissal path should still be tested on-device before that behavior is considered fully verified.

## Current checkpoint

At this checkpoint:

- Download Mode entry: **PASS**
- Normal boot after Download Mode: **PASS**
- Root restoration: **PASS**
- Run 801: **FAIL (superseded by build fix)**
- Run 802: **BUILD PASS**
- Biometric dismissal runtime test: **pending device verification**
