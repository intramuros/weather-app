"""Paints the impasto style from the anime pictures, in code: no image generation.

Each anime scene is repainted as thick oil paint, the way a painter would:
broad strokes first, then smaller ones only where the painting still differs
from the scene. Strokes follow the scene's contours, their colours are pulled
towards a small palette per scene, and each leaves bristle marks and a ridge
of paint that catches the light from the top left. Away from the girl it stays
loose and abstract; on her the strokes get small enough to read her outfit,
which is what the picture is for.

    pip install pillow numpy scipy
    python3 tools/scenes/impasto.py              # all 30 scenes
    python3 tools/scenes/impasto.py rain-mild    # just these

It writes app/src/main/assets/scenes/impasto/<scene>.webp (1200 x 1200).
Every scene is seeded from its name, so a re-run paints the same strokes.
"""

import argparse
import sys
import zlib
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
from scipy.cluster.vq import kmeans2

from import_style import ASSETS, ROOT, SIZE, scene_slugs

SOURCE = ASSETS / "anime"
OUT = ASSETS / "impasto"

# Brush radii in pixels, broadest first.
BRUSHES = (44, 22, 10, 5, 3)
# Below this weight on the figure mask a fine brush only paints strong edges.
FINE_BRUSH_FIGURE = {10: 0.25, 5: 0.4, 3: 0.8}
PALETTE_SIZE = 24


def figure_mask(h, w):
    """Where the girl stands: every scene puts her in the middle, her face at 44 % height."""
    yy, xx = np.mgrid[0:h, 0:w]
    body = np.exp(-(((xx - w * 0.5) / (w * 0.12)) ** 2 + ((yy - h * 0.62) / (h * 0.30)) ** 2))
    face = np.exp(-(((xx - w * 0.5) / (w * 0.06)) ** 2 + ((yy - h * 0.44) / (h * 0.06)) ** 2))
    return body + face


def bold_palette(src, seed):
    """A few strong colours from the scene, more saturated than they were."""
    palette, _ = kmeans2(src[::6, ::6].reshape(-1, 3), PALETTE_SIZE, seed=seed, minit="++")
    grey = palette.mean(1, keepdims=True)
    return np.clip(grey + (palette - grey) * 1.6, 0, 255)


def stroke_path(x0, y0, radius, colour, ref, gx, gy, rng):
    """A curved stroke along the scene's contours, ending where the colour changes."""
    h, w, _ = ref.shape
    pts = [(x0, y0)]
    cx, cy = float(x0), float(y0)
    dx0 = dy0 = 0.0
    for _ in range(int(rng.integers(1, 5))):
        gxx, gyy = gx[int(cy), int(cx)], gy[int(cy), int(cx)]
        if np.hypot(gxx, gyy) < 1e-2:
            dx, dy = (dx0, dy0) if dx0 or dy0 else (1.0, 0.15)
        else:
            dx, dy = -gyy, gxx  # along the contour, across the gradient
        n = np.hypot(dx, dy)
        dx, dy = dx / n, dy / n
        if dx * dx0 + dy * dy0 < 0:
            dx, dy = -dx, -dy
        if dx0 or dy0:  # bend gently
            dx, dy = 0.5 * (dx + dx0), 0.5 * (dy + dy0)
            n = np.hypot(dx, dy) or 1
            dx, dy = dx / n, dy / n
        cx, cy = cx + dx * radius * 1.3, cy + dy * radius * 1.3
        if not (0 <= cx < w and 0 <= cy < h) or np.abs(ref[int(cy), int(cx)] - colour).sum() > 100:
            break
        pts.append((cx, cy))
        dx0, dy0 = dx, dy
    if len(pts) == 1:  # a dab
        a = rng.uniform(0, np.pi)
        pts.append((x0 + np.cos(a) * radius, y0 + np.sin(a) * radius))
    return pts


def paint(src, seed):
    rng = np.random.default_rng(seed)
    h, w, _ = src.shape
    figure = figure_mask(h, w)
    palette = bold_palette(src, seed)

    # The underpainting only shows where no stroke lands, at the very edges.
    canvas = Image.fromarray(ndimage.gaussian_filter(src, (30, 30, 0)).astype(np.uint8))
    relief = Image.new("L", (w, h), 0)
    draw, rdraw = ImageDraw.Draw(canvas), ImageDraw.Draw(relief)

    for layer, radius in enumerate(BRUSHES):
        ref = ndimage.gaussian_filter(src, (radius * 0.5, radius * 0.5, 0))
        lum = ndimage.gaussian_filter(ref.mean(2), radius * 0.5)
        gy, gx = ndimage.sobel(lum, 0), ndimage.sobel(lum, 1)
        painted = np.asarray(canvas, dtype=np.float32)
        error = ndimage.uniform_filter(np.sqrt(((painted - ref) ** 2).sum(2)), radius)

        cells = [(y, x) for y in range(0, h, radius) for x in range(0, w, radius)]
        rng.shuffle(cells)
        for y, x in cells:
            y0 = min(h - 1, y + int(rng.integers(0, radius)))
            x0 = min(w - 1, x + int(rng.integers(0, radius)))
            if layer > 0:  # the first layer covers everything
                f = min(figure[y0, x0], 1.0)
                if radius in FINE_BRUSH_FIGURE and f < FINE_BRUSH_FIGURE[radius] and error[y0, x0] < 80:
                    continue
                if error[y0, x0] < (30 if radius > 10 else 45) * (1 - 0.6 * f):
                    continue

            colour = ref[y0, x0]
            colour = 0.4 * palette[((palette - colour) ** 2).sum(1).argmin()] + 0.6 * colour
            pts = stroke_path(x0, y0, radius, colour, ref, gx, gy, rng)
            base = np.clip(colour + rng.normal(0, 6, 3), 0, 255)
            width = int(radius * rng.uniform(1.3, 1.9))
            draw.line(pts, fill=tuple(int(v) for v in base), width=width, joint="curve")
            rdraw.line(pts, fill=int(rng.integers(90, 150)), width=width, joint="curve")

            # Bristle marks: thin streaks of lighter and darker paint, and ridges, along the stroke.
            (ax, ay), (bx, by) = pts[0], pts[-1]
            n = np.hypot(bx - ax, by - ay) or 1
            nx, ny = -(by - ay) / n, (bx - ax) / n
            for _ in range(3 if radius > 5 else 1):
                off = rng.uniform(-0.45, 0.45) * width
                streak = [(px + nx * off, py + ny * off) for px, py in pts]
                tint = np.clip(base * rng.uniform(0.85, 1.12), 0, 255)
                draw.line(streak, fill=tuple(int(v) for v in tint), width=max(1, width // 7))
                rdraw.line(streak, fill=int(rng.integers(150, 230)), width=max(1, width // 6))

    # Light the ridges of paint from the top left, over a faint canvas weave.
    yy, xx = np.mgrid[0:h, 0:w]
    height = ndimage.gaussian_filter(np.asarray(relief, dtype=np.float32), 1.5)
    height += np.sin(xx * 1.9) * np.sin(yy * 1.9) * 6
    light = np.clip(-(ndimage.sobel(height, 1) + ndimage.sobel(height, 0)) / 1100, -0.14, 0.14)
    out = np.asarray(canvas, dtype=np.float32) * (1 + light[..., None])
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))


def render(slug):
    with Image.open(SOURCE / f"{slug}.webp") as image:
        src = np.asarray(image.convert("RGB").resize((SIZE, SIZE), Image.Resampling.LANCZOS), dtype=np.float32)
    paint(src, zlib.crc32(slug.encode())).save(OUT / f"{slug}.webp", quality=90, method=6)
    return slug


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenes", nargs="*", help="scene names (default: all)")
    args = parser.parse_args()

    slugs = scene_slugs()
    unknown = [s for s in args.scenes if s not in slugs]
    if unknown:
        sys.exit(f"Not a scene: {', '.join(unknown)}")
    missing = [s for s in slugs if not (SOURCE / f"{s}.webp").is_file()]
    if missing:
        sys.exit(f"The anime pictures are needed first; missing: {', '.join(missing)}")

    OUT.mkdir(parents=True, exist_ok=True)
    with ProcessPoolExecutor() as pool:
        for slug in pool.map(render, args.scenes or slugs):
            print(f"painted {slug}")
    print(f"Wrote to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
