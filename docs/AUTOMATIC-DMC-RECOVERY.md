> **User-facing name:** Samsung Recovery Protection (**D2 SRP**). The Samsung vault and technical identifiers remain `DMC`. Menu labels use **D2 SRP status**; longer explanations use Samsung Recovery Protection. These names do not change verification behavior or guarantee recovery.

# Automatic System Server D2 SRP recovery

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

The **D2 SRP → View feature status** dialog shows all four
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

@diablOw originally discovered the Samsung recovery behavior behind this recovery feature.


## Power-action guard (experimental)

The System Framework LSPosed scope installs a guard at `ShutdownThread.beginShutdownSequence(Context)`, after standard confirmation and before service shutdown. This covers normal restart and power off, and safe-mode/recovery/download restart requests **when they reach this method on the device firmware**. It does not replace Samsung's power-menu layout or require OneUIX to be installed. OneUIX's Samsung power-menu implementation informed the investigation; the guard code is independently implemented in D2.

An additional `ActivityManagerService.restart()` hook covers Android's orderly userspace restart. It checks the original `SET_ACTIVITY_WATCHER` permission before deferring the request. Direct root `kill`, init/property-driven restarts, hardware forced restarts, kernel crashes, and battery loss do not necessarily enter these methods and are not covered. Restarting SystemUI alone is not restarting the phone and is not intercepted.

For each covered request, a single background worker uses the same vault lock as boot/setup recovery, validates the record, applies the existing DMC policy, and performs another fresh read. The UI thread is not blocked. The action resumes only if the record matches and verification completes within five seconds. Failure or timeout cancels that request and shows a message; a result arriving after cancellation cannot initiate a delayed power action. Repeated requests are suppressed while a worker is running. A hung vault call leaves the guard busy rather than creating unlimited workers.

Battery and thermal emergency shutdown reasons bypass the check and cancel a pending approval. These must remain immediate. The guard does not hook low-level reboot primitives or disable emergency power-off mechanisms.

Verification is a point-in-time vault read, not a guarantee of firmware durability or protection against a later write by another Samsung service. Unknown vault layouts remain rejected. If a hook is unavailable or protective Xposed exception handling falls back to the framework, shutdown may proceed without the guard. Check hook installation logs before relying on this feature.

### On-device validation still required

With System Framework scope enabled, confirm both `D2_SRP_POWER_HOOK_INSTALLED` entries. Test standard Restart and Power off first, then the exact soft-restart tool being used. Check `D2_SRP_POWER_VERIFIED` before each covered action and read DMC status again after boot. Test failure and timeout cancellation in a controlled test setup before calling the guard validated. Do not test by deliberately enabling unsafe DMC flags on the daily-use device.

Useful log tags: `D2XposedBridge`, `D2DmcPowerGuard`. Outcomes: `D2_SRP_POWER_VERIFIED`, `D2_SRP_POWER_CANCELLED`, `D2_SRP_POWER_DUPLICATE`, `D2_SRP_POWER_HOOK_UNAVAILABLE`, `D2_SRP_POWER_RESUME_FAILED`.


### Failed status versus a missing hook

- **Covered action, failed/unknown check:** cancel that request, keep the phone on, and show the D2 status/retry message.
- **Covered action, five-second timeout:** cancel that request. A later successful vault response cannot trigger a delayed reboot. A still-running vault worker keeps duplicate requests suppressed until it returns.
- **Status dialog only:** run recovery/check and display results. No power action is requested; an unverified result does not globally disable all reboot routes.
- **Shutdown hook absent or unsupported:** standard Samsung power actions may proceed without D2 verification. Protective Xposed fallback can also leave an action unguarded. Do not equate missing hooks with enforced cancellation.
- **D2 soft restart with its guard unavailable:** refuse the D2 request and report the missing guard.
- **Hardware/root bypass or power/thermal emergency:** these routes remain outside enforcement.

After cancellation, open D2 SRP status, resolve the issue, and explicitly retry. This is a pre-action read-back check, not a guarantee of durable firmware persistence or protection against subsequent Samsung writes.
