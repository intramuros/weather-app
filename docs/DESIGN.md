# Weather Buddy: design

An Android app (iOS later, maybe) that shows the current weather as a cute
character dressed for it. The picture can be shown in a home-screen widget or
set as the home and/or lock screen wallpaper. The art style can be changed.

## Decisions so far

| Topic | Decision |
|---|---|
| Platform | Android first |
| Art styles (v1) | **Pixel art**, **Ukiyo-e**, **Delfts Blauw** |
| Image production | **Pixel art:** five finished scenes, one per kind of weather. **Other styles:** layered assets composited on the device (offline, free, consistent character) |
| Forecast | Open-Meteo using KNMI HARMONIE (`models=knmi_seamless`) |
| Rain nowcast | Buienradar `raintext` (2 h ahead, 5-minute steps) |
| Language | **Kotlin** throughout. Pure logic sits in a plain Kotlin module (`core/`), so it can become Kotlin Multiplatform if iOS ever happens. |
| Location | Coarse, read only while the app is open, rounded to ~1 km. No background-location permission; the background refresh reuses the last saved location (default: De Bilt). Its town name comes from the platform `Geocoder`, when it has one. |

## Architecture

```
core/                  pure Kotlin/JVM, no Android
  OpenMeteo, Buienradar  URLs + response parsers → Conditions
  Scene, Outfit          weather → sky/precipitation/wind + clothes/expression
  Style, RenderPlan      → ordered list of asset paths per style

app/                   Android (Compose, Glance, WorkManager, DataStore)
  RefreshWorker          every 30 min while online
  Refresher              fetch → plan → Compositor → save images
                         → update widget → set wallpaper if enabled and changed
  Compositor             stacks assets/styles/<style>/… into a Bitmap
  InfoOverlay            draws the widget's icons and text: place, temperature,
                         condition, humidity, wind
  WeatherWidget          Glance widget showing the last picture
  MainActivity           preview, style picker, wallpaper switches, location, credits

tools/placeholders/    generates simple placeholder art for the layered styles
tools/scenes/          turns the pixel-art scene mock-ups into assets
```

`core` has no networking and no I/O. It turns API responses into
`Conditions`, `Conditions` into a `Scene` (sky, precipitation, day/night,
wind) and an `Outfit` (clothes, accessories, facial expression), and those
into an ordered list of asset paths. That keeps every decision unit-testable
on the JVM without an emulator.

The wallpaper is only re-set when the picture actually changes (style, layers
or screen size), so a refresh every 30 minutes doesn't cause flicker.

## Weather sources

| Source | Used for | Key | Terms |
|---|---|---|---|
| Open-Meteo `/v1/forecast?models=knmi_seamless` | temperature, feels-like, humidity, wind speed/direction/gusts, WMO weather code, day/night, UV | none | Free for non-commercial use (< 10k calls/day). A commercial release needs a paid plan or a switch to KNMI open data. |
| Buienradar `gpsgadget.buienradar.nl/data/raintext` | rain right now + next 2 h | none | Free if we credit buienradar.nl with a link. Show this in the app's About/credits. |
| *Fallbacks* | | | |
| KNMI Data Platform | official open data (CC-BY 4.0) | free key | Raw NetCDF/HDF5, so better processed on a server |
| MET Norway `api.met.no` | forecast | none (User-Agent required) | CC-BY 4.0 |

## Dressing rules (summary)

Based on the *feels-like* temperature:

| Feels like | Top | Bottom | Shoes | Outerwear | Extras |
|---|---|---|---|---|---|
| ≥ 25 °C | tank top | shorts | sandals | – | |
| 20–25 | t-shirt | shorts | sneakers | – | |
| 15–20 | long sleeve | trousers | sneakers | – | |
| 10–15 | sweater | trousers | sneakers | light jacket | |
| 3–10 | sweater | trousers | boots | coat | scarf < 8 °C |
| < 3 | sweater | trousers | boots | puffer coat | scarf, beanie, gloves |

Modifiers:
- **Raining now and calm wind:** open umbrella; the rain is drawn *behind* the buddy.
- **Rain within the hour:** closed umbrella in hand.
- **Rain and gusts ≥ 35 km/h:** no umbrella (it would break in Dutch wind). Raincoat and rain boots instead.
- **Snow:** boots, scarf, beanie, gloves.
- **Sun** (clear or partly cloudy, daytime, dry): sunglasses at UV ≥ 3, sun hat at UV ≥ 6, but no hat in a storm.
- **Expression priority:** windswept (storm) > soggy (wet without an umbrella) > shivering (< 0 °C) > sweaty (≥ 28 °C) > sleepy (night) > happy.

## Asset pack spec

### Pixel art: finished scenes

Pixel art is a set of finished square pictures of the same girl on an
Amsterdam canal (`ScenePicture`). Each shows one kind of sky (`SceneKind`)
and dresses her for one band of feels-like temperature (`Warmth`, the same
bands as the dressing rules above):

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
pixel-art/scene/<picture>.webp        the scene, no icons or text (1200 × 1200)
pixel-art/scene/<picture>-icons.webp  its weather, drop and wind icons, on transparency
```

The sources are widget mock-ups with example text, in `tools/scenes/source/`,
named `<sky>-<warmth>`. `tools/scenes/prepare.py` removes the frame, text and
icons, fills the gaps with the surrounding sky, and writes both files. The
wallpaper uses the plain scene (its middle, on a phone). The widget draws the
icons on top, then the live text where the mock-up had it: place, humidity
and wind on the right, temperature and condition on the left. `SceneLayout`
holds those positions per picture, measured from the artwork. The
temperature uses Jersey 10 and the rest DotGothic16 (Latin subset), both SIL
OFL, in `assets/fonts/`.

To add a picture: put the mock-up in `tools/scenes/source/`, add its text and
icon boxes to `prepare.py` and run it, then add a `ScenePicture` entry and
its `SceneLayout`.

### Layered styles

Ukiyo-e and Delfts Blauw ship the **same file list**, generated by
`Style.requiredAssets()`. A unit test checks that every layer the logic can
draw exists in that list and that nothing in the list is unreachable.

```
<style>/background/<sky>-<day|night>.png    sky: clear, partly-cloudy, overcast, fog, thunderstorm
<style>/body/base.png
<style>/face/<expression>.png               happy, sleepy, shivering, sweaty, soggy, windswept
<style>/bottom/<shorts|trousers>.png
<style>/footwear/<sandals|sneakers|boots|rain-boots>.png
<style>/top/<tank-top|t-shirt|long-sleeve|sweater>.png
<style>/outerwear/<light-jacket|raincoat|coat|puffer-coat>.png
<style>/accessory/<scarf|gloves|sunglasses|beanie|sun-hat|umbrella-closed|umbrella-open>.png
<style>/fx/<drizzle|rain|heavy-rain|snow|hail>.png
<style>/fx/wind-<breezy|stormy>.png
```

That is 45 images per style, all transparent PNGs, in two shapes:

- **Scenery** (`background/`, `fx/`) is **square**. It is the whole world the
  buddy stands in.
- **The buddy** (every other folder) is a **9:20 frame of the same height**.
  All buddy layers share that frame, so stacking them needs no offsets.

The app scales the scenery to cover the target and draws the buddy's frame at
the same scale, wherever it should stand:

- **Wallpaper:** the buddy is centred. A 9:20 phone shows exactly the middle
  of the scene, so the buddy's frame fills the screen.
- **Widget:** the picture is square; the widget fits it to its own shape. The
  buddy stands at 32 % of the width, and the place, temperature, condition,
  humidity and wind are drawn on the right (`InfoOverlay`), in a serif.

- Layout: the character stands in the lower-middle, from about 35 % (umbrella
  top) to 87 % of the height. The ground line is at 87 %. The top of the
  picture stays free for the lock-screen clock, and the top right of the
  scene for the widget's text, so keep the sun and moon left of centre.
- Draw at **2400 × 2400** (scenery) and **1080 × 2400** (buddy) or larger.
- **Ukiyo-e:** flat colour areas, bold outlines and a woodblock paper
  texture in the background layer. Rain is drawn as Hiroshige-style
  diagonal lines. Prussian blue, vermilion and ochre.
- **Delfts Blauw:** cobalt on off-white glaze, drawn with brush hatching. The
  background includes a tile border, with windmill and canal motifs.
  Precipitation is painted in the same blue. Nearly monochrome, so the
  weather has to read through shapes rather than colour.

## Roadmap

1. ✅ Core logic: parsing, scene, outfit and layer plan, with tests
2. ✅ Android shell: refresh worker, settings screen, compositor
3. ✅ Placeholder asset pack (`./gradlew :tools:placeholders:run`)
4. ✅ Glance widget + home/lock screen wallpaper
5. Real art: ✅ pixel art (finished scenes); Ukiyo-e and Delfts Blauw still
   placeholders. Drop PNGs into `app/src/main/assets/styles/<style>/` with
   the names above.
6. ✅ Widget layout: place, temperature, condition, humidity and wind
7. Polish: subtle animation in the widget (rain frames), a forecast strip
   ("rain at 14:45"), more styles, release signing
