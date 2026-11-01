# DPL interactive panel: audit and plan

Audited 30 Oct 2026 over ADB. **Nothing has been changed on the panel yet.** The raw inventory is in
`baseline/` (`packages.txt`, `getprop.txt`, `settings.txt`).

## The device

| | |
|---|---|
| Model | DPL "Smart Interactive Tablet", an interactive whiteboard panel. Firmware `T982_C4DWPSA/A1/B_V1.0.0_20230213_BZ`. Vendor apps are by RuiPin (`com.rp.*`, `com.fqcx.*`). |
| Android | 11 (API 30), **userdebug / test-keys** build. 32-bit userspace (`armeabi-v7a`), 4 GB RAM, 32 GB storage. |
| Screen | 3840×2160 at 30 Hz, density 480. Touch and stylus both come from `RP Uart Touch`. |
| Network | **Wi-Fi `192.168.1.2` and Ethernet `192.168.1.128`**, both on the home LAN. The Mac and photo MAC `DC:36:43:6D:10:BE` belong to the Ethernet port. |
| ADB | Plain ADB on port 5555, **no authorization prompt**, on both interfaces. |
| Google | Play Services 26.39, Play Store 28.0.26 (old, updates itself), a Google account already signed in. Device is `leanback_only`, so the Play Store shows TV versions of apps. |
| Bluetooth | ✅ Realtek, switched on, named `ohm`, nothing paired. Pairing screen: RP Settings → Bluetooth. |
| Home screen | `com.rp.launcher` ("EduLauncher"), with the Windows button and "More Apps". |
| Mirroring | ✅ The **QuickShare** app (`com.bozee.usbdisplay`) already runs an **AirPlay receiver** named "Smart Interactive Tablet", plus DLNA. No Miracast receiver was found. |

## Apps

### Needed for what you asked: keep

| App | Package | Why |
|---|---|---|
| Netflix | `com.netflix.mediaclient` 9.90 | Phone version, works by touch. See the note on the TV versions below. |
| Prime Video | `com.amazon.avod.thirdpartyclient` 3.0 | Phone version, as above. |
| YouTube | `com.google.android.youtube` | |
| Play Store, Play Services, GSF | `com.android.vending`, `com.google.android.gms`, `.gsf` | Installing SonyLIV, FanCode, JioHotstar. |
| Chrome | `com.android.chrome` 155 | |
| Gboard | `com.google.android.inputmethod.latin` | On-screen keyboard. |
| Whiteboard | `com.fqcx.wb` | The built-in pen whiteboard, which becomes the Whiteboard tile. |
| QuickShare | `com.bozee.usbdisplay`, `com.bozee.andisplayguide` | Mac AirPlay mirroring and Windows casting. Its guide screen opens at every start; that can be switched off in the app. |
| RP Settings, RP Tools, panel services | `com.rp.rpsettings`, `com.fqcx.rptools`, `com.rp.rpservice`, `com.rp.rpsystemservice`, `com.rp.ui`, `com.rp.uhd` | Settings including Bluetooth, the side bars, brightness and volume pop-ups, the Windows/HDMI switching. |
| Droidlogic TV stack | `com.droidlogic.*` | HDMI inputs and the Windows slot. |
| Android system | `com.android.*`, `android.ext.*` | |

### Useful extras: keep unless you say otherwise

VLC (`org.videolan.vlc`), the file manager (`com.fqcx.rpfilemanager`), the image and video players
(`cn.myapp.nativeimageplayer`, `com.droidlogic.videoplayer`), the camera (`com.rp.camera2`).

### Your call

| App | Package | Note |
|---|---|---|
| Microsoft Teams | `com.microsoft.teams` (2022 build) | Outdated, built into the system. |
| Zoom | `us.zoom.videomeetings` 5.10 (2022) | Outdated, built into the system. |
| WPS Office | `cn.wps.moffice_eng` | Has its own signed-in account. |
| Calculator, Calendar, "Write number" tool | `com.rp.calculator`, `com.rp.calendar`, `com.rp.writenumbertool` | Classroom tools. |

### Proposed to disable (switched off, not uninstalled, so this can be undone)

| App | Package | Why |
|---|---|---|
| Memory cleaner | `com.rp.memoryclean` | Kills background apps, and could kill the iPhone remote bridge. |
| Welcome screen, product intro | `com.fqcx.welcome`, `com.rp.instroduce` | Vendor demo screens. |
| Music | `com.android.music` | Old AOSP player. |
| App lock | `com.rp.apklock` | Can lock apps behind a password. Disable only if you don't use it. |

Leave the factory apps (`com.rp.factorytest`, `com.rp.rpfactory`, `com.amlogic.factorymenu`) alone:
they cost nothing and are needed for service.

## Your goals: feasibility

| Goal | Status | How |
|---|---|---|
| Netflix, Prime Video, SonyLIV, FanCode, JioHotstar | 🟡 likely | Install the missing apps from the Play Store. Each app needs testing: this firmware is not Google-certified, so some apps may be hidden from the Play Store or play only in SD. Phone versions (touch) and TV versions (remote) can be compared per app. |
| Play Store | ✅ | Already installed and signed in. |
| Stremio | ✅ | Sideload the 32-bit build from strem.io, as on the Haier. |
| Pen whiteboard | ✅ | The built-in RuiPin whiteboard as a tile. Pen and finger need testing. |
| Tile home screen | ✅ | The Clean Your TV launcher from the Haier (`cleanyourtv-core/launcher`), with this panel's own app list. The RuiPin launcher stays installed as a fallback. Touch use and the Windows button (a "Windows PC" tile) need to be built and tested. |
| "Gupta's" animated backdrop | ✅ | A new animated backdrop in that launcher, in the style of the Haier's horizon scene. Design options are to be shown first. |
| iPhone Apple TV Remote | ✅ likely | Same bridge (`com.corvofeng.fakeatv`, the APK has a 32-bit build). It shows up next to QuickShare's AirPlay entry, so it gets its own name, for example "Gupta's TV Remote", while mirroring keeps "Smart Interactive Tablet". The PIN is fixed at 1111, as on the Haier. |
| Bluetooth speakers and earphones, AirPods | ✅ | Hardware present and on. Pair from RP Settings → Bluetooth (a Bluetooth tile can open it). Rename it from `ohm` to "Gupta's TV". AirPods connect as ordinary Bluetooth earphones (no automatic switching between Apple devices). |
| MacBook mirroring | ✅ likely | Already offered: Mac → Control Center → Screen Mirroring → "Smart Interactive Tablet". Needs a test from the Mac. |
| Windows mirroring | 🟡 | No Miracast (Windows + K) receiver found. Options: QuickShare's Windows client or its USB dongle (the guide screen shows both), or an HDMI cable. Needs a test from the PC. |

## Security

- **Anyone on the Wi-Fi or Ethernet LAN can get a shell on the panel** (open ADB on 5555, no prompt).
  After the setup, switch to Wireless debugging (pairing code, as on the Haier) and close 5555, if the
  firmware allows it.
- QuickShare accepts anyone on the LAN who mirrors to it. That's normal for a meeting-room panel.

## Proposed order

1. Back up the current state, disable the small list above, switch off QuickShare's start-up guide.
2. Install SonyLIV, FanCode, JioHotstar from the Play Store; sideload Stremio; test playback in each app.
3. Install the iPhone remote bridge and test it with an iPhone.
4. Build the tile launcher for this panel with the "Gupta's" backdrop (designs first), then set it as Home.
5. Test Bluetooth and both kinds of mirroring.
6. Lock down ADB.
