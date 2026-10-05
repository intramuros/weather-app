# Pop art scenes: Codex prompts

The app's Pop art style is the same 30 scenes as pixel art, redrawn as
a 1960s pop art comic panel, with black outlines and Ben-Day dots. They were made with ChatGPT's image generation through
the Codex CLI (`codex exec`), one scene per call, with the matching
pixel-art picture as the reference, so the sky and outfit stay right for
the weather the app picks it for; the setting and composition are the
style's own, not the pixel-art canal. The originals are in `source/`.

## The prompt

Every scene gets the same three parts, then the scene's own line:

1. `tools/scenes/codex-common.txt`: what the app needs from every picture.
   The girl and the weather come first: the picture is often seen as a
   smallish widget, so she is large, centred and the strongest thing in it,
   the weather is bold around her, and the background is sparse and pale.
   The top third stays quiet sky for the widget's text and the lock screen's
   clock. No text, frame or watermark.
2. [`style.txt`](style.txt): the medium, her face, how it paints each
   kind of weather, and its own setting and composition.
3. The scene's line (weather, warmth and outfit) from the table in
   [`../anime/PROMPTS.md`](../anime/PROMPTS.md).

## How to make them

Make one scene first, without references, and regenerate until you like
her; it fixes how she looks. Then give it to every other scene as a
reference. From the repository root:

```sh
tools/scenes/codex_scene.sh pop-art windy-cool ~/pop-art
for scene in $(sed -nE 's/^\| `([a-z-]+)\.png` .*/\1/p' tools/scenes/anime/PROMPTS.md); do
  tools/scenes/codex_scene.sh pop-art $scene ~/pop-art ~/pop-art/windy-cool.png
done
```

It skips scenes that are already there, so delete a picture you don't like
and run it again. Look at every picture, at full size and shrunk to widget
size, and regenerate any that move her off-centre, lose the weather, or put
anything busy in the top third. Then import them as for anime:

```sh
python3 tools/scenes/import_style.py pop-art ~/pop-art
./gradlew :core:test
```
