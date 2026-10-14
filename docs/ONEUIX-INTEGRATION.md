# OneUIX integration test build

Baseline: successful GitHub Actions run 38598510630 (build 1090), commit db294a12d035827ce625fdfc79809f9ac4320df4. Checked for newer successful runs on October 14, 2026 before continuing; none existed. Upstream OneUIX author/contributor credits and AGPL-3.0 terms are retained.

## Scope and actual status

| Requested category | Implementation | Device status |
|---|---|---|
| 72-hour PIN/pattern | Read-only PeriodicCredentialHook; all scheduler suppression removed | Separate PIN/pattern verification pending; unsupported signatures retain stock behavior |
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
- D2's existing power guard, read-only credential diagnostics, accessibility fallback, boot handoff and watchdog remain separate. No unguarded upstream restart actions or broad periodic authentication hook are routed.
- Original camera/Call/Health Monitor DexKit discovery and versioned caches remain. DexKit and Kotlin serialization are runtime dependencies; CI includes the resolved dependency report with deliverables.
- The framework observers change no arguments, results, or authentication state. Signals are debounced and dispatched off the system_server callback. Safe diagnostics defaults to recording only; explicit Immediate relock can use the existing Guardian reassert path after user unlock.
- Galaxy Island remains optional. Its disabled native integration controls have not been silently activated.

## Device acceptance

1. Install over successful build 38598510630 (1090); accessibility service must remain in the APK component list. Confirm its saved opt-in, Shizuku-first screen off and watchdog health still work.
2. With imported master OFF, perform cold boot, soft reboot, lock/wake, fingerprint/PIN recovery and incoming-call tests. Existing working behavior must match build 1090.
3. Enable D2 in LSPosed and select only the affected scopes. Apply config and confirm read-back, then soft reboot once. Test one category at a time; collect [OneUIX] and D2Framework logs plus app behavior.
4. Turn master OFF, Apply, then soft reboot/restart affected processes. Existing process hooks need process restart to restore stock behavior.
5. Test optional framework diagnostics with safe mode ON. Confirm sleep/keyguard/resume timeline entries, no relock loops, and no notification content in logs. Disable safe mode only for isolated Immediate relock tests; verify call/emergency/authenticated surfaces still yield through Guardian's existing guards.
6. Follow ONEUIX-PORT-VALIDATION.md for PIN and pattern separately, face ON/OFF, real timeout, managed-device guard, boot/lockdown and failed biometric requirements. No recommendation is made before these tests.

CI can verify compilation, lint and generic emulator tests; it cannot verify Samsung firmware hooks. Record each item as unsupported, installed, behavior verified, or failed rather than treating a saved switch as success.

## Complete upstream feature expansion

The current upstream HEAD was checked at b8f81448b2bcaa314c0cb8abec0c8ff527a14e27 on October 14, 2026. The new ChargingLimitItem is adapted into D2's root-assisted 20–100% charging-limit panel with settings read-back. Additional hook routing covers Browser, Calendar, Dual App, Gallery, Samsung Home, Photo Retouching, SketchBook, Samsung Device Care CN, Galaxy Store, Weather, Video, Watch Manager, SPen translation and optional FLAG_SECURE screenshot controls. Feature defaults remain off.

Paid Theme Center trial-expiry bypass is excluded. D2's read-only periodic credential diagnostics replace its prior opt-out; the upstream scheduler bypass is removed. Power-menu order/visibility is exposed through D2's existing Samsung-hosted actions; SystemUI, recovery and download restart actions require confirmation and fresh SRP verification. Emergency call and medical information are always preserved; unsupported action reconstruction leaves the original menu intact. SystemUI restart is only available for the expected same-UID SystemUI process, never system_server.

The root D2 5G QS tile supports the active data SIM through a separate UID-0 app_process worker. It changes only the user network-type NR bit, reads back that policy, and preserves other RAT flags. It is an experimental replacement for the upstream native 5G hook's Android-14-only path; carrier connection to 5G is not guaranteed. D2 immersive mode remains an explicit QS tile.

Feature panels now use shared rounded glass surfaces, category search and separate help. Validate normal/large font and long labels on Samsung. Configuration/read-back and hook installation remain distinct from behavior verification. All additional Samsung app hooks require their matching LSPosed scopes and app/process restart.
