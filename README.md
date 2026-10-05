# Weather Buddy

A cute girl dressed for the weather where you are, shown as an animated
Android wallpaper (rain, snow and wind move around her), a widget, or a still
lock screen. The widget also shows the place, temperature, humidity and wind,
and has a button to refresh it. The app also shows the forecast for the coming
days. You can pick the art style: **Pixel art**, **Anime**, **Ukiyo-e**,
**Delft blue**, **Mucha** (Art Nouveau posters), **Van Gogh**, **Watercolour**,
**Paper cut**, **Art Deco** or **Pop art**; each has one finished picture per kind of weather, and
**Browse all pictures** shows them all.

Weather data comes from [Open-Meteo](https://open-meteo.com) (KNMI HARMONIE
model) and rain radar from [Buienradar](https://www.buienradar.nl).

## Layout

- `core/`: pure Kotlin logic. It turns API responses into conditions, then
  into a scene, the picture that fits it and the particles moving over it.
- `app/`: the Android app: settings screen, home-screen widget, wallpaper and
  lock screen, and a background refresh every 30 minutes.
- `app/src/main/assets/scenes/<style>/`: the 30 finished pictures per style
  (pixel art also has the widget's icons for each).
- `tools/preview/`: renders animated GIF previews of the live wallpaper.
- `tools/scenes/`: the pixel-art scene pictures and the script that turns
  them into assets, plus `import_style.py` for another style's pictures and
  `<style>/PROMPTS.md`, the ChatGPT prompts for every style
  but pixel art.
- `docs/DESIGN.md`: decisions, asset spec and roadmap.

## Install on your phone

Every push builds the app on GitHub Actions and publishes it as the
[latest release](https://github.com/intramuros/weather-app/releases/latest).
On your phone, open
<https://github.com/intramuros/weather-app/releases/latest/download/weather-buddy.apk>
(no GitHub account needed). Android will ask you to allow installs from your
browser once; if Play Protect warns about an unknown developer, choose
**More details → Install anyway**. New builds install over the old one.

## Build and run

Open the project in Android Studio, or from the command line (needs JDK 17+
and the Android SDK):

```sh
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug                      # Android lint
./gradlew :app:installDebug                   # install on a connected phone
./gradlew :tools:preview:run                  # GIFs of the live wallpaper → tools/preview/build/previews
```

After installing, open the app once and tap **Set** next to "Animated
wallpaper". You can also add the widget from your home screen's widget picker.

Builds are signed with the shared key in `app/debug.keystore`, so builds from
CI, Android Studio and the command line all install over each other. That key
is public; a store release would need a private one.
