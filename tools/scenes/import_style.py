"""Turns finished pictures for a new style into the app's assets.

Every style draws the same scenes as pixel art (ScenePicture in core/), one
square picture each. Put them in a folder, named after the scene they show
(clear-hot.png, rain-cold.jpg, ...; any format Pillow reads), then:

    pip install pillow
    python3 tools/scenes/import_style.py anime ~/Downloads/anime

This crops each picture to a square around its middle, scales it to
1200 x 1200 and writes it to app/src/main/assets/scenes/<style>/<scene>.webp.
It writes nothing unless every scene is there, since the app only offers a
style that is complete. Run with --check to only list what's missing.

The style must also be an entry in Style (core/.../Style.kt).
"""

import argparse
import re
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/io/github/intramuros/weatherbuddy/core"
ASSETS = ROOT / "app/src/main/assets/scenes"
SIZE = 1200
EXTENSIONS = (".png", ".jpg", ".jpeg", ".webp")


def scene_slugs():
    """The ScenePicture slugs, in declaration order."""
    source = (CORE / "Summary.kt").read_text()
    return re.findall(r'^\s+[A-Z_]+\("([a-z-]+)", SceneKind\.', source, re.MULTILINE)


def style_slugs():
    source = (CORE / "Style.kt").read_text()
    return re.findall(r'^\s+[A-Z_]+\("([a-z-]+)", "', source, re.MULTILINE)


def find(folder, slug):
    for ext in EXTENSIONS:
        for path in (folder / f"{slug}{ext}", folder / f"{slug}{ext.upper()}"):
            if path.is_file():
                return path
    return None


def square(image):
    w, h = image.size
    side = min(w, h)
    left, top = (w - side) // 2, (h - side) // 2
    return image.crop((left, top, left + side, top + side))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("style", help="the style's slug, e.g. anime")
    parser.add_argument("folder", type=Path, help="folder with one picture per scene, named <scene>.png")
    parser.add_argument("--check", action="store_true", help="only list missing pictures")
    args = parser.parse_args()

    if args.style not in style_slugs():
        sys.exit(f"'{args.style}' is not a Style in core (known: {', '.join(style_slugs())})")
    if args.style == "pixel-art":
        sys.exit("pixel art comes from tools/scenes/prepare.py")

    slugs = scene_slugs()
    found = {slug: find(args.folder, slug) for slug in slugs}
    missing = [slug for slug, path in found.items() if path is None]
    extra = sorted(
        p.name for p in args.folder.iterdir()
        if p.suffix.lower() in EXTENSIONS and p.stem not in slugs
    ) if args.folder.is_dir() else []

    print(f"{len(slugs) - len(missing)} of {len(slugs)} scenes found in {args.folder}")
    if extra:
        print("Not a scene name, ignored: " + ", ".join(extra))
    if missing:
        print("Missing: " + ", ".join(missing))
    if args.check:
        return
    if missing:
        sys.exit("Nothing written: the app needs every scene.")

    out = ASSETS / args.style
    out.mkdir(parents=True, exist_ok=True)
    for slug, path in found.items():
        with Image.open(path) as image:
            picture = square(image.convert("RGB")).resize((SIZE, SIZE), Image.Resampling.LANCZOS)
        picture.save(out / f"{slug}.webp", quality=90, method=6)
    print(f"Wrote {len(found)} pictures to {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
