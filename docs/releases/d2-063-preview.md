# Kiosk D2 Guardian 0.6.3 — Preview 1
## Audit repairs for Android 17 / One UI 9 testing
This prerelease packages **0.6.3-audit-repairs-v23** from successful [Build 993](https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38038382440), commit `f6a64d646646e851190e4d31df8029dffd1311ed`. The APK retains its original verified signing certificate. This is a debug preview, not a stable certification.

## What changed
- Real lock requests upgrade reused previews; protected locks cannot be downgraded to preview.
- Pattern cancellation, fingerprint-session ownership and trusted-auth cleanup improved.
- Root commands run off the main thread with output limits and timeouts. Terminal sessions serialize commands and require fresh PIN authentication on resume.
- Boot claims use boot count and elapsed time, require armed policy, and are confirmed by real lock-screen visibility. Unconfirmed launches receive bounded recovery.
- Backups and profiles validate schema before writes, preserve appearance/icon selections, and exclude protected runtime modes.
- Optional phone-state handling and ongoing-call notification filtering improved.
- USB recovery snapshots and verifies original settings; sleep uses explicit SLEEP instead of POWER toggling.
- Update checks reject downgrades. CI now propagates emulator failures rather than masking them.
- Unfinished passkey provider and behavioral hook switches remain disabled and labeled.

See the bundled audit repair report for detailed behavior and remaining work.

## Downloads
- **Kiosk-D2-Guardian-debug.apk** — install normally as a user app.
- **Kiosk-D2-Guardian-KernelSU-OPTIONAL.zip** — optional bridge, disabled unless intentionally testing it; no APK is embedded.
- **Kiosk-D2-Guardian-source.zip**, runtime dependency report, setup guide, audit repair report, release notes and checksums.

Install the APK explicitly to update D2. The optional module is separate from in-app KernelSU Root Mode and is not required for normal installation. It uses no system overlay and does not provision UID 1000.

## Galaxy Island companion
Includes **Galaxy-Island-v0.2.1-beta-run191.apk**, carried forward unchanged from D2 v0.6.2-beta.1. Its SHA-256 is verified against the previous release. Install separately if wanted; this is not a new Galaxy Island build or new device validation.

## Verification
Build 993 passed build, lint, JVM tests, emulator runtime tests, original signing-certificate verification and deliverable packaging.
Samsung/KernelSU/LSPosed device validation is still pending for this exact candidate: cold boot before credential unlock, PIN/fingerprint cancellation and recreation, calls/proximity wake, kiosk release, USB rollback, idle/double-tap sleep, and restore behavior.
The existing framework hook implementation is unchanged; the earlier frozen run985 remains the reference for physical-device behavior.

## Limitations
Passkeys, individual unfinished framework hooks, Shizuku terminal command execution and expanded Live Hub integration are not completed features.
Recorded framework events do not prove live enforcement. Root-command cancellation cannot roll back effects already accepted by the device. A storage/package-manager failure may leave a partial restore; review errors before arming protection.
D2 is an app privacy screen, not a replacement for Samsung's secure lock screen. Without active kiosk, Android navigation can bypass it. It cannot repair firmware or guarantee prevention of Samsung D2 errors.

## Credits
Thanks to D2 testers and contributors, including **@reckaH2281**, and to the KernelSU, Shizuku, LSPosed, Android and SESL projects used by the app. Original licenses and THIRD_PARTY_NOTICES remain included.
