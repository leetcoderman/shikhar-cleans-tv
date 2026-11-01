# iPhone Apple TV Remote for an Android TV

Any iPhone on the home Wi-Fi controls the TV from **Control Center → Apple TV Remote → <your TV>**: swipe for the
D-pad, tap to select, Back, Home, play/pause, volume, the iPhone **keyboard**, and a **cursor** for phone-style apps
such as Netflix. No computer needed after the setup.

It is [corvofeng/atv-core](https://github.com/corvofeng/atv-core) ("FakeAtv", commit `8a4ada29`) with our patches in
`atv-core.patch`: first made for a Haier MatrixTV EE (12 patches, `../haier-matrixtv-ee/IPHONE-REMOTE.md`), then
changed for the DPL panel "Gupta's TV" (below). Upstream has no licence file, so this repo carries only the patch;
`build.sh` downloads upstream and applies it.

## Set it up

1. **Name.** `./build.sh` once (it clones upstream into `atv-core/`, applies the patch and builds), then change
   `PANEL_NAME = "Gupta's TV"` in `atv-core/android-tv/app/src/main/java/com/corvofeng/fakeatv/DeviceInfo.kt` and run
   `./build.sh` again. (The app's `custom_device_name` setting also overrides it.)
2. **Build needs:** git, JDK 17, Android SDK (build-tools 34) + NDK 27.2.12479018, rustup with the Android targets and
   `cargo-ndk` (see `atv-core/scripts/build_android.sh`). The first build makes your own signing key in `keystore/`
   (git-ignored). Keep it: updates must be signed with the same key.
3. **The TV must give the bridge ADB to itself** (it injects keys over the TV's own ADB on 127.0.0.1):
   - **TV with plain network ADB on 5555 and no key prompt** (the DPL panel): nothing to do.
   - **Google TV / Android TV with Wireless debugging** (the Haier): after installing, grant
     `adb shell pm grant com.corvofeng.fakeatv android.permission.WRITE_SECURE_SETTINGS`; the bridge turns Wireless
     debugging on at boot. On the TV, accept its "Allow debugging?" prompt once with **Always allow**
     (it shows as `fakeatv@<model>`). Then you can remove your computer from the paired devices.
4. **Install and enable** (`TV` is the TV's `ip:port`):

   ```bash
   TV=192.168.1.50:5555 ./build.sh install
   adb -s $TV shell am start -n com.corvofeng.fakeatv/.MainActivity     # first install only
   # append the Accessibility service (never overwrite the list):
   adb -s $TV shell 'cur=$(settings get secure enabled_accessibility_services); [ "$cur" = null ] && cur=; settings put secure enabled_accessibility_services "${cur:+$cur:}com.corvofeng.fakeatv/com.corvofeng.fakeatv.AtvAccessibilityService"; settings put secure accessibility_enabled 1'
   ```

5. **Pair:** iPhone → Control Center → Apple TV Remote → your TV → PIN **1111**. Once per iPhone; survives restarts.
   If the remote isn't in Control Center: Settings → Control Center → add Apple TV Remote.

Rules: **never `am force-stop com.corvofeng.fakeatv`** (it can drop the Accessibility service), always update with
`pm install -r` (`./build.sh install` does). Logs: `adb logcat -s AtvService AtvNative Bonjour LocalAdbDriver AtvAccessibility`.

## Using it

| | |
|---|---|
| D-pad, select | Swipe on the touch area; tap to select. |
| Back, Home | `<` button; the TV button. |
| Dock | **Hold `<` about 1 s**: opens the dock from `../dpl-panel/dock` if it is installed (broadcast `com.gupta.dock.action.FOCUS_DOCK`). Without the dock a held Back does nothing. |
| Volume | The iPhone's volume buttons. |
| Keyboard | Opens by itself when a normal text field gets focus (Play Store, Aurora, Netflix sign-in, most TV apps) and on YouTube's search screen. Elsewhere **hold play/pause about 1 s**: it presses Search, then opens the iPhone keyboard. |
| Cursor | **On by itself in Netflix (phone build), TV Bro and RuiPin's Settings.** Anywhere else **hold the TV button about 1 s**. Slide slowly to move the arrow, tap to click, flick to scroll. |

## DPL changes to the Haier source

All of these, and the Haier patches, are in `atv-core.patch`.

1. **Name "Gupta's TV"** (`DeviceInfo.PANEL_NAME`; a `custom_device_name` pref still overrides it).
2. **No AirPlay stub** (`ADVERTISE_AIRPLAY_STUB = false` in `server.rs`). The iPhone remote doesn't need it.
3. **Bonjour answered by the bridge itself** (`Bonjour.kt`, since 30 Oct 2026; formerly Android's NsdManager, before
   that the native mdns-sd library). An IPv4 and an IPv6 multicast socket on wlan0 answer every query for
   `Gupta's TV._companion-link._tcp` / `._mediaremotetv._tcp` (multicast, QU and plain unicast) with host
   `Guptas-TV-Remote.local` and the current Wi-Fi **IPv4** address only, announce 3× at start and every minute, and log
   each answer plus a 10-minute count (`adb logcat -s Bonjour`). NsdManager answered iPhones' multicast queries only some
   of the time and advertised `Android.local` with rotating temporary IPv6 addresses; mdns-sd sent nothing on Wi-Fi.
   Java's default `MulticastSocket` is dual-stack here and never received IPv4 queries, hence two `DatagramChannel`s.
4. **Listens on IPv6 too.** Android's responder also hands out the panel's IPv6 addresses, iPhones
   try those first, and an IPv4-only bridge made the TV vanish from the iPhone's list.
5. **No server restart when the default network flips** between Wi-Fi and the wired link. It did
   flip, and each restart dropped the iPhone's session.
6. **Fast keys: `Injector.kt`.** `input keyevent` starts a Java runtime per key, about 0.9 s on this
   CPU. The bridge instead runs one injector over its local ADB shell
   (`CLASSPATH=<apk> exec app_process / com.corvofeng.fakeatv.Injector`) that reads `k`/`t`/`d`/`c`/`s`
   lines and injects keys, text, taps and drags in milliseconds. `exec` means nothing sent later
   can run as a shell command. If it doesn't start within 10 s, the bridge falls back to `input`.
7. **Hold play/pause** presses Search first (opens search in phone/TV apps), then opens the
   keyboard by hand if no text field appeared.
8. **Cursor mode** (hold the TV button, `session.rs`; `CursorOverlay.kt`; taps and drags through the injector).
9. **Netflix cursor and flicks** (`AtvAccessibilityService.followApp`, `flick`; `nativeSetTrackpadMode` in
   `atv-android/src/lib.rs` calls `AtvServer::set_trackpad_mode_transient`, which switches the touch surface without
   saving it as the default). The pre-change APK is in `backup-before-wipe/FakeAtv-dpl-before-netflix-cursor.apk`.
10. **Wi-Fi power save off while the bridge runs** (`AtvService`: a `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi lock). In power
   save this panel's Wi-Fi chip dropped incoming multicast, so iPhones' Bonjour questions never arrived and the Remote
   stopped listing Gupta's TV (the panel still announced itself). Check: `adb shell iw dev wlan0 get power_save` → off.
11. (Superseded by item 3: the bridge's own responder re-announces every minute.)

If one iPhone still doesn't list the TV while another does, reset that phone's view of the network: Wi-Fi off and on,
restart it, and in its Wi-Fi settings for this network turn Limit IP Address Tracking off and set Private Wi-Fi Address
to Off or Fixed.
9. **Native logs in logcat** under the tag `AtvNative` (start-up, connections, warnings only; typed
   text is never logged).

## Tested on the DPL panel (30 Oct 2026, with an iPhone)

| | Result |
|---|---|
| Discovery and pairing (PIN 1111) | ✅ |
| D-pad, select, Back, Home, volume | ✅ no noticeable delay since the injector |
| Keyboard in Play Store | ✅ opens by itself |
| Keyboard in YouTube TV | ✅ with hold play/pause. ❌ not automatic: this YouTube TV build (5.30) logs nothing and shows Accessibility nothing on its search screen, so the Haier's detection can't work. SmartTube would open the keyboard by itself (normal text field). |
| Netflix (phone build): cursor, tap, keyboard in the sign-in email field | ✅ |
| Cursor scroll (rest, then slide) | ⚠️ not confirmed yet |
| Bridge back after an app update | ✅ iPhone reconnected within 5 s (after the IPv6 fix) |
| Bridge back after a panel restart | ✅ **Full shutdown** (power button held), then power on, 03:54: the bridge started at boot by itself (Accessibility entry intact, key injector up ~30 s after boot). The iPhone found and connected to Gupta's TV **without re-pairing**; D-pad, select, Back, Home, volume, keyboard (Play Store) and cursor all worked. An earlier soft restart (~02:05) also brought the bridge back. A soft `adb reboot` at 03:40 came up with Wi-Fi unable to find its USB Wi-Fi module (panel issue, likely USB hub routing to the Windows slot, not the bridge); a hold-power full shutdown fixed it. |

Not tested: Prime Video, JioHotstar, SonyLIV, FanCode, Apple TV, Stremio keyboards (apps not
signed in yet); play/pause as a media key; mute; third-party iPhone keyboards.

## Known limitations and security

- **Anyone on the LAN can send remote keys**: the PIN is fixed (1111) and pair-verify doesn't check
  which iPhone it is (same as the Haier).
- The bridge depends on **plain ADB on 5555** being open on the panel, which also means anyone on
  the LAN gets a shell. When ADB is locked down later, either keep 5555 but enable authorization
  (the bridge's key then needs one "Allow USB debugging?" → Always allow on the panel), or move to
  Wireless debugging and `pm grant com.corvofeng.fakeatv android.permission.WRITE_SECURE_SETTINGS`
  so the bridge switches it on at boot, as on the Haier. Test the remote right after.
- The listeners are reachable on the panel's **global IPv6 address**. Home routers block inbound
  IPv6 by default; keep it that way and never forward ports to the panel.
- Typing over ADB (YouTube TV) is ASCII only; fields Accessibility can set take any language.
- After an app update the iPhone may need the remote closed and reopened once.

## Known limits and security

- **Anyone on the Wi-Fi can send remote keys**: the PIN is fixed (1111) and pair-verify doesn't check which iPhone it is.
- On a TV with plain ADB on 5555 (DPL), anyone on the Wi-Fi can also get a shell. That's the TV, not the bridge, but
  the bridge relies on it.
- The listeners (TCP 49152, 49153) are reachable on the TV's IPv6 address too. Keep the router's default of blocking
  inbound IPv6 and never forward ports to the TV.
- Typing over ADB (YouTube, Netflix) is ASCII only; fields Accessibility can set take any language.
- If one iPhone doesn't list the TV while another does: Wi-Fi off and on, restart it, and for this network turn
  Limit IP Address Tracking off and set Private Wi-Fi Address to Off or Fixed.

## Undo

```bash
adb -s $TV shell pm uninstall com.corvofeng.fakeatv
# if its entry is still in the list, put back only the others:
adb -s $TV shell settings get secure enabled_accessibility_services
```
