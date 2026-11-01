# Cleaning up a Haier TV from a Mac

How to remove ads and bloat from a Haier Android TV / Google TV with a **MacBook**. No Android
phone is needed.

- The phone app ([cleanyourtv-app](https://github.com/udhaykumarbala/cleanyourtv-app)) only runs on
  Android. **An iPhone can't do this.**
- The app is a front end for [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv), which
  is built for a computer. It uses the same Haier lists and the same launcher. This repo is a fork of it, so
  everything below runs from the repo root.
- Its tools speak ADB from Python (`adb-shell`), so you don't need Google's `adb` binary.

There are two parts:

| Part | What it does | Mac needs |
| --- | --- | --- |
| **A. Debloat** | Disables Haier ads, telemetry and preloads. This is where most of the speed-up comes from. | Python 3 (already installed) |
| **B. New home screen** (optional) | Replaces the ad-filled Google home with the Clean Your TV launcher. | JDK 17 + Android SDK, to build the launcher |

Everything is **disabled, not uninstalled**, and one command undoes it.

---

## 1. Find which Haier list fits your TV

On the TV, open **Settings → System (or Device Preferences) → About** and note the model.

| List (`examples/…`) | Model string | Platform | Stock launcher |
| --- | --- | --- | --- |
| `haier-h32s80efx` | Haier MatrixTV CE (device `deto`) | Google TV 14 | `com.google.android.apps.tv.launcherx` |
| `haier-android-tv-dvb2k` | Haier Android TV DVB2K (device `broadway`) | Android TV 11 | `com.google.android.tvlauncher` |

Neither list has been tested through a restart (both were tested the same day only). If your model
matches neither, **don't run one of these lists blindly**. Use the guided route in step 9.

## 2. Turn on debugging on the TV

**Google TV:**
1. Go to **Settings → System → About**.
2. Select **Android TV OS build** 7 times.
3. Go back to **System → Developer options**.
4. Turn on **USB debugging**, and **Wireless debugging** if it's there.

**Android TV:**
1. Go to **Settings → Device Preferences → About**.
2. Select **Build** 7 times.
3. Go back to **Developer options**.
4. Turn on **USB debugging**, and **Network / ADB debugging** if it's there.

Then:
- Note the TV's IP address (**Settings → Network → your Wi-Fi**).
- Leave the TV on.
- Ideally give the TV a fixed IP in your router.

## 3. Set up the tools on the Mac

```bash
cd path/to/shikhar-cleans-tv
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.x.x          # your TV's IP
```

## 4. Connect

```bash
tools/venv/bin/python -I tools/tv.py --probe
```

- **On the TV:** tick **Always allow from this computer** and choose **OK**.
- **On the Mac:** if macOS asks to let Terminal *find devices on your local network*, choose
  **Allow**. If you see "No route to host", go to **System Settings → Privacy & Security → Local
  Network** and turn it on for Terminal / your IDE.
- **If port 5555 stays closed** (some Google TVs use pairing-code Wireless debugging only), run
  `brew install android-platform-tools`, then:
  ```bash
  adb pair <IP>:<PAIR_PORT>        # code from Developer options → Wireless debugging → Pair device
  adb connect <IP>:<PORT>
  adb tcpip 5555
  ```

Check that `--probe` prints a model matching one of the lists in step 1.

## 5. Debloat (part A)

```bash
# Google TV (MatrixTV CE)
tools/disable.sh examples/haier-h32s80efx/debloat.txt
# — or — Android TV (DVB2K)
tools/disable.sh examples/haier-android-tv-dvb2k/debloat.txt
```

- Before changing anything, `disable.sh` saves the TV's current state in `~/.cleanyourtv/`.
- It refuses unsafe lists and skips whichever app currently owns Home.
- Open the list first and **comment out (`#`) anything you use**.
- **DVB2K:** comment out `com.mediatek.android.leanbacklauncher.partnercustomizer`. On similar
  MediaTek TVs, disabling it broke the Input key after a restart.
- **Google TV:** the setup wizard (`setupwraith`) has to go before a new launcher can take Home.
  If your Wi-Fi network was saved during first setup, it belongs to setupwraith, and disabling
  setupwraith deletes it. `disable.sh` skips it in that case. Fix it by forgetting the network and
  re-joining it in Settings, then rerun the script.

> ⚠️ **Haier / CVTE warning:** never *open* packages named factory, `fac`, `hkcfac`, `at.factory` or
> `factorymenu`, not even to look at them. On Haier boards that flips a factory-mode flag, which
> kills the Input key and CEC. The lists already keep these packages.

## 6. New home screen (part B, optional)

The launcher is built from source on the Mac, which takes a one-off setup of about 1–2 GB.

```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"

cd launcher
# Google TV:
STOCK_LAUNCHER=com.google.android.apps.tv.launcherx ./build-and-install.sh
# Android TV:
STOCK_LAUNCHER=com.google.android.tvlauncher ./build-and-install.sh
```

The script:
- builds the launcher,
- pushes it to the TV,
- makes it the Home screen,
- disables the stock launcher.

You can set dock apps and their order in `launcher/app/src/main/res/values/config.xml`
(`dock_apps`, `columns`).

**Google TV only:** afterwards, press every remote button: Netflix, YouTube, Input, Settings and
voice. The stock launcher used to handle some of them. Any that stop working can be remapped in
`config.xml` (see the README section on key services).

Skip part B if you're happy with the stock home once the Haier ads are gone.

## 7. Restart and check

```bash
tools/venv/bin/python -I tools/tv.py --reboot
```

Check each of these:
- [ ] The home screen loads.
- [ ] The **Input / Source** key and every HDMI input work.
- [ ] Live TV works, if you use it.
- [ ] The remote, voice and Chromecast work.
- [ ] Wi-Fi works.
- [ ] The Play Store works and updates.

Then check that nothing came back after the restart:

```bash
tools/venv/bin/python -I tools/check.py examples/<your list>/debloat.txt
```

## 8. Lock the TV back down, and how to undo

When you're done, close network ADB:
1. On the TV, turn **USB debugging** and **Network / Wireless debugging** off.
2. Revoke the authorisations, then restart the TV.
3. Check that the port is closed:
   ```bash
   tools/venv/bin/python -I tools/adb-closed.py $TV_HOST
   ```

To **undo everything** later, turn debugging back on and run the matching command:

```bash
tools/restore.sh examples/haier-h32s80efx/debloat.txt com.google.android.apps.tv.launcherx   # Google TV
tools/restore.sh examples/haier-android-tv-dvb2k/debloat.txt com.google.android.tvlauncher   # Android TV
```

To bring back **one app**:

```bash
tools/venv/bin/python -I tools/tv.py 'pm enable --user 0 <package.name>'
```

## 9. The easy way: let Claude Code drive it

This project is designed to be run by a coding assistant on a computer, and that's this setup.
In this folder, ask Claude Code something like:

> "Use `PROMPT.md`. My TV is a Haier <model>, IP <x.x.x.x>."

It then:
- connects to the TV,
- backs up first,
- checks each Haier package with `dumpsys` before touching it,
- asks before touching streaming apps, voice, Chromecast or updates,
- disables in small batches and checks a screenshot from the TV after each one,
- builds and installs the launcher,
- restarts and checks the TV,
- closes ADB at the end.

Use this route if your model isn't one of the two in step 1.
