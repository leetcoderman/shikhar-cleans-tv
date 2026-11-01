# AirPlay screen mirroring receiver (built for "Gupta's TV", a DPL panel)

An Android app (`com.gupta.airplayreceiver`) that receives **AirPlay screen mirroring from a Mac**
(tested with macOS 27.0.1 on a Mac17,9) and shows it full screen, with sound, on the DPL interactive
panel. It appears on the Mac as **"Gupta's TV"**.

- Starts at boot as a foreground service and stays advertised on the LAN.
- When the Mac starts mirroring, a full-screen view opens on its own. When the Mac stops, the view
  closes and the panel returns to the app that was open before. Back on the panel's remote also
  ends the session.
- Sound plays on the panel's current media output: its speakers, or a paired Bluetooth device.
  The Mac's volume slider sets the stream volume; the panel's own volume is not touched.
- No ads, analytics or internet access. The only network traffic is AirPlay with the Mac on the LAN
  (mDNS on UDP 5353, the AirPlay control port and the media ports, all picked at random).
- Optional PIN, off by default: open the app → "Ask for a code on the Mac the first time it
  connects". The code then appears full screen on the panel.
- Optional 4K/H.265 mode (off by default; 1920×1080 H.264 at 30 fps is the tested setting).

## How it is built

| Part | Source | Licence |
|---|---|---|
| AirPlay protocol: pairing (legacy and SRP/PIN), FairPlay SAP handshake, stream decryption, RTSP/HTTP server, NTP, built-in mDNS responder | [UxPlay](https://github.com/FDH2/UxPlay) `lib/`, commit in `app/src/main/cpp/uxplay/UPSTREAM_COMMIT` | GPL-3.0 (UxPlay), with LGPL-2.1+ and MIT parts; notices kept in `app/src/main/cpp/uxplay/` |
| Crypto | OpenSSL 3.5.9 (LTS) `libcrypto`, static, armeabi-v7a | Apache-2.0, `third_party/openssl/LICENSE.txt` |
| Property lists | libplist 2.8.0 | LGPL-2.1+, `third_party/libplist/COPYING.LESSER` |
| JNI bridge (replaces UxPlay's GStreamer front end) | `app/src/main/cpp/airplay_jni.c` | GPL-3.0-or-later |
| Video: MediaCodec (`OMX.amlogic.avc.decoder`) onto a SurfaceView. Audio: AAC-ELD, ALAC or AAC-LC via MediaCodec → AudioTrack | `app/src/main/java/...` | GPL-3.0-or-later |

The whole app is therefore GPL-3.0.

**Changes to UxPlay** (all in `uxplay-android.patch`, two small patches to `lib/mdnsd`):
1. Android's `gethostname()` returns `localhost`, so UxPlay would advertise the host `localhost.local`
   (the same mistake QuickShare makes). The app sets the host label `Guptas-TV-Mirror` instead.
2. The app passes the panel's LAN IPv4 address for the mDNS A record (the default network first,
   then Ethernet, then Wi-Fi), so the record never points at a loopback or VPN address. It watches
   every network, not only the default one, and re-checks every 30 s. At boot the panel's internal
   Ethernet can briefly hold an address and then lose it, and the receiver must not keep announcing it.

**Names and ports, so the Apple TV Remote bridge doesn't clash:** service name "Gupta's TV", mDNS
host `Guptas-TV-Mirror.local`, a random locally administered device ID (stored in the app's
preferences), and ports chosen dynamically at every start. The bridge should use a different
service name (e.g. "Gupta's TV Remote") and a different host label.

## Use it on your own TV

Any Android 10+ TV or panel that can run 32-bit ARM apps (`armeabi-v7a`; almost all TVs, 32- or 64-bit) should
work; it was only tested on the DPL panel. The video decoder is whatever MediaCodec offers for H.264.

1. **Name.** Change `NAME` ("Gupta's TV", what the Mac and iPhone list) and `HOST_LABEL` (`Guptas-TV-Mirror`, letters,
   digits and dashes only) in `app/src/main/java/com/gupta/airplayreceiver/Prefs.java`. If you also run the iPhone remote
   bridge, it may use the same name, but its host label must differ (it uses `<Name>-Remote`).
2. **Build** (JDK 17, Android SDK, NDK 27.2.12479018):

   ```bash
   cd extras/airplay-receiver
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
   ./gradlew assembleRelease
   # → app/build/outputs/apk/release/app-release.apk (armeabi-v7a only, about 4 MB)
   ```

   The prebuilt `libcrypto.a` (OpenSSL 3.5.9) is checked in under `app/src/main/cpp/third_party/openssl/`. To rebuild
   it, download and verify an OpenSSL 3.5.x source tarball and run `scripts/build-openssl.sh /path/to/openssl-3.5.x`.

   **Signing:** without a key it is signed with the Android debug key of the computer that builds it, which is fine,
   but updates then have to be built on the same computer. For a key of your own, put a PKCS#12 file at
   `keystore/receiver.p12` (alias `guptas-tv`) and its password on one line in `keystore/password.txt`
   (`keystore/` is git-ignored). Keep both: Android refuses an update signed with a different key.
3. **Install** (`TV` is your TV's `ip:5555`):

   ```bash
   adb connect $TV
   adb -s $TV install -r app/build/outputs/apk/release/app-release.apk
   # lets the service open the mirroring screen from the background (Android 10+ rule):
   adb -s $TV shell appops set com.gupta.airplayreceiver SYSTEM_ALERT_WINDOW allow
   # first start (after that it starts by itself at boot):
   adb -s $TV shell am start -n com.gupta.airplayreceiver/.MainActivity
   ```

On the Mac: Control Center → Screen Mirroring → your TV's name. On an iPhone: Control Center → Screen Mirroring.

Logs: `adb -s $TV logcat -s GuptaAirPlay`.

## Undo

```bash
adb -s $TV shell pm uninstall com.gupta.airplayreceiver
```

This removes the app, its permission and its pairing key. Nothing else on the TV is changed.

## Why the built-in QuickShare receiver fails with macOS 27

QuickShare (`com.bozee.usbdisplay` 3.1.143) advertises AirPlay as "Smart Interactive Tablet" on port
7011. Its AirPlay code (`libairplay_sdk.so`) is built on the old RPiPlay/"AirplayServer" code base
(`fairplay.c`, ed25519 legacy pair-setup, an embedded Apple mDNSResponder). It was inspected with JADX,
`strings` and by sending it the same requests a Mac sends (the panel's own APK, not in this repo;
no code was copied). Three problems, the first two fatal:

1. **It doesn't answer the Mac's first request.** A modern Mac opens every mirroring session with
   `GET /info RTSP/1.0` and expects an RTSP reply carrying a binary plist: device ID, public key,
   features, audio formats, and the `displays` entry (resolution, refresh rate, maxFPS). QuickShare
   replies `HTTP/1.1 200 OK` with an empty body: the wrong protocol and no capability list. Without
   that the Mac shows "Could not connect", or starts a session the receiver can't describe, which
   leaves the panel on a black mirroring screen. Its `/server-info` (srcvers 120.2) is the AirPlay 1
   video endpoint, which macOS no longer uses for screen mirroring.
2. **Wrong pairing mode.** Its TXT record says `features=0X527FFFF7`: bit 27 ("supports legacy
   pairing") is off. That tells macOS to use AirPlay 2's HomeKit-style SRP pair-setup, but the
   library only implements the old ed25519 legacy pair-setup, so pairing can't succeed. (The library
   also contains a `0x5A7FFFF7` variant, which suggests the vendor toggled this.) UxPlay advertises
   `0x5A7FFEE6`: legacy pairing on, which current macOS accepts, and SRP only when a PIN is wanted.
3. **`localhost.local` host name.** Its embedded mDNS responder uses Android's `gethostname()`,
   which is `localhost`. On this LAN the Mac still resolves it to the panel (192.168.1.2), so this is not
   the failure on its own. But the name is reserved for loopback (RFC 6761) and is fragile, and the panel
   has two addresses (Wi-Fi .2, Ethernet .128).

For comparison, the same RTSP `GET /info` sent to this app returns `RTSP/1.0 200 OK` with an
870-byte plist (`model AppleTV3,2`, `sourceVersion 220.68`, `features 0x5A7FFEE6`, display
1920×1080 @ 30), and the Mac then pairs, sets up FairPlay and streams.

## Tested (30 Oct 2026)

- Mac17,9 on macOS 27.0.1 → "Gupta's TV": picture and sound confirmed by the owner. H.264
  1920×1080 decoded by `OMX.amlogic.avc.decoder.awesome2`; audio AAC-ELD 44.1 kHz stereo through
  `c2.android.aac.decoder`. 
- Panel restart: the service came back by itself at boot (foreground, advertised within about 30 s
  of boot), and mirroring from the Mac worked again, confirmed by the owner.

### Changes on 30 Oct 2026 (second round), not yet tested by the owner

- **Fixed: wrong address after a restart.** After the panel went Wi-Fi only, the receiver had
  picked up the internal Ethernet address (192.168.1.128) during boot and kept announcing it after
  it disappeared, so a Mac or iPhone could not have connected. It now follows address changes on
  every network and announces 192.168.1.2 (checked with an mDNS query from the Mac).
- **Added: ALAC for audio-only AirPlay** (Music on the Mac → AirPlay → Gupta's TV), using the panel's
  `OMX.google.alac.decoder` with AirPlay's fixed stream settings (352 samples/frame, 16 bit,
  44.1 kHz stereo). It builds and installs, but no one has played music through it yet.
- **Coexistence with the iPhone remote bridge** (`com.corvofeng.fakeatv`): it also uses the name
  "Gupta's TV", but only for `_companion-link` and `_mediaremotetv`. It doesn't advertise
  `_airplay`. The service types differ, so the names don't clash in mDNS, and Screen Mirroring
  lists only this receiver. Not yet checked on an iPhone.

## Known limits

- **Leaving the mirroring screen ends the session.** Home, Back, or another app coming to the front
  disconnects the Mac. AirPlay has no way to ask the Mac for a new key frame, so a picture
  interrupted mid-stream could not come back; disconnecting is the clean option. Pick "Gupta's TV" on
  the Mac again to restart.
- **ALAC (audio-only AirPlay) is untested** (see above). Uncompressed PCM audio is not supported.
- **AirPlay 2 "buffered" audio** (multi-room) is not offered; senders fall back to classic AirPlay audio.
- **No A/V sync buffer.** Video and audio play as soon as they arrive (lowest latency); they stayed
  in step in testing, but there is no lip-sync correction.
- **HLS "video URL" casting** (YouTube-style AirPlay video from an iPhone app) is not offered.
- **iPhone/iPad mirroring** uses the same protocol and should work, but is untested.
- **4K/H.265 mode** is untested. The panel has the hardware decoder (`OMX.amlogic.hevc.decoder.awesome2`).
