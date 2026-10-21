# Glass Launcher

A custom home screen for the VU TV (Android TV 11, 1920×1080 @ density 320). Build brief: [CLAUDE.md](CLAUDE.md). Designs: [design/](design/).

## Status

- **Phase 1 (done, tested on the TV 21 Oct 2026):** Home screen with the placeholder "Dusk Ridge" wallpaper and glass cards drawn from a pre-blurred copy, live clock and date, a dock of real apps, a dynamic All apps grid, D-pad focus rules, and the hold-Back recovery hatch. The widgets showed sample content until phase 3.
- **Phase 2 (done, tested on the TV 21 Oct 2026):** Glass Launcher is the home screen. The stock launcher is disabled. Home, reboot and a launcher crash all land back on Glass Launcher. The remote's app buttons work (see below).

- **Phase 3 (done, tested on the TV 21 Oct 2026):** live widgets and their expanded screens.
  - **Weather:** Open-Meteo, Pune, refreshed every 30 min, with Indian AQI (CPCB, from 24-h PM2.5/PM10 means).
  - **Up next:** any ICS calendar link (Apple iCloud public calendars, or subscriptions like a Formula 1 feed), with RRULE, EXDATE, RECURRENCE-ID and time zones, via biweekly. Events from 2 weeks back to 5 weeks ahead are loaded. Long titles wrap to two lines on Home, and if they still don't fit the middle is cut so the end (e.g. "- Practice 1") stays visible.
  - **Now watching:** a stack you flip with ◀ ▶. OK resumes and hold OK opens details.
  - **Offline:** cached data stays on screen with its age ("Offline · 5 min"), and each calendar shows its own sync error.
  - **Setup:** calendars and the watch list are managed from the phone companion (phase 5), or over adb (see below).

- **Phase 4 (done, tested on the TV 21 Oct 2026):** wallpaper library and Ambient.
  - **Wallpapers:** three built-in scenes (Dusk Ridge, Blue Hour, Ember Peaks) plus your own photos, stored at 1920×1080 JPEG 85%. Glass cards draw from a pre-blurred copy of the current wallpaper. In the rotating modes (daily / every N hours / time of day), Home changes wallpaper only quietly, when waking from Ambient or returning to Home. Picking a wallpaper on the phone or in Quick settings applies straight away.
  - **Ambient:** after 3 min idle on Home. It's a slideshow of the whole library with a slow drift and a 2.5 s crossfade every 40 s, plus the big clock, date and weather, a glass chip with your next event after today, and "Resume <show>" (OK resumes). Any other key wakes it with focus where you left it.
  - **Screensaver:** the same Ambient screen is the system screensaver (`GlassDream`). It starts after the TV's own 10-min timeout, e.g. on a paused video.
  - **Memory (measured in phase 4):** about 100 MB PSS on Home and ~150 MB during the slideshow. Slides are freed as they leave the screen. Not re-measured after phases 5–6.

- **Phase 5 (done, tested on the TV 21 Oct 2026):** phone companion and automatic Now watching (details below).

- **Phase 6 (done, tested on the TV 22 Oct 2026):** Quick settings panel (input switching confirmed on HDMI) with an added Wi-Fi section, dock editing on the TV, and hold-OK in All apps.

All six phases in the brief are done.

## Changes from the design

These are deliberate departures from the designs in `design/`, made during the build:

- **Dock:** it spans the full content width (120–1800 px) so the top bar, widgets and dock line up on both sides. Tiles are 124 px tall instead of 104, and filled edge to edge with each app's TV banner (or its icon on the icon's own colour if there's no banner). They're about 194 px wide with 7 apps.
- **Now watching:** the stack's right edge lines up with the rest of Home. Items behind the front one peek out as 14 px slivers (instead of 30 px).
- **Eye button:** a new top-bar button (left of the weather chip) hides or shows the widgets to see the wallpaper. The choice is remembered.
- **Wi-Fi section:** added to Quick settings (not in the design).
- **Pairing card:** it's larger than the design (1320 × 680 px) so the title and expiry line fit.

## Quick settings

Open it with OK on **M** or the remote's **Settings** button. Back closes it. The panel shows:

- **Input:** the TV's inputs, read from `TvInputManager` (HDMI 1–3, AV, Antenna). Choosing one switches straight away: a passthrough channel URI for HDMI and AV, and the Live TV app for the antenna. Android doesn't tell apps which input is on screen, so a blue dot marks the last one picked here.
- **Wi-Fi:** nearby networks with yours first (✓), signal and padlocks. Apps can't switch Wi-Fi on Android 10+, so choosing a network opens the TV's Wi-Fi settings. This needs the location permission (see System changes).
- **Wallpaper:** thumbnails of the library. Picking one sets it as Home's wallpaper (mode becomes fixed).
- **TV settings:** Picture & brightness (`android.settings.DISPLAY_SETTINGS` → Hisense PictureActivity), Sound & Bluetooth (`SOUND_SETTINGS`), Sleep timer (`android.settings.TIMER_SETTINGS`), All settings, and Pair a phone.

## Dock editing

- **Hold OK on a dock app:** opens the editor with that app picked up.
- **Moving:** ◀ ▶ moves the app you're holding, and OK drops it. When nothing is held, ◀ ▶ moves the selection and OK picks an app up.
- **Removing:** ▼ removes the selected app.
- **Adding:** OK on the dashed "+" opens All apps; choose an app to add it.
- **Back:** finishes.
- **Also in All apps:** holding OK on any app adds it to the dock.

Changes save immediately and match the phone's Dock tab. The dock holds up to 8 apps.

## Phone companion

**Pairing:**
1. On the TV, open Quick settings › **Pair a phone** (or hold OK on **M**). The pairing screen shows a QR code and a 4-digit code, valid for 5 minutes.
2. Scan the QR with your phone camera. The page opens with the code filled in.
3. Tap **Pair**. You only do this once per phone, and the session lasts a year.

Or open `http://192.168.1.4:8080` on the phone and type the code.

**Tabs:**
- **Watching:** add or edit titles, set progress, add artwork, reorder, and remove.
- **Dock:** add, reorder and remove apps (up to 8).
- **Wallpapers:** upload photos (resized on the phone), choose which shows on Home, and pick the mode (fixed / daily / every N hours / time of day).
- **Calendar:** add an ICS link with a name and colour, see sync status, sync now, and remove.

**Security:**
- The server (NanoHTTPD, port 8080) answers only requests from the home network that use the TV's IP address. That blocks DNS-rebinding tricks.
- Changes require a custom header (`X-Glass`) and the session cookie (`HttpOnly`, `SameSite=Strict`). The TV stores only hashes of session tokens.
- Five wrong pairing codes replace the code.
- Calendar links are never sent back to the phone and are never logged.
- It's plain `http` on the home Wi-Fi.

To forget every paired phone, run `--es cmd unpair_all` (see below).

**Automatic Now watching** (`MediaWatcher`):
- The phone's Watching tab has an **Automatic Now watching** card. It lists each app seen playing as "✓ Shares titles" or "✗ Doesn't share titles · add these by hand", and fills in as you watch.
- Artwork comes from the app when it sends a picture or a picture link. SonyLIV does; JioHotstar doesn't, so its titles get placeholder art unless you add one from the phone.
- Android shares other apps' media sessions with an approved listener. When an app reports a title and is playing, that title is added to the front of Now watching, marked **Auto** on the phone, and its progress and time left are kept up to date. Notification contents are ignored.
- Titles shorter than 5 min (trailers, previews, Shorts) are skipped. If no length is reported, a title counts only after 5 min of playing.
- Finishing a title (95%+) removes it. At most 4 auto titles are kept.
- Editing an auto title on the phone makes it a manual one.
- **Netflix sends no title, so add Netflix shows by hand.** YouTube Music, Live TV and the Assistant are ignored.

## Configure from adb (alternative to the phone)

Commands go to `DevConfigReceiver`, which requires the `DUMP` permission, so only `adb shell` and the system can call it. Run them yourself so calendar links stay out of chat logs. Links are stored only in the app's private storage and are never written to logcat.

Add an Apple calendar. In Calendar on the iPhone/Mac, share it as a **Public Calendar** and copy the `webcal://…` link. `color` is optional:

```bash
adb -s 192.168.1.4:5555 shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd add_calendar --es name "'Personal'" --es color "'#7fd1ff'" --es url "'webcal://pXX-caldav.icloud.com/published/2/…'"
```

Remove a calendar by name:

```bash
adb -s 192.168.1.4:5555 shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd remove_calendar --es name "'Holidays (test)'"
```

Add something you're watching. Only `title` and `package` are required. `link` is an optional deep link:

```bash
adb -s 192.168.1.4:5555 shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd add_watching --es title "'The Long Field'" --es package com.netflix.ninja --es upto "'S2 · E5'" --es left "'24 min left'" --es progress 0.6
```

Clear the watch list:

```bash
adb -s 192.168.1.4:5555 shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd clear_watching
```

### Wallpapers

Add a photo of your own (any size or orientation; it's cropped to fill 1920×1080). `use=true` also makes it Home's wallpaper. `credit=` is optional and shown on Ambient:

```bash
adb -s 192.168.1.4:5555 shell 'content write --uri "content://com.manishlad.glasslauncher.dev/wallpaper?name=Goa%20Beach&use=true"' < ~/Pictures/goa.jpg
```

Remove a photo:

```bash
adb -s 192.168.1.4:5555 shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd remove_wallpaper --es name "'Goa Beach'"
```

Choose how Home picks its wallpaper:
- `fixed` (with `name`)
- `daily`
- `hours` (with `hours`, e.g. 6)
- `timeofday` (with `morning`, `afternoon`, `evening` and/or `night`, each a list of names like `"'Goa Beach|Blue Hour'"`)

For example:

```bash
adb -s 192.168.1.4:5555 shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd wallpaper_mode --es mode daily
```

Testing Ambient sooner: `--es cmd idle --es seconds 10` (resets on restart; `--es seconds 180` is the normal 3 min).

### Other commands

`--es cmd unpair_all` (forget all paired phones), `--es cmd refresh` (fetch weather and calendars now), and `--es cmd offline` / `--es cmd offline --es on false` (simulate no network inside the app only, to check offline states; it resets on restart).

## Toolchain (Mac, arm64)

Installed with Homebrew: `openjdk@17`, `gradle` (used only to create the wrapper), and the `android-commandlinetools` cask, with SDK platform 35, build-tools 35.0.0 and platform-tools.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

`local.properties` points Gradle at the SDK. It isn't checked in.

## Build and install

Unit tests (Indian AQI maths, ICS recurrence expansion):

```bash
./gradlew testReleaseUnitTest
```

Use the release build: it's minified, and Compose debug builds are noticeably slow on this TV. It's signed with the debug key, which is fine for sideloading on one TV.

```bash
./gradlew assembleRelease
```

```bash
adb connect 192.168.1.4:5555
```

```bash
adb -s 192.168.1.4:5555 install -r app/build/outputs/apk/release/app-release.apk
```

```bash
adb -s 192.168.1.4:5555 shell am start -n com.manishlad.glasslauncher/.MainActivity
```

Logs: `adb -s 192.168.1.4:5555 logcat -s GlassLauncher AndroidRuntime`

## Remote behaviour

- The screen has three zones, stacked: top bar (eye button, weather chip, Wi-Fi, profile "M"), widgets, dock. ◀ ▶ stop at the ends of the top bar and of the dock.
- ▲ from the dock goes to the widget directly above the focused tile.
- On launch, focus lands on the last-used dock app. The same happens on Back and when you return from an app.
- On Weather or Up next, OK opens the full screen.
- Now watching: ◀ ▶ flip the stack (◀ on the first item moves to Up next). OK resumes and hold OK opens the details screen.
- Back from a full screen returns focus to its widget. On the Calendar screen, ◀ ▶ change the day.
- **Hold Back for 5 s** to open Android Settings (the recovery hatch).
- The remote's app buttons:
  - **Netflix** (`KEY_FN_F5`) sends Netflix's own start intent and works on its own.
  - **YouTube** (key 293), **Prime Video** (295) and **Hotstar** (337) normally go to Hisense's handler (`com.hisense.tv.smartsense`), which sends a broadcast that only the stock launcher can receive. With the stock launcher off, they did nothing.
  - **Settings** (`KEYCODE_NOTIFICATION`, 83) also did nothing.
  - `RemoteKeyService`, an accessibility service with key filtering, now catches those four keys system-wide. It opens the app, or Quick settings for the Settings button.
- "M" opens Quick settings; holding OK on "M" goes straight to phone pairing. The Wi-Fi chip opens Wi-Fi settings, and the eye button hides or shows the widgets.
- The remote's Settings button opens Quick settings.

## System changes and how to undo them

### 21 Oct 2026 · phase 2: Glass Launcher becomes the home screen

The following commands were run:

- `cmd package set-home-activity com.manishlad.glasslauncher/.MainActivity` (on its own, the stock launcher still won)
- `pm disable-user --user 0 com.google.android.apps.tv.launcherx`
- `pm disable-user --user 0 com.google.android.tungsten.setupwraith`

**Undo** (restores the stock Google TV home and its button handling), with the TV connected over adb:

```bash
adb -s 192.168.1.4:5555 shell pm enable com.google.android.apps.tv.launcherx
```

```bash
adb -s 192.168.1.4:5555 shell pm enable com.google.android.tungsten.setupwraith
```

```bash
adb -s 192.168.1.4:5555 shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

Then press Home. If adb isn't reachable, hold Back for 5 s in Glass Launcher to get to Android Settings (Apps lets you re-enable the stock launcher).

### 21 Oct 2026 · phase 2: remote-button accessibility service

The following commands were run (both settings were empty or off before):

- `settings put secure enabled_accessibility_services com.manishlad.glasslauncher/com.manishlad.glasslauncher.RemoteKeyService`
- `settings put secure accessibility_enabled 1`

**Undo:**

```bash
adb -s 192.168.1.4:5555 shell settings delete secure enabled_accessibility_services
```

```bash
adb -s 192.168.1.4:5555 shell settings put secure accessibility_enabled 0
```

### 21 Oct 2026 · phase 4: Ambient as the system screensaver

The following command was run (the previous value was `com.google.android.apps.tv.dreamx/.service.Backdrop`):

- `settings put secure screensaver_components com.manishlad.glasslauncher/.GlassDream`

**Undo** (restores Google's Backdrop screensaver):

```bash
adb -s 192.168.1.4:5555 shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
```

### 21 Oct 2026 · phase 5: automatic Now watching (media-session access)

The following command was run (the permission wasn't granted before):

- `cmd notification allow_listener com.manishlad.glasslauncher/com.manishlad.glasslauncher.MediaWatcher`

**Undo:**

```bash
adb -s 192.168.1.4:5555 shell cmd notification disallow_listener com.manishlad.glasslauncher/com.manishlad.glasslauncher.MediaWatcher
```

### 21 Oct 2026 · phase 6: location permission for the Wi-Fi list

The following command was run. Android 10+ requires location access for Wi-Fi scan results, and even for the connected network's name. Location services were already on.

- `pm grant com.manishlad.glasslauncher android.permission.ACCESS_FINE_LOCATION`

**Undo** (the Wi-Fi section then shows a note instead of the list):

```bash
adb -s 192.168.1.4:5555 shell pm revoke com.manishlad.glasslauncher android.permission.ACCESS_FINE_LOCATION
```

### Removing Glass Launcher completely

Uninstalling Glass Launcher while the stock launcher is disabled leaves only `com.android.tv.settings/.system.FallbackHome`. **Run all the undo blocks above first**, then:

```bash
adb -s 192.168.1.4:5555 uninstall com.manishlad.glasslauncher
```

## Known limitations

- **Netflix** shares no titles with other apps, so Netflix shows must be added from the phone. **JioHotstar** shares titles but no artwork.
- **Finished titles disappear:** an automatically added title is removed once it reaches 95%.
- **Inputs:** Android doesn't tell apps which input is on screen. The blue dot in Quick settings marks the last one picked there.
- **Wi-Fi:** apps can't switch networks on Android 10+, so choosing a network opens the TV's Wi-Fi settings.
- **Weather location** is fixed to Pune (`WeatherPlace` in `data/Weather.kt`). Changing it from the phone isn't built.
- **TMDB artwork lookup** was skipped by choice. Use the phone's "Add artwork" for titles without pictures.
- **Memory:** RSS (about 180–230 MB) is above the brief's ~150 MB target, partly because RSS includes shared system libraries. PSS is about 100 MB on Home.

## Notes

- **Theme gotcha:** the app theme must not inherit `Theme.DeviceDefault`. On this Hisense build it made the activity count as translucent (`occludesParent=false`), so pressing Home left the previous app on screen. It now uses `Theme.Material.NoActionBar` with `windowIsTranslucent=false`.
- If an app freezes (logcat shows "pause timeout" / "stop timeout" for it), launches won't show it. Run `adb shell am force-stop <package>`. This happened once to Prime Video while Home was broken.

- Inter is bundled from rsms/inter v4.1 (OFL). The licence is in `third_party_inter_LICENSE.txt`.
- Package visibility uses `<queries>` for LEANBACK_LAUNCHER and LAUNCHER intents rather than QUERY_ALL_PACKAGES.
- `tv-material` isn't used. Every surface is a custom glass draw, so its components would mostly be overridden. Plain Compose foundation handles the focus.
