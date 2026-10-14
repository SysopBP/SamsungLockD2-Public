# OneUIX port validation priorities

User priority recorded October 14, 2026: test the periodic Samsung credential
prompt control before recommending it. Validate PIN and pattern separately.

## Periodic credential timeout

OneUIX reference revision 591d09c9cc69faf3c8faf258e39869b2df7981f4 targets
LockSettingsStrongAuth.rescheduleStrongAuthTimeoutAlarm in system_server.
This operates on the primary-auth timeout scheduler, rather than a PIN entry
widget. Pattern and password may therefore share the same timeout path; this
is an inference, not a Samsung Android 17 test result.

Proposed label: Periodic PIN / pattern check (experimental).
Proposed help: Reduces periodic requests for Samsung's backup credential while
using D2 fingerprint. Consider only after validating it on this firmware.
Samsung face recognition is a separate option and has its own timeout behavior.

Do not present absence of face recognition as proof the setting is required or
recommend the setting until PIN and pattern tests pass. Keep it opt-in/off by
default and explain that it reduces a periodic authentication safeguard.

## Required tests

- Baseline PIN with hook disabled, then enabled; distinguish periodic timeout
  from reboot, lockdown and failed-attempt reasons.
- Repeat with Samsung pattern as the primary credential.
- Test D2 fingerprint with Samsung face recognition disabled and enabled.
- Confirm target method/signature exists on Android 17 / One UI 9; record
  installed, missing and failed states instead of treating a switch as proof.
- Confirm behavior for an already scheduled timeout and for an already active
  timeout requirement; scheduler suppression alone may not clear either.
- Preserve reboot/first-unlock, lockdown, failed-attempt and device-policy
  requirements. Do not clear strong-auth flags indiscriminately.
- Disable the option, soft reboot and verify normal timeout scheduling returns.
- Compare Samsung keyguard behavior with D2's separate PIN and biometric state.

A shortened controlled timeout test can validate the targeted scheduler path;
retain a real periodic-duration observation before claiming a verified 72-hour
result. Do not change the device's clock as a substitute for elapsed-realtime
alarm verification.
