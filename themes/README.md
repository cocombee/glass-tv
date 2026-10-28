# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Pushing one changes the
launcher's dock, layout and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora          # push a theme and restart Home
TV_HOST=192.168.1.50 tools/theme.sh --reset                # back to the built-in config.xml
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
```

| Theme | What it does |
|---|---|
| `default/` | The built-in look written out as JSON, as a starting point for your own |
| `aurora/` | A dark aurora wallpaper (teal and violet on navy, darker at the bottom) with a matching background colour |
| `six-columns/` | Six smaller tiles per row and one grid row visible under the dock |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The dock row, in order; missing apps are skipped. Once someone arranges the apps on the TV (hold OK, then Move), their order is used instead. A phone-only app named here (no TV entry, e.g. the phone Play Store some boxes ship) is shown too, with its icon; phone-only apps not named stay hidden |
| `hidden_apps` | list of package names | Never shown as tiles |
| `live_tv_package`, `live_tv_action` | string | The Live TV app and, if it has no leanback entry, the action to start it with |
| `source_panel_service`, `source_panel_action` | string | The TV's own source panel, for sets with no TvInputManager |
| `input_action` | string | Action sent with an HDMI/AV row's channel URI when nothing handles the standard `VIEW` (XGIMI: `com.xgimi.action.hdmiPlayer`) |
| `columns` | 1 to 8 | Tiles per row; tiles shrink to fit from 5 up |
| `rows_below_dock` | 0 to 3 | Grid rows left visible under the dock |
| `shamsi_date` | bool | Show the Solar Hijri date above the Gregorian one |
| `hijri_date` | bool | Show the Hijri (Umm al-Qura) date in Arabic above the Gregorian one, which is then in Arabic too |
| `clock_right` | bool | Clock and dates at the top right, the Inputs / Wi-Fi / Settings buttons at the top left |
| `arabic_digits` | bool | Clock and dates in Arabic-Indic digits (٠١٢٣٤٥٦٧٨٩), AM/PM as ص/م |
| `place_text` | bool | The place's name, country and description above the dock; `false` shows the picture alone |
| `prayer_times` | bool | The next prayer and the time left, in Arabic, under the dates and on the screensaver. Umm al-Qura method, worked out on the TV (no network) |
| `prayer_latitude`, `prayer_longitude`, `prayer_timezone` | number, number, string | Where prayer times are for; Riyadh (`24.7136`, `46.6753`, `Asia/Riyadh`) by default |
| `football_team` | string | ESPN team id whose next match is shown under the dates and on the screensaver, e.g. `"359"` for Arsenal. Goes online every 6 hours (ESPN's public schedule feed) |
| `football_team_name` | string | The team's name as shown, e.g. `"أرسنال"`; ESPN's name otherwise |
| `module_cards` | bool | Look for module cards and ask about them (`true`); `false` turns the feature off |
| `reduced_motion` | bool | Calmer motion for weak GPUs: stiff highlight spring, no tile overshoot, short fades, no entrance replay |
| `vpn_app` | string | App the network pill opens when a VPN is configured; also shows a red "VPN off" badge while no VPN is up |
| `show_home_settings_shortcut` | bool | The "Default home" tile, where the TV has a home picker |
| `show_network_pill` | bool | The Wi-Fi / Ethernet pill in the top bar; `false` hides it |
| `show_live_tv` | bool | The Live TV row in the Inputs menu; `false` leaves only the HDMI and AV inputs |
| `background_color` | `#RRGGBB` | Colour behind everything; black by default |
| `wallpaper` | bool | Show `wallpaper.jpg` from the theme folder (`true`), or ignore it (`false`) |
| `wallpaper_seconds` | 5 to 86400 | Seconds between pictures; when set, it wins over `wallpaper_minutes` |
| `wallpaper_minutes` | 0 to 1440 | Minutes between pictures when the theme has a `wallpapers/` folder; 0 changes only when Home starts. Default 30 |
| `cache_clean_days` | 0 to 90 | Days between clearing the TV's app caches, which the TV does itself (default 15); `0` turns it off. See [docs/cache-clean.md](../docs/cache-clean.md) |
| `places` | bool | Pictures of places to visit (Bing's daily images, no key), renewed weekly with last week's deleted, each named with its country and a few lines about it; pictures with people in them are skipped. On by default, and they replace the theme's own pictures. `false`: the theme's own pictures, and the launcher never goes online |

## Wallpapers

`wallpaper.jpg` is drawn full screen behind the launcher, centre-cropped. Keep it dark, 1920×1080 and
under about 300 KB: the text, the hero name and the dock band are designed for black, and a 1 GB TV
decodes the picture into memory on every Home start. Pictures larger than the screen are downsampled.

## Reduced motion

`"reduced_motion": true` keeps the look but calms the movement: the highlight spring is stiff instead
of bouncing, tiles zoom without overshoot, fades are shorter, and Home does not replay the tile
entrance when you come back to it. Use it on TVs whose GPU cannot keep up; a MediaTek MT5895 on
Android 10 is the known case. The default, `false`, keeps the normal motion.

```json
{ "reduced_motion": true }
```

## Adding a theme

Open a PR with a new folder here, a one-line row in the table above and a screenshot from a real TV
in the PR description. Keep the dock list generic (the big streaming apps) unless the theme is for a
specific TV, and say which TV you tried it on.
