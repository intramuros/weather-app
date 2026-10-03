# Weather Buddy: design

An Android app (iOS later, maybe) that shows the current weather as a cute
character dressed for it. The picture can be shown in a home-screen widget or
set as the home and/or lock screen wallpaper. The art style can be changed.

## Decisions so far

| Topic | Decision |
|---|---|
| Platform | Android first |
| Art styles (v1) | **Pixel art**, **Ukiyo-e**, **Delfts Blauw** |
| Image production | **Layered assets** composited on the device (offline, free, consistent character) |
| Character | A girl, the same in all three styles |
| Animation | **Live wallpaper** with moving rain, snow and wind, plus frame loops for her hair, blinking and scarf. The widget and the "still picture" wallpaper options stay still. |
| Forecast | Open-Meteo using KNMI HARMONIE (`models=knmi_seamless`) |
| Rain nowcast | Buienradar `raintext` (2 h ahead, 5-minute steps) |
| Language | **Kotlin** throughout. Pure logic sits in a plain Kotlin module (`core/`), so it can become Kotlin Multiplatform if iOS ever happens. |
| Location | Coarse, read only while the app is open, rounded to ~1 km. No background-location permission; the background refresh reuses the last saved location (default: De Bilt). |

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
  LiveRenderer           draws the plan at any moment: still runs flattened once,
                         frame loops + particles per frame
  BuddyWallpaperService  the animated wallpaper; draws only while visible
  WeatherWidget          Glance widget showing the last picture + temperature
  MainActivity           preview, style picker, wallpaper switches, location, credits

tools/placeholders/    generates placeholder art for every layer (`run`) and
                       animated GIF previews of the live wallpaper (`preview`)
```

`core` has no networking and no I/O. It turns API responses into
`Conditions`, `Conditions` into a `Scene` (sky, precipitation, day/night,
wind) and an `Outfit` (clothes, accessories, facial expression), and those
into an ordered list of asset paths. That keeps every decision unit-testable
on the JVM without an emulator.

The wallpaper is only re-set when the picture actually changes (style, layers
or screen size), so a refresh every 30 minutes doesn't cause flicker.

## Animation

- **Weather effects are code, not art.** `ParticleSpec` turns the weather into
  particle counts, speeds, slant and gustiness: heavier radar rain means more,
  longer and faster drops, wind slants them, and gusts come and go. Snow sways,
  and a storm adds tumbling leaves. `ParticleField` computes every particle's
  position directly from the time, so frames never depend on the ones before
  and nothing drifts over hours. Each style sets the particle colour and line
  width.
- **The girl moves in frame loops.** Her hair blows in 4 frames (faster in a
  storm), she blinks every few seconds, and her scarf flutters in the wind.
  Clothes stay still.
- **Wind direction.** Art is drawn with the wind blowing to the right. When the
  real wind blows west (an east wind), the whole picture is mirrored.
- **Frame rate and battery.** Pixel art runs at 12 fps and the other styles at
  24, or 6 in battery saver. Nothing is drawn while the wallpaper is hidden.
  Pixel art is drawn at its native 135 × 300 and scaled up, so the drops land
  on the same pixel grid as the art.
- Applying a live wallpaper always needs the user to confirm it in the system
  wallpaper screen; the app opens that screen for them. While the animated
  wallpaper is active, the "still picture on home screen" option is ignored so
  it can't replace it.

## Weather sources

| Source | Used for | Key | Terms |
|---|---|---|---|
| Open-Meteo `/v1/forecast?models=knmi_seamless` | temperature, feels-like, wind/gusts, WMO weather code, day/night, UV | none | Free for non-commercial use (< 10k calls/day). A commercial release needs a paid plan or a switch to KNMI open data. |
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

All three styles ship the **same file list**, generated by
`Style::required_assets()`. A unit test checks that every layer the logic can
draw exists in that list and that nothing in the list is unreachable.

```
<style>/background/<sky>-<day|night>.png    sky: clear, partly-cloudy, overcast, fog, thunderstorm
<style>/body/base.png                       the girl without hair
<style>/face/<expression>.png               happy, sleepy, shivering, sweaty, soggy, windswept
<style>/face/<expression>-blink.png         all except sleepy
<style>/bottom/<shorts|trousers>.png
<style>/footwear/<sandals|sneakers|boots|rain-boots>.png
<style>/top/<tank-top|t-shirt|long-sleeve|sweater>.png
<style>/hair/calm.png
<style>/hair/<breezy|stormy>-<0..3>.png     a loop, blowing to the right
<style>/outerwear/<light-jacket|raincoat|coat|puffer-coat>.png
<style>/accessory/<scarf|gloves|sunglasses|beanie|sun-hat|umbrella-closed|umbrella-open>.png
<style>/accessory/scarf-wind-<0..2>.png     a loop, fluttering to the right
<style>/fx/<drizzle|rain|heavy-rain|snow|hail>.png   for still pictures only
<style>/fx/wind-<breezy|stormy>.png                  for still pictures only
```

Drawing order, bottom to top: background, (rain, when under an umbrella),
body, face, bottom, footwear, top, hair, outerwear, accessories, rain, wind.

That is 62 images per style. Every layer is a transparent PNG with the **same
9:20 aspect ratio and size within a style**, so compositing is just stacking
with no offsets. The app scales layers to cover the target and crops around
the buddy: a tall wallpaper shows everything, and a square widget shows the
buddy with a bit of sky. Animated frames are trimmed to their visible pixels
when loaded, so full-canvas frames don't cost much memory.

- Layout: the character stands in the lower-middle, from about 47 % to 87 %
  of the height. The ground line is at 87 %. The top of the picture stays
  free for the lock-screen clock.
- **Pixel art** is drawn at **135 × 300** and shipped at that size. The app
  scales it up with nearest-neighbour scaling, which keeps pixels crisp. The
  palette is limited (≈ 32 colours).
- Other styles: draw at **1080 × 2400** or larger.
- **Ukiyo-e:** flat colour areas, bold outlines and a woodblock paper
  texture in the background layer. Prussian blue, vermilion and ochre. Rain is
  drawn as long, thin diagonal lines in the style of Hiroshige; the app's
  particles imitate that.
- **Delfts Blauw:** cobalt on off-white glaze, drawn with brush hatching. The
  background includes a tile border, with windmill and canal motifs.
  Precipitation is painted in the same blue. Nearly monochrome, so the
  weather has to read through shapes rather than colour.

## Roadmap

1. ✅ Core logic: parsing, scene, outfit and layer plan, with tests
2. ✅ Android shell: refresh worker, settings screen, compositor
3. ✅ Placeholder asset pack (`./gradlew :tools:placeholders:run`)
4. ✅ Glance widget + home/lock screen wallpaper
5. ✅ Live wallpaper: particles, hair/blink/scarf loops, wind direction
6. ✅ CI: tests, lint and an installable APK on every push
7. Real art for the three styles (pixel art first). Drop PNGs into
   `app/src/main/assets/styles/<style>/` with the names above.
8. Polish: rain splashes, a forecast strip ("rain at 14:45"), more styles,
   a private release key for a store release
