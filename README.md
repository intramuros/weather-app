# Weather Buddy

A cute girl dressed for the weather where you are, shown as an animated
Android wallpaper (rain, snow and wind move around her), a widget, or a still
lock screen. The widget also shows the place, temperature, humidity, wind and
when the weather was last fetched, and has a button to refresh it. The app
also shows the next hours over the picture, each with an icon for its
weather (sunny, cloudy, windy, rain…), alongside the forecast for the coming
days. You can pick the art
style: **Pixel art**, **Anime**, **Ukiyo-e**, **Delft blue**, **Mucha** (Art
Nouveau posters), **Van Gogh**, **Watercolour**, **Paper cut**, **Art Deco**
or **Pop art**; each has one finished picture per kind of weather, and
**Browse all pictures** shows them all.

With **Use my location**, the weather follows your phone. It looks again
each time you open the app, when the animated wallpaper comes into view (at
most every 15 minutes) and when you tap the widget's refresh button. To have
the widget and wallpaper keep up without any of that, turn on **Follow me in
the background** and allow location all the time; Android then tells the app
when you've moved about a kilometre (it checks a few times an hour, without
GPS), and the scheduled refresh looks too. Without it, the app never asks for
your location in the background.

The first time you open the app, it asks to use your location (and, from
Android 13, to send notifications). If location is off later, not allowed or
switched off for the whole phone, and your phone seems to be somewhere else,
the app reminds you to turn it on, with a card in the app and a notification.
Without location it can only tell from the time zone and the mobile network's
country, so it notices a trip abroad, not a move within the country.

You can also **make your own style**: describe it in a short chat, look at a
preview, and the app draws its 30 pictures on your phone with OpenAI's models.
This needs your own [OpenAI API key](https://platform.openai.com/api-keys),
and OpenAI charges your account for the chat and the pictures. The key stays
on your phone (encrypted, not backed up) and is only sent to OpenAI.

Weather data comes from [Open-Meteo](https://open-meteo.com) (KNMI HARMONIE
model). [Buienradar](https://www.buienradar.nl) supplies the local rain prediction
and the animated rain forecast over an OpenStreetMap base map. The forecast map
covers the Netherlands and nearby areas, and shows the available future images
up to three hours ahead.

The rain histogram below the radar shows Buienradar's next two hours in
five-minute bars (mm/h); tap a bar or move the slider to inspect its time and
intensity. Expired or unavailable intervals are left blank.
The map and histogram autoplay together on one five-minute timeline. Play/Pause
controls both; selecting a bar pauses both at that time. Forecast maps use
Buienradar's Netherlands/regional images, and missing map frames are skipped
during autoplay rather than replaced with unrelated radar history.

## Layout

- `core/`: pure Kotlin logic. It turns API responses into conditions, then
  into a scene, the picture that fits it and the particles moving over it.
- `app/`: the Android app: settings screen, home-screen widget, wallpaper and
  lock screen, and a background refresh that adapts to the rain: every 10–15 minutes while
  rain is within two hours, every 30 minutes on a wet or unknown day, and every
  60–90 minutes when it is dry.
- `app/src/main/assets/scenes/<style>/`: the 30 finished pictures per style
  (pixel art also has the widget's icons for each).
- `tools/preview/`: renders animated GIF previews of the live wallpaper.
- `tools/scenes/`: the pixel-art scene pictures and the script that turns
  them into assets, plus `import_style.py` for another style's pictures and
  `<style>/PROMPTS.md`, the ChatGPT prompts for every style
  but pixel art. The app draws the styles you make from the same prompts.
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
