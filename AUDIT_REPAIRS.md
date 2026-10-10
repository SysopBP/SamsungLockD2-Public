# D2 audit repairs — v23

This is an explicitly authorized application repair candidate, not a new device-verified release baseline. The existing systemReady/framework hook implementation is unchanged. The frozen run985 baseline remains the reference for physical-device behavior.

## Implemented

- A real lock request upgrades a reused preview; a protected lock cannot be downgraded to preview.
- Pattern cancellation notifies its owner; destroyed activities cancel their owned fingerprint session and clear trusted-auth state. Trusted-auth handling is consistent during an interactive session.
- Root commands drain output concurrently, retain at most 256 KiB, time out, and execute off the main thread. The terminal captures its selected backend, serializes commands, bounds history, hides stopped sessions, and requires a fresh D2 PIN when resumed. Terminal and authentication windows are protected from screenshots/overlays.
- Early-boot launch requires a mirrored, armed policy. The launch claim is persisted before execution; only real-surface visibility confirms it. An unconfirmed request gets a delayed bounded post-boot fallback. Destroyed service callbacks are removed.
- Backups validate all keys and types before writing. Protected runtime modes are excluded at every profile depth. Versioned backups and new profiles include appearance and launcher icon. Legacy flat backups remain accepted. Stale wallpaper grants are skipped. Previously corrupted preference types are removed and protection is disabled pending review.
- Phone-state permission is optional. When granted, Telecom call state is authoritative; otherwise trusted ongoing call notifications remain the compatibility fallback. Missed/nonongoing notifications cannot suspend protection.
- USB recovery snapshots original global settings and USB functions, uses checked command chains, verifies applied/restored state, and keeps its switch usable after a failure. Older installations without a snapshot must restore original USB settings through Android settings.
- Credential throttling uses elapsed time within the same boot, while retaining bounded persisted delays across reboot. Pattern node indices are validated.
- Sleep commands use explicit SLEEP rather than the POWER toggle and have a timeout. The Shizuku fallback respects its enable preference.
- Automatic updates require a strictly newer comparable version. Unknown tags do not produce downgrade prompts.
- The exported passkey provider is disabled. Saved credentials remain manageable. Re-enable only after caller verification, Digital Asset Links, clientDataHash/origin validation, allow/exclude lists, and supported algorithms are implemented and tested.
- Unimplemented behavioral hook switches are disabled and labeled accordingly. Terminal framework panes describe recorded events/local diagnostics rather than claiming a live command channel.
- JVM tests run in CI. Instrumentation failures propagate their exit status; deliverables are not packaged after a failed gate. Existing recreation test uses the supported Back authentication entry point.

## Still needs implementation or device validation

- Full passkey provider protocol/security work; command execution through a supported Shizuku user service; individual behavioral framework hooks; additional Live Hub sources and renderer integration. These are not represented as completed features.
- System/zygote badges elsewhere in the dashboard still show configuration or historical observations. They are not proof of a live RPC connection; there is no live framework command API.
- Physical Samsung/KernelSU/LSPosed testing: cold boot before CE unlock, preview-to-lock promotion, cancellation/recreation during fingerprint and PIN entry, incoming/outgoing/ended calls and proximity wake, kiosk release, USB rollback, idle sleep/double-tap, and restore on another installation.
- Terminal commands cannot retract root-side effects already accepted before the app stops. Cancellation closes the local process; this is not a transaction rollback.
- Settings storage spans multiple preference files and launcher aliases. Validation is performed before writes, but a storage or package-manager failure can leave a partial restore; review the shown error before arming protection.

## Regression coverage

JVM: shell pipe flooding/truncation/timeouts/failures; strict settings/profile validation; downgrade protection; existing Live Hub ordering.
Instrumentation: preview promotion/no downgrade; pattern cancellation callback; terminal PIN/window protection; import validation before writes; existing PIN/kiosk/call/notification tests.

A passing emulator build does not substitute for the Samsung device checks above.
