> Updated October 14: the opt-in upstream scheduler option is restored. See
> [restoration and device test](72-HOUR-ONEUIX-RESTORE.md). Earlier read-only-only
> descriptions below document the preceding build, not the restored option.

# Samsung strong-auth validation

Baseline: Actions 38598510630 (build 1090), db294a12d035827ce625fdfc79809f9ac4320df4.
The build was checked against newer successful runs before development. Do not
revert to build 1074.

## Reference and policy

Reviewed SoClear/OneUIX Android.kt at 591d09c9cc69faf3c8faf258e39869b2df7981f4:
disablePinVerifyPer72h hooks all methods named rescheduleStrongAuthTimeoutAlarm
and returns null. This suppresses mandatory primary-auth timeout scheduling;
it is not PIN verification and does not make D2 biometrics equivalent to a
Samsung credential. D2 now preserves every original call and ignores the old
opt-out setting. The unused imported bypass is removed.

Diagnostics probe exact signatures, record registration separately from
callback counts, and query only user-0 credential type and strong-auth flags on
a separate worker. Request reasons are not represented as current flags.
Unsupported APIs produce unavailable status. Unknown/OEM flags remain visible.
No passwords, patterns, PINs, tokens, caller names or phone numbers are logged.

## Device tests — repeat separately for Samsung PIN and Samsung pattern

1. Install the new APK over build 1090 using the matching signed CI artifact.
   Keep Samsung's credential and root-manager module-disable recovery available.
2. With imported OneUI master OFF, perform an ordinary reboot. Unlock Samsung
   normally, then check D2 PIN/pattern, fingerprint, launcher, accessibility,
   wake/lock, power actions and watchdog status. Do not kill system_server.
3. In Tools → System Integration → 72-hour PIN / pattern diagnostics, record
   scheduler/reason observer registration, callback counts, credential type,
   last-request reason, last-sampled flags and sample age.
   Unavailable or installed with zero callbacks is not verified behavior.
4. Exercise ordinary unlock and Settings' existing lockdown control. Confirm
   Samsung still demands its credential after reboot/lockdown as appropriate.
   Do not force lockout or modify alarms, timeouts, device policy or device time.
5. Observe the real primary-auth timeout on the device. A timeout flag (0x10)
   differs from after-boot (0x1), policy (0x2), lockout (0x8) and lockdown (0x20).
   Samsung face recognition has separate restrictions; repeat face OFF/ON if used.
6. Repeat with Samsung pattern, then restore the preferred credential. Record
   PIN and pattern results separately; neither is certified by compilation.

## Calls and recovery

- Test incoming calls from another phone with the screen off, Samsung keyguard
  showing, D2 showing, and D2 fingerprint/PIN UI open. Test answer/reject,
  proximity near/far, voicemail and a missed call. Repeat with phone-state
  permission denied to exercise the notification fallback.
- Confirm D2 does not sleep or reassert over a trusted active call. After the
  call ends, confirm normal D2 lock/wake and recovery resume without relock loops.
- Enable OneUI call recording/location independently. On unsupported Dialer
  constructors, location enhancement must stay unavailable with stock call UI
  intact; other features should continue. Do not place test emergency calls.
- Validate cold boot recovery and preserved working UI/launcher paths. Capture
  full unfiltered LSPosed/logcat output around a failure; filtered logs can omit
  the exception message. Disable D2 scope/module via the established root
  recovery path if boot problems arise; do not kill system_server.

CI checks compilation, unit tests, lint, generic emulator runtime tests, signing
and source packaging. It cannot validate Samsung/One UI hook availability,
a real 72-hour elapsed alarm, proximity hardware or the reported black screen.
