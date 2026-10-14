# OneUIX 72-hour scheduler restoration — October 14, 2026

Device diagnostic: Android 17 S948U1UEU4ZZHK, system_server PID 8329, boot 664;
read-only scheduler unavailable, reason observer installed, credential type 3
(PIN), flags 0. This establishes a registration failure, not its exact cause.

Compared upstream SoClear/OneUIX b2466b689c358eed1389979f89e2958e21dd91cb:
`hook/.../Android.kt` selects all declared methods by scheduler name and returns
null; `Main.kt` enables it from remote configuration at system_server startup.
D2 removed both the call and implementation in e500498 and hid its setting.

Restore this same configuration and hook route. Add state, discovered signatures
and suppression counts to the existing diagnostic snapshot. Keep separate
read-only observers; do not interpret their zero callbacks as failure when
another interceptor skips execution. Existing call/hook fixes remain intact.
Upstream attribution and AGPL source packaging are retained.

Install the resulting APK, enable D2's System Framework scope, enable the OneUI
port and Framework 72-hour option, Apply, soft reboot, and authenticate Samsung
once. Read `d2_periodic_credential_hook_status` after locking/unlocking. Expected:
`oneuiPeriodic.state=installed`, nonempty methods, and eventually a positive
suppressedSchedules count. Pattern requires a separate test after configuring it
normally. No claim of elapsed 72-hour verification is made by this source change.
