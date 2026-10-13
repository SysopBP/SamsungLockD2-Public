# Automatic System Server DMC recovery

D2 automatically targets these Samsung DMC vault states:

| Feature | Target |
| --- | --- |
| Screen-lock flag | Disabled |
| Maintenance Mode flag | Disabled |
| Download Mode AT Command (`AT+SUDDLMOD`) flag | Enabled |
| Download Mode eligibility derived from DMC flags | Allowed |

There is no enable switch or Fix Now requirement. Recovery runs inside Xposed
System Server (UID 1000). Enable D2 in LSPosed for System Framework and reboot.
There is no root shell or app_process fallback. Only the primary user's DMC
policy is supported. Guardian boot handoff remains independent.

## Credential and biometric setup detection

Post-call observers watch successful `LockSettingsService.setLockCredential`
and `setLockCredentialWithToken` operations (PIN, pattern, password changes or
removal), plus `FingerprintUtils` and `FaceUtils` enrollment additions/removals.
Rejected credential changes do not refresh recovery. No credentials, tokens,
biometric templates, or hook arguments are inspected, stored, or logged.

Setup bursts are coalesced into a check 2 seconds after the last change, with a
second check at 12 seconds to catch delayed Samsung DMC updates. Both use the
existing guarded recovery and full read-back verification; matching records are
not rewritten. The app also receives a refresh signal to update its cached
feature status, bypassing the ordinary startup cooldown. Changes in another
profile can trigger a check, but recovery still applies only to the primary
user. Authentication attempts and template renames are not observed.

Hook installation failures are isolated and logged as
`DMC_SETUP_OBSERVER_*`/`DMC_SETUP_CLASS_UNAVAILABLE`; they never block Android
setup. Samsung firmware may use different methods, so on-device validation is
required. Existing boot/app checks remain available if a setup hook is absent.
`DMC_SECURITY_SETUP_CHANGED` records detection, and
`DMC_SYSTEM_SERVER_RECOVERY_VERIFIED` records successful vault read-back.

Failed checks have three bounded retries after 5, 15, and 30 seconds while the app
process remains alive. Subsequent startup events can try again. Installing a new
app does not itself deliver a boot broadcast, and Android suppresses broadcasts
to a never-opened/force-stopped app; open D2 once after initial installation.

The **Automatic DMC Recovery → View feature status** dialog shows all four
observed states, targets, verification result, and check time. It updates while
open. Unknown, denied, unsupported, and timed-out states never count as verified.
Stored status is scoped to Android's boot count.

## Backend and limits

The supplied `JBCompanion_1.0_src.apk` reads Samsung
`VaultKeeperManager.getInstance("DMC").read(1)`. It expects a 32-byte record and
labels bytes 0, 1, and 2 as screen lock, Maintenance Mode, and AT-command flags.
Its Fix Now/boot action changes only byte 2 to 1. D2's independently implemented
policy changes the requested three flags to 0, 0, 1, preserves the other 29
bytes, validates the record and boolean flag values, and requires successful
write plus a full matching read-back. Correct records are not rewritten.

Download Mode status follows JBCompanion's flag-derived rule; it is not a
physical bootloader/Odin test or a separate fourth writable flag. The helper
does not execute the AT command or reboot into Download Mode. It does not erase
Android passwords, PINs, patterns, or biometric enrollment, and it does not
start/stop the actual Samsung Maintenance Mode session. The panel reports the
corresponding DMC vault flags, separately from D2 authentication.

System Server identity does not guarantee Samsung VaultKeeper access on every
firmware. Unknown, denied, unsupported, and failed reads remain unverified.
The observer only refreshes DMC flags; it does not modify Android credentials
or enrollments. Validate PIN replacement, fingerprint add/remove, and face
add/remove on the target phone before claiming firmware compatibility.

## Validation

Unit tests cover preserving unrelated vault bytes, idempotence, malformed and
unknown records, write rejection, ineffective writes, read-back mismatch, and
all eight combinations of the three DMC flags. An emulator cannot validate
Samsung VaultKeeper permissions or bootloader eligibility.

## Discovery credit

@diablOw originally discovered the DMC behavior behind this recovery feature.
