# 72-hour PIN re-verification: finished, not just observed

Android's *strong auth* rule asks for your PIN / pattern / password (never a fingerprint)
every 72 hours. `LockSettingsStrongAuth.rescheduleStrongAuthTimeoutAlarm` is where that alarm
is armed. SoClear/OneUIX disables it outright by nulling the call; D2 makes the interval a
choice instead.

Commit `162c7ac5` on `merge/1139-into-main` already carried the observer scaffolding
(`PeriodicCredentialHook`, `PeriodicCredentialPolicy`, `StrongAuthDiagnostics`,
`SchedulerSignature`, tests, docs) but `PeriodicCredentialPolicy.allowed()` always returned
`false`, so nothing ever changed the schedule. This patch finishes it.

## How it works

1. `PeriodicCredentialPolicy` maps the stored choice onto a timeout.
   `0` = stock, `168` = 7 days, `720` = 30 days, `-1` = never (ten-year offset).
2. `PeriodicCredentialHook` rewrites the timeout argument *before* `chain.proceed()`, so
   Android's own field, alarm and bookkeeping stay consistent. `"now"` is distinguished from
   the timeout by distance from `elapsedRealtime()`, because `SchedulerSignature` proves two
   or three `long` arguments with no fixed order.
3. `PeriodicCredentialControl` (app side) mirrors the choice into
   `Settings.Global d2_strong_auth_timeout_hours` with root and pings System Server.
4. System Server reads the mirror on boot and on every scheduler callback, so the value
   cannot drift. Stock is the default: nothing changes until you opt in.

## Verify on device

1. LSPosed: D2 enabled for **System Framework**, then soft reboot.
2. D2 -> Security Center -> **Require PIN again** -> pick 7 days.
3. Unlock once (this triggers a reschedule).
4. Re-open the dialog and confirm `timeouts rewritten` is above zero.

If `schedulerDetail` shows a signature the heuristic mis-reads, that line prints the live
signature; adjust `NOW_TOLERANCE_MS` only if a real timeout ever lands within 5 minutes of
`elapsedRealtime()`.

## Files

Written: `PeriodicCredentialPolicy.java`, `PeriodicCredentialHook.kt`,
`PeriodicCredentialControl.kt`, `PeriodicCredentialSettings.kt`, `StrongAuthPolicyTest.java`.
Edited: `Prefs.kt`, `SettingsSchema.kt`, `GuardianXposedBridge.kt`, `MainActivity.kt`.
