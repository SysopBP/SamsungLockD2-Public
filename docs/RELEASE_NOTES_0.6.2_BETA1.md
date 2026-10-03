# Kiosk D2 Guardian v0.6.2 Beta 1 + Galaxy Island 0.2.1 Beta

Release baseline: October 2, 2026.

## Verified binaries

This release intentionally reuses the exact successful physical-device/test baselines. The applications are not rebuilt for release packaging.

- Kiosk D2 Guardian: Actions run #985, commit `811888c8ab9a45ec05ff0dfc542cc59475806908`
  - APK SHA-256: `da14cd04581c64e5ab061f4b1f27574046111ac5a20b59b1b37ef324a618c390`
- Galaxy Island: Actions run #191, commit `69a15914943a1e3446ff690f778e0f11245762e5`
  - APK SHA-256: `0b1c69374bfb6789abd9d27b070d99efe0142fd65d4cf49ee4e4bdd61cc6ab6d`

## Kiosk D2 Guardian — What's new

- Improved call-active handling so Guardian focus and kiosk repair defer while the Phone UI is active.
- Fixed call/voicemail relock edge cases.
- Added kiosk-background double-tap screen-off without stealing taps from interactive controls.
- Added screen-off fallback routing through Root, Shizuku, and opt-in Device Admin.
- Improved Guardian Dock behavior with always-visible, hide-on-scroll, and exit-after-scroll modes.
- Added GitHub release preferences, update checks, and in-app release update UI.
- Preserved the known-good System Server BOOT_READY fast-launch, boot-token confirmation, and duplicate-launch suppression architecture.

## Galaxy Island — What's new

- Camera ring now follows real physical camera activity.
- Expanded visual-alert coverage for live notifications, silent visual notifications, calls, expanded cards, and microphone activity.
- Corrected call and microphone renderer wiring and broadened regression coverage.
- Added a dedicated Root & System Bridge settings area with root mode, watchdog/fallback controls, and SystemUI bridge status.
- Added a dedicated Galaxy AI settings category for assistant responses, shortcuts, and island controls.
- Expanded Galaxy Glass customization including frosted-pill controls and adjustable expanded-card opacity.
- Improved notification swipe dismissal and expanded-card interaction.
- Galaxy Island remains the floating island/camera-ring project; AndroGlass remains a separate status-bar project.

## Installation

Install Kiosk D2 Guardian as a normal user APK. The KernelSU bridge ZIP is optional and should only be installed when intentionally testing the separate bridge path.

Galaxy Island is supplied as its own APK and remains independent from the D2 lock-screen security boundary.

## Security / integration boundary

D2 remains responsible for lock-screen presentation, authentication, kiosk behavior, and boot protection. External privilege providers such as TokenX must not become prerequisites for D2 presentation or unlock.

## Credits

Thanks to everyone testing the physical-device builds and reporting regressions. Special thanks to `reckaH2281` for D2 testing and bug reports.

Galaxy Island remains based on Expressive Cutout with original GPL-3.0 licensing and upstream credits retained.
