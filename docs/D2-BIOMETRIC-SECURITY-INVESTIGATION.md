# Samsung D2 / Biometric Security Investigation

**Test platform:** Samsung SM-S948U1, Android 17 / One UI 9  
**Project:** Kiosk D2 Guardian  
**Status:** Active investigation — 2026-09-26

This document records observed device behavior from the D2 Guardian fingerprint and Samsung secure-lock investigation. Observations are kept separate from hypotheses so that future development does not accidentally treat an inference as a confirmed Samsung implementation detail.

## Why this matters

D2 Guardian exists to provide a usable lock screen while avoiding the Samsung secure-lock configuration associated with the D2/D116 Download Mode recovery problem being investigated on this device.

A fingerprint implementation is therefore only useful to Guardian if it does not defeat that recovery objective. The first Guardian fingerprint prototype used Android's system `BiometricPrompt`. It successfully authenticated the enrolled Samsung fingerprint, but this required Samsung's biometric/security state to remain configured.

## Confirmed baseline: Samsung PIN + fingerprint enabled

The device initially reported:

```text
lockscreen.disabled = 0
fingerprint_screen_lock = 1

LockSettings:
LSKF-based SP protector ID: 9e39f54452b2633c
Secure Mode: 0
SID: 3151c0d03213992e
Quality: 0
CredentialType: PIN
PrimaryAuthFlags: 0

Fingerprint:
sensorId: 0
provider: FingerprintProvider
count: 1
biometricStrength: 15
```

The fingerprint service history contained multiple successful authentications. Guardian was able to authenticate using `BiometricPrompt` with `BIOMETRIC_STRONG`.

Additional Samsung state observed:

```text
knox.kg.state = Completed
ro.boot.kg = 0x4
sys.locksecured = false
vaultkeeper = running
vaultkeeper_aidl = running
```

Important: `sys.locksecured=false` must not be interpreted by itself as proof that no Android secure credential exists. At the same time, LockSettings clearly reported an active PIN, SID, and LSKF-backed synthetic-password protector.

## Experiment 1: disable Samsung "Fingerprint unlock" only

Samsung's **Fingerprint unlock** toggle was turned OFF without deleting the enrolled fingerprint.

Result:

```text
fingerprint_screen_lock = 0

FingerprintProvider:
count: 1
```

This is an important intermediate state:

- Samsung PIN remained configured.
- Fingerprint template remained enrolled.
- Samsung fingerprint lock-screen use was disabled.
- Guardian's existing system `BiometricPrompt` fingerprint path no longer worked in this configuration.

### Finding

For this tested configuration, retaining an enrolled template by itself was not sufficient for the current Guardian fingerprint implementation. The working Guardian `BiometricPrompt` path depended on Samsung's fingerprint-unlock state being enabled.

This does **not** prove that the fingerprint HAL cannot be accessed through another privileged/root path. It only describes the behavior of the current Android framework `BiometricPrompt` implementation.

## Experiment 2: remove Samsung secure lock normally

The Samsung Settings UI was used to change from the secure PIN lock to a non-secure lock. Samsung displayed its normal warning that biometric data would be removed. **Remove data** was confirmed.

No synthetic-password files, Weaver files, biometric files, or LockSettings databases were manually modified.

### Result after removal

```text
lockscreen.disabled = 0
cmd lock_settings get-disabled 0 = false

LockSettings:
LSKF-based SP protector ID: af7b763839d529e5
Secure Mode: 0
SID: 0000000000000000
Quality: 0
CredentialType: NONE
statsCredentialType = 0

Fingerprint:
fingerprint_screen_lock = 0
sensorId: 0
provider: FingerprintProvider
count: 0
```

### Finding

Samsung cleanly transitioned the device from:

```text
PIN + SID + PIN-backed LSKF + 1 enrolled fingerprint
```

to:

```text
CredentialType NONE + zero SID + NONE-state protector + 0 enrolled fingerprints
```

The changed LSKF protector ID is expected evidence that LockSettings transitioned to a different protector state; it should not be interpreted as the PIN protector remaining active.

## Download Mode / D2-D116 result

**NOT YET CONFIRMED.**

At the time this document was written, the device had reached the clean `CredentialType: NONE` / fingerprint `count: 0` state, but the controlled Download Mode test had not yet been completed.

Do not document "removing biometrics fixes D2/D116" as a confirmed project result until that test is performed.

The experiment is intended to answer:

1. Does Download Mode operate normally with Samsung `CredentialType: NONE` and zero enrolled biometrics?
2. If it does, which component was decisive: secure credential state, biometric enrollment, fingerprint-unlock enablement, or a combination?

The experiments so far do **not** isolate those variables completely.

## Guardian fingerprint implementation findings

### What works

With Samsung's secure biometric configuration active:

- Android recognizes the fingerprint sensor.
- The sensor is exposed through `FingerprintProvider`.
- Strong biometric authentication succeeds.
- Guardian receives successful `BiometricPrompt` callbacks.
- Guardian can use that success to enter its unlock path.

### What does not meet the final Guardian goal

The current implementation uses Samsung/Android's secure biometric stack and visible system `BiometricPrompt`.

That creates two architectural problems:

1. Samsung's system biometric surface temporarily owns foreground focus.
2. The working configuration depends on Samsung biometric/security state that Guardian is specifically investigating whether it must avoid for reliable D2/Download Mode recovery.

Therefore the existing `BiometricPrompt` implementation should be considered a **compatibility/prototype path**, not yet the final independent Guardian fingerprint architecture.

## BiometricPrompt / kiosk focus findings

When Guardian launches the system biometric prompt, Samsung's secure biometric UI takes foreground focus. Guardian's kiosk watchdog originally interpreted this intentional focus transfer as a failure and attempted to recover D2 focus.

Guardian now has a trusted biometric handoff state:

```text
GUARDIAN_BIOMETRIC_HANDOFF_BEGIN
GUARDIAN_REASSERT_DEFERRED_AUTH
GUARDIAN_BIOMETRIC_HANDOFF_END reason=...
```

During the trusted handoff, kiosk recovery does not fight the system biometric surface.

On success, Guardian completes its normal unlock flow.

On cancellation/error, Guardian ends trusted authentication and restores D2 focus rather than immediately reopening the Samsung prompt.

## Fingerprint prompt escape / UI work

The Guardian-owned fingerprint glass was changed from a large obstructive card to a compact fingerprint pill. The Guardian pill can be swiped away.

Samsung's actual `BiometricPrompt` is a system-owned secure surface; Guardian cannot simply draw its own close button inside that Samsung UI.

Guardian therefore handles the lifecycle around it:

- System Back / gesture cancellation is treated as intentional dismissal.
- The prompt's PIN/pattern fallback is handled explicitly.
- Intentional dismissal prevents the fingerprint prompt from immediately reopening during that lock session.
- Trusted biometric handoff is ended and D2 focus is restored.

Relevant development sequence:

- **760** — trusted biometric prompt/kiosk handoff
- **765** — compact swipe-dismiss Guardian fingerprint glass
- **766** — safe Samsung biometric prompt dismissal lifecycle
- **767** — Android framework BiometricPrompt compile compatibility fix

## Keyguard / Xposed finding

Separate boot-race logging confirmed that the LSPosed/SystemUI bridge can observe Samsung Keyguard state changes.

Observed signal:

```text
GUARDIAN_XPOSED_KEYGUARD_SIGNAL
state=GOING_AWAY
target=com.android.systemui.statusbar.policy.KeyguardStateControllerImpl#notifyKeyguardGoingAway
```

Android 17 cross-process broadcasts may report `sentFromUid=-1`. Guardian originally rejected that signal. The receiver was changed to rely on its privileged `android.permission.STATUS_BAR` sender gate rather than rejecting a valid SystemUI signal solely because `sentFromUid` was unavailable.

This is relevant to future fingerprint work because LSPosed/SystemUI integration may provide lifecycle and presentation control that ordinary application APIs cannot.

## Current architectural direction

If the clean Samsung `CredentialType: NONE` state is confirmed to restore the desired Download Mode recovery behavior, Guardian should preserve that configuration as the primary recovery-safe baseline.

The next fingerprint research question becomes:

> Can Guardian authenticate against the fingerprint sensor through a root/privileged/LSPosed path without recreating Samsung's secure Keyguard credential and enrolled-biometric state?

Areas to investigate include:

- Fingerprint framework service behavior with no Android enrollment.
- Samsung fingerprint provider/HAL boundaries.
- Whether enrollment itself requires GateKeeper/Weaver/Synthetic Password state.
- Whether a Guardian-owned template is technically possible without modifying Samsung's system biometric database.
- LSPosed hooks that can observe sensor/authentication lifecycle without weakening Android's system security model.
- A recovery-first design that automatically falls back to Guardian PIN/pattern if any fingerprint integration fails.

Do **not** directly edit synthetic-password, Weaver, GateKeeper, or Samsung biometric storage merely to preserve an enrolled template after removing the Samsung credential. The normal Settings transition demonstrated a clean supported state; preserving recoverability is more important than retaining the prototype fingerprint path.

## Next controlled tests

1. Verify Guardian PIN/pattern operates correctly with Samsung `CredentialType: NONE` and fingerprint `count: 0`.
2. Test Samsung Download Mode in exactly that state.
3. Record whether D2/D116 is present.
4. Preserve the result here before changing Samsung security configuration again.
5. If the recovery-safe baseline is confirmed, begin the independent fingerprint/HAL/LSPosed investigation from that known-good state.

## Summary

The most important confirmed result so far is not that "fingerprint causes D2." That has **not** yet been proven.

What has been proven on the test device is:

- Guardian fingerprint works through the normal system biometric stack when Samsung fingerprint unlock is active.
- Turning Samsung Fingerprint unlock OFF while retaining the template caused the current Guardian BiometricPrompt path to stop working.
- Samsung's supported removal flow cleanly removes the PIN credential state and biometric enrollment together.
- The resulting device state is `CredentialType: NONE`, SID zero, `fingerprint_screen_lock=0`, and fingerprint template count zero.
- Whether that clean state eliminates the D2/D116 Download Mode restriction is the next required test.
