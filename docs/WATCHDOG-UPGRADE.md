# Watchdog upgrade — experimental branch

Main remains Build 1070. This extends the accessibility experiment without
changing D2 PIN/fingerprint authorization or Samsung authentication.

- Foreground service health checks every 15 seconds while interactive and every
  60 seconds while asleep. No wake lock is added; Handler timing is best effort.
- Checks D2 armed/PIN state, cached Root availability, Shizuku connection and
  authorization, accessibility connection/fallback setting, and keyguard handoff.
- Health transitions are logged. Readiness, fallback cards and copied diagnostics
  show the latest check time, monitor state, and last recovery outcome.
- The existing kiosk surface watchdog keeps its 750 ms checks while awake and
  reduces them to 5 seconds asleep. UI repair defers for calls, trusted
  authentication, Samsung keyguard handoff, and screen-off. Verified release
  remains separate from repair.
- Surface watchdog requests are limited to three per 60-second window, at least
  five seconds apart. A rejected/bounded request never authorizes an unlock.
- The existing native RootKiosk lease heartbeat and independent recovery remain
  active. Its separate three-attempt short repair/release safeguard is retained,
  with the same trusted-surface/screen-off UI deferrals.
- A stale old Activity can no longer stop the newer Activity's surface watchdog.
  Exceptions in a health check schedule another check instead of ending the loop.

The periodic monitor reports dependency loss; it does not grant permissions,
auto-enable accessibility, restart Shizuku, kill system_server, or treat a stale
biometric session as an authenticated unlock. These in-process checks cannot
run during an app-process freeze or force-stop. The native lease recovery is a
separate existing mechanism.

Validation: retry-budget unit tests cover cooldown boundaries, exhaustion,
window renewal, and monotonic-clock reset. CI runs the existing unit, lint and
emulator checks. Device testing must include screen-off/wake, incoming calls,
long authentication attempts, Samsung keyguard, verified unlock, and deliberate
integration disconnection. Inspect Readiness → Guardian watchdog after each test.
