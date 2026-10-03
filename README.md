# Weather Buddy

A cute girl dressed for the weather where you are, shown as an animated
Android wallpaper (rain, snow and wind move around her), a widget, or a still
lock screen. The widget also shows the place, temperature, humidity and wind,
and has a button to refresh it. You can pick the art style: **Pixel art**,
**Ukiyo-e** or **Delfts Blauw**.

Weather data comes from [Open-Meteo](https://open-meteo.com) (KNMI HARMONIE
model) and rain radar from [Buienradar](https://www.buienradar.nl).

## Layout

- `core/`: pure Kotlin logic. It turns API responses into conditions, then
  into a scene, an outfit and an ordered list of image layers.
- `app/`: the Android app: settings screen, home-screen widget, wallpaper and
  lock screen, and a background refresh every 30 minutes.
- `app/src/main/assets/styles/`: one folder per style. Pixel art has nine
  finished scenes; the other styles have placeholder PNG layers.
- `tools/placeholders/`: regenerates the placeholder art for the layered
  styles and renders animated GIF previews of the live wallpaper.
- `tools/scenes/`: the pixel-art scene pictures and the script that turns
  them into assets.
- `docs/DESIGN.md`: decisions, dressing rules, asset pack spec and roadmap.

## Install on your phone

Every push builds the app on GitHub Actions and publishes it as the
[latest release](https://github.com/intramuros/weather-app/releases/latest).
On your phone, signed in to GitHub, open that page and tap
**weather-buddy.apk**. Android will ask you to allow installs from your browser
once; if Play Protect warns about an unknown developer, choose
**More details → Install anyway**. New builds install over the old one.

## Build and run

Open the project in Android Studio, or from the command line (needs JDK 17+
and the Android SDK):

```sh
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug                      # Android lint
./gradlew :app:installDebug                   # install on a connected phone
./gradlew :tools:placeholders:run             # regenerate placeholder art
./gradlew :tools:placeholders:preview         # GIFs of the live wallpaper → tools/placeholders/build/previews
```

After installing, open the app once and tap **Set** next to "Animated
wallpaper". You can also add the widget from your home screen's widget picker.

Builds are signed with the shared key in `app/debug.keystore`, so builds from
CI, Android Studio and the command line all install over each other. That key
is public; a store release would need a private one.
