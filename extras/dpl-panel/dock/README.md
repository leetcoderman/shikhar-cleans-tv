# Gupta's TV dock

An Accessibility service (`com.gupta.dock/.DockService`) that draws a glass bar at the bottom of the DPL panel,
over every app, for touch and for the iPhone remote.

```
Back  Home  Close | Inputs  Annotate  Screenshot | −  Speaker  + | −  Sun  + | More tools  Android settings  Clean RAM  Power
```

Each button is a coloured rounded tile with a white glyph, Mac-dock style (graphite Back, blue Home, dark red Close app, indigo Inputs,
orange Annotate, teal Screenshot, pink speaker, yellow sun, purple More tools, green Android head for Android settings, slate chip for Clean RAM, red Power).

## How it behaves

- **On Home** (`com.gathin.tvhome`) the bar stays open.
- **Inside an app** it folds to a small arrow at the bottom centre. A tap on the arrow opens it, and it folds again
  5 s after the last touch. A screen change inside the same app (YouTube does this a lot) leaves it alone; only a
  different app coming to the front folds it.
- **Which app is in front** comes from the window list (the topmost app window; the dock, Android's and System UI's
  dialogs and RuiPin's tool screens are looked through), checked 150 ms after any window event. Before 2.1 it came
  from the window event itself and only counted events naming an Activity, so a Home event landing after an app's own,
  or an app whose screens report fragments, dialogs or bottom sheets, could leave the bar open inside the app.
- **While a RuiPin tool is up** (crop, curtain, annotation, magnifier, spotlight, recorder, timer screen) the dock
  hides completely, arrow included: those tools put their own controls at the bottom centre. It comes back when no
  RuiPin tool screen is left in the window list. The small floating timer clock and RuiPin's side bars don't count.
- **Remote** (iPhone Apple TV Remote, or any D-pad): on Home, press Down on the last row of tiles and a white ring
  moves into the dock. Left/Right move it, select presses, holding select on − or + keeps stepping. Up (or Back)
  hands focus back to the tiles. With More tools open, Up moves into its tiles and Back closes it. Focus also goes
  back to Home after 15 s without a key, or when Home is shown again.
  - How: the iPhone bridge injects its keys, and injected keys never reach an accessibility key filter. So the
    launcher broadcasts `com.gathin.tvhome.action.FOCUS_BELOW` when Down has nowhere to go on its last row, and the
    dock makes its window focusable (normally `FLAG_NOT_FOCUSABLE`) until you leave.
  - **Inside apps (2.3): hold Back (`<`) about 1 s** on the iPhone remote. The bridge sends
    `com.gupta.dock.action.FOCUS_DOCK` instead of Back; the dock opens and takes focus the same way (Left/Right,
    select, Up or Back to leave; hold Back again also leaves). It folds 5 s after focus goes back to the app. In
    cursor mode (Netflix, TV Bro, RuiPin Settings) it only opens and the cursor clicks it. Ignored while a RuiPin tool
    has the screen.
- **Screenshot** hides the dock first, then opens RuiPin's crop tool, because the tool freezes the screen as it opens.

## Buttons

| Button | What it opens |
|---|---|
| Back, Home | Android's global Back and Home |
| Close app (dark red X) | Fully stops the app in front (as Settings' Force stop does) and goes Home, through `am force-stop` over the local ADB (`LocalAdb.java`). Refuses Home, the iPhone remote bridge, the dock, AirPlay, the keyboard and RuiPin/Amlogic system apps (`keepsTvRunning`); on Home it says there's nothing to close |
| Inputs | RuiPin's source picker (`android.intent.action.RPSource`) |
| Annotate | RuiPin annotation over the screen (`RPAnnotation`). Its exit button asks "Save file to exit annotation?" |
| Screenshot | RuiPin crop tool (`RPCut`) |
| Volume − speaker + | media volume (speaker, 0–100) down/up by 5, holding repeats; the speaker mutes and unmutes |
| Brightness − sun + | RuiPin's backlight down/up by 5 (`action.com.rp.brightness.down` / `.up`), holding repeats; RuiPin shows its own slider at the top |
| More tools (wrench) | pop-up: Record (`RPScreenRecord`), Timer (`android.intent.action.timer`), Spotlight (`RPSpotlight`), Magnifier (`RPMagnifier`), Curtain (`RPCurtain`), Bluetooth (RP Settings' Bluetooth page), AirPlay (`com.gupta.airplayreceiver`'s screen), iPhone remote (`com.corvofeng.fakeatv`'s screen) |
| Android settings | Android TV Settings (`com.android.tv.settings`, explicitly). The panel's own RuiPin settings are a different app, reached from Home's top-bar gear |
| Clean RAM (slate chip) | Kills cached and empty background processes with `am kill-all` over the local ADB (`LocalAdb.java`, like Restart; not force-stop), then shows "Freed N MB · X GB free" in a small glass notice above the bar for 3.5 s (from `ActivityManager.MemoryInfo.availMem` before and 1.5 s after). Running apps and services (Home, the iPhone remote bridge, AirPlay, the dock, the keyboard) aren't cached and stay up. A toast would sit behind the dock, since Android 11 ignores a text toast's position; since 2.3 every message (Close app, Restart failures, "Not available") uses this notice |
| Power (red) | pop-up: **Power off** opens RuiPin's power-off screen (`android.intent.action.ShutDown`: Screen Off, Shut down OPS, or Shutdown after a 15 s countdown; Back, Home or a touch outside cancels). **Restart** sends `shell:reboot` to the panel's own unauthenticated ADB on 127.0.0.1:5555 (`LocalAdb.java`, needs the INTERNET permission); it stops working if ADB gets a key requirement. A soft restart once came back without Wi-Fi (CHANGES.md); a full power-off fixes that. |

Not in the dock: eye protection and freeze screen (system-only in RuiPin), Recents (Launcher3's quickstep crashes on
this firmware), and pinned apps (removed at the owner's request; Home has the tiles).

## Tested on the panel, 30 Oct 2026

2.2: Clean RAM (left of Power) on Home after leaving TV Bro: "Freed 91 MB · 2.0 GB free" above the bar; the iPhone
bridge, AirPlay receiver, Home and the dock kept their processes.

2.1 (window-list front app): the bar stays open on Home and folds to the arrow in Aurora Store (main screen and its
settings), TV Bro, Netflix and Android TV Settings; in Aurora the arrow opens it and it folds again after 5 s; it
hides under RuiPin's curtain and the arrow comes back when the curtain closes; the remote broadcast still moves focus
into the dock on Home, and Back hands it back.

2.0:

Inside TV Bro: the arrow opens the bar, Volume and More tools pop-ups open and close, the bar folds after 5 s, and
every button opens its screen. Curtain, crop and annotation close with their own buttons and the arrow comes back.

Known quirks (RuiPin's, not the dock's):
- The timer's X shrinks it to a floating clock instead of closing it, and the shell can't stop its service.
- Annotation's exit dialog stays on top of everything until you answer it.

## Build and install

**Only for panels with RuiPin's firmware** (`com.rp.*` / `com.fqcx.*` apps: DPL and other interactive whiteboards).
Inputs, Annotate, Screenshot, brightness, More tools and Power open RuiPin screens; on any other TV those buttons do
nothing ("Not available"), though Back, Home, Close app, volume and Clean RAM still work. Close app, Clean RAM and
Restart need the TV's plain ADB on 127.0.0.1:5555 without a key prompt, as on the DPL panel.

```bash
cd extras/dpl-panel/dock
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew --no-daemon assembleRelease
adb -s $TV install -r app/build/outputs/apk/release/app-release.apk
```

Without a key it is signed with the build computer's debug key. For your own: `keystore/dock.p12` (alias `guptas-dock`)
and its password in `keystore/password.txt` (git-ignored); keep both, or updates won't install over the current app.

Then enable it by **appending** to the accessibility list (never overwrite it; the iPhone remote bridge lives there too):

```bash
adb -s $TV shell 'cur=$(settings get secure enabled_accessibility_services); [ "$cur" = null ] && cur=; settings put secure enabled_accessibility_services "${cur:+$cur:}com.gupta.dock/.DockService"; settings put secure accessibility_enabled 1'
adb -s $TV shell settings get secure enabled_accessibility_services
# e.g. com.corvofeng.fakeatv/com.corvofeng.fakeatv.AtvAccessibilityService:com.gupta.dock/.DockService
```

An update keeps the service enabled.

The Home screen's ring hand-off (Down from the last row of tiles, `com.gathin.tvhome.action.FOCUS_BELOW`) needs this
repo's launcher; holding Back on the iPhone remote needs this repo's bridge (`extras/iphone-remote`).

### ADB lockdown (off in this copy)

Dock 2.4 added `AdbGuard.java`: at every boot it makes adbd listen on localhost only, so nobody on the Wi-Fi gets a
shell, and More tools → **Mac ADB** opens it again for 15 minutes. On the first panel that tile failed to reopen ADB,
and the only way back in was a full power-off (hold power) and connecting in the ~30 s before the dock starts. So it
is **switched off here** (`ADB_GUARD = false` in `DockService.java`). Turn it on only once you can reach the TV some
other way, and test the Mac ADB tile straight away.

## Remove

Remove `com.gupta.dock/.DockService` from `enabled_accessibility_services` (keep the bridge's entry), then
`adb shell pm uninstall com.gupta.dock`.
