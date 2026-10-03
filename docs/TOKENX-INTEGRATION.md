# D2 ↔ TokenX integration boundary

## Purpose

Kiosk D2 Guardian and TokenX solve different problems. D2 owns its independent lock screen, kiosk state, boot presentation, and Guardian unlock flow. TokenX may provide privileged Android services for other apps, but it must not become a prerequisite for D2 to present or unlock its verified lock surface.

## Frozen D2 reference

The known-good D2 application is GitHub Actions run **#985 / 36825757183**, commit `811888c8ab9a45ec05ff0dfc542cc59475806908`.

Preserve from that build:

- the early `ActivityManagerService.systemReady()` → `BOOT_READY` handoff;
- D2's early root launch;
- boot-token `PENDING` → `CONFIRMED` transition when `LockScreenActivity` is created;
- first-presentation behavior and Guardian watchdog;
- late Android boot-receiver deduplication;
- existing lock-screen, kiosk, notification, call, media, gesture, and settings behavior.

## Integration rule

**D2 first; privileged clients second.**

A privileged external service must not use its authority to bypass, dismiss, cover, or race the D2 lock surface during the protected boot window. If an external service needs a D2-aware startup policy, implement that policy in the external service/module rather than modifying the frozen D2 application.

For TokenX specifically:

1. TokenX/system_server provisioning may initialize independently.
2. Privileged client handoff that could expose another app or UI should remain gated while D2 is in its protected locked state.
3. D2 unlock is the release point for D2-aware client activation.
4. Absence of D2 must not permanently block TokenX; the external integration should detect whether D2 is installed/active and fail safely.
5. TokenX failure, stop, restart, or soft reboot must not be treated as authorization to dismiss D2.
6. Do not give the D2 APK UID 1000, move it to `/system/priv-app`, or make D2 depend on TokenX's package identity.
7. Keep any compatibility adapter optional and independently removable.

## Ownership

| Area | Owner |
| --- | --- |
| D2 lock UI and Guardian credential | D2 |
| D2 boot token and duplicate-launch suppression | D2 |
| D2 kiosk/watchdog behavior | D2 |
| TokenX UID/system_server provisioning | TokenX |
| TokenX client binding and app enablement | TokenX |
| D2-aware TokenX startup gate | TokenX / external bridge |
| Failure/recovery of TokenX services | TokenX |

## Validation before calling an integration stable

- Boot repeatedly with D2 locked and confirm D2 remains first usable presentation.
- Confirm D2 unlock releases any D2-aware external gate exactly once.
- Confirm a missing/disabled D2 installation does not strand TokenX.
- Confirm TokenX stop/restart does not unlock or dismiss D2.
- Confirm soft reboot returns to D2 protection before privileged client UI becomes usable.
- Compare D2 boot timing and token logs against the known-good baseline; investigate regressions rather than changing D2 to mask them.

This document is an integration contract, not an instruction to modify the verified D2 application.
