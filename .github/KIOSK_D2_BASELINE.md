# Kiosk D2 Guardian verified baseline

Do not replace this baseline with another branch, artifact, APK, source archive, or bulk merge without explicit verification.

## Git anchor
- Known-good workflow run: 36096182415
- Baseline commit: c3a110c3bd10b2bff697045cba82eea2f6250db5
- Original branch: integration/guardian-watchdog-current

## User-verified deliverables baseline
Source package: SamsungLockD2-deliverables (61).zip

- SamsungLockD2-debug.apk
  - size: 9,462,153 bytes
  - SHA-256: 6e8b98ae…57f156c
- SamsungLockD2-KSUNext-v0.5.6-paired.zip
  - SHA-256: fc996711…ddd5fb0
- SamsungLockD2-source.zip
  - SHA-256: 3c2b2a5e…736191

## Integration rule
Bring later changes forward as explicit, reviewable patches/commits. Do not wholesale replace the source tree from a newer/older artifact. Before merging to the main development line, verify lineage, package/version identity, expected files, and build outputs. Unexpected APK shrinkage or baseline mismatch is a stop condition.


Build verification marker: baseline-control-1
