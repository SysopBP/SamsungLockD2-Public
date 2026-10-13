# D2 Guardian 0.6.4 Preview — Samsung Recovery Protection & Fingerprint Login

Prepared October 13, 2026. Release copy and findings; publication pending.
Suggested tag: `v0.6.4-preview.1` (confirm availability before creating).
Current source version: `0.6.4-srp-fingerprint-login`, versionCode 29.
Reviewed source head: `e75a33a2ab94932c3ee74c13f595e923ecf307a5`.

## Important fingerprint finding

**D2 fingerprint authentication currently does not complete Samsung device unlock. Samsung's PIN is still required afterward in the latest device test.** The reported sequence is D2 fingerprint → Samsung-unlock prompt → Samsung PIN, with an additional D2 screen sometimes appearing during handoff. This remains a known limitation for this preview, both during the boot flow and subsequent locking tests. Further development may improve the handoff; a way to eliminate the additional Samsung PIN has not been established.

D2's PIN/pattern, D2 biometric authorization, and Samsung's device credential are separate. Keep the Samsung PIN available. The first device-credential unlock after reboot remains required. Presentation hooks do not satisfy Android keyguard authentication or remove credentials.

## What's new

### Samsung Recovery Protection — D2 SRP

D2 SRP is the short name for Samsung Recovery Protection. Release explanations and feature labels use D2 SRP or Samsung Recovery Protection.

Recovery runs within the Xposed System Server context (UID 1000), rather than through a root shell or app_process fallback. The primary-user vault targets are:

| Target | Intended state |
| --- | --- |
| SRP screen-lock flag | Disabled |
| SRP Maintenance Mode flag | Disabled |
| Download Mode AT-command flag | Enabled |
| Derived Download Mode eligibility | Allowed |

These are Samsung vault flags, separate from Android PIN/password/pattern credentials and biometric enrollment. Recovery applies the targets and requires successful read-back before reporting verification. Completed credential changes and biometric enrollment changes schedule a refresh after two seconds and another check after twelve seconds to account for delayed Samsung updates. Firmware-specific setup detection still needs wider validation.

### Guarded power actions

Supported System Framework hooks check the vault before confirmed normal power-off and restart. Orderly ActivityManager soft restart has a separate guard. Fresh validation, target enforcement, and a second read occur before proceeding.

- Failed or unknown verification cancels the covered request and leaves the phone on.
- A five-second timeout cancels the request; late success cannot trigger a delayed restart.
- Duplicate requests are suppressed.
- Opening SRP status checks/recovery never requests a restart or shutdown by itself.

A missing or unsupported shutdown hook differs from a failed verification: stock Samsung actions may proceed without a check when the guard is not installed. D2's own soft-restart request is refused if its guard is unavailable.

Direct root zygote restarts, process kills, init/property restarts, forced hardware resets and power loss are outside this protection. Battery/thermal emergency shutdown remains immediate. Recovery Center soft restart now uses the guarded System Server route; Restart SystemUI requires a fresh SRP check before its root action. Other apps and terminals can still restart zygote independently.

A successful vault read is a point-in-time result, not proof of durable firmware persistence or protection against later Samsung writes. D2 does not guarantee recovery from every D2/Download Mode condition.

### Samsung power-menu integration

The experimental menu integration adds D2 power off, D2 restart, D2 soft restart and D2 SRP status while retaining Samsung action models, confirmations and unrelated emergency/lockdown actions. Soft restart and status receive matching icons. Status response identity is checked, with a six-second response timeout.

Recent patches address the SRP status dialog appearing behind the power menu, including matching its window layer. The supplied device capture now shows the SRP status dialog in the foreground over the lock-screen background. This confirms that captured presentation, while other menu states still need validation. Unsupported Samsung APIs leave the standard menu available. This is an action integration within Samsung's menu, not a complete replacement layout.

### Fingerprint login and app authorization

Selecting Login with fingerprint also allows strong-biometric authentication into the D2 app and protected settings. Use D2 PIN remains the fallback for cancellation, unavailable biometrics and authentication errors. A non-matching scan never authorizes settings. Leaving the app revokes authorization and invalidates pending callbacks.

Fingerprint Center's Test Fingerprint remains a sensor diagnostic and does not authorize settings or unlock D2. The secure biometric prompt is rendered by Samsung.

Recent patches make unlock gestures respect the selected fingerprint method, avoid competing D2 PIN prompts, request the fingerprint prompt on wake and correct the fingerprint unlock label. These changes do not resolve the Samsung PIN requirement described above.

### Experimental lock-screen presentation

Supported SystemUI hooks temporarily defer Samsung PIN-panel presentation while a fresh D2 visible-surface signal is active and the user has completed the first device unlock. The lease expires after three seconds or is released when D2 hands off, pauses, is destroyed or unlocks.

In enabled Guardian kiosk mode, D2 also refreshes Android's show-when-locked flag after the first device unlock, on resume, screen-on and user-unlock events. The user reported improved D2 presentation over Samsung at boot and normal lock, but Samsung PIN still appears after D2 fingerprint authentication. Full D2-only unlock is not a completed feature.

### Consistent glass dialogs

Reusable rounded dark glass dialogs add leading icons, readable text, large buttons, scrollable long content and transparent windows to avoid the square Android panel. Fingerprint guidance, unavailable-fingerprint information, unlock choices, launcher-icon help and recovery confirmations use the shared style. Fingerprint setup includes an Open settings shortcut. Specialized surfaces continue to need individual validation; Samsung's secure prompt retains its system appearance.

### Controls marked in development

Additional System Framework and Galaxy Island control-grid switches remain disabled placeholders. Their individual hooks require implementation and testing. Existing boot/wake observers, security-setup refresh, SRP checks and power-menu hooks run separately; disabled grid switches do not control them. Development of these additional controls is deferred.

## New safety protections

### Vault integrity and honest status

- Validate the expected 32-byte Samsung vault record and boolean flag values; reject malformed or unknown layouts.
- Change only the three intended flags and preserve the other 29 bytes.
- Avoid rewriting an already-correct record.
- Require successful writes and a full matching read-back before reporting verified.
- Keep unknown, denied, unsupported, failed and timed-out results unverified.
- Scope cached status to the current Android boot count; show the check time and update the open status dialog.
- Report Download Mode eligibility as a derived result, not a separate writable flag or a physical Odin/bootloader test.

### Recovery scheduling and setup privacy

- Coalesce successful credential/enrollment setup changes into checks after two and twelve seconds.
- Do not inspect, store or log PINs, passwords, credential tokens, biometric templates or hook arguments.
- Ignore rejected credential changes for setup-triggered recovery.
- Isolate observer installation failures so they do not block Android security setup.
- Bound failed app-side checks to retries after five, fifteen and thirty seconds while the app remains alive; later startup events can try again.
- Limit vault enforcement to the primary user, even when a different profile triggers a refresh.

### Power-request safety

- Serialize recovery and power verification with the shared vault lock and a single background worker.
- Keep the UI thread free while checks run.
- Validate, apply the target policy, and perform another fresh read before resuming a covered power action.
- Cancel failed/unknown checks and requests exceeding five seconds; never reboot on a late successful result.
- Suppress repeated requests while checking. A hung vault call keeps the guard busy instead of spawning unlimited workers.
- Preserve the original permission check for orderly ActivityManager soft restart.
- Accept D2 soft-restart requests only from the trusted primary-user SystemUI or D2 app UID; refuse them if the guard is unavailable.
- Verify the System Server sender identity for live status responses, with a six-second response timeout.
- Route Recovery Center soft restart through the guarded System Server path and require fresh SRP verification before its Restart SystemUI root action.
- Preserve immediate battery/thermal emergency shutdown and cancel any pending approval for those emergencies.

Normal power-off/restart is covered only when the installed hooks receive the action. Safe-mode, recovery and download restarts are covered only if they enter that same supported framework path. Direct root zygote/process restarts and hardware/power-loss routes remain outside enforcement. A missing shutdown hook can leave stock actions unguarded; this is different from an active guard cancelling a failed check.

### Fingerprint authorization and recovery

- Require successful strong-biometric authentication or the configured D2 credential before opening protected settings; a non-match does not authorize access.
- Retain PIN/pattern recovery when biometrics are cancelled, unavailable or fail.
- Revoke the app session when leaving the app, cancel pending prompts and invalidate late callbacks.
- Keep the Fingerprint Center sensor test separate from real login authorization.
- Keep Samsung credentials and enrollment intact; D2 fingerprint success does not satisfy Samsung keyguard or remove its PIN requirement.

### Lock-screen presentation recovery

- Limit Samsung PIN-panel deferral to a fresh D2 visible-surface lease after the first device unlock.
- Release the signal on pause, destruction, unlock and biometric handoff; expire it after three seconds.
- Restore deferred Samsung presentation when the surface is released or the lease expires.
- Leave stock behavior available on unsupported firmware.
- Restrict the post-unlock show-when-locked behavior to the enabled Guardian kiosk surface, rather than Preview/disabled modes.

These presentation protections reduce stale overlays; they do not provide a completed D2-only Samsung unlock flow.

See [automatic recovery implementation and limits](AUTOMATIC-DMC-RECOVERY.md) for the detailed vault and power-guard behavior. These are implementation protections with firmware-specific validation requirements, not a claim that every failure path has been tested on every device.

## Installation guide

See [Installation and fingerprint setup](INSTALLATION-0.6.4-PREVIEW.md) for the complete steps. **Set up Samsung PIN and enroll a fingerprint first**, then turn Samsung Fingerprint unlock OFF while keeping both. Configure D2’s recovery PIN/pattern and select Login with fingerprint.

## Setup and update

1. Exit kiosk using your configured D2 credential before updating.
2. Install the matching D2 APK. Updating the optional bridge module alone does not guarantee the installed app is replaced.
3. For SRP and power-menu hooks, enable D2 in LSPosed System Framework and SystemUI scopes. Reload the relevant processes using the established reboot/recovery procedure.
4. Confirm hook installation and live SRP results on your firmware before relying on the guarded paths.
5. Complete Samsung's required first PIN unlock after reboot. Retain your Samsung PIN for subsequent fingerprint handoff.

The checkpoint's fingerprint setup guidance was to keep enrollment and disable Samsung Settings → Fingerprints → Fingerprint unlock. This does not guarantee elimination of Samsung PIN prompts.

## Validation and remaining work

User device: SM-S948U1, Android 17 / One UI 9. User reports SRP working, power-menu actions appearing, and improved D2 presentation. The latest confirmed limitation is Samsung PIN after D2 fingerprint. Do not expand these reports into claims of complete power-action coverage or universal firmware compatibility.

The saved development checkpoint records three app-session authorization tests, nine vault/power-decision tests and targeted hook compilation passing at that checkpoint. These are historical targeted results, not a fresh full validation of the final release APK.

Before attaching release assets, pin the chosen commit and successful workflow; confirm APK version, signing compatibility and checksums; verify repeated boot/lock/wake, fingerprint cancellation and PIN fallback, calls, status layering, SRP refresh after credential changes, and covered restart/power-off. Do not intentionally enable unsafe vault states on the daily-use phone to test rejection paths.

No APK/module is attached to this preparation document. A release build and final artifact verification are still required.

## Credits and acknowledgments

- **@diablOw** — original discovery of the Samsung DMC behavior behind D2 SRP. This discovery credit belongs prominently in the release, README and in-app credits.
- **SoClear / OneUIX** — inspiration for Samsung power-menu action integration. D2's implementation is independent, using reflection and libxposed. https://github.com/SoClear/OneUIX
- **LSPosed and libxposed contributors** — the Xposed framework/API used for System Framework and SystemUI integration.
- **RikkaApps / Shizuku contributors** — privileged-service API/provider used by D2's Shizuku integration. This is separate from the current SRP System Server execution path.
- **KernelSU and KernelSU Next contributors** — root/module ecosystem; KernelSU Next's theme approach inspired earlier Material/dynamic-color/AMOLED work.
- **@reckah / reckaH2281** — acknowledgment of earlier external D2 testing; no claim of validating every feature in this preview.
- **Open-Meteo** — weather data, CC BY 4.0. https://open-meteo.com/ and https://creativecommons.org/licenses/by/4.0/
- **JetBrains, AndroidX, Material and SESL contributors** — libraries/UI dependencies used by the project. Preserve the applicable notices and licenses with the release.

D2 original code: Copyright (c) 2026 D2 Project. All rights reserved. Third-party components retain their own licenses. Retain LICENSE, THIRD_PARTY_NOTICES.txt and in-app Licenses and credits. Acknowledgment does not imply endorsement.

## Evidence

- Current README: https://github.com/SysopBP/SamsungLockD2-Public/blob/main/README.md
- Latest reviewed commit: https://github.com/SysopBP/SamsungLockD2-Public/commit/e75a33a2ab94932c3ee74c13f595e923ecf307a5
- Version metadata: https://github.com/SysopBP/SamsungLockD2-Public/blob/main/app/build.gradle.kts
- Third-party notices: https://github.com/SysopBP/SamsungLockD2-Public/blob/main/THIRD_PARTY_NOTICES.txt
- Saved October 12 reboot checkpoint and user's October 13 device-test findings.
