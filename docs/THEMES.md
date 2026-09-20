# Galaxy Island + Samsung Lock D2: themes preview

## D2 0.4.4

Enter your D2 PIN to open settings, then find **THEME & COLORS**. Choose Follow system, Light, Dark or AMOLED black. Accent options are system wallpaper, blue, teal, lavender, rose, amber, sage and a custom six-digit hex color.

Notifications can keep different soft colors per app, use your accent, or stay neutral. Adjust card opacity (20–100%), banner opacity (40–100%) and corner radius (8–36 dp). The notification stack stays centered in the space between the clock/weather and lower controls. Longer lists scroll instead of stopping at four cards.

The **FLOATING LOCK-SCREEN BAR** keeps your existing shortcuts and PIN control. Adjust width (65–100% of the available area), opacity (40–100%), and spacing above navigation (8–72 dp). Open Preview to check the position against your wallpaper. Media remains below notifications and above the shortcut bar when enabled.

Light themes use dark text; dark themes and custom lock wallpapers use light text. Raise opacity if your wallpaper makes text difficult to read. Reset appearance restores theme defaults without resetting your PIN or kiosk preferences.

## Galaxy Island

Open **Profile → Theme** for System, Light, Dark or AMOLED. **App accent** offers matching presets, wallpaper colors, and the existing custom color picker. **Copy current accent to island** applies a tinted background, accent outline and white text to the island. Further shape, opacity and layout controls remain in the existing island settings.

The apps save themes independently; choose the same preset in each for a matching look. Wallpaper colors can differ slightly because D2 uses an Android accent resource while Galaxy Island uses Material 3 color roles. Galaxy Island retains its existing version label 0.2.0-beta; identify this themed build by the release filename and checksum.

## Update

Exit kiosk using your PIN, then install both APKs over the previous paired builds. They retain the same paired signing certificate. If Android reports a signature mismatch, stop rather than uninstalling and clearing your data.

The optional KernelSU Next bridge 0.5.6 ZIP bundles D2 0.4.4 for fresh installs. It preserves already-installed apps, so install the D2 APK manually even when updating the ZIP. Galaxy Island is installed separately. The module's root scripts are unchanged; theme controls do not need a new root mechanism.

## Credits and testing context

Theme options were inspired by KernelSU Next's Material 3/dynamic-color/AMOLED approach. This is an independent implementation, not a port of KernelSU code or Miuix, and it does not synchronize KernelSU Manager settings. Reference: https://github.com/KernelSU-Next/KernelSU-Next/blob/dev/manager/app/src/main/java/com/rifsxd/ksunext/ui/theme/Theme.kt

Galaxy Island is based on EvanKoe's Expressive Cutout (GPL-3.0). D2: copyright 2026 D2 Project, all rights reserved. Weather: Open-Meteo.com, CC BY 4.0. Full notices are retained in the source archives and apps.

The user reported testing the earlier combo on a Samsung Galaxy S26 Ultra running Android 17. This update is checked separately with local builds and Android 16/API 36 emulator captures; it has not been physically tested on that handset. Screenshot messages and calls are simulated fixtures. See Build-verification.txt for exact results.

