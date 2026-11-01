# Changes made to the DPL panel

> The day-by-day log of how "Gupta's TV" (a DPL interactive panel) was set up, kept as a reference. Paths like
> `backup-before-wipe/`, `backup-apks/` and `baseline/` were local backups and are not in this repo; `cleanyourtv-core/` is
> this repo's root.

## 30 Oct 2026

**Backup:** APKs of 22 vendor apps in `backup-apks/`, the original state in `baseline/`.

**Removed for the user (`pm uninstall --user 0`), 22 apps, 100 → 78 packages.** See `removed.log`.

- Asked for by the owner: Teams, Zoom, WPS Office, Calculator, Calendar, Write number tool,
  Memory cleaner, Welcome screen, Product intro, App lock, Android Music.
- Other extras nobody uses: Launcher3 (unused stock launcher), Print spooler, System tracing,
  Bluetooth MIDI, Dynamic system installer, Hotspot 2.0 login, VLC, RP file manager, RP image player,
  RP video player, RP camera.

Undo one app: `adb shell cmd package install-existing <package>` (the APK is still in /system).

**Property:** `persist.rp.smalltool.hide` set from `off` to `on`, to hide the round floating tool.
It had no visible effect without a restart. Undo: `adb shell setprop persist.rp.smalltool.hide off`.

**Not touched on purpose:** `com.fqcx.rptools` draws the side bars and the floating button, but it
also does input switching (Windows/HDMI), on/off timers, the screen lock and the volume and
brightness pop-ups. Removing it would break those. The side bars will be handled when the new home
screen is built.

## 30 Oct 2026, home screen

- **Launcher** `com.gathin.tvhome` installed and set as Home (`cmd package set-home-activity`). The RuiPin
  launcher `com.rp.launcher` is still installed and enabled as a fallback. Undo:
  `cmd package set-home-activity --user 0 com.rp.launcher/.MainActivity`, then `pm uninstall com.gathin.tvhome`.
- **Theme** `extras/dpl-panel/theme/config.json` pushed with `tools/theme.sh`: 6-column tile grid, Bluetooth tile
  (RP Settings' Bluetooth page), Whiteboard, `home_name` "Gupta's". Undo: `tools/theme.sh --reset`.
- **New launcher code (local, uncommitted):** `home_name` (house + name on the horizon scene, off unless set)
  and `NavPillService` (Back/Home pill for touch, only when enabled in Accessibility). The pill was removed
  again the same day at the owner's request (a custom side bar will replace it later). The Haier is unaffected
  until its launcher is rebuilt, and `home_name` stays off there. The APK is now 508 KB, over the repo's 480 KB gate.
- **Accessibility:** `com.gathin.tvhome/.NavPillService` enabled, then removed again from the list the same day
  when the pill was taken out.
- **RP Settings → Personal settings:** "Suspended window" and "Side navigation bar" switched off.
- **AirScreen** installed from Play and uninstalled again (its terms were never accepted).
- **Play Store:** JioHotstar, SonyLIV, FanCode and Apple TV are "not compatible" because the firmware declares
  `leanback_only` without `android.software.leanback`. The owner chose to add the feature; the file is in
  `system-patch/`, and the owner runs the steps (they disable verified boot).

## 30 Oct 2026, TV apps via Aurora Store

- **Aurora Store** 4.8.4 (`com.aurora.store`, F-Droid build, signer CN=FDroid) installed; install and storage
  permissions granted; signed in anonymously with the device profile spoofed to **Sony BRAVIA VU2** (Android TV 11,
  armeabi-v7a), so it fetches TV builds from Google Play. Updates for the apps below go through Aurora.
- **Installed (TV builds):** YouTube for Android TV `com.google.android.youtube.tv` 5.30, Prime Video
  `com.amazon.amazonvideo.livingroom` 6.24, JioHotstar `in.startv.hotstar` 26.10, SonyLIV `com.sonyliv` 6.29,
  FanCode `com.fancode.tv` 3.1.1, Apple TV `com.apple.atve.androidtv.appletv` 17.0, Airtel Xstream Play
  `tv.airtel.xstream.tvapp` 1.42, Stremio `com.stremio.one` 1.12.
- **Netflix TV** (`com.netflix.ninja`) installed and removed again: "not compatible with your device" (needs a
  Netflix-certified device). The phone Netflix `com.netflix.mediaclient` stays.
- **Uninstalled phone versions:** Prime Video `com.amazon.avod.thirdpartyclient` (gone), YouTube
  `com.google.android.youtube` (update removed, system copy removed for the user;
  `cmd package install-existing com.google.android.youtube` restores it). APKs in `backup-before-wipe/apks/`.
- **Theme:** tiles in this order: Netflix, Prime Video, JioHotstar, SonyLIV, FanCode, Xstream Play, Apple TV,
  YouTube, Stremio, Chrome, Play Store, Whiteboard, Bluetooth. Aurora, the AirPlay receiver and the iPhone bridge
  are hidden from Home (still in App library).
- **TV Bro** `com.phlox.tvwebbrowser` 2.1.6 installed from Aurora (remote-friendly browser, next to Chrome).
- **Tiles:** Netflix gets a banner (`banner_netflix`, a 960x540 PNG added to the local launcher under
  `res/drawable-nodpi/`); the Play Store tile is replaced by Aurora, labelled "App Store"; the Play Store stays
  installed but hidden from Home.
- **Chrome** `com.android.chrome` uninstalled at the owner's request (TV Bro stays); APK in `backup-before-wipe/apks/`.
- **QuickShare** `com.bozee.usbdisplay` (update + system copy) and its guide `com.bozee.andisplayguide` removed for the user at the owner's request; `cmd package install-existing <pkg>` restores them. `persist.rp.screenshare` still names the guide, so a RuiPin screen-share shortcut, if any, now does nothing.
- **Home:** the remaining apps (AirPlay receiver, iPhone remote receiver, Play Store, Android TV Settings, Files, the RuiPin home as "Classic Home") are now tiles too.
- **Network pill:** local launcher change in `MainActivity.updateWifiLabel`: with both Ethernet and Wi-Fi up, the pill shows the Wi-Fi logo (it opens Wi-Fi settings anyway); Ethernet-only setups keep the Ethernet symbol.

## 30 Oct 2026, network loss after a restart

After a soft restart at 03:40 Wi-Fi came up off and found no networks; `.128` is not a cable but a fixed address on the internal link to the Windows (OPS) slot. A full shutdown (hold the panel's power button, wait, power on) brought Wi-Fi back. Likely cause: the USB Wi-Fi modules sit on hubs that RuiPin re-routes to the Windows slot when the input changes. If Wi-Fi ever flips itself off again, do that full shutdown first.

## 30 Oct 2026, Windows slot (OPS) cleared

- RP Settings → Personal settings → **Boot channel**: already Android (unchanged).
- **OPS boot**: "Open with any channel" → **"Open with OPS"** (the slot only powers when switched to).
- Internet → **Wired network** (the internal link to the slot, no cable): manual `192.168.1.128` / gw `192.168.1.1` / DNS 8.8.8.8, 8.8.4.4 → **automatic**. eth0 now has no address; Wi-Fi is the only network.
- The side bar's source list can only rename inputs, not hide them, so "OPS" stays listed.
- Undo: pick the old options in the same screens.

## 30 Oct 2026, brightness

Brightness kept rising and falling: the RuiPin **Light sensor** tool (side bar → tools panel) was on, auto-adjusting the backlight from an ambient sensor that reads nothing (`lightValue -1`). Switched it off (`rp.sys.light.sensor=off`) and set the brightness slider to 80 (`persist.rp.backlight` 0 → 80). Undo: tap Light sensor again.

- 2026-10-30: App library moved from the pill under the tiles to a round button in the top bar, left of Inputs (theme `"library_button": true`; launcher layout puts btnLibraryTop before Inputs and drops the bottom pill while library_button is on). Undo: remove `"library_button": true` from theme/config.json and re-run `TV_HOST=192.168.1.2 tools/theme.sh extras/dpl-panel/theme` from the repo root (the pill comes back).
- 2026-10-30: Launcher: the home_name lettering ("Gupta's") moved from the bottom-left of the backdrop (now under the dock) to the top centre, between the clock/date and the round buttons: centred, 44 dp, baseline 92 dp, drawn outside the hill parallax so it stays put; same colour and glow, house on the hill unchanged (HorizonView.java, config.xml comment).

## 30 Oct 2026, bottom dock

- **Dock app** `com.gupta.dock` (source `extras/dpl-panel/dock/`, key `dock/keystore/dock.p12`, password in
  `keystore/password.txt`), an Accessibility service drawing a glass bar at the bottom: Back, Home | Netflix,
  YouTube, JioHotstar, Whiteboard | Inputs, Annotate, Screenshot, Volume, More tools (record, timer, spotlight,
  magnifier, curtain, Bluetooth), Settings. Open on Home; inside apps it folds to an arrow and re-folds 5 s after
  the last touch. Enabled in `enabled_accessibility_services` after the iPhone bridge's entry.
  Undo: remove `com.gupta.dock/.DockService` from that list, then `pm uninstall com.gupta.dock`.
- **Launcher3** restored for Recents, then removed again (`pm uninstall --user 0`): its quickstep service crashes on
  this firmware (no INPUT_CONSUMER permission). Recents dropped from the dock at the owner's request.
- Brightness, eye protection and freeze screen are not in the dock: RuiPin sets them through a system-only API
  (setprop and brightness keys don't move `/sys/class/backlight/aml-bl/brightness`).

## 30 Oct 2026, dock tested inside apps (`dock/README.md`)

- All buttons tested inside TV Bro. Three fixes in `DockService.java`:
  - **Timer:** RuiPin's `TimerService` isn't exported (SecurityException), so the dock now opens its public timer
    screen (`android.intent.action.timer`).
  - **Screenshot:** the crop tool froze the screen with the dock in it; the dock now hides before opening it.
  - **RuiPin tools** (curtain, crop, annotation, magnifier...): their controls sit at the bottom centre, under the
    arrow (tapping the curtain's X opened the dock instead). The dock now hides while one of their screens is up and
    returns when it closes.
- The dock's accessibility config now also reads the window list (`canRetrieveWindowContent`,
  `flagRetrieveInteractiveWindows`, `typeWindowsChanged`), since RuiPin's tools are see-through and report no screen
  change. Still no key handling. Undo: remove the dock (`dock/README.md`, "Remove").

## 30 Oct 2026, RuiPin side arrows retired

- The arrows on both edges are `com.rp.uhd`'s side bar (`com.rp.tool_slidebar`, windows `RPTools_Left/Right_Slidebar`).
  At boot it reads `sys.sidebar.window.switch` (System setting, already `0` = hidden), but `com.rp.rpsystemservice`
  then shows it again on every touch (`com.rp.OperationBroadcastNotification`) and hides it after
  `persist.sys.rp.no_operation_notification` seconds idle (`NoOperationBroadcastNotification`).
- **Property:** `persist.sys.rp.no_operation_notification` `15` → `0`, so those broadcasts stop and the side bar stays
  hidden; hidden right away with `am broadcast -a com.fqcx.floatwindow.hide`. Tested: taps, swipes, Home and Prime
  Video bring nothing back. Not yet checked across a restart.
- Lost with the arrows: RuiPin's side menu (brightness, eye protection, freeze screen, light sensor). Brightness can
  still be stepped with `am broadcast -a action.com.rp.brightness.up` / `.down` (RuiPin shows its own slider);
  eye protection (`RpCommonManager`) and freeze (an injected key) are system-only.
- Undo: `adb shell setprop persist.sys.rp.no_operation_notification 15`, then touch the screen.

## 30 Oct 2026, dock 1.1

- Pinned apps (Netflix, YouTube, JioHotstar, Whiteboard) removed from the dock at the owner's request.
- **Brightness** button added after Volume: − / + pop-up that steps RuiPin's backlight by 5 through
  `action.com.rp.brightness.down` / `.up` (hold to repeat); RuiPin's slider shows the level. Tested 80 → 85 → back to 80.
- Undo: install the previous dock source (pinned apps were in `DockService.PINNED`) or remove the dock (`dock/README.md`).

## 30 Oct 2026, Classic Home removed

- **RuiPin launcher** `com.rp.launcher` ("Classic Home", `/system/app/EduLauncher/EduLauncher.apk`) removed for the user
  (`pm uninstall --user 0`) at the owner's request; `com.gathin.tvhome` stays Home, and there is no fallback launcher
  any more. APK in `backup-before-wipe/apks/com.rp.launcher/` (30 MB). The launcher dropped the tile by itself.
  Undo: `adb shell cmd package install-existing com.rp.launcher`, then put it back in the theme.
- **Theme:** `com.rp.launcher` taken out of `dock_apps`, `library_apps` and `app_labels` in `extras/dpl-panel/theme/config.json`
  (local file only; not pushed, because `tools/theme.sh` force-stops the launcher; the next theme push carries it).
  Undo: add `"com.rp.launcher"` back to the end of both lists and `"com.rp.launcher=Classic Home"` to `app_labels`.

## 30 Oct 2026, dock 1.4 and remote access

- **Dock** (`dock/README.md`): volume and brightness are inline `−  icon  +` groups (volume steps the media volume by 5;
  the speaker mutes); Bluetooth stays in More tools; More tools has a wrench icon (the grid icon looked like the app
  library); every button is a coloured icon tile; the accessibility config no longer asks to filter keys.
- **Remote:** injected keys (the iPhone bridge) never reach an accessibility key filter, so the launcher now sends
  `com.gathin.tvhome.action.FOCUS_BELOW` when Down is pressed on its last row with nothing below
  (`MainActivity.onTileVerticalKey`, local uncommitted change), and the dock takes window focus until Up/Back, 15 s
  idle, or Home. Tested with injected keys: into the dock, volume +/−, More tools and its tiles, Settings, back out.
- Undo: reinstall the previous dock/launcher builds, or remove the dock (`dock/README.md`, "Remove").

## 30 Oct 2026, OPS input hidden

- The Windows slot (OPS) is gone from the source picker (side bar, launcher Inputs, dock); Type-C, HDMI1, HDMI2,
  Pre HDMI, DP and AV are unchanged. RuiPin's own switch: the `hideSource` list in
  `/mnt/vendor/nodelete/rp_app_list_info.xml` (persistent partition, not /system) now holds `OPS` and `Windows`;
  it also hides OPS from RP Settings' Boot channel list. Applied without a restart by renaming OPS to "OPS"
  (`service call sdk_SourceManager 1 s16 OPS s16 OPS`), which makes RuiPin rebuild the input list.
- `persist.sys.ops_enable` true → false (no OPS start at boot, on top of "Open with OPS").
- Still possible: RuiPin's floating-tool OPS button (floating tool is off) or a PC key on a remote. The slot's
  main power stays on while a PC module is in it (RuiPin code, no setting for it).
- Before-state in `backup-before-wipe/ops-before.txt`, original file `backup-before-wipe/rp_app_list_info.xml.orig`.
  Undo: copy that file back over the panel's file (adb push to /data/local/tmp, then `cat > `), run the same
  `service call`, and `setprop persist.sys.ops_enable true`.

## 30 Oct 2026, Home tiles: App Store banner, AirPlay and remote moved to the dock

- **App Store banner** for Aurora's tile: `launcher/app/src/main/res/drawable-nodpi/banner_appstore.png` (960x540,
  6.9 KB), drawn here (blue gradient, white App-Store-style "A", "App Store"), not Apple's artwork; theme
  `app_banners` gains `com.aurora.store=banner_appstore`.
- **AirPlay receiver and iPhone remote** off Home (theme: out of `dock_apps` and `library_apps`, into `hidden_apps`;
  still in the App library) and into the dock's More tools (dock 1.5).
- **Theme writes** now go straight through `content://com.gathin.tvhome.theme` (config.json + a new theme_revision),
  then `adb install -r` or a launcher restart loads them; `tools/theme.sh` is not used because it force-stops the
  launcher. Panel copies before each write: `backup-before-wipe/theme-config-panel-20261030-160827.json` and
  `-161020.json`. The Bluetooth and Settings tiles the owner removed in the App library stay removed.
- **Launcher fix:** when the dock takes focus, the tile and the focus glass lose their highlight until focus comes
  back (`MainActivity.onWindowFocusChanged`, local uncommitted). Launcher APK now 517,271 B (gate is 491,520 B).
- Undo: restore a theme backup the same way; remove the banner line; reinstall the previous dock.

## 30 Oct 2026, 16:20 full power-off restart: everything held

Owner held the power button for a full shutdown and powered on again (uptime 1 min at 16:21). Wi-Fi came back by
itself. Checked: Home is `com.gathin.tvhome`; both accessibility services bound (iPhone bridge, dock); the bridge
was sending keys again; RuiPin side arrows stay hidden after touches
(`persist.sys.rp.no_operation_notification` 0, `sys.sidebar.window.switch` 0); OPS still missing from the source
picker and `persist.sys.ops_enable` false; brightness held at 80 (sysfs 201) with no light-sensor drift; Classic Home
still removed; remote Down from the last row enters the dock and Up leaves it.

## 30 Oct 2026, dock 1.6: Power button

- Red **Power** button right of Settings, with a pop-up: **Power off** opens RuiPin's own power-off screen
  (`android.intent.action.ShutDown`, 15 s countdown, cancel with Back/Home/touch outside; tested and cancelled), and
  **Restart** sends `reboot` through the panel's local ADB (127.0.0.1:5555, no key on this panel), because Android's
  reboot needs a system permission. The ADB handshake was checked with `echo` from the Mac; Restart itself not pressed
  yet. The dock now has the INTERNET permission (for that localhost socket only). If LAN ADB is locked down with keys
  later, Restart stops working and shows a message.

## 30 Oct 2026, Home tiles: App Store, Play Store, Whiteboard and Files as full banners

- The four tiles that looked out of place (a small icon on a dark card, or a thin App Store glyph) are now full-bleed
  banners like the streaming ones, drawn here (not the brands' artwork): App Store (blue gradient, white "A", redrawn),
  Play Store (light grey, four-colour play triangle), Whiteboard (warm white, marker drawing a blue squiggle), Files
  (Google blue, white folder). 960x540 palette PNGs of 5-10 KB in
  `cleanyourtv-core/launcher/app/src/main/res/drawable-nodpi/banner_{appstore,playstore,whiteboard,files}.png`.
- Theme `app_banners` gains `com.android.vending=banner_playstore`, `com.fqcx.wb=banner_whiteboard`,
  `com.android.documentsui=banner_files`; pushed through the content provider + new theme_revision, then
  `adb install -r`. Panel backup: `backup-before-wipe/theme-config-panel-20261030-162500.json`. Launcher APK 542,663 B.
- Undo: delete the three new PNGs, drop the three `app_banners` lines, rebuild, push the theme and reinstall the same way.

## 30 Oct 2026, header aligned with the tiles; dock 1.7

- **Launcher:** new theme key `header_inset` (dp, default 0, documented in `cleanyourtv-core/themes/README.md`;
  `Config.java`, `config.xml`, applied in `MainActivity`). This panel's theme sets 24, which moves the greeting, clock
  and date right and the round buttons left so both edges line up with the tiles (clock and first tile start at
  x=240; the gear and last tile end at x=3600/3599). Local, uncommitted. Undo: remove `header_inset` from the theme.
- **Dock 1.7:** the gear is now **Android settings** (green Android head), opening `com.android.tv.settings`
  explicitly; Home's tile is blue so the two greens don't clash. The top bar's gear is unchanged.
- Theme pushed through the content provider (backup in `backup-before-wipe/theme-config-panel-*.json`, latest one).

## 30 Oct 2026, iPhone remote: cursor in Netflix and flick to scroll

- The Netflix phone app couldn't be scrolled from the iPhone remote. The bridge (`iphone-remote/`, local source)
  now switches to cursor mode by itself when Netflix comes to the front, and back to the D-pad when another app's
  screen does (overlays such as the dock, RuiPin pop-ups and the keyboard don't count). In cursor mode a quick flick
  (under 0.3 s) scrolls what is under the arrow in that direction (a 120 ms swipe through the key injector), and the
  arrow stays put; slow slides still move it and taps still click. Tested: auto on/off; injected flicks scroll
  Netflix's page and a row. Not yet tried with the iPhone itself.
- New native call `nativeSetTrackpadMode` → `AtvServer::set_trackpad_mode_transient` (doesn't save the mode). Cargo
  tests pass; installed with `pm install -r` (pairing kept, no force-stop), both accessibility services bound.
- Undo: `adb push backup-before-wipe/FakeAtv-dpl-before-netflix-cursor.apk /data/local/tmp/f.apk`, then
  `adb shell 'pm install -r /data/local/tmp/f.apk && rm /data/local/tmp/f.apk'`.

## 30 Oct 2026, iPhone remote: TV Bro too; vertical flicks flipped

- The automatic cursor and flick scrolling now also apply in **TV Bro** (`com.phlox.tvwebbrowser`), alongside Netflix
  (`CURSOR_APPS` in `AtvAccessibilityService.kt`).
- **Up/down flicks now work like a mouse wheel:** flick down to move further down the page, up to go back up (the
  owner found the phone-style direction reversed). Sideways flicks are unchanged (the row follows the finger).
- Tested: cursor on in TV Bro; the downward-flick swipe scrolls a page down. Installed with `pm install -r`; both
  accessibility services bound. Undo: the APK backup in the previous entry.

## 30 Oct 2026, dock 1.9: Close app; AirPlay off Home again

- **Close app** (dark red X, after Home): force-stops the app in front through the local ADB and goes Home; apps the
  TV runs on are refused (launcher, iPhone bridge, dock, AirPlay, keyboard, `com.rp.*`, `com.fqcx.*`, Droidlogic,
  Amlogic). Tested: TV Bro's process ended and Home came up; AirPlay refused and kept running.
- **AirPlay tile** had come back on Home: the launcher shows an app that was added from the App library even when the
  theme hides it. Removed with the tile's own long-press → Remove from Home (saved in the launcher's
  `library_home_removed.txt`), so it stays off; still in the dock's More tools.

## 30 Oct 2026, top gear opens the panel's settings

- **Launcher:** new theme key `settings_button_app` (package or component, default empty = Android's settings; in
  `config.xml`, `Config.java`, `MainActivity.settingsButtonIntent`; documented in `cleanyourtv-core/themes/README.md`).
  This panel's theme sets `com.rp.rpsettings`, so Home's top-bar gear opens RuiPin's panel Settings (boot channel, OPS
  boot, internet, display, sound, timers). The dock's green Android button still opens Android TV Settings. Tested
  both. Local, uncommitted; launcher APK 543,211 B. Undo: remove `settings_button_app` from the theme and push it.

## 30 Oct 2026, iPhone Remote stopped listing Gupta's TV: Wi-Fi power save

- **Cause:** the panel's Wi-Fi chip was in power save (`iw dev wlan0 get power_save` → on) and dropped incoming
  multicast. The panel kept announcing itself, but never heard iPhones asking (tcpdump on the panel: broadcasts from
  others arrived, mDNS multicast from others didn't). With power save off, iPhone queries arrived and the panel answered
  `PTR Gupta's TV._companion-link._tcp`; a second iPhone listed it at once. The owner's own iPhone needed a reset of its
  cached state (Wi-Fi off/on, restart, Limit IP Address Tracking off, Private Wi-Fi Address Off/Fixed), then worked.
- **Fix in the bridge:** holds a `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi lock while it runs (power save goes off as it starts;
  tested by turning power save on and reinstalling), and re-registers Bonjour every 10 minutes. Cargo tests pass;
  `pm install -r`, pairing kept.
- For the diagnosis adb ran as root for a few minutes (`adb root` … `adb unroot`; back to uid 2000).
- Undo: reinstall `backup-before-wipe/FakeAtv-dpl-before-netflix-cursor.apk` (also undoes the Netflix cursor changes).

## 30 Oct 2026, name, Aurora updates, tile order, menu icon

- **Device and Bluetooth name** "ohm" → **Gupta's TV** (Android TV Settings → rename, which sets `device_name`,
  `bluetooth_name` and the adapter name; checked with `dumpsys bluetooth_manager`). Undo: rename again the same way.
- **Aurora:** automatic updates were already "Check & install" every 3 hours; the "When device is idle" restriction is
  now off (a TV is rarely idle in Android's sense, so updates would never run). Android 11 lets Aurora's session installer
  install only with a one-tap confirmation, so some updates may wait for someone to open App Store.
- **Home tiles:** Play Store now follows App Store; Whiteboard moved to the end after Files (theme `dock_apps` and
  `library_apps`; backup `backup-before-wipe/theme-config-panel-20261030-174247.json`).
- **Dock 2.0:** More tools shows a hamburger (three bars) instead of the wrench.
- **iPhone remote:** the automatic cursor and flicks now also apply in RuiPin's Settings panel (`com.rp.rpsettings`),
  whose lists don't scroll with the D-pad.
- Bluetooth pairing: the panel already had "AirPods - Find My" bonded; a fresh scan lists "Shikhar's AirPods Pro". Pairing
  and audio not yet confirmed.

## 30 Oct 2026, flicks back to phone-style; AirPods paired

- Up/down flicks reverted to phone-style at the owner's request (the content follows the finger, as sideways already
  did); bridge rebuilt and reinstalled with `pm install -r`, both accessibility services bound.
- **Bluetooth:** the owner paired Shikhar's AirPods Pro from RuiPin's Bluetooth page and TV sound plays through them.

## 30 Oct 2026, storage cleanup

- **Old Chrome libraries:** 30 orphaned `com.google.android.trichromelibrary_*` copies (left by Chrome updates, no users after Chrome was removed) uninstalled with `pm uninstall`, about 4.1 GB. The newest, `_792213730` (105 MB), is kept in case Chrome is restored.
- **Downloads:** the old QuickShare installer `USBDisplayUnion-982-release-3.1.143.apk` (119 MB) deleted. The PDFs stay.
- **Gboard:** cache cleared (182 MB) via a short `adb root` session, then `adb unroot`.
- Result: userdata used 7.0 GB → 2.4 GB of 23.8 GB (89% free).

## 30 Oct 2026, dock 2.1 and 2.2

- **Dock 2.1:** folds reliably inside every app. It used to pick the front app from single window events that name an
  Activity; an app's first event is its starting window, later ones are fragments/dialogs/bottom sheets, and Home sends
  trailing events, so a late Home event could leave the bar open over an app (seen over Android TV Settings and Aurora).
  Now it reads the topmost app window from the window list 150 ms after any window event (looking through the dock,
  System UI dialogs, the keyboard and RuiPin tools). Tested: open on Home; folds in Aurora, TV Bro, Netflix, TV Settings;
  hides under RuiPin's curtain and returns; remote focus from Home still works.
- **Dock 2.2: Clean RAM** (slate chip icon, just left of Power): `am kill-all` through the local ADB, which ends only
  cached background apps (not force-stop); Home, the iPhone bridge, AirPlay, the dock and Gboard stay up. Shows "Freed N
  MB · X GB free" in a notice above the bar (a toast would hide behind the dock on Android 11). First press: 91 MB freed,
  2.0 GB free. The older toasts (Close app, Restart failures) have the same hiding problem; not changed yet.
- The owner pressed the dock's **Restart** themselves: the panel came back fine.

## 30 Oct 2026, iPhone remote: the bridge answers Bonjour itself

- **Why it kept dropping:** Android's own responder (NsdManager), which the bridge used, answered iPhones' network-wide
  queries for `_companion-link` only some of the time (captured: iPhone multicast queries unanswered, direct ones
  answered), and advertised the panel as `Android.local` with its rotating temporary IPv6 addresses; the owner's iPhone
  came back only after something made Android re-announce everything (`adb root`). The native mdns-sd library was tried
  again (limited to IPv4/Wi-Fi) but sent nothing on Wi-Fi.
- **Fix:** a small responder inside the bridge (`Bonjour.kt`, plain Java multicast socket on wlan0): answers every
  multicast, QU and direct query for both services, always with the current Wi-Fi IPv4 address, as host
  `Guptas-TV-Remote.local` (no IPv6), and announces at start (3×) and every minute. NsdManager is no longer used;
  `server.rs` keeps mdns-sd off on Android. Its announcements were checked on the wire with tcpdump (9 well-formed
  records). Note: the owner's Mac can't exchange multicast with the panel through the router, so it can't be used to test
  discovery; an iPhone can.
- Previous APKs: `backup-before-wipe/FakeAtv-dpl-before-own-mdns.apk` (NsdManager version). adb was rooted a few times
  for tcpdump and is back to uid 2000.
- **Update, same evening:** the first version of the responder opened only an IPv6 dual-stack socket that never received
  IPv4 queries. It now uses two sockets on wlan0 (IPv4 224.0.0.251 and IPv6 ff02::fb), both answering with the IPv4
  address. Confirmed: log shows answers to the owner's iPhone (192.168.1.22) over IPv4 and IPv6, and the iPhone connected
  over IPv4; the owner reports the Remote connects. Check anytime with `adb logcat -s Bonjour`.

## 30 Oct 2026, AirPlay from the iPhone; dock 2.3 and the remote

- **AirPlay:** the owner mirrored from the iPhone to "Gupta's TV" and it works (Mac already tested). 4K/H.265 and
  music-only AirPlay not checked separately.
- **Dock from inside apps:** holding Back (`<`) on the iPhone remote about 1 s now reaches the dock in any app. The bridge
  (`session.rs`: `menu_down_at`, a held Back becomes the `dock` button, same 0.8 s as the other holds; `AtvService.onButton`)
  sends `com.gupta.dock.action.FOCUS_DOCK` to `com.gupta.dock` instead of a Back key; the dock (2.3) opens and takes focus
  as it does from Home, doesn't fold while the remote is in it, and folds 5 s after focus goes back. In cursor mode the bridge
  passes `cursor=true` and the dock only opens. A second hold, Up or Back leaves. Tested with the broadcast during YouTube:
  dock window took focus, second broadcast gave it back to YouTube. Not yet tried with the iPhone itself, and the white
  focus ring still needs a look on the real screen.
- **Dock messages:** the last toasts (Close app's "nothing to close" / "the TV needs this one" / failure, Restart failure,
  "Not available") now use the glass notice above the bar, since toasts hid behind the dock.
- Cargo tests pass; both installed with `-r` (pairing kept, no force-stop), both accessibility services bound.
- Undo: `adb install -r -d backup-before-wipe/com.gupta.dock-before-dock-leftovers-20261030-1828.apk` (`-d`: 2.3 has
  version code 14, 2.2 had 13); for the bridge push `backup-before-wipe/com.corvofeng.fakeatv-before-dock-leftovers-20261030-1828.apk`
  to /data/local/tmp/f.apk and `pm install -r` it (same version code).
- **Fix, same evening:** holding Back on Home put the remote in the dock, but the Home tile kept its ring (the launcher only
  dropped it when it had sent `FOCUS_BELOW` itself). The launcher (`MainActivity.overlayFocusCheck`, local uncommitted) now
  drops the focused view's highlight whenever window focus goes to an overlay while Home stays resumed (checked 200 ms after
  the focus loss, so starting an app doesn't count), and puts it back when focus returns. Launcher APK 543,363 B. Undo:
  `adb install -r backup-before-wipe/com.gathin.tvhome-before-overlay-focus-*.apk`.
- **Confirmed by the owner with the iPhone:** hold Back reaches the dock, the white focus ring shows in the dock, and the
  Home tile's ring goes away while the dock has focus. Dock leftovers (open item 4) done.
- **AirPlay from the iPhone (open item 2) closed:** the owner reports iPhone AirPlay to "Gupta's TV" works smoothly.

## 30 Oct 2026, whiteboard trial: myViewBoard and xnotes

- Installed side by side with RuiPin's whiteboard (`com.fqcx.wb`, untouched; the Home Whiteboard tile still opens it), for
  the owner to try with a finger:
  - **myViewBoard** `com.viewsonic.droid` 3.12.204 (min SDK 30, armeabi-v7a included), the APK from ViewSonic's own link
    (`dl.myviewboard.com/latest?android_open` → `MVBA_OPEN_v3.12.204.apk`, 421 MB), signer `O=ViewSonic`, SHA-256 of the
    APK `440e5548…b122e`. Asks for camera, microphone, overlay and install permissions (none granted yet) and starts at
    boot. Not from Aurora, so it won't auto-update unless the Play build shares its signer.
  - **xnotes** `com.xnotes` 0.9.5 (MIT, from F-Droid `com.xnotes_66.apk`, 36 MB), signer `CN=Shardul Singh`, APK SHA-256
    `55c307e8…1950c`. Offline, no account; microphone only for its optional audio notes.
- Both are in the App library, not on Home. Storage 2.3 → 2.7 GB used.
- Undo: `adb uninstall com.viewsonic.droid`, `adb uninstall com.xnotes`.

## 30 Oct 2026, evening: ADB lockdown started (dock 2.4), paused with a bug

- **Panel restarted ~20:17** (not by us); everything came back (Wi-Fi, Home, both accessibility services).
- **Findings:** `ro.adb.secure` unset (no auth), `ro.debuggable` 0 but `adb root` works, verity green and /system read-only
  (no init .rc can be added), no `su`, **SELinux permissive**, so plain shell may set `service.adb.listen_addrs` and
  `ctl.restart adbd` (denials logged with permissive=1). Android 11 adbd with `service.adb.listen_addrs=tcp:localhost:5555`
  listens on loopback only (adbd source: socket_spec.cpp, `tcp_host_is_local`). The bridge reconnects by itself after an
  adbd restart (`LocalAdbDriver.sendLine` → `initAsync`).
- **Dock 2.4** (versionCode 15): new `AdbGuard.java`. On every dock start (boot) it checks `/proc/net/tcp{,6}` for a 5555
  listener on the any-address and, if found, sets `service.adb.listen_addrs tcp:localhost:5555` + `ctl.restart adbd`
  (retries every 5 s, 36 tries). `LocalAdb.output()` added (reads WRTE payloads until CLSE). New More tools tile
  **Mac ADB** (laptop icon, slate): clears the property and restarts adbd for 15 min, then re-locks; tapping again locks.
- **Result:** installed 20:23; within seconds the Mac got "Connection refused" on 5555 (lock works).
- **Bug, unresolved:** the owner pressed Mac ADB and 5555 stayed refused for 25+ s. Not diagnosed (no ADB from the Mac).
  Suspects: `setprop service.adb.listen_addrs ''` via `LocalAdb.output` not taking effect, the restart not happening, or
  adbd on restart still seeing the old value. Ask the owner what the pill said ("ADB open … until …" vs "Couldn't
  change ADB"). Not verified yet: bridge keys after the lock, the dock's Close app / Restart / Clean RAM after the lock.
- **Getting back in:** full power-off and on (hold power) clears the non-persistent property; 5555 is open for ~30 s
  after boot until the dock starts. In that window: `adb connect 192.168.1.2:5555` then
  `adb install -r -d backup-before-wipe/com.gupta.dock-2.3-before-adb-guard.apk` (removes the lock), or
  `adb shell pm disable-user com.gupta.dock` if speed matters (re-enable later; the accessibility entry stays).
- Undo: install the 2.3 backup above in that window; then `adb shell 'setprop service.adb.listen_addrs ""; setprop ctl.restart adbd'`
  if adbd is still local-only.
