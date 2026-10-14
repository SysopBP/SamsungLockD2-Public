# D2 AMOLED security icon system

Twelve original 24dp Android vectors share a rounded monochrome style. Their black, white and silver concept has been simplified for small controls. No raster backgrounds or wine-red accents are embedded in the symbols.

| Resource | Use |
| --- | --- |
| ic_d2_guardian | Guardian cards, navigation, kiosk and lock-screen status |
| ic_d2_screen_lock | Security navigation, cards and common dialogs |
| ic_d2_fingerprint | Fingerprint setup, telemetry and unlock choices |
| ic_d2_pin | PIN prompt and Change PIN action |
| ic_d2_srp | SRP overview and live vault status |
| ic_d2_boot_guard | Framework boot integration heading |
| ic_d2_usb_lock | USB recovery and SRP AT-command row |
| ic_d2_watchdog | Guardian protection summary |
| ic_d2_recovery | Recovery Center and download eligibility row |
| ic_d2_system_server | System Server health and bridge actions |
| ic_d2_privacy | Notification privacy heading |
| ic_d2_core_search | Floating CORE search control |

`D2Icons.applyTo` provides a theme-foreground tint, scaled bounds and RTL-aware spacing for TextView glyphs. Existing backgrounds, user appearance settings and meaningful status indicators remain under the app's appearance and health logic. Decorative icons retain their adjacent text labels. Authentication, boot, SRP, call handling and recovery behavior are unchanged.

Validation: every new Android XML resource parses, every new drawable reference resolves, and the exported glyphs were visually reviewed. GitHub's normal APK workflow performs resource compilation, lint and existing unit/runtime checks. Test readability on the Samsung device with dark and light appearance settings after installing the build.
