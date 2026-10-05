---
name: run-weather-buddy
description: Build, install, run and screenshot the Weather Buddy Android app (settings screen, gallery, live wallpaper, home-screen widget) on a headless emulator, and drive it by tapping on-screen text. Use when asked to run, start, launch, test, try, check or screenshot the app, a style, the wallpaper or the widget, or to confirm a change works on a device.
---

# Run Weather Buddy

Weather Buddy is an Android app (Kotlin, Compose, Glance), so on Linux you drive it
on a **headless Android emulator** through `adb`. The driver
`.claude/skills/run-weather-buddy/driver.sh` wraps all of it in one-shot commands:
boot, build, install, launch, tap by visible text, screenshot, set the live
wallpaper, add the widget, fake the location. Paths are relative to the repo root.
Screenshots go to `/tmp/weather-buddy/` (override with `OUT=`).

## Prerequisites (once per machine, no sudo)

```sh
.claude/skills/run-weather-buddy/driver.sh setup
```

This installs JDK 21 (`brew install openjdk@21`, as CI uses 21) unless a JDK 21
is already found, then puts the Android command-line tools in `~/Android/Sdk`,
along with `platform-tools`, `emulator`, `platforms;android-37.0`,
`build-tools;37.0.0` and the `system-images;android-36;google_apis;x86_64` image.
It also creates a Pixel 6 AVD called `weather-buddy`. That's about 6 GB, done in
about 4 minutes. Needs `/dev/kvm`. For plain Gradle commands, put the same
environment in your shell first:

```sh
eval "$(.claude/skills/run-weather-buddy/driver.sh env)"
```

## Run (agent path)

```sh
D=.claude/skills/run-weather-buddy/driver.sh
$D boot              # headless emulator, returns when booted (30-50 s; `boot --wipe` = factory-fresh)
$D build             # ./gradlew :app:assembleDebug (~2 min cold, seconds warm)
$D install
$D launch            # main screen; prints Status: ok
$D ss                # -> /tmp/weather-buddy/screen.png; then Read it
$D tap Mucha         # taps the first node whose text contains "Mucha", scrolling down to find it
$D scroll top; $D ss /tmp/weather-buddy/mucha.png
$D ui                # every labelled node: "x y<TAB>text<TAB>flags", to pick the next tap
$D stop
```

Other commands:

| Command | Does |
|---|---|
| `tap-xy X Y`, `scroll down\|up\|top`, `back`, `home` | raw input |
| `tap "Browse all pictures"` | the Gallery: 30 pictures per style, with a chip row of styles |
| `wallpaper` | sets the live wallpaper on home and lock screen through the system preview, and checks it took |
| `widget` | adds the 2x2 widget to the home screen through the launcher's widget picker |
| `locate 60.39 5.32` | fakes the location (here Bergen), taps **Use my location**, prints the place it found |
| `pref` | the app's saved settings (style slug, switches, location) |
| `log` | logcat for the app's process plus the crash buffer |
| `launch --clear` | wipes app data first |

To see a given weather on the device, use `locate` to move to a place that has
it right now. Find one with Open-Meteo:

```sh
curl -s "https://api.open-meteo.com/v1/forecast?latitude=60.39&longitude=5.32&current=weather_code,precipitation"
```

Codes 51 and up are drizzle or rain, and 71 and up are snow. Then `$D wallpaper; $D home; $D ss`
shows the particles moving over that scene.

A full check of a style change, as verified:

```sh
$D boot && $D build && $D install && $D launch && $D tap Mucha && $D scroll top && $D ss
$D wallpaper && $D home && $D ss /tmp/weather-buddy/home.png
$D widget && $D ss /tmp/weather-buddy/widget.png
```

## Direct invocation (no emulator)

Most PRs touch `core/` (scene choice, particles) or the pictures. These run on
the JVM alone:

```sh
eval "$(.claude/skills/run-weather-buddy/driver.sh env)"
./gradlew :core:test :app:testDebugUnitTest      # what CI runs, minus lint (~2 min cold)
./gradlew :app:lintDebug
./gradlew :tools:preview:run                     # 3 animated GIFs per style -> tools/preview/build/previews/ (~25 s)
```

The preview GIFs (`<style>-windy-rain.gif`, `-snow.gif`, `-storm-from-east.gif`)
use the app's own particle code over a phone-shaped crop, so they're the fastest way to judge a
style's particle colours. The Read tool shows only the first frame of a GIF.
Pull a middle frame out with Pillow to see moving particles.

## Run (human path)

`./gradlew :app:installDebug` with a phone attached over USB, or open the
project in Android Studio. Neither works headless.

## Gotchas

- **A force-stop removes the live wallpaper.** `am force-stop` (and `pm clear`, `am start -S`)
  kills the wallpaper service, and Android silently goes back to the stock
  wallpaper. `launch` therefore restarts only the activity
  (`-f 0x10008000`). Run `wallpaper` again after `launch --clear` or a reinstall.
- **`adb emu geo fix` doesn't move the app.** The app asks the *fused* provider at
  BALANCED power with coarse permission, which never turns on the emulator's GPS, so
  `last location` stays null and the place stays "De Bilt (default)". `locate`
  replaces `fused` with a test provider (`cmd location providers
  add-test-provider fused` after `appops set com.android.shell android:mock_location allow`).
- **Style cards have no labels of their own.** In the UI dump, a card is a clickable node with no text,
  and its name is a separate text node just below it, inside the card's bounds. Tapping the
  name selects the card. The selected card is no longer `clickable`.
- **The settings screen is taller than the display.** The Style row, the switches and **Use my
  location** sit below the fold. `tap` scrolls down up to 5 times to find its text,
  and `scroll top` goes back to the scene picture.
- **The weather is real.** It comes from Open-Meteo for the current location (De Bilt by
  default), so the scene depends on the hour and the place. Rain radar
  (Buienradar) covers only the Netherlands. Elsewhere a `Buienradar unavailable` warning in
  `log` is expected.
- **The wallpaper is a portrait crop of a square picture**, so the system preview and the home
  screen show only the middle of the scene. That's by design, not a bug.
- `sdkmanager` now prints a deprecation warning pointing at the new `android` CLI. It still
  works, and `--licenses` is a no-op.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `JAVA_HOME is not set and no 'java' command could be found` from `./gradlew` | `eval "$(.claude/skills/run-weather-buddy/driver.sh env)"` (or run `setup`) |
| Home screen shows the stock wallpaper after `launch` or reinstall | `$D wallpaper` again (see the force-stop gotcha) |
| Location stays "De Bilt (default)" | use `$D locate LAT LON`, not `adb emu geo fix` |
| `error: no '<text>' on screen` | `$D ui` to see what is there. The text may only be a substring of a longer label, or on another screen |
