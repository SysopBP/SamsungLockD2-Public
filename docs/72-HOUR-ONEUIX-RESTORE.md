# Consolidated OneUIX periodic credential diagnostics

The prior experimental scheduler bypass is replaced with read-only observation.
The legacy `disablePinVerifyPer72h` key/method is retained only for compatibility.
Every original scheduler invocation runs exactly once; results and exceptions,
credential verification and strong-auth requirements remain authoritative.

Enable the OneUI feature port and Framework observation option, tap Apply and
reboot normally. Installed means registration; a positive `observedSchedules`
count means a completed scheduler callback. `suppressedSchedules` stays zero.
PIN and pattern compatibility require separate physical-device testing.

The widget can display observation count and elapsed time since the last
observed callback. It is not a 72-hour deadline, successful authentication,
or proof that periodic authentication enforcement was disabled.
