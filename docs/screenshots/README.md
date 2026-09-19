# D2 screenshots

These are unedited captures of D2 v0.4.0 running in an Android 16/API 36 emulator with the default background, sample media metadata, and disposable test data. They are not captures from a Samsung handset.

- `01-settings.png`: authenticated app settings.
- `02-preview.png`: unlocked lock-screen preview.
- `03-kiosk-lock.png`: independent PIN screen with full root kiosk mode active.
- `04-pin-prompt.png`: the D2 PIN dialog over the active lock screen.
- `05-customization.png`: Celsius and locked media switches.
- `06-shortcuts.png`: notification privacy and configurable bottom actions.

To refresh them, run **Capture app screenshots** from the repository's Actions page and download the **D2-screenshots** artifact. Inspect the images before replacing these files.

The opt-in instrumentation test temporarily clears screenshot-protection flags on the disposable emulator. This code is compiled into the separate test APK, not the released app. Production screenshot protection is unchanged. The test credential is discarded after capture; it is not a default or recovery PIN.
