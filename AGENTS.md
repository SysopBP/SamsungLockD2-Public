# D2 Guardian development continuity

Use branch `fix/d2-widget-srp-diagnostics-20261014` and existing draft PR #71. Do not merge main or publish a release without approval.

## Baseline and intentional removal

Successful pre-removal baseline: GitHub Actions run 38672204822, commit `19319698392672336ebd075158e3f445e961538c`. OneUIX feature-port source, compile stubs, settings and hook routing were intentionally removed after that baseline. Preserve D2's independent Samsung power-menu actions, SRP protection, AMOLED icons, lockscreen, accessibility, watchdog/call policy, widgets, boot/wake hooks and diagnostics. Never restore OneUIX to fix an unrelated regression.

Read `docs/CONSOLIDATED-DEVELOPMENT.md` and `D2_FEATURE_BASELINE.json` before changing source or packaging. Run `python3 scripts/verify_feature_baseline.py`, the APK workflow, signing and deliverable verification. A passing build is not evidence of Samsung power-menu functionality on hardware. Do not reduce checks for features that remain; adjust checks only for deliberately removed OneUIX code.

D2's periodic credential hook is read-only and must preserve mandatory Samsung StrongAuth. SRP status is separate from credential authentication. Preserve applicable third-party attribution and notices for historical adaptations. Do not full-reboot a root-dependent test device merely to validate a UI change; coordinate a safe test plan.
