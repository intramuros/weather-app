# Anime scenes: ChatGPT prompts

The app's anime style is the same 30 scenes as pixel art, redrawn. Each
prompt below goes with the matching pixel-art picture as the reference, so
the sky, outfit and layout stay right for the weather the app picks it for.

## How to make them

1. In ChatGPT, start a new chat and send the **character prompt** below with
   three pixel-art pictures attached (`clear-hot`, `partly-cloudy-mild` and
   `windy-cool` show her best). Regenerate until you like her, then download
   the sheet as `character.png`; it's your reference for every scene.
2. For each scene, attach its pixel-art picture from
   `app/src/main/assets/scenes/pixel-art/<scene>.webp` and send the **scene
   prompt**: the shared part plus the scene's line. Stay in the same chat so
   the style stays consistent; if the chat gets long or the look starts to
   drift, start a new one and attach `character.png` with the first scene.
3. Download each result and save it as `<scene>.png` (for example
   `rain-cold.png`) in one folder. Regenerate any that change the composition,
   move her off-centre, or put anything busy in the top third.
4. When all 30 are there, from the repository root:

   ```sh
   pip install pillow
   python3 tools/scenes/import_style.py anime ~/path/to/folder --check   # what's missing
   python3 tools/scenes/import_style.py anime ~/path/to/folder           # writes the assets
   ./gradlew :core:test :tools:preview:run                               # checks, and GIF previews
   ```

   The app offers "Anime" in its style picker as soon as all 30 are in.

What the app needs from every picture:

- **Square**, the girl standing in the **horizontal middle**, in the lower
  middle. A phone wallpaper shows only the middle strip (about 45 % of the
  width), so anything important must be near the centre.
- **The top third is quiet sky.** The widget writes the temperature,
  condition, place, humidity and wind there, and the lock screen puts its
  clock there.
- **No text, icons, frame or watermark.** The app adds its own.
- Rain or snow can be painted in; the app adds moving rain, snow and wind on
  top, in the live wallpaper.

## Character prompt

> I'm redrawing a set of 30 pixel-art scenes as anime illustrations, one at a
> time, and the girl must look the same in all of them. Using the attached
> pixel-art pictures as the reference, draw a character reference sheet of
> her in a modern anime film style (clean line art, soft cel shading, warm
> light): a cute young woman with long, slightly wavy chestnut-brown hair with
> bangs, big brown eyes, a gentle expression, and a small brown leather
> crossbody bag. Show her full body from the front and from three-quarters,
> plus a head close-up, on a plain light background. No text.

## Scene prompt

Shared part (send it with every scene, then the scene's own line):

> Redraw the attached pixel-art picture as a high-quality anime illustration
> in the same style and with the same girl as the character sheet: clean line
> art, soft cel shading and a detailed painted background, like a modern anime
> film. Keep the square 1:1 format and the composition: the Amsterdam canal
> with its arched bridge, the canal houses and the black street lamp on the
> left, with the girl standing facing us in the horizontal centre of the
> picture, in the lower middle, at the same size. Keep her outfit, the weather
> and the time of day exactly as in the reference. Keep the top third of the
> picture as calm, open sky with nothing important in it. No text, letters,
> numbers, icons, logos, frame, border, signature or watermark.

| Save as | Scene line |
|---|---|
| `clear-hot.png` | This scene: sunny (day), hot (25 °C+). She wears: sundress, sun hat, sandals. |
| `clear-warm.png` | This scene: sunny (day), warm (20–25 °C). She wears: dress, cardigan, sunglasses. |
| `clear-freezing.png` | This scene: sunny (day), snow on the ground, freezing (< 3 °C). She wears: puffer, bobble hat, scarf. |
| `clear-night-hot.png` | This scene: clear night, hot. She wears: T-shirt, shorts. |
| `clear-night-warm.png` | This scene: clear night, warm. She wears: T-shirt, shorts. |
| `clear-night-cold.png` | This scene: clear night, cold (3–10 °C). She wears: coat, scarf. |
| `partly-cloudy-mild.png` | This scene: partly cloudy (day), mild (15–20 °C). She wears: sweater, jeans, sneakers. |
| `partly-cloudy-cold.png` | This scene: partly cloudy (day), cold. She wears: coat, scarf, closed umbrella. |
| `cloudy-cool.png` | This scene: cloudy, cool (10–15 °C). She wears: sweater, jacket. |
| `cloudy-cold.png` | This scene: cloudy, cold. She wears: coat, scarf, closed umbrella. |
| `cloudy-freezing.png` | This scene: cloudy, freezing. She wears: coat, scarf. |
| `fog-cool.png` | This scene: fog, cool. She wears: sweater, jacket, scarf. |
| `fog-freezing.png` | This scene: fog, freezing. She wears: coat, scarf. |
| `windy-mild.png` | This scene: windy (day), mild. She wears: hoodie, jeans. |
| `windy-cool.png` | This scene: windy, autumn leaves (day), cool. She wears: trench coat, scarf. |
| `windy-night-cool.png` | This scene: windy night, cool. She wears: hoodie, jeans. |
| `windy-freezing.png` | This scene: windy (day), freezing. She wears: coat, blowing scarf. |
| `windy-night-freezing.png` | This scene: windy night, freezing. She wears: coat, blowing scarf. |
| `rain-hot.png` | This scene: rain, hot. She wears: T-shirt, shorts, umbrella. |
| `rain-warm.png` | This scene: rain (day), warm. She wears: T-shirt, shorts, umbrella. |
| `rain-mild.png` | This scene: rain, mild. She wears: light jacket, umbrella. |
| `rain-night-cool.png` | This scene: rain at night, cool. She wears: coat, scarf, umbrella. |
| `rain-cold.png` | This scene: rain, cold. She wears: coat, scarf, umbrella. |
| `rain-freezing.png` | This scene: freezing rain, freezing. She wears: coat, scarf. |
| `storm-warm.png` | This scene: storm, warm. She wears: raincoat, rain boots. |
| `storm-cool.png` | This scene: storm, cool. She wears: raincoat, rain boots, umbrella. |
| `storm-cold.png` | This scene: storm, cold. She wears: coat, scarf, umbrella. |
| `snow-cold.png` | This scene: snow, cold. She wears: coat, scarf, umbrella. |
| `snow-freezing.png` | This scene: snow (day), freezing (< 3 °C). She wears: puffer, bobble hat, mittens. |
| `snow-night-freezing.png` | This scene: snow at night, freezing. She wears: coat, bobble hat, scarf. |
