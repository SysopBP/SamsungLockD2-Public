# D2 Guardian independent development baseline

## Source of truth

- Known-good pre-removal reference: GitHub Actions run [38672204822](https://github.com/SysopBP/SamsungLockD2-Public/actions/runs/38672204822), source commit `19319698392672336ebd075158e3f445e961538c`.
- Active development branch: `fix/d2-widget-srp-diagnostics-20261014`; existing pull request: [#71](https://github.com/SysopBP/SamsungLockD2-Public/pull/71).
- OneUIX-removal/documentation checkpoint: `da3ac6a4472ebfc0d81e6b3c271c6badfdc84118` (eight commits after reference).
- **The reference workflow succeeded before removal. It does not validate the post-removal APK.** No post-removal build is considered stable until CI and physical-device verification pass.

## Preservation contract

Keep D2-owned Samsung power-menu actions (power off, restart, soft restart, SRP status, Lock D2 and mobile data where available), custom icons, SRP vault checks, fingerprint/PIN/pattern unlock, lockscreen, watchdog, widgets, accessibility, boot/wake hooks, and read-only StrongAuth diagnostics. Keep Samsung emergency and lockdown behavior. Do not restore the OneUIX feature port or its compile stubs. Historical attribution for adapted or referenced code must remain where required.

## Audit and release gates

1. Compare the exact reference commit to the development head; investigate any lost D2-owned behavior before changing code.
2. Review AGPL-3.0 obligations and all dependency licenses, including DexKit if found. Check source, Gradle modules, dependency resolution, APK contents, and third-party notices.
3. Remove only dependencies demonstrated to be unused. Do not remove historical attribution for retained adaptations.
4. Verify required D2 classes and wiring **and** absence of removed OneUIX classes from the built APK; source-text presence checks alone are insufficient.
5. Run unit tests, lint, Android emulator tests, APK signature verification, corresponding-source packaging and deliverable verification. Compare output against the reference run.
6. On the Samsung SM-S948U1 / Android 17 / One UI 9, validate power-menu actions, SRP protection, fingerprint and credential fallback, watchdog, widgets, StrongAuth diagnostics, and boot/wake behavior. Test reboot paths cautiously because loss of root or hook attachment may require recovery.
7. Update README, installation instructions, release notes and notices with verified behavior only.

Do not merge into `main`, publish a release, or label a build stable without explicit approval and verification. Keep all remediation on the existing branch and PR #71.

## Samsung native unlock compatibility observation (October 14, 2026)

On the SM-S948U1 test device, Samsung face recognition and Extend Unlock / Trusted Places were user-confirmed working. They remain **Samsung-native capabilities**, not D2-owned authentication. **D2 face recognition is not implemented**; do not advertise or implement it in this cleanup. D2 fingerprint and configured PIN/pattern recovery remain separate. Future readiness UI may document the observed Samsung result and guided checks without mislabeling it as D2 face recognition. Documentation-only change; no APK behavior or CI success implied.
