"""Composes the suprematism style in code: no image generation, no source pictures.

Each scene is built, as Malevich would, from flat planes, circles and bars
floating on a plain ground, chosen by what the scene means (ScenePicture in
core/: its sky, how warm it feels, and day or night) rather than by what the
other styles draw:

- everything floats along one main diagonal on a plain ground: cream by
  day, black at night, grey for skies that suit either; a wide band along
  the diagonal stands for the sky
- the sun is a yellow disc, the moon a white crescent, clouds grey slabs,
  fog white on white, rain and wind bundles of thin parallel lines, a storm
  a black wedge with a red bolt, snow scattered white squares
- the windier the sky, the steeper everything leans
- accent planes and small squares take colours from how warm it feels
- Amsterdam is a few tilted blocks, a blue bar of canal and a black arch
- the girl is a faceless figure of a few shapes, as in Malevich's late
  peasants: her outfit's cut and colours follow how warm it feels (OUTFITS)

The middle of the picture holds what matters, since a phone shows only that,
and the widget writes its text over the top corners. Edges wobble a little
and the paint is a little uneven, like a painted canvas.

    pip install pillow numpy scipy
    python3 tools/scenes/suprematism.py              # all 30 scenes
    python3 tools/scenes/suprematism.py rain-mild    # just these

It writes app/src/main/assets/scenes/suprematism/<scene>.webp (1200 x 1200).
Every scene is seeded from its name, so a re-run draws the same picture. A
new ScenePicture needs an entry in OUTFITS.
"""

import argparse
import math
import re
import sys
import zlib

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

from import_style import ASSETS, CORE, ROOT, SIZE

OUT = ASSETS / "suprematism"
SCALE = 2  # drawn at twice the size, then scaled down for smooth edges

# Malevich's colours.
CREAM = "#ebe4d3"
NIGHT = "#16171d"
BLACK = "#1b1b1d"
WHITE = "#f7f5ef"
RED = "#c3301f"
YELLOW = "#e7b52a"
BLUE = "#26479a"
DEEP_BLUE = "#1d2a58"
SKY_BLUE = "#8db5dc"
GREEN = "#3c7a4d"
OCHRE = "#c58a3a"
PINK = "#e3a1a0"
BROWN = "#5b3624"
BRICK = "#9a3a2a"
GREY = "#9b9ea5"
LIGHT_GREY = "#c4c6cb"
DARK_GREY = "#5d6169"
SKIN = "#e6bf9e"
HAIR = "#6a3a22"
DENIM = "#3d5b8c"

# Her clothes per picture: shapes from bottom to top, see draw_figure. Warm
# colours for warm weather, dark and blue ones for cold; the cut follows the
# outfits of the other styles (docs/DESIGN.md).
OUTFITS = {
    "clear-hot": [("dress", YELLOW, 0.74), ("sunhat", OCHRE)],
    "clear-warm": [("dress", PINK, 0.75), ("cardigan", BLUE)],
    "clear-freezing": [("trousers", DENIM), ("boots", BROWN), ("puffer", BLUE), ("scarf", RED), ("bobble", WHITE)],
    "clear-night-hot": [("shorts", DENIM), ("top", WHITE)],
    "clear-night-warm": [("shorts", DENIM), ("top", PINK)],
    "clear-night-cold": [("trousers", BLACK), ("boots", BROWN), ("coat", DEEP_BLUE, 0.8), ("scarf", RED)],
    "partly-cloudy-mild": [("trousers", DENIM), ("top", GREEN)],
    "partly-cloudy-cold": [("trousers", BLACK), ("boots", BROWN), ("coat", BLACK, 0.8), ("scarf", RED), ("closed-umbrella", RED)],
    "cloudy-cool": [("trousers", DENIM), ("coat", GREEN, 0.68)],
    "cloudy-cold": [("trousers", BLACK), ("boots", BROWN), ("coat", BLACK, 0.8), ("scarf", RED), ("closed-umbrella", RED)],
    "cloudy-freezing": [("trousers", BLACK), ("boots", BROWN), ("coat", DEEP_BLUE, 0.82), ("scarf", RED)],
    "fog-cool": [("trousers", DENIM), ("coat", OCHRE, 0.68), ("scarf", RED)],
    "fog-freezing": [("trousers", BLACK), ("boots", BROWN), ("coat", BLACK, 0.82), ("scarf", RED)],
    "windy-mild": [("trousers", DENIM), ("top", GREEN), ("hood-down", GREEN)],
    "windy-cool": [("trousers", BLACK), ("coat", OCHRE, 0.78), ("scarf", RED)],
    "windy-night-cool": [("trousers", DENIM), ("top", GREEN), ("hood-down", GREEN)],
    "windy-freezing": [("trousers", BLACK), ("boots", BROWN), ("coat", DEEP_BLUE, 0.82), ("scarf", RED)],
    "windy-night-freezing": [("trousers", BLACK), ("boots", BROWN), ("coat", BLACK, 0.82), ("scarf", RED)],
    "rain-hot": [("shorts", DENIM), ("top", WHITE), ("umbrella", RED)],
    "rain-warm": [("shorts", DENIM), ("top", YELLOW), ("umbrella", RED)],
    "rain-mild": [("trousers", BLACK), ("boots", BROWN), ("coat", OCHRE, 0.74), ("umbrella", RED)],
    "rain-night-cool": [("trousers", BLACK), ("boots", BROWN), ("coat", BLACK, 0.8), ("scarf", RED), ("umbrella", RED)],
    "rain-cold": [("trousers", BLACK), ("boots", BROWN), ("coat", DEEP_BLUE, 0.8), ("scarf", RED), ("umbrella", RED)],
    "rain-freezing": [("trousers", BLACK), ("boots", BROWN), ("coat", BLACK, 0.82), ("scarf", RED)],
    "storm-warm": [("boots", YELLOW), ("coat", YELLOW, 0.76), ("hood", YELLOW)],
    "storm-cool": [("boots", YELLOW), ("coat", YELLOW, 0.76), ("hood", YELLOW), ("umbrella", BLACK)],
    "storm-cold": [("trousers", BLACK), ("boots", BROWN), ("coat", DEEP_BLUE, 0.8), ("scarf", RED), ("umbrella", RED)],
    "snow-cold": [("trousers", BLACK), ("boots", BROWN), ("coat", BLACK, 0.8), ("scarf", RED), ("umbrella", RED)],
    "snow-freezing": [("trousers", DENIM), ("boots", BROWN), ("puffer", RED), ("scarf", WHITE), ("bobble", WHITE)],
    "snow-night-freezing": [("trousers", BLACK), ("boots", BROWN), ("coat", DEEP_BLUE, 0.82), ("scarf", RED), ("bobble", WHITE)],
}


def scenes():
    """(slug, kind, warmth, time) per ScenePicture, time None for skies that suit day and night."""
    source = (CORE / "Summary.kt").read_text()
    return re.findall(
        r'^\s+[A-Z_]+\("([a-z-]+)", SceneKind\.([A-Z_]+), Warmth\.([A-Z]+)(?:, TimeOfDay\.([A-Z]+))?\)',
        source,
        re.MULTILINE,
    )


def rgb(colour):
    return np.array([int(colour[i:i + 2], 16) for i in (1, 3, 5)], dtype=np.float32)


class Canvas:
    """Draws in units of the picture's side (0..1, y down), each colour faded towards [haze] by [fade]."""

    def __init__(self, ground):
        self.px = SIZE * SCALE
        self.image = Image.new("RGB", (self.px, self.px), ground)
        self.draw = ImageDraw.Draw(self.image)
        self.haze = rgb(ground)
        self.fade = 0.0

    def colour(self, colour):
        c = rgb(colour) * (1 - self.fade) + self.haze * self.fade
        return tuple(int(round(v)) for v in c)

    def polygon(self, points, colour):
        self.draw.polygon([(x * self.px, y * self.px) for x, y in points], fill=self.colour(colour))

    def rect(self, cx, cy, w, h, angle, colour):
        """A rectangle around its centre, turned by [angle] radians (positive is clockwise)."""
        c, s = math.cos(angle), math.sin(angle)
        corners = [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]
        self.polygon([(cx + x * c - y * s, cy + x * s + y * c) for x, y in corners], colour)

    def bar(self, x1, y1, x2, y2, width, colour):
        """A straight bar between two points."""
        length = math.hypot(x2 - x1, y2 - y1)
        self.rect((x1 + x2) / 2, (y1 + y2) / 2, length, width, math.atan2(y2 - y1, x2 - x1), colour)

    def circle(self, cx, cy, r, colour):
        p = self.px
        self.draw.ellipse(((cx - r) * p, (cy - r) * p, (cx + r) * p, (cy + r) * p), fill=self.colour(colour))

    def half_circle(self, cx, cy, r, colour):
        """The top half of a circle, flat side down at [cy]."""
        p = self.px
        self.draw.chord(((cx - r) * p, (cy - r) * p, (cx + r) * p, (cy + r) * p), 180, 360, fill=self.colour(colour))

    def arch(self, cx, cy, r, width, colour):
        """A bridge's arch: the top half of a ring."""
        p = self.px
        self.draw.arc(((cx - r) * p, (cy - r) * p, (cx + r) * p, (cy + r) * p), 180, 360,
                      fill=self.colour(colour), width=int(width * p))

    def trapezoid(self, cx, top, bottom, top_w, bottom_w, colour):
        self.polygon([(cx - top_w / 2, top), (cx + top_w / 2, top), (cx + bottom_w / 2, bottom), (cx - bottom_w / 2, bottom)], colour)


def axis_angle(kind):
    """The composition's main diagonal, in radians (negative rises to the right): steeper the windier."""
    return {"WINDY": -0.62, "STORM": 0.42, "RAIN": -0.5, "SNOW": -0.3, "FOG": -0.12}.get(kind, -0.4)


def ground_colour(kind, time):
    if time == "NIGHT":
        return NIGHT
    if time == "DAY":
        return "#e3e7ea" if kind == "SNOW" else CREAM
    return {
        "CLOUDY": "#dcd8cf",
        "FOG": "#ebe9e3",
        "RAIN": "#d3d4d4",
        "STORM": "#b9b8b4",
        "SNOW": "#dde1e5",
    }.get(kind, "#dcd8cf")


# Accent colours for how warm it feels: hot is red and yellow, freezing blue, white and black.
WARMTH_COLOURS = {
    "HOT": [RED, YELLOW, "#e0701f"],
    "WARM": [PINK, YELLOW, RED],
    "MILD": [GREEN, YELLOW, BLUE],
    "COOL": [OCHRE, GREEN, BLUE],
    "COLD": [BLACK, BLUE, RED],
    "FREEZING": [BLUE, WHITE, BLACK],
}


class Axis:
    """Positions along the main diagonal through the middle: [t] along it, [d] across it."""

    def __init__(self, angle):
        self.angle = angle
        self.u = (math.cos(angle), math.sin(angle))
        self.n = (-math.sin(angle), math.cos(angle))

    def at(self, t, d=0.0):
        return 0.5 + t * self.u[0] + d * self.n[0], 0.5 + t * self.u[1] + d * self.n[1]


def sky_colour(kind, night):
    return {
        "CLEAR": DEEP_BLUE if night else SKY_BLUE,
        "PARTLY_CLOUDY": "#a6c3e0",
        "CLOUDY": GREY,
        "FOG": "#dedcd6",
        "WINDY": DEEP_BLUE if night else "#9cbcdf",
        "RAIN": DEEP_BLUE if night else "#6f7a8a",
        "STORM": "#3a3d46",
        "SNOW": DEEP_BLUE if night else "#aab6c3",
    }[kind]


def draw_planes(cv, kind, time, warmth, ax, rng):
    """The big planes: the sky's band along the diagonal, the canal, the city's blocks."""
    night = time == "NIGHT"
    a = ax.angle
    accents = WARMTH_COLOURS[warmth]
    # A wide band of sky along the diagonal, and a long thin one of canal water below it.
    cv.rect(*ax.at(0.02, -0.07), 1.25, 0.21, a, sky_colour(kind, night))
    cv.rect(*ax.at(0.1, 0.12), 0.78, 0.045, a, "#22305e" if night else BLUE)
    # Two planes in the warmth's colours, crossing the band.
    cv.rect(*ax.at(-0.28, 0.0), 0.24, 0.075, a + 0.35, accents[0])
    cv.rect(*ax.at(0.3, 0.06), 0.2, 0.05, a - 0.25, accents[1])
    # Gabled houses: upright blocks, at right angles to the diagonal, lit when it's dark.
    dark = night or kind == "STORM" or (time is None and kind == "RAIN")
    house_colours = ["#3a2a2a", "#26262c", "#4a3a26", "#2e2a2a"] if night else [BRICK, BLACK, OCHRE, BROWN]
    for start, count in ((-0.42, 5), (0.22, 4)):
        for i in range(count):
            length = rng.uniform(0.08, 0.2)
            w = rng.uniform(0.026, 0.042)
            cx, cy = ax.at(start + i * 0.048, 0.05 - length / 2)
            cv.rect(cx, cy, w, length, a, house_colours[(i + count) % 4])
            if dark and rng.random() < 0.7:
                wx, wy = ax.at(start + i * 0.048, 0.05 - length * rng.uniform(0.45, 0.8))
                cv.rect(wx, wy, 0.012, 0.016, a, YELLOW)
    # A bridge's arch over the canal.
    ox, oy = ax.at(0.34, 0.11)
    cv.arch(ox, oy, 0.06, 0.014, "#3b4459" if night else BLACK)


def draw_weather(cv, kind, time, ax, rng):
    night = time == "NIGHT"
    a = ax.angle
    sky = sky_colour(kind, night)
    if kind == "FOG":
        # White on white: soft bands of barely different whites over everything.
        for i, y in enumerate(np.linspace(0.14, 0.88, 8)):
            cv.rect(0.5 + rng.uniform(-0.12, 0.12), y, rng.uniform(0.5, 0.95), rng.uniform(0.03, 0.06), a * 0.5,
                    ["#f5f4f0", "#d6d4ce", "#e4e2dc"][i % 3])
        return
    if kind in ("CLEAR", "PARTLY_CLOUDY", "WINDY") and not night:
        cv.circle(0.52, 0.27, 0.115, YELLOW)
    if night and kind in ("CLEAR", "WINDY"):
        cv.circle(0.52, 0.26, 0.08, WHITE)
        cv.circle(0.56, 0.235, 0.07, sky)  # the crescent's dark side
        if kind == "CLEAR":
            for _ in range(10):
                x, y = rng.uniform(0.1, 0.9), rng.uniform(0.12, 0.6)
                if math.hypot(x - 0.52, y - 0.26) > 0.12:
                    cv.rect(x, y, 0.01, 0.01, rng.uniform(0, 1), WHITE)
    if kind == "PARTLY_CLOUDY":
        cv.rect(0.43, 0.3, 0.34, 0.06, a + 0.1, WHITE)
        cv.rect(0.66, 0.34, 0.22, 0.04, a - 0.05, LIGHT_GREY)
    if kind in ("CLOUDY", "RAIN", "STORM", "SNOW"):
        greys = ["#2b3346", "#4a536a", "#262d3e", "#3b4459"] if night else [DARK_GREY, LIGHT_GREY, "#74787f", WHITE]
        for (t, d, length, w, turn), colour in zip(
            [(-0.12, -0.2, 0.5, 0.075, 0.0), (0.12, -0.24, 0.42, 0.06, 0.12), (-0.02, -0.33, 0.3, 0.04, -0.1), (0.22, -0.12, 0.2, 0.03, 0.05)],
            greys,
        ):
            cv.rect(*ax.at(t, d), length, w, a + turn, colour)
    if kind == "STORM":
        # A black wedge cutting in, and a red bolt.
        cv.polygon([(0.08, 0.14), (0.66, 0.2), (0.16, 0.42)], BLACK)
        bolt = [(0.66, 0.12), (0.59, 0.3), (0.68, 0.29), (0.58, 0.48)]
        for (x1, y1), (x2, y2) in zip(bolt, bolt[1:]):
            cv.bar(x1, y1, x2, y2, 0.02, RED)


def draw_lines(cv, kind, time, warmth, ax, rng, front):
    """Thin lines: two long ones across everything, and bundles for rain and wind. [front] ones go over her."""
    night = time == "NIGHT"
    ink = WHITE if night else BLACK
    if not front:
        cv.bar(*ax.at(-0.62, 0.2), *ax.at(0.62, 0.2), 0.004, ink)
        cv.bar(*ax.at(-0.25, -0.42), *ax.at(0.05, 0.45), 0.003, ink)
    if kind in ("RAIN", "STORM"):
        colour = "#a8b8d4" if night or kind == "STORM" else "#26324a"
        slant = 0.3 if kind == "STORM" else 0.22
        # Rain falls in bundles of parallel strokes, as Malevich drew movement.
        for cx, cy in ((0.24, 0.6), (0.74, 0.52), (0.45, 0.78)) if not front else ((0.6, 0.66),):
            for _ in range(12):
                x, y = cx + rng.uniform(-0.12, 0.12), cy + rng.uniform(-0.12, 0.12)
                length = rng.uniform(0.06, 0.16)
                cv.bar(x, y, x - slant * length, y + length, 0.0035, colour)
    if kind == "WINDY" and not front:
        for i in range(6):
            d = -0.05 + i * 0.045
            t0, t1 = rng.uniform(-0.7, -0.4), rng.uniform(0.1, 0.6)
            cv.bar(*ax.at(t0, d), *ax.at(t1, d), 0.004, ink)


def draw_scatter(cv, kind, time, warmth, ax, rng):
    """Small squares drifting along the diagonal; snowflakes, leaves; the red square."""
    accents = WARMTH_COLOURS[warmth]
    for _ in range(7):
        x, y = ax.at(rng.uniform(-0.45, 0.45), rng.uniform(0.18, 0.32))
        s = rng.uniform(0.014, 0.03)
        cv.rect(x, y, s, s * rng.uniform(0.4, 1), ax.angle + rng.uniform(-0.3, 0.3), accents[int(rng.integers(0, 3))])
    if kind == "SNOW":
        for _ in range(60):
            x, y = rng.uniform(0.03, 0.97), rng.uniform(0.1, 0.92)
            s = rng.uniform(0.008, 0.022)
            cv.rect(x, y, s, s, rng.uniform(0, 1.5), WHITE if rng.random() < 0.75 else "#9fb3cc")
    if kind == "WINDY" and warmth in ("COOL", "MILD"):
        for _ in range(14):
            x, y = ax.at(rng.uniform(0.0, 0.5), rng.uniform(-0.15, 0.25))
            s = rng.uniform(0.012, 0.024)
            cv.rect(x, y, s, s * 0.6, rng.uniform(0, 3), [RED, OCHRE, YELLOW][int(rng.integers(0, 3))])
    cv.rect(0.83, 0.62, 0.045, 0.045, 0.3, RED)


def draw_figure(cv, outfit, kind, ax):
    """The girl, faceless and upright in the middle: a disc on a stack of bars, as in Malevich's late figures."""
    x = 0.5
    windy = kind in ("WINDY", "STORM")
    garments = {g[0]: g[1:] for g in outfit}
    head_y, r = 0.46, 0.03
    shoulders, feet = 0.5, 0.87

    # Legs: one bar, bare unless covered, and a black or booted foot.
    leg = garments.get("trousers", (SKIN,))[0]
    cv.rect(x, (0.62 + feet) / 2, 0.034, feet - 0.62, 0, leg)
    if "boots" in garments:
        cv.rect(x, feet - 0.03, 0.04, 0.06, 0, garments["boots"][0])
    else:
        cv.rect(x + 0.006, feet - 0.008, 0.046, 0.016, 0, BLACK)

    if "shorts" in garments:
        cv.rect(x, 0.66, 0.07, 0.05, 0, garments["shorts"][0])
    if "top" in garments:
        cv.rect(x, (shoulders + 0.645) / 2, 0.075, 0.645 - shoulders, 0, garments["top"][0])
    if "dress" in garments:
        colour, hem = garments["dress"]
        cv.trapezoid(x, shoulders, hem, 0.06, 0.15, colour)
    if "cardigan" in garments:
        cv.rect(x - 0.03, 0.56, 0.02, 0.12, 0, garments["cardigan"][0])
    if "coat" in garments:
        colour, hem = garments["coat"]
        cv.rect(x, (shoulders + hem) / 2, 0.085, hem - shoulders, 0, colour)
    if "puffer" in garments:
        cv.rect(x, 0.6, 0.11, 0.2, 0, garments["puffer"][0])
        cv.rect(x, 0.6, 0.11, 0.006, 0, BLACK)
    if "hood-down" in garments:
        cv.rect(x, shoulders + 0.006, 0.06, 0.016, 0, garments["hood-down"][0])

    # Her long brown hair is one bar beside the head, streaming out in the wind.
    if windy:
        cv.bar(x, head_y, x + 0.13, head_y + 0.05, 0.03, HAIR)
    else:
        cv.rect(x - 0.03, head_y + 0.05, 0.02, 0.12, 0, HAIR)
    cv.circle(x, head_y, r, SKIN)
    if "scarf" in garments:
        # One bar crossing her, out to the side in the wind.
        end = (x + 0.15, shoulders - 0.04) if windy else (x + 0.06, shoulders + 0.1)
        cv.bar(x - 0.05, shoulders + 0.01, *end, 0.02, garments["scarf"][0])
    if "sunhat" in garments:
        cv.rect(x, head_y - 0.024, 0.15, 0.014, -0.12, garments["sunhat"][0])
    if "bobble" in garments:
        cv.circle(x, head_y - 0.045, 0.016, garments["bobble"][0])
    if "hood" in garments:
        cv.half_circle(x, head_y + 0.004, r + 0.01, garments["hood"][0])
    if "closed-umbrella" in garments:
        cv.bar(x + 0.06, 0.58, x + 0.1, 0.86, 0.012, garments["closed-umbrella"][0])
    if "umbrella" in garments:
        cv.bar(x + 0.02, 0.6, x + 0.02, 0.4, 0.006, BLACK)
        cv.half_circle(x + (0.03 if windy else 0.0), 0.405, 0.13, garments["umbrella"][0])


def finish(image, rng):
    """Painted, not printed: edges that wobble a little, and uneven paint."""
    a = np.asarray(image, dtype=np.float32)
    h, w, _ = a.shape
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    wobble = [ndimage.gaussian_filter(rng.normal(0, 1, (h, w)).astype(np.float32), 6) for _ in range(2)]
    wobble = [d / (np.abs(d).max() or 1) * 1.6 for d in wobble]
    a = np.stack([ndimage.map_coordinates(a[..., c], [yy + wobble[0], xx + wobble[1]], order=1, mode="nearest")
                  for c in range(3)], 2)
    mottle = ndimage.gaussian_filter(rng.normal(0, 1, (h // 8, w // 8)).astype(np.float32), 3)
    mottle = np.kron(mottle / (np.abs(mottle).max() or 1), np.ones((8, 8), np.float32))[:h, :w]
    a = a * (1 + 0.035 * mottle[..., None]) + rng.normal(0, 2.2, (h, w, 1))
    out = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    return out.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def compose(slug, kind, warmth, time):
    rng = np.random.default_rng(zlib.crc32(slug.encode()))
    ax = Axis(axis_angle(kind))
    cv = Canvas(ground_colour(kind, time))
    if kind == "FOG":
        cv.fade = 0.55
    draw_planes(cv, kind, time, warmth, ax, rng)
    draw_weather(cv, kind, time, ax, rng)
    draw_lines(cv, kind, time, warmth, ax, rng, front=False)
    draw_scatter(cv, kind, time, warmth, ax, rng)
    if kind == "FOG":
        cv.fade = 0.35
    draw_figure(cv, OUTFITS[slug], kind, ax)
    draw_lines(cv, kind, time, warmth, ax, rng, front=True)
    return finish(cv.image, rng)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("scenes", nargs="*", help="scene names (default: all)")
    args = parser.parse_args()

    known = {s[0]: s for s in scenes()}
    unknown = [s for s in args.scenes if s not in known]
    if unknown:
        sys.exit(f"Not a scene: {', '.join(unknown)}")
    no_outfit = [s for s in known if s not in OUTFITS]
    if no_outfit:
        sys.exit(f"Add an outfit to OUTFITS for: {', '.join(no_outfit)}")

    OUT.mkdir(parents=True, exist_ok=True)
    for slug in args.scenes or known:
        _, kind, warmth, time = known[slug]
        compose(slug, kind, warmth, time or None).save(OUT / f"{slug}.webp", quality=90, method=6)
        print(f"composed {slug}")
    print(f"Wrote to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
