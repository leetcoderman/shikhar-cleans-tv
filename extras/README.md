# Extras from "Gupta's TV"

Everything here was built and tested on two TVs in one household: a **Haier MatrixTV EE** (Google TV 14) and a
**DPL interactive panel** called "Gupta's TV" (Android 11). Pick what fits your TV.

| Folder | What it gives you | Works on |
|---|---|---|
| [`iphone-remote/`](iphone-remote/) | Control the TV from the iPhone's **Apple TV Remote** (Control Center): D-pad, keyboard, cursor, volume | Android / Google TV with ADB (tested: Haier, DPL) |
| [`airplay-receiver/`](airplay-receiver/) | **AirPlay screen mirroring** from a Mac or iPhone, with sound, no ads | Android 10+ that runs 32-bit ARM apps (tested: DPL) |
| [`dpl-panel/`](dpl-panel/) | Full setup of a RuiPin/DPL whiteboard panel as a TV, its theme, and the **bottom dock** app | RuiPin-firmware panels |
| [`haier-matrixtv-ee/`](haier-matrixtv-ee/) | Debloat lists for the Haier MatrixTV EE, a Mac-only cleanup guide, an after-firmware-update check | Haier MatrixTV EE (lists); the guide fits any Haier |

The launcher changes (horizon backdrop, hourly quotes, `home_name`, `header_inset`, `settings_button_app`, the Home ↔
dock focus hand-off) are not here: they are folded into this repo's `launcher/` and documented in `themes/README.md`.

## Order for a new TV

1. Clean the TV and install the launcher with the normal CleanYourTV flow (top-level README, Quick start). Your TV's
   model may already be in `examples/`.
2. Optional theme: start from `themes/` or `dpl-panel/theme/config.json` (change `home_name`, the app lists and the
   banners to your apps), then `TV_HOST=<ip> tools/theme.sh <theme folder>`.
3. Then the extras you want, each with its own README: build, install, undo.

## Names to change

The apps call the TV "Gupta's TV". Before building, put your own name in:

- `airplay-receiver/app/src/main/java/com/gupta/airplayreceiver/Prefs.java` (`NAME`, `HOST_LABEL`)
- `iphone-remote`: `DeviceInfo.kt` → `PANEL_NAME` (after the first `build.sh`, see its README)
- the theme's `home_name`

The package names (`com.gupta.*`) can stay as they are.

## Not included

Signing keys, passwords, vendor APKs and device backups. Each app builds with your own key (or the debug key) instead,
so you can't update the APKs installed on Gupta's TV, and that TV can't receive yours.
