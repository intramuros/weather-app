# Weather Buddy

A cute character dressed for the weather where you are, shown as an Android
widget, wallpaper or lock screen. You can pick the art style: **Pixel art**,
**Ukiyo-e** or **Delfts Blauw**.

Weather data comes from [Open-Meteo](https://open-meteo.com) (KNMI HARMONIE
model) and rain radar from [Buienradar](https://www.buienradar.nl).

## Layout

- `core/`: pure Kotlin logic. It turns API responses into conditions, then
  into a scene, an outfit and an ordered list of image layers.
- `app/`: the Android app: settings screen, home-screen widget, wallpaper and
  lock screen, and a background refresh every 30 minutes.
- `app/src/main/assets/styles/`: one folder of PNG layers per style. These are
  placeholders for now.
- `tools/placeholders/`: regenerates the placeholder art.
- `docs/DESIGN.md`: decisions, dressing rules, asset pack spec and roadmap.

## Build and run

Open the project in Android Studio, or from the command line (needs JDK 17+
and the Android SDK):

```sh
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug                      # Android lint
./gradlew :app:installDebug                   # install on a connected phone
./gradlew :tools:placeholders:run             # regenerate placeholder art
```

After installing, open the app once, then add the widget from your home
screen's widget picker or turn on the wallpaper switches.
