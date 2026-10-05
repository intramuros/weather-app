# Weather Buddy: design

An Android app (iOS later, maybe) that shows the current weather as a cute
character dressed for it. The picture can be shown in a home-screen widget or
set as the home and/or lock screen wallpaper.

## Decisions so far

| Topic | Decision |
|---|---|
| Platform | Android first |
| Art styles | **Pixel art**, **Anime**, **Ukiyo-e**, **Delft blue**, **Mucha**, **Van Gogh**, **Watercolour**, **Paper cut**, **Art Deco** and **Pop art** (all but pixel art made with ChatGPT from the pixel-art scenes: `tools/scenes/<style>/PROMPTS.md`; the originals are in `tools/scenes/<style>/source/`). Every style draws the same scenes; the app only offers a style whose 30 pictures are all there. (Ukiyo-e and Delfts Blauw were first tried as layers combined on the device, with placeholder art, and dropped; they came back as finished pictures like anime.) |
| Image production | Finished scenes, one per kind of weather and warmth, bundled with the app (offline, free) |
| Character | The same girl in every scene of a style. In the eight styles drawn with Codex she is a young East Asian woman with long dark brunette hair, drawn the way her style draws women, in a setting and composition from the style's own tradition (a Hiroshige dyke, a Delft tile vignette, van Gogh's Arles, Mucha's ornament with no place behind it), so the styles look distinct. Pixel art and anime, made by hand, still show the original chestnut-haired girl on an Amsterdam canal |
| Animation | **Live wallpaper** with rain, snow and wind moving over the scene; she is painted in and stays still. The widget and the "still picture" wallpaper options stay still. |
| Forecast | Open-Meteo using KNMI HARMONIE (`models=knmi_seamless`) |
| Rain nowcast | Buienradar `raintext` (2 h ahead, 5-minute steps) |
| Language | **Kotlin** throughout. Pure logic sits in a plain Kotlin module (`core/`), so it can become Kotlin Multiplatform if iOS ever happens. |
| Location | Coarse, read only while the app is open, rounded to ~1 km. No background-location permission; the background refresh reuses the last saved location (default: De Bilt). Its town name comes from the platform `Geocoder`, when it has one. |

## Architecture

```
core/                  pure Kotlin/JVM, no Android
  OpenMeteo, Buienradar  URLs + response parsers → Conditions
  Scene                  weather → sky/precipitation/day-night/wind
  ScenePicture, RenderPlan → the picture that fits, plus the particles over it
  Style                  per style: asset paths, frame rate, particle looks

app/                   Android (Compose, Glance, WorkManager, DataStore)
  RefreshWorker          every 30 min while online, and on the widget's
                         refresh button
  Refresher              fetch → plan → Compositor → save preview
                         → update widget → set wallpaper if enabled and changed
  Compositor             draws the picture from assets/scenes/<style>/ into a Bitmap
  LiveRenderer           draws the plan at any moment: the picture, then the
                         particles for that frame
  BuddyWallpaperService  the animated wallpaper; draws only while visible
  InfoOverlay            draws the widget's icons and text: place, temperature,
                         condition, humidity, wind
  WeatherWidget          Glance widget drawing the last weather's picture in its
                         own shape, with a refresh button in the bottom-right corner
  MainActivity           preview, forecast for the coming days, style picker
                         (once there's a choice), wallpaper switches, location,
                         credits
  GalleryActivity        every picture of every style in a grid; tap one to see
                         it whole and swipe through the rest

tools/preview/         animated GIF previews of the live wallpaper (`run`)
tools/scenes/          turns the pixel-art scene mock-ups into assets (`prepare.py`),
                       and another style's finished pictures (`import_style.py`)
```

`core` has no networking and no I/O. It turns API responses into
`Conditions`, `Conditions` into a `Scene` (sky, precipitation, day/night,
wind), and those into a `RenderPlan`: which `ScenePicture` to show and which
particles move over it. That keeps every decision unit-testable on the JVM
without an emulator.

The wallpaper is only re-set when the picture actually changes (picture or
screen size), so a refresh every 30 minutes doesn't cause flicker.

## Animation

- **Weather effects are code, not art.** `ParticleSpec` turns the weather into
  particle counts, speeds, slant and gustiness: heavier radar rain means more,
  longer and faster drops, wind slants them, and gusts come and go. Snow sways,
  and a storm adds tumbling leaves. `ParticleField` computes every particle's
  position directly from the time, so frames never depend on the ones before
  and nothing drifts over hours. Each style sets the particle colour and line
  width.
- **The girl is painted in**, so only the weather moves, falling over the
  picture.
- **Wind direction.** Particles are simulated blowing to the right. When the
  real wind blows west (an east wind), they are mirrored. The scenes never
  are: they're composed one way round, and the widget's text positions are
  measured on them.
- **Frame rate and battery.** Pixel art runs at 12 fps and the others at 24, or 6
  in battery saver. Nothing is drawn while the wallpaper is hidden. For pixel
  art, particles snap to the scene's pixel grid, so they look like part of
  the art; for the others they're smooth, thin lines and dots, coloured to
  match: glassy for anime, indigo rain for ukiyo-e, cobalt for Delft blue,
  powder blue and cream for Mucha, thick paint strokes for Van Gogh, soft
  washes for watercolour, strips of card for paper cut, fine cream lines for
  Art Deco, and solid comic blue with black speed lines for pop art.
- Applying a live wallpaper always needs the user to confirm it in the system
  wallpaper screen; the app opens that screen for them. While the animated
  wallpaper is active, the "still picture on home screen" option is ignored so
  it can't replace it.

## Weather sources

| Source | Used for | Key | Terms |
|---|---|---|---|
| Open-Meteo `/v1/forecast?models=knmi_seamless` | temperature, feels-like, humidity, wind speed/direction/gusts, WMO weather code, day/night, UV; per day for 5 days: weather code, high/low, rain total, strongest gust | none | Free for non-commercial use (< 10k calls/day). A commercial release needs a paid plan or a switch to KNMI open data. |
| Buienradar `gpsgadget.buienradar.nl/data/raintext` | rain right now + next 2 h | none | Free if we credit buienradar.nl with a link. Show this in the app's About/credits. |
| *Fallbacks* | | | |
| KNMI Data Platform | official open data (CC-BY 4.0) | free key | Raw NetCDF/HDF5, so better processed on a server |
| MET Norway `api.met.no` | forecast | none (User-Agent required) | CC-BY 4.0 |

## Scenes

The art is a set of finished square pictures per style of the girl, on an
Amsterdam canal or in her style's own setting (`ScenePicture`). Each shows one kind of sky (`SceneKind`)
and dresses her for one band of feels-like temperature (`Warmth`): hot
(25 °C+), warm (20–25), mild (15–20), cool (10–15), cold (3–10) or freezing
(< 3):

| Picture | Sky | Warmth | Outfit |
|---|---|---|---|
| `clear-hot` | sunny (day) | hot (25 °C+) | sundress, sun hat, sandals |
| `clear-warm` | sunny (day) | warm (20–25 °C) | dress, cardigan, sunglasses |
| `clear-freezing` | sunny (day), snow on the ground | freezing (< 3 °C) | puffer, bobble hat, scarf |
| `clear-night-hot` | clear night | hot | T-shirt, shorts |
| `clear-night-warm` | clear night | warm | T-shirt, shorts |
| `clear-night-cold` | clear night | cold (3–10 °C) | coat, scarf |
| `partly-cloudy-mild` | partly cloudy (day) | mild (15–20 °C) | sweater, jeans, sneakers |
| `partly-cloudy-cold` | partly cloudy (day) | cold | coat, scarf, closed umbrella |
| `cloudy-cool` | cloudy | cool (10–15 °C) | sweater, jacket |
| `cloudy-cold` | cloudy | cold | coat, scarf, closed umbrella |
| `cloudy-freezing` | cloudy | freezing | coat, scarf |
| `fog-cool` | fog | cool | sweater, jacket, scarf |
| `fog-freezing` | fog | freezing | coat, scarf |
| `windy-mild` | windy (day) | mild | hoodie, jeans |
| `windy-cool` | windy, autumn leaves (day) | cool | trench coat, scarf |
| `windy-night-cool` | windy night | cool | hoodie, jeans |
| `windy-freezing` | windy (day) | freezing | coat, blowing scarf |
| `windy-night-freezing` | windy night | freezing | coat, blowing scarf |
| `rain-hot` | rain | hot | T-shirt, shorts, umbrella |
| `rain-warm` | rain (day) | warm | T-shirt, shorts, umbrella |
| `rain-mild` | rain | mild | light jacket, umbrella |
| `rain-night-cool` | rain at night | cool | coat, scarf, umbrella |
| `rain-cold` | rain | cold | coat, scarf, umbrella |
| `rain-freezing` | freezing rain | freezing | coat, scarf |
| `storm-warm` | storm | warm | raincoat, rain boots |
| `storm-cool` | storm | cool | raincoat, rain boots, umbrella |
| `storm-cold` | storm | cold | coat, scarf, umbrella |
| `snow-cold` | snow | cold | coat, scarf, umbrella |
| `snow-freezing` | snow (day) | freezing (< 3 °C) | puffer, bobble hat, mittens |
| `snow-night-freezing` | snow at night | freezing | coat, bobble hat, scarf |

`ScenePicture.choose` picks the picture whose outfit is closest to how warm
it feels, among pictures of the same or a similar sky. Clothes count for
more than the sky: a cold, sunny day gets the partly cloudy picture with a
coat, not the sunny one with a dress. Snow is only ever shown as snow, and a
sky for the wrong time of day (sun at night, stars by day) counts against a
picture. Adding a picture for a missing combination (for instance a mild
foggy day or a cold windy night) makes the match exact.

```
scenes/pixel-art/<picture>.webp        the scene, no icons or text (1200 × 1200)
scenes/pixel-art/<picture>-icons.webp  its weather, drop and wind icons, on transparency
scenes/<other style>/<picture>.webp    the same scene in that style (1200 × 1200)
```

The sources are widget mock-ups with example text, in `tools/scenes/source/`,
named `<sky>-<warmth>`. `tools/scenes/prepare.py` removes the frame, text and
icons, fills the gaps with the surrounding sky, and writes both files. The
wallpaper uses the plain scene (its middle, on a phone). The widget draws the
icons on top, then the live text where the mock-up had it: place, humidity
and wind on the right, temperature and condition on the left. `SceneLayout`
holds those positions per picture, measured from the artwork. The widget's
picture is drawn in the widget's own shape, the scene covering it like the
wallpaper; on a widget that isn't square, the icons and text keep their size
relative to its narrower side, at the top, with the left column against the
left edge and the right column against the right. The
temperature uses Jersey 10 and the rest DotGothic16 (Latin subset), both SIL
OFL, in `assets/fonts/`. In every style, the widget's refresh button sits in
the bottom-right corner, over the ground; it shows three dots while the
refresh runs.

To add a picture: put the mock-up in `tools/scenes/source/`, add its text and
icon boxes to `prepare.py` and run it, then add a `ScenePicture` entry and
its `SceneLayout`. Every other style then needs the picture too.

### Other styles

Other styles redraw the pixel-art scenes, keeping each one's sky, outfit and
layout, so `ScenePicture.choose` works the same for all of them. They have no
icon layer: the widget writes its text in the sky, which those pictures keep
clear (temperature and condition top left; place, humidity and wind top
right, over a soft shade). `tools/scenes/import_style.py` crops, scales and
converts a folder of finished pictures; it writes nothing until all of them
are there, and `StyleTest` fails on a half-finished style folder.

To add a style: add a `Style` entry (slug, name, frame rate, particle looks),
make its 30 pictures, and import them. `tools/scenes/anime/PROMPTS.md` is how
the anime ones are made; the other styles' folders in `tools/scenes/` have the
prompts, and `codex_scene.sh` the script, that made them with the Codex CLI.

## Roadmap

1. ✅ Core logic: parsing, scene and picture choice, with tests
2. ✅ Android shell: refresh worker, settings screen, compositor
3. ✅ Glance widget + home/lock screen wallpaper
4. ✅ Live wallpaper: particles, wind direction
5. ✅ CI: tests, lint and an installable APK on every pull request; `master`
   publishes it
6. ✅ Art: 30 finished scenes each in pixel art, anime and eight
   painted styles
7. ✅ Widget layout: place, temperature, condition, humidity and wind
8. Polish: rain splashes, a forecast strip ("rain at 14:45"), pictures for
   the missing weather combinations, a private release key for a store
   release
