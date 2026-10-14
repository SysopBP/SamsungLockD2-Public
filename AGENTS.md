# D2 Guardian development continuity

Use `fix/d2-widget-srp-diagnostics-20261014` as the canonical development branch
and update existing draft PR #71. Do not replace it with an older baseline or
another branch merely because that branch has a higher Actions run number.

The consolidated history includes both Build 1095 (`fa86939`) and Build 1106
(`2fd96a9`). Preserve the combined OneUIX settings/hooks, accessibility,
watchdog/call fixes, D2 Hooks diagnostics, SRP checks, widget and boot telemetry.
Read `docs/CONSOLIDATED-DEVELOPMENT.md` and `D2_FEATURE_BASELINE.json` before
changing feature wiring or packaging. When comparing builds, verify branch and
commit ancestry as well as artifact contents. Never infer feature inclusion
from a successful build or a larger APK alone.

Run `python3 scripts/verify_feature_baseline.py`; the normal APK workflow also
checks required classes/native ABIs in the actual deliverable. Do not weaken
these checks to make an accidentally incomplete build pass. Preserve imported
licenses/notices and complete corresponding-source packaging.

The legacy OneUI `disablePinVerifyPer72h` identifier is compatibility-only:
its implementation observes scheduler calls and must preserve required Samsung
strong authentication. Distinguish installation, execution, failure, unavailable
and not observed. SRP verification never establishes credential authentication.

Do not merge into main or publish a release without the owner's approval.
