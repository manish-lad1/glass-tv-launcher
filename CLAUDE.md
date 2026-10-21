# Agent instructions: get Glass TV Launcher running on an Android TV

You're setting up this repo's launcher as the home screen of the user's Android TV, over adb from their Mac. `README.md` describes what the launcher does; this file is the procedure. Work through the steps in order and verify each one on the TV before moving on.

## Ground rules

- **Ask before changing the TV's system state:** disabling packages, changing `settings`, granting permissions, setting the screensaver. Explain what each command does and give its undo before running it. Read-only commands (`dumpsys`, `getprop`, `pm list`, `settings get`, `logcat`, `screencap`) don't need permission.
- **Keep the undo list complete:** for every system change, tell the user the command and its undo. [Undo / restore](#undo--restore) below lists every change this procedure makes. If you add a new kind of change, add its undo there.
- **Check the foreground app before every key press** you inject (`input keyevent`):
  ```bash
  adb -s <TV> shell dumpsys activity activities | grep -m1 mResumedActivity
  ```
  Only send keys when `com.manishlad.glasslauncher` is in front, and stop immediately if anything else is. The user may be watching something, and keys meant for the launcher will land in their app. Installing an APK is safe at any time (it doesn't send keys), but avoid it while someone is watching, because it restarts the launcher's background services.
- **Never** log, print, commit or echo back calendar links, pairing codes after use, or session tokens. Calendar links are secrets: anyone who has one can read that calendar.
- **Take screenshots for visual checks:** `adb shell screencap -p /sdcard/s.png`, then `adb pull`. Video apps block screenshots (you get a 0-byte file), which means another app is in front.
- **Use release builds** on the TV. Compose debug builds are too slow there.

## What you need from the user

1. **TV IP address and network debugging:** on the TV, Settings › System › About › tap "Build" 7 times to enable Developer options, then turn on **Network debugging** (sometimes called ADB over network).
   - Below, `<TV>` means `<TV_IP>:5555`.
   - The first connection shows "Allow debugging?" on the TV. The user ticks "Always allow".
2. **Personal values to change in code** (or keep the defaults):
   - **Weather location:** `WeatherPlace` in `app/src/main/java/com/manishlad/glasslauncher/data/Weather.kt` (default Pune, 18.5204, 73.8567, Asia/Kolkata).
   - **Profile name and initial:** shown in Quick settings and on the top-bar button. In `ui/QuickSettings.kt` (`"Manish"`, `"M"`) and `ui/Home.kt` (`T("M", …)`).
   - **TV name shown on the phone page:** `"Living room TV"` in `companion/CompanionServer.kt`.
   - **Default dock apps:** `DEFAULT_DOCK` in `AppRepository.kt` (package names; missing apps are skipped). The user can change the dock later on the TV or the phone.

## 1. Toolchain (macOS, Apple silicon)

```bash
brew install openjdk@17 gradle
```

```bash
brew install --cask android-commandlinetools
```

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

Create `local.properties` (it isn't in git):

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Every Gradle command needs `JAVA_HOME` set as above. The repo includes the Gradle wrapper (8.11.1, AGP 8.7.3, Kotlin 2.1.0), so always use `./gradlew`, not a globally installed `gradle`.

## 2. Connect to the TV and look around (read-only)

```bash
adb connect <TV>
adb devices
```

Run `./tv-check.sh` after setting `TV=` at its top. It writes `tv-report.txt`: model, Android version, screen size and density, installed apps, the current HOME handler, TV inputs and screensaver. Check:

- **Android version:** 11 or later (`ro.build.version.sdk` ≥ 30).
- **Screen:** 1920×1080 at density 320 is what the layout is designed for. Other densities still work, but sizes will differ (layout uses `Number.px` = design px ÷ 2).
- **Stock launcher package:** on Google TV it's usually `com.google.android.apps.tv.launcherx`, plus a recovery activity in `com.google.android.tungsten.setupwraith`.
- **Live TV app:** whether `com.mediatek.wwtv.tvcenter` exists. If the TV uses a different one, update `LIVE_TV_PACKAGE` in `data/TvSources.kt` and the Live TV entry in `DEFAULT_DOCK`.

## 3. Build and test

```bash
./gradlew testReleaseUnitTest assembleRelease
```

The tests cover the Indian AQI maths and ICS recurrence expansion. The APK is `app/build/outputs/apk/release/app-release.apk`, minified and signed with the debug key (fine for sideloading).

## 4. Install and run as a normal app first

```bash
adb -s <TV> install -r app/build/outputs/apk/release/app-release.apk
adb -s <TV> shell am start -n com.manishlad.glasslauncher/.MainActivity
```

Check with a screenshot:
- the wallpaper and the widgets load (weather needs internet)
- the dock shows the apps' banners
- ◀ ▶ ▲ ▼ move focus as described in `README.md`
- **holding Back for 5 s opens Android Settings.** Confirm this recovery route works before step 5. Ask the user to hold Back on the real remote; `input keyevent` can't hold a key.

## 5. Make it the home screen (ask first)

1. Try the gentle way:
   ```bash
   adb -s <TV> shell cmd package set-home-activity com.manishlad.glasslauncher/.MainActivity
   adb -s <TV> shell input keyevent HOME
   adb -s <TV> shell dumpsys activity activities | grep -m1 mResumedActivity
   ```
2. If the stock launcher still comes up (it did on the VU TV), **ask the user**, then disable the stock launcher and its recovery activity:
   ```bash
   adb -s <TV> shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   adb -s <TV> shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
   ```
3. Verify:
   - Home from inside an app returns to the launcher.
   - After `adb reboot` (ask first), the TV boots into it.
   - After `adb shell am crash com.manishlad.glasslauncher`, Android restarts it.
   - The previous app doesn't stay visible behind it. If it does, check that the app theme still uses `Theme.Material.NoActionBar` with `windowIsTranslucent=false`. `Theme.DeviceDefault` made the window translucent on the VU TV.

## 6. Remote app buttons (ask first)

With the stock launcher disabled, buttons that the TV vendor routes to it (on the VU TV: YouTube, Prime Video, Hotstar, Settings) stop working. Netflix's button keeps working (it starts Netflix directly). To find what each button sends, clear logcat, ask the user to press each button with a few seconds between presses, then read `logcat | grep interceptKeyTq` for the key codes. Map them in `APP_BUTTONS` in `RemoteKeyService.kt`; the Settings button is `KEYCODE_NOTIFICATION` and opens Quick settings. Then enable the accessibility service:

```bash
adb -s <TV> shell settings get secure enabled_accessibility_services
adb -s <TV> shell settings put secure enabled_accessibility_services com.manishlad.glasslauncher/com.manishlad.glasslauncher.RemoteKeyService
adb -s <TV> shell settings put secure accessibility_enabled 1
```

If the first command printed other services, keep them: join them with `:` instead of overwriting. Verify with the real remote; injected keys skip accessibility filters, so `input keyevent` can't test this.

## 7. Optional features (each needs its own approval)

- **Ambient as the system screensaver** (record the old value first):
  ```bash
  adb -s <TV> shell settings get secure screensaver_components
  adb -s <TV> shell settings put secure screensaver_components com.manishlad.glasslauncher/.GlassDream
  ```
  Test with `adb shell am start -n com.android.systemui/.Somnambulator`.
- **Automatic Now watching** (reads apps' "now playing" info; notifications are ignored):
  ```bash
  adb -s <TV> shell cmd notification allow_listener com.manishlad.glasslauncher/com.manishlad.glasslauncher.MediaWatcher
  ```
  The log shows `media watcher connected`. Have the user play something for 5 minutes, then check `logcat -s GlassLauncher` for `now watching in <package>` or `skipped: <reason>`.
- **Wi-Fi list in Quick settings:** Android needs location access for Wi-Fi scans and even the connected network's name, and location services must be on (`settings get secure location_mode` ≠ 0).
  ```bash
  adb -s <TV> shell pm grant com.manishlad.glasslauncher android.permission.ACCESS_FINE_LOCATION
  ```

## 8. Configure

The best route is the **phone companion**:
1. On the TV, open Quick settings (OK on **M**) › **Pair a phone**.
2. The user scans the QR code with their phone and taps **Pair**.
3. On the phone page they add calendar links, watch-list titles and wallpapers, and arrange the dock.

From the Mac, `curl` may go through a proxy that can't reach the home network. Use `curl --noproxy '*'`. The built-in browser panes of some AI tools refuse home-network addresses.

**adb alternative** (useful when you're doing the setup). The commands go to a receiver that requires `DUMP`, which only adb and the system hold. Have the user run calendar commands themselves so the link doesn't pass through you.

```bash
adb -s <TV> shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd add_calendar --es name "'Personal'" --es url "'webcal://…'"
adb -s <TV> shell am broadcast -n com.manishlad.glasslauncher/.DevConfigReceiver --es cmd add_watching --es title "'Show'" --es package com.netflix.ninja --es upto "'S1 · E2'"
adb -s <TV> shell 'content write --uri "content://com.manishlad.glasslauncher.dev/wallpaper?name=Beach&use=true"' < photo.jpg
```

Other `cmd` values:
- `remove_calendar` (`name`)
- `clear_watching`
- `remove_wallpaper` (`name`)
- `wallpaper_mode` (`mode` = `fixed` + `name` | `daily` | `hours` + `hours` | `timeofday` + `morning`/`afternoon`/`evening`/`night` = `"Name|Name"`)
- `refresh`
- `unpair_all`
- `idle` (`seconds`; shortens the time before Ambient, for testing)
- `offline` (`on` = `false` to undo; simulates no network for testing)

The test settings reset when the launcher restarts.

## 9. Final check

- **Home screen:** Home, reboot and a crash all land on the launcher, and holding Back for 5 s opens Android Settings.
- **Widgets:** weather loads, calendars sync, and Now watching fills in.
- **Ambient:** appears after 3 min idle and any key wakes it, with focus where it was. The screensaver starts after the TV's own timeout.
- **Quick settings:** switching to an HDMI input works (have the user try it; it changes what's on screen).
- **Dock and remote:** dock editing (hold OK on a dock app) works, and the remote's app buttons open their apps.
- **Memory:** check `dumpsys meminfo com.manishlad.glasslauncher` (about 100 MB PSS on Home is normal).

## Updating the app

Rebuild, then install with `install -r` while the launcher is on screen. Installing restarts the launcher process; Android brings it back as home, and the screensaver, media listener and accessibility service rebind automatically. Permissions and settings survive updates.

## Troubleshooting

- **Home key leaves the old app on screen:** the theme has become translucent (see step 5). Check with `dumpsys activity activities`: a home task with `translucent=true` or `occludesParent=false` is the problem.
- **An app won't open from the dock:** it may be frozen. Look for "pause timeout" or "stop timeout" in `logcat -s ActivityTaskManager`. Fix with `adb shell am force-stop <package>`.
- **Remote button does nothing:** check that the accessibility service is still enabled (`settings get secure enabled_accessibility_services`). Then press the button and look for the key code in `logcat | grep interceptKeyTq`.
- **Now watching misses an app:** check `logcat -s GlassLauncher | grep watch:` for the skip reason. "no title" means the app doesn't share titles (Netflix). "short" means under 5 min. "no length, played under 5 min" means it's a live stream that hasn't played long enough.
- **Phone can't reach the page:** the phone must be on the same Wi-Fi and use the TV's IP (other host names are rejected). The server logs `companion server on :8080` at start-up.
- **Logs:** `adb -s <TV> logcat -s GlassLauncher AndroidRuntime`.

## Undo / restore

Run these to put the TV back as it was, then uninstall. Uninstalling while the stock launcher is still disabled leaves the TV with only a bare fallback home screen.

```bash
adb -s <TV> shell pm enable com.google.android.apps.tv.launcherx
adb -s <TV> shell pm enable com.google.android.tungsten.setupwraith
adb -s <TV> shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb -s <TV> shell settings delete secure enabled_accessibility_services   # or restore the previous value
adb -s <TV> shell settings put secure accessibility_enabled 0
adb -s <TV> shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop   # or the recorded previous value
adb -s <TV> shell cmd notification disallow_listener com.manishlad.glasslauncher/com.manishlad.glasslauncher.MediaWatcher
adb -s <TV> shell pm revoke com.manishlad.glasslauncher android.permission.ACCESS_FINE_LOCATION
adb -s <TV> uninstall com.manishlad.glasslauncher
```

If adb isn't reachable, hold Back for 5 s in the launcher to get to Android Settings, then go to Apps › show system apps and re-enable the stock launcher.

## Code conventions

- **Sizes:** designs are in px at 1920×1080. In code, write `132.px` / `132.pxSp` (design px ÷ 2 = dp at density 320) so numbers match `design/html`.
- **Glass:** surfaces use `GlassSurface` / `GlassButton` in `ui/Glass.kt`, which draw the pre-blurred wallpaper crop. There's no live blur on these TVs.
- **Animation:** transform and alpha only. Keep memory low (1080p bitmaps, and free retired slides).
- **Remote handling:** OK is handled on key-up via `onSelect` (hold detection uses key repeat).
- **Focus:** ◀ ▶ must stop at the ends of each row (`focusProperties { left/right = Cancel }`).
- **Testing:** add unit tests in `app/src/test` for pure logic. Verify UI on the TV with screenshots.
