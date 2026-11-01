# iPhone Apple TV Remote on the Haier MatrixTV EE

The TV is controlled from the **Apple TV Remote in the iPhone's Control Center**, including typing
with the iPhone keyboard. A patched build of [atv-core](https://github.com/corvofeng/atv-core)
("FakeAtv") makes the TV announce itself as an Apple TV and turns remote buttons and typing into
Android key presses. It needs no computer: since 24 Oct 2026 the Mac is cut off from the TV.

TV: Haier MatrixTV EE (`omonia`), Google TV 14 / API 34, firmware UKN5.251016.114, CVTE/MediaTek,
1.8 GB RAM, 32-bit userspace.

## What was installed and changed on the TV

| What | Detail |
|---|---|
| App | `com.corvofeng.fakeatv` ("Apple TV Remote Receiver"), built here from upstream commit `8a4ada29` plus the local patches below. Build on the TV: (not in this repo; build it with `extras/iphone-remote/build.sh`), SHA-256 `b086e00a…d313` (installed 24 Oct 2026, 04:22), signed with your own keystore (`extras/iphone-remote/keystore/`, made by `build.sh`) (alias `fakeatv`). Always sign updates with this keystore so `pm install -r` works. The build before it is `FakeAtv-local-a6a00c4e.apk`. |
| Accessibility | `com.corvofeng.fakeatv/.AtvAccessibilityService` enabled, next to Sleep Timer's `SleepService`. Needed for the keyboard (finding text fields, setting their text). |
| Permission | `WRITE_SECURE_SETTINGS`, granted with `pm grant`. The app uses it to switch Wireless debugging on at boot and to set `adb_allowed_connection_time=0` (its ADB approval never expires). |
| ADB | Wireless debugging stays **on**. The only approved ADB key is the bridge's own, listed under Wireless debugging → Paired devices as **`fakeatv@Haier_MatrixTV_EE`** (approved 24 Oct 2026, 04:23). USB debugging is off. Plain ADB (5555) is closed. |
| Home dock | `com.corvofeng.fakeatv` added to `dock_apps` in `launcher/app/src/main/res/values/config.xml`. |
| Stremio | `com.stremio.one` 1.10.4 sideloaded from dl.strem.io (signer `O=Stremio`, cert SHA-256 `7e6a979c…2c6b`); no auto-update. Unrelated to the remote. |

Debloat lists were not changed.

## Local patches to atv-core

Full diff: `extras/iphone-remote/atv-core.patch` (together with the DPL changes) (nothing committed upstream).

1. **Debug inspector off on Android**, bound to 127.0.0.1 elsewhere (upstream PR #7). Upstream exposed
   it on 0.0.0.0:8765 with no auth, and `POST /api/action` put any string into an ADB shell line:
   command execution for anyone on the Wi-Fi, or any web page via the browser.
2. **Only whitelisted key names reach the ADB shell** (`LocalAdbDriver.KEY_MAP`).
3. **Pairing key kept**: a random 32-byte seed stored in the app's prefs. Upstream made a new key
   every start, so iPhones had to pair again after every reboot.
4. **Aliyun Maven mirrors removed** (Google and Maven Central only).
5. **dadb replaced by `AdbTls.kt`**, adapted from Shizuku's Apache-2.0 ADB client: plain 5555 or
   Wireless debugging (TLS) with the app's own key, port found by local mDNS, Wireless debugging
   switched on at boot, reconnect in the background. BouncyCastle 1.70 for the certificate.
   The key is kept in **device-protected storage** (`LocalAdbDriver.keyDir`), and is named
   `fakeatv@Haier_MatrixTV_EE` (no spaces, so Settings shows the whole name). Until build
   `b086e00a` it was kept in credential-protected storage, which is still locked when the bridge
   starts during Direct Boot. When the bridge started in that window, the key looked missing, so it
   made a new one, which overwrote the approved key once storage unlocked. After such a reboot the
   TV refused the bridge, and the D-pad stopped working inside apps. Seen on 24 Oct 2026: the
   bridge started 0.7 s before `sys.user.0.ce_available`. The fixed build moved the old key over
   once, and it never makes a key while storage is locked.
6. **Restarts the server and Bonjour when the TV's IP changes** (seen working: .22 → .11 without a
   reboot). Android 14 receiver flag fix. iCloud account ID kept out of logcat.
7. Apple-and-remote **tile banner**.
8. **iPhone keyboard** (Remote Text Input, `crates/atv-core/src/text_input.rs`, own code; the archive
   layout follows a real Apple TV capture documented in thiccaxe/CompanionGames, AGPL, nothing
   copied). The TV answers `_tiStart` and pushes `_tiStarted`/`_tiStopped` when a text field gets
   or loses focus, and decodes `_tiC`: typed text, deletions, `textToAssert`, and **QuickType
   suggestions / autocorrect** (`acceptedCandidate`: `candidate` replaces the typed `input`).
   Frame reading runs in its own task (`read_exact` is not cancel-safe).
9. **Text goes in two ways**: Accessibility `ACTION_SET_TEXT` when Android can see the field (Play
   Store, Stremio, JioHotstar); otherwise typed over local ADB with `input text`, one character per
   command (Netflix drops a repeated letter otherwise). The ADB path remembers what it typed and
   sends only the difference to what the iPhone shows, and never sends Delete into an empty box
   (YouTube takes that as Back).
10. **YouTube opens the keyboard by itself** (`YouTubeSearchWatcher.kt`): YouTube draws its own
    keyboard and shows Accessibility nothing, but its log says `Navigated to …/tv#/search`. The
    bridge follows that one line over a second local ADB stream (`logcat -s cobalt:I | grep
    'Navigated to '`); only the route is read, URLs are never logged. Before the first letter it
    sends one D-pad Right, because YouTube's search opens with the highlight on its left menu, where
    letters do nothing. The iPhone's return key does nothing in YouTube (Enter would press the
    highlighted key or the mic).
11. **Hold play/pause ~1 s to open or close the iPhone keyboard** in any app (`KEYBOARD_HOLD` =
    800 ms in `session.rs`). For Netflix and other apps that draw their own keyboard. A short press
    still plays and pauses. This replaced an earlier Siri-button trigger: the Control Center remote
    on this iPhone has no Siri button.
12. Diagnostic logging of keyboard messages the decoder cannot read: field names and text lengths
    only, never what is typed (`AtvService: onEvent: TextInput << _tiC …`, `Keyboard: insert N,
    delete N, assert N -> …`).

## What works

| | Result |
|---|---|
| D-pad, select, Back, Home, play/pause, volume, mute | ✅ via local ADB, ~80 ms per key. The iPhone remote is the touchpad kind: swipe for the D-pad, tap to select. |
| Survives reboots, without the Mac | ✅ with build `b086e00a`. On 24 Oct 2026: a reboot where the bridge started before storage unlocked, then a Settings → Restart with the Mac cut off (new port, the TV refuses the Mac, 5555 closed). After both, the bridge reconnected by itself about 50 s after boot, and the D-pad in apps, Back and Home were tested by hand. Older builds could lose their key on a reboot (patch 5). Standby is not a reboot. |
| Survives an IP change | ✅ |
| Keyboard: Play Store, Stremio, JioHotstar | ✅ opens by itself when a search box gets focus |
| Keyboard: YouTube | ✅ opens by itself on YouTube's search screen |
| Keyboard: Netflix | ✅ hold play/pause on the search screen; letters appear as you type. The double-letter fix (one character per command) was not checked on its own. |
| QuickType suggestions | ✅ checked: next-word suggestions, 21 characters on the TV matched the iPhone exactly |

## What doesn't, or isn't checked

- **Back doesn't take you out of Netflix.** Inside Netflix, Back works on both remotes. On
  Netflix's top screen it never leaves the app, not even with the physical Haier remote or after a
  force-stop of Netflix. `getevent` shows the TV receives `KEY_BACK`, Netflix gets it, no
  Accessibility service filters keys, and Netflix never tries to close itself. So this is
  Netflix's own behaviour (13.2.1). Use **Home** on the Haier remote or the **TV** button on the
  iPhone.
- **YouTube Music** (`com.google.android.youtube.tvmusic`) is a separate app; it does not open the
  keyboard by itself. Hold play/pause should open it, but its search may need the same D-pad-Right
  trick as YouTube. Not tested.
- **SwiftKey** and other third-party iPhone keyboards: not verified after the QuickType fix.
  Replacing a half-typed word with a suggestion (`hel` → `hello`) and autocorrect are covered by a
  unit test but not tested by hand.
- Typing over ADB (YouTube, Netflix) is **ASCII only** (`input text`). Fields Accessibility can set
  take any language.
- After adbd restarts (e.g. Wireless debugging toggled), the bridge reconnects on the **next key
  press**, not by itself; the first key may be slow, and YouTube's automatic keyboard is back after
  that press.
- **For about a minute after boot**, the D-pad moves only on Home, not inside apps. Android
  switches Wireless debugging off at boot when Wi-Fi isn't up yet. The bridge switches it back on
  when the network comes up, then logs in. Until then keys go through Accessibility, which handles
  Home and select but not the D-pad inside apps.
- The bridge's log says `via [本地 ADB (127.0.0.1:5555)]` for every key over ADB. That text is
  fixed: the connection is usually the Wireless debugging port.

## Known limitations (security)

- **Any device on the Wi-Fi can send remote keys.** The pairing PIN is fixed (`1111`) and
  pair-verify does not check which iPhone it is. A fix was offered and declined.
- **Wireless debugging stays on.** That is how the bridge presses keys. adbd listens on a random
  port, TLS only, and only accepts keys that are already approved: today only the bridge's.
  A new computer cannot get in without someone at the TV either pairing it with a code shown on
  screen or tapping Allow. Never tap "Allow" on an ADB prompt you did not expect.
- The iPhone's **Power** button sends `KEYCODE_POWER`.
- **Never forward any port to the TV on the router.** Keep AP/client isolation and guest Wi-Fi off
  for the TV and iPhone, or they cannot see each other.
- Give the TV a **DHCP reservation** in the router: its address has changed after reboots and
  Wi-Fi changes (.22 ↔ .11). The bridge copes, but the Mac scripts and these notes are easier with
  a fixed address.

Open ports checked on 24 Oct 2026 against `atv-remote/baseline/ports-before.txt`: new are only the
bridge (49152 MRP, 49153 Companion, one AirPlay stub port), the Wireless debugging TLS port,
Chromecast built-in (`mediashell`, random ports) and Spotify Connect. Plain 5555 is closed.
Checked again at 04:05 the same day, with the same result.

## Pair an iPhone (again)

Control Center → Apple TV Remote → pick **Haier MatrixTV EE** → PIN **1111**. If the remote is not
in Control Center: Settings → Control Center → add Apple TV Remote. Pairing survives reboots.

## Everyday use of the keyboard

- Most apps: go to the search box; the iPhone keyboard appears.
- YouTube: open Search; the keyboard appears. Pick a suggestion with the remote afterwards.
- Netflix and other apps that draw their own keyboard: on the search screen, **hold play/pause about
  1 second**. Hold again (or press Back/Home/select/short play-pause) to close it.

## After a reboot

Nothing to do. If the remote stops answering after a reboot:

1. Wait a minute; the bridge switches Wireless debugging on and reconnects by itself.
2. If Android shows **"Allow wireless debugging on this network?"**, tick **Always allow**, Allow.
3. Open the Apple TV Remote Receiver app from the dock. In its status line, `ADB[●已连]` means local
   ADB is connected and `ADB[○断开]` means it is not. After `当前生效:` it shows the path the last
   key took: `本地 ADB` (ADB) or `无障碍服务` (the Accessibility fallback). Wireless debugging →
   Paired devices also shows `fakeatv@Haier_MatrixTV_EE` as "Currently connected" when the bridge
   is in.
4. If the Wi-Fi network changed (new router, the other band with another name), step 2 comes back
   once for that network.
5. If the D-pad still works only on Home after that, the TV no longer accepts the bridge's key.
   Re-approve it as described in the next section.

## After a firmware update

An update can re-enable debloated apps, give Home back to the stock launcher, revoke the special
permission or switch Accessibility services off. Check, from the TV:

- Settings → Accessibility: the Apple TV Remote Receiver service is on.
- Developer options still exists and Wireless debugging is on.
- The iPhone remote moves around Home, and a Play Store search opens the iPhone keyboard.

To check the debloat lists with `after-firmware-update.sh`, pair the Mac first (next section).
That script's comments are older than the lock-down: the Mac is **no longer** paired, and do
**not** switch Wireless debugging off at the end (the bridge needs it). Switching USB debugging off
is fine.

## Letting the Mac in again (updates, checks)

1. TV: Developer options → Wireless debugging → **Pair device with pairing code**.
2. Mac: `/opt/homebrew/bin/adb pair <tv ip>:<pairing port>` and enter the code, then
   `adb connect <tv ip>:<port shown on the Wireless debugging screen>`.
3. To update the bridge: build and sign as in `IPHONE-REMOTE-CONTINUE-PROMPT.md`, then
   `adb push FakeAtv-local.apk /data/local/tmp/f.apk` and `adb shell pm install -r /data/local/tmp/f.apk`.
   **Never `am force-stop com.corvofeng.fakeatv`**: on Android 14 that removes its Accessibility
   service from the enabled list, and putting it back is an Accessibility change (reboot after).
4. Cut the Mac off again: Wireless debugging → Paired devices → **your computer's entry** →
   **Forget**, and `adb kill-server` on the Mac. The Mac's key fingerprint starts `3B:2B:E3:5A`.
   **Never forget `fakeatv@Haier_MatrixTV_EE`**, and never use "Revoke USB debugging
   authorizations": either one removes the bridge's key, and the remote stops working inside apps.
   Forgetting one row keeps the others; Android rewrites the key list from the rows that are left.
   Check from the Mac that it is refused: `adb connect <tv ip>:<tls port>` fails. After a real
   restart the port changes, and 5555 is closed.

### Re-approving the bridge's key

It's needed when Paired devices no longer lists `fakeatv@Haier_MatrixTV_EE`, or the TV refuses
the bridge (log: `Local ADB on port … failed: … CERTIFICATE_UNKNOWN`). A new key gets the
"Allow USB debugging?" prompt only on plain ADB, so:

1. Pair the Mac (steps 1–2 above). On the Mac: `adb tcpip 5555` (open until the next reboot),
   then `adb kill-server`.
2. On the iPhone, swipe once. The bridge tries 127.0.0.1:5555 and waits 60 s for approval.
3. On the TV: **"Allow USB debugging?"** → tick **Always allow** → **Allow**. Deny it if the
   fingerprint starts `3B:2B:E3:5A` (that's the Mac).
4. Check it, then cut the Mac off again (step 4). A row left by an old bridge key can be forgotten
   once the new `fakeatv@Haier_MatrixTV_EE` row is there.

## Undo everything

From a paired Mac (`adb shell …`), one step per pass with a reboot after the Accessibility change:

1. `pm uninstall com.corvofeng.fakeatv`: removes the app, its Accessibility service and its
   permission. Its ADB key stays approved until step 3.
2. `settings put secure enabled_accessibility_services io.github.luyzfernando08.sleeptimer/io.github.luyzfernando08.sleeptimer.SleepService`
   (only Sleep Timer's service left), if the uninstall did not already remove the entry.
3. Developer options → **Revoke USB debugging authorizations**.
4. Developer options → Wireless debugging **off**, and
   `settings put global adb_allowed_connection_time 604800000` (Android's default, 7 days).
5. Take `com.corvofeng.fakeatv` out of `dock_apps` in `config.xml` and reinstall the launcher
   (`launcher/build-and-install.sh`).
6. If you don't want Stremio: `pm uninstall com.stremio.one`.

Nothing needs undoing on the iPhone; once the app is gone the TV no longer appears in the Apple TV
Remote's device list.

## Files

- `atv-remote/atv-core/`: the source, upstream clone with uncommitted local changes.
- (not in this repo; build it with `extras/iphone-remote/build.sh`): the build on the TV. Older builds `FakeAtv-local-<hash>.apk` beside it.
  To go back to the build before the key-storage fix, `pm install -r FakeAtv-local-a6a00c4e.apk`
  (it would lose the key again on some reboots).
- your own keystore (`extras/iphone-remote/keystore/`, made by `build.sh`): the signing key (keep it, or updates need an uninstall first).
- `atv-remote/baseline/`: open ports and ADB/Accessibility settings before the install.
- `IPHONE-REMOTE-CONTINUE-PROMPT.md`: build commands and the working rules.
