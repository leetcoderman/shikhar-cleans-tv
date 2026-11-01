# DPL interactive panel ("Gupta's TV")

How a DPL "Smart Interactive Tablet" (an interactive whiteboard panel: Android 11 userdebug, Amlogic T982, 32-bit,
4K touch, RuiPin firmware `T982_C4DWPSA/A1/B_V1.0.0_20230213_BZ`) was turned into a family TV. If your panel's
vendor apps are `com.rp.*` / `com.fqcx.*` (RuiPin), most of this applies; on other TVs use the main CleanYourTV
flow plus the generic extras (`../iphone-remote`, `../airplay-receiver`).

| File | What |
|---|---|
| `AUDIT.md` | The first look at the panel: what it had, what was planned |
| `SETUP-LOG.md` | Every change, in order, with undo steps. Read this before copying a step |
| `removed-apps.txt` | The 22 apps removed for the user |
| `theme/config.json` | The launcher theme: tiles, banners, horizon backdrop, `home_name` "Gupta's" |
| `dock/` | The bottom dock app (accessibility overlay with RuiPin's tools, volume, brightness, Close app, Clean RAM, Power) |
| `android.software.leanback.xml` | Optional system feature file (see step 3) |

## Steps

1. **Connect.** These panels ship with plain ADB on port 5555 and no key prompt: `adb connect <ip>:5555`.
   (That also means anyone on your Wi-Fi can get a shell. See "Security" below.)
2. **Remove the extras** (for the user only; the APKs stay in /system):

   ```bash
   while read p; do adb shell pm uninstall --user 0 "$p"; done < extras/dpl-panel/removed-apps.txt
   # undo one: adb shell cmd package install-existing <package>
   ```

   Keep `com.fqcx.rptools` and `com.rp.uhd`: they do input switching, timers, volume and brightness pop-ups.
   **Launcher3 must stay removed**: its quickstep service crashes on this firmware.
3. **TV apps.** The Play Store calls JioHotstar, SonyLIV, FanCode and Apple TV "not compatible" (the firmware
   declares `leanback_only` without `android.software.leanback`). What worked: **Aurora Store** (F-Droid build),
   signed in anonymously with the device profile spoofed to **Sony BRAVIA VU2** (Android TV 11, armeabi-v7a); it then
   installs the TV builds of YouTube, Prime Video, JioHotstar, SonyLIV, FanCode, Apple TV, Airtel Xstream and Stremio,
   and keeps them updated. Netflix's TV app needs a certified device; the phone Netflix works (the iPhone remote
   switches to a cursor in it). Adding `android.software.leanback.xml` to `/system/etc/permissions` is the other way,
   but needs verified boot disabled; it wasn't needed.
4. **Panel settings** (RP Settings → Personal settings): "Suspended window" and "Side navigation bar" off. Side arrows
   gone for good: `adb shell setprop persist.sys.rp.no_operation_notification 0`. Light sensor off if the brightness
   keeps changing by itself. If there is no Windows (OPS) module: OPS boot → "Open with OPS", and the internal wired
   network back to automatic.
5. **Home screen.** Build this repo's launcher, install it and make it Home, then push the theme (edit `home_name`
   and the app lists first):

   ```bash
   TV_HOST=<ip> launcher/build-and-install.sh
   TV_HOST=<ip> tools/theme.sh extras/dpl-panel/theme
   ```

   Keep the RuiPin launcher (`com.rp.launcher`) installed as a fallback.

6. **Extras:** the iPhone remote (`../iphone-remote`), AirPlay mirroring for Mac and iPhone (`../airplay-receiver`;
   the panel's own QuickShare receiver fails with current macOS, the README explains why), then the dock (`dock/`).
   Bluetooth speakers and AirPods pair from RP Settings → Bluetooth (the dock's More tools → Bluetooth opens it).

## Things that bite

- **Wi-Fi gone after a restart:** do a full shutdown (hold the power button), not a soft restart.
- **Never switch to the OPS input** if no Windows module is fitted.
- `am force-stop` the launcher or the iPhone bridge only if you must; the accessibility list must keep both
  `com.corvofeng.fakeatv/...AtvAccessibilityService` and `com.gupta.dock/.DockService` (append, never overwrite).
- Sending HOME or an `am start` to the panel ends an active AirPlay mirroring session.

## Security

Open ADB on 5555 means anyone on the Wi-Fi (or the panel's Ethernet) can get a shell. The dock has an ADB lockdown
(`AdbGuard`) that makes adbd listen on localhost only, but it is off in this copy because its reopen button failed on
the first panel. See `dock/README.md` → "ADB lockdown". At minimum keep the panel on a home network you trust.
