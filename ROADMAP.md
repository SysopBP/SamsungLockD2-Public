# Kiosk D2 Guardian Roadmap

This roadmap tracks the reliability, protection, recovery, diagnostics, and stabilization work for Kiosk D2 Guardian.

## Phase 1 — Guardian Reliability

- [ ] Complete active call → Home → automatic D2 relocation.
- [ ] Restore D2 after power-button screen OFF → ON during an active call.
- [ ] Restore D2 after Samsung double-tap sleep/wake during an active call.
- [ ] Confirm incoming and outgoing calls never cause a Guardian lockout.
- [ ] Reassert D2 cleanly when a call ends.
- [ ] Verify boot protection consistently restores Guardian.
- [ ] Keep the D2 home-screen widget as a reliable manual relocation fallback.
- [ ] Expand GUARDIAN_* logging for recovery decisions and skipped reassertions.

**Goal:** When Guardian is armed, no ordinary transition should leave Home exposed unintentionally.

## Phase 2 — Guardian Watchdog

- [ ] Introduce a central Guardian health/recovery watchdog.
- [ ] Detect when the protected D2 surface disappears unexpectedly.
- [ ] Automatically recover protection when policy says D2 should be visible.
- [ ] Add reassert cooldown/debounce protection to prevent activity-launch loops.
- [ ] Track protection states such as:
  - PROTECTED
  - TRUSTED_UI
  - TEMPORARILY_RELEASED
  - REASSERTING
- [ ] Record the reason for each state transition.

**Goal:** Guardian should recover from unexpected surface loss instead of relying only on individual Android events.

## Phase 3 — Trusted Surface Engine

- [ ] Centralize trusted temporary UI handling.
- [ ] Treat the Samsung Phone/InCall UI as a trusted call surface.
- [ ] Support approved authentication/passkey surfaces.
- [ ] Support explicitly allowed camera/emergency surfaces where configured.
- [ ] Reassert Guardian when a trusted surface exits to Home/Launcher.
- [ ] Return cleanly to D2 after trusted UI completes.

Example policy:

- D2 → Phone → Phone = allow.
- D2 → Phone → Home = reassert.
- D2 → approved authentication UI → D2 = allow/recover.

**Goal:** Guardian understands *why* D2 lost foreground rather than blindly reacting to every focus change.

## Phase 4 — Escape Resistance & Regression Suite

Build a repeatable Guardian Escape Test Suite covering:

- [ ] Home.
- [ ] Recents.
- [ ] Split screen.
- [ ] Pop-up/multi-window.
- [ ] Notification interactions.
- [ ] Settings launches.
- [ ] Assistant/voice actions.
- [ ] External intents/deep links.
- [ ] Rotation/configuration changes.
- [ ] D2 crash/restart.
- [ ] Process kill.
- [ ] Screen OFF/ON.
- [ ] Rapid button/event sequences.
- [ ] Active-call transitions.
- [ ] Call-end transitions.

**Goal:** Previously fixed escape paths should remain fixed after future development.

## Phase 5 — Recovery & Fail-Safe

- [ ] Add a controlled owner recovery path.
- [ ] Recover cleanly from Guardian service crashes.
- [ ] Harden boot recovery.
- [ ] Handle invalid/corrupted Guardian state safely.
- [ ] Handle temporary loss of root/root companion gracefully.
- [ ] Prevent recovery logic from trapping the legitimate owner in a reassert loop.
- [ ] Surface clear diagnostics when Guardian cannot establish protection.

**Goal:** Strong kiosk behavior without creating unrecoverable owner lockouts.

## Phase 6 — Security Architecture Cleanup

Move protection decisions toward a central architecture:

GuardianController → State → TrustedSurface → ReassertPolicy → Recovery

- [ ] Centralize Guardian state.
- [ ] Centralize reassert decisions.
- [ ] Centralize trusted-surface decisions.
- [ ] Consolidate boot, wake, call, focus, and recovery policy.
- [ ] Reduce duplicated activity-launch logic.
- [ ] Keep UI components responsible for presentation rather than security policy.

**Goal:** One understandable protection engine instead of independent security decisions spread throughout the app.

## Phase 7 — Guardian Diagnostics & Self-Test

Add an in-app Guardian diagnostics screen showing items such as:

- Guardian status.
- Kiosk state.
- Pattern protection state.
- Root companion health.
- Boot recovery readiness.
- Wake recovery readiness.
- Call protection readiness.
- Current trusted surface.
- Last reassert time.
- Last reassert reason.
- Last recovery failure.

- [ ] Add a **Run Guardian Self-Test** action.
- [ ] Produce a concise diagnostic report suitable for bug reports.
- [ ] Keep detailed GUARDIAN_* logcat markers for development.

**Goal:** Common Guardian problems should be diagnosable without manually digging through large logcat dumps.

## Phase 8 — Beta Stabilization & Polish

After the protection engine is stable:

- [ ] UI/animation polish.
- [ ] Glass styling refinements.
- [ ] Pattern-screen refinements.
- [ ] Accessibility review.
- [ ] Global weather improvements.
- [ ] Widget improvements.
- [ ] Galaxy Island integration/polish.
- [ ] Performance and battery optimization.
- [ ] Settings cleanup.
- [ ] Temporary feature freeze.
- [ ] Full regression cycle before the next public beta.

**Goal:** Stabilize the complete experience before promoting a new public beta.

---

## Current Priority

1. Finish PR #51 call/wake recovery.
2. Run call/wake regression testing.
3. Build Guardian Watchdog.
4. Build Trusted Surface Engine.
5. Establish Guardian Escape Test Suite.
6. Continue recovery, architecture, diagnostics, and beta stabilization phases.
