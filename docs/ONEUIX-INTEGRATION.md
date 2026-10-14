# OneUIX integration test build

Baseline: successful D2 Build 1074, plus Build 1075 AGPL/source packaging and Build 1076 experimental periodic credential option. Upstream: SoClear/OneUIX revision 591d09c9cc69faf3c8faf258e39869b2df7981f4 (AGPL-3.0); author/contributor credits retained.

## Scope and actual status

| Requested category | Implementation | Device status |
|---|---|---|
| 72-hour PIN/pattern | Existing narrow PeriodicCredentialHook; upstream blanket bypass is not used | Experimental; PIN and pattern each require real timeout tests |
| Calls | Recording, recording button, location, CHN log, call/text other devices | Ported; Samsung app method lookup needs device verification |
| Samsung Settings | Refresh-rate option, outdoor mode, battery/package info, Wi-Fi speed, fonts, auto power, displayed Official status | Ported; not a Knox attestation change |
| Framework | Keep-open count, blockable notification categories, app-jump block, rotations, FCM limit, unplug wake suppression, Writing Toolkit | Ported; firmware checks required |
| SystemUI | Status bar padding, battery size/text/hiding, network layout/threshold, clock format/scale/seconds, folder/Bluetooth/eSIM icons, double tap sleep, notification count, carrier, lockscreen bar | Ported; individual method lookups may be unavailable |
| Quick Settings | Clock style/text size, native 5G support path, footer/data hiding, lunar date and related upstream QS hooks | Ported with upstream OS/OneUI guards. Native 5G path only supports Android 14; unavailable on Android 17 |
| Immersive QS tile | D2 root tile writes policy_control, preserves the previous value when D2 owns the change | Experimental; Samsung may ignore policy_control on newer firmware |
| AOD and notifications | AOD status bar/lunar; ongoing media activity hiding, grouping, auto-expand | Ported; individual firmware checks required |
| Camera | All menu flags plus optional temperature-check suppression; DexKit discovery/cache | Ported; not verified on Samsung camera APK |
| Notes / Messages / Health Monitor | Feature gates, message blocking, country check | Ported; server/region/hardware support is not guaranteed |
| Custom power menu | Existing D2 SRP-guarded actions retained | Upstream power menu is deliberately not installed in parallel |
| Framework panel screenshot | Optional after-call sleep, keyguard-show and activity-resume observers; diagnostics-only receiver policy | Implemented for testing; no boot/wake baseline replaced |
| Remaining framework/Galaxy Island panel switches | Home/Recents, gestures, native notifications/media/calls, QS guard, recovery, Island positioning/sync/fallback | Still disabled; separate implementation and validation needed |

## Adaptations

- Selected upstream hooks/common/util are retained in oneui-port; compile-only platform stubs in oneui-stub are excluded from the APK.
- Kotlin context parameters and serialization use D2's Kotlin/Java 17 toolchain instead of changing D2 to upstream Java 25.
- Every imported interceptor uses libxposed PROTECTIVE exception handling. Unsupported signatures keep the original behavior and are logged; installation is not proof of effect.
- All imported Boolean feature defaults are off. The private remote-file configuration requires an explicit master opt-in. No external writable preference provider is added.
- D2's existing power guard, credential hook, accessibility fallback, boot handoff and watchdog remain separate. No unguarded upstream restart actions or broad periodic authentication hook are routed.
- Original camera/Call/Health Monitor DexKit discovery and versioned caches remain. DexKit and Kotlin serialization are runtime dependencies; CI includes the resolved dependency report with deliverables.
- The framework observers change no arguments, results, or authentication state. Signals are debounced and dispatched off the system_server callback. Safe diagnostics defaults to recording only; explicit Immediate relock can use the existing Guardian reassert path after user unlock.
- Galaxy Island remains optional. Its disabled native integration controls have not been silently activated.

## Device acceptance

1. Install over Build 1074; accessibility service must remain in the APK component list. Confirm its saved opt-in, Shizuku-first screen off and watchdog health still work.
2. With imported master OFF, perform cold boot, soft reboot, lock/wake, fingerprint/PIN recovery and incoming-call tests. Behavior must match 1074.
3. Enable D2 in LSPosed and select only the affected scopes. Apply config and confirm read-back, then soft reboot once. Test one category at a time; collect [OneUIX] and D2Framework logs plus app behavior.
4. Turn master OFF, Apply, then soft reboot/restart affected processes. Existing process hooks need process restart to restore stock behavior.
5. Test optional framework diagnostics with safe mode ON. Confirm sleep/keyguard/resume timeline entries, no relock loops, and no notification content in logs. Disable safe mode only for isolated Immediate relock tests; verify call/emergency/authenticated surfaces still yield through Guardian's existing guards.
6. Follow ONEUIX-PORT-VALIDATION.md for PIN and pattern separately, face ON/OFF, real timeout, managed-device guard, boot/lockdown and failed biometric requirements. No recommendation is made before these tests.

CI can verify compilation, lint and generic emulator tests; it cannot verify Samsung firmware hooks. Record each item as unsupported, installed, behavior verified, or failed rather than treating a saved switch as success.
