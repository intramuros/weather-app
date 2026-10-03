"""Turns the finished scene pictures in source/ into the app's assets.

Pictures are named <sky>-<warmth>, matching ScenePicture in core/.

Each source is a widget mock-up: a square scene inside a rounded frame, with
icons and example text drawn on it. For every picture this writes, to
app/src/main/assets/scenes/:

  <name>.webp        the scene with the frame, icons and text removed
                     (used as the wallpaper, and under the widget)
  <name>-icons.webp  the icons alone, on transparency, the same size
                     (the widget draws them, then the live text)

Removed areas are filled with the surrounding sky and re-pixelated on the
art's grid, so they blend in. The text positions the app uses are in
SceneLayout (app/.../render/InfoOverlay.kt); re-measure them if a picture
changes.

Usage (from the repository root):
    pip install opencv-python-headless numpy pillow
    python3 tools/scenes/prepare.py
"""

from pathlib import Path

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent / "source"
OUT = ROOT / "app/src/main/assets/scenes"
SIZE = 1200  # output size; the boxes below are in these units
GRID = 10  # the art's pixel size at SIZE
INSET = 6  # trims the frame's anti-aliased edge

# Example text to remove: temperature, condition, place, humidity, wind, direction.
TEXT = {
    "snow-cold": [(200, 270, 390, 390), (210, 420, 350, 470), (730, 100, 1070, 160),
             (870, 216, 990, 270), (870, 310, 1070, 360), (870, 370, 920, 420)],
    "storm-cold": [(100, 260, 340, 390), (110, 410, 270, 460), (830, 100, 1110, 156),
              (940, 220, 1050, 270), (940, 310, 1136, 360), (940, 360, 990, 410)],
    "rain-cold": [(130, 280, 440, 430), (130, 444, 290, 500), (730, 80, 1110, 150),
             (900, 210, 1024, 264), (900, 310, 1136, 370), (900, 380, 1000, 436)],
    "cloudy-cold": [(210, 270, 480, 410), (216, 430, 420, 490), (744, 110, 1076, 170),
               (876, 230, 990, 280), (876, 330, 1100, 390), (876, 396, 960, 450)],
    "partly-cloudy-cold": [(130, 260, 370, 400), (130, 410, 524, 470), (770, 90, 1100, 146),
                      (910, 210, 1024, 270), (910, 310, 1136, 370), (910, 380, 960, 424)],
    "windy-cool": [(170, 280, 430, 420), (175, 440, 355, 500), (735, 85, 1115, 135),
                   (905, 205, 1025, 255), (905, 315, 1140, 365), (905, 380, 995, 432)],
    "snow-freezing": [(165, 270, 435, 405), (180, 420, 330, 470), (755, 85, 1110, 135),
                      (905, 205, 1030, 250), (905, 305, 1140, 355), (905, 365, 945, 415)],
    "clear-warm": [(225, 280, 490, 405), (230, 425, 395, 475), (765, 100, 1105, 150),
                   (910, 220, 1025, 270), (910, 315, 1110, 365), (910, 375, 980, 420)],
    "storm-cool": [(140, 280, 430, 425), (145, 445, 330, 495), (745, 80, 1115, 130),
                   (910, 205, 1025, 250), (910, 310, 1140, 365), (910, 375, 960, 425)],
    "rain-mild": [(127, 273, 440, 427), (133, 440, 287, 500), (753, 93, 1133, 147),
                  (900, 207, 1027, 267), (900, 320, 1147, 373), (900, 380, 993, 433)],
    "clear-night-cold": [(173, 267, 367, 413), (133, 433, 460, 493), (733, 80, 1120, 140),
                         (907, 200, 1027, 260), (907, 313, 1147, 373), (907, 380, 987, 433)],
    "cloudy-cool": [(167, 280, 460, 407), (180, 433, 373, 487), (760, 93, 1113, 147),
                    (907, 213, 1027, 267), (907, 320, 1147, 373), (907, 380, 993, 430)],
    "partly-cloudy-mild": [(167, 273, 440, 407), (167, 433, 520, 487), (733, 80, 1113, 140),
                           (904, 207, 1024, 260), (904, 320, 1133, 373), (904, 380, 953, 433)],
    "clear-hot": [(220, 273, 507, 407), (227, 427, 400, 480), (760, 87, 1113, 140),
                  (904, 213, 1024, 260), (913, 320, 1113, 373), (913, 380, 973, 427)],
    "fog-cool": [(147, 267, 460, 433), (160, 447, 287, 507), (733, 80, 1113, 140),
                 (907, 207, 1027, 260), (907, 320, 1113, 373), (907, 380, 947, 433)],
    "windy-mild": [(160, 273, 447, 413), (167, 433, 320, 487), (733, 80, 1113, 147),
                   (904, 207, 1020, 267), (904, 320, 1133, 373), (904, 387, 987, 440)],
    "clear-freezing": [(167, 273, 453, 420), (167, 433, 340, 487), (733, 80, 1113, 147),
                       (900, 207, 1020, 267), (900, 320, 1133, 373), (900, 387, 980, 440)],
    "clear-night-warm": [(160, 273, 447, 413), (160, 427, 460, 487), (733, 80, 1113, 147),
                         (904, 200, 1020, 267), (904, 313, 1133, 373), (904, 380, 950, 433)],
    "rain-warm": [(160, 280, 440, 420), (160, 433, 280, 487), (740, 80, 1120, 147),
                  (900, 200, 1027, 267), (900, 313, 1133, 373), (900, 380, 993, 437)],
    "windy-night-cool": [(160, 273, 453, 420), (167, 433, 327, 487), (733, 80, 1113, 147),
                         (904, 200, 1020, 267), (904, 313, 1133, 373), (904, 380, 950, 433)],
    "clear-night-hot": [(160, 273, 453, 420), (167, 433, 420, 487), (733, 80, 1113, 147),
                        (900, 200, 1020, 267), (900, 313, 1093, 373), (900, 380, 980, 433)],
    "cloudy-freezing": [(167, 273, 453, 420), (173, 433, 353, 487), (733, 80, 1113, 147),
                        (904, 207, 1020, 267), (904, 313, 1133, 373), (904, 380, 990, 437)],
    "storm-warm": [(160, 273, 473, 413), (160, 427, 520, 480), (733, 80, 1113, 147),
                   (900, 200, 1020, 267), (900, 313, 1133, 373), (900, 380, 987, 437)],
    "fog-freezing": [(167, 273, 460, 413), (167, 427, 513, 487), (733, 80, 1113, 147),
                     (904, 207, 1027, 267), (904, 313, 1107, 373), (904, 380, 947, 433)],
    "windy-night-freezing": [(187, 273, 460, 413), (173, 427, 560, 487), (733, 80, 1113, 147),
                             (900, 207, 1027, 267), (900, 313, 1133, 373), (900, 380, 993, 437)],
    "rain-freezing": [(167, 273, 453, 420), (173, 427, 507, 487), (740, 87, 1120, 147),
                      (904, 207, 1027, 267), (904, 313, 1140, 373), (904, 380, 987, 437)],
    "rain-night-cool": [(153, 273, 447, 420), (160, 427, 420, 487), (733, 80, 1113, 147),
                        (900, 200, 1027, 267), (900, 313, 1133, 373), (900, 380, 993, 437)],
    "snow-night-freezing": [(167, 280, 440, 420), (180, 433, 333, 480), (753, 87, 1127, 147),
                            (904, 207, 1027, 267), (904, 320, 1140, 373), (904, 380, 950, 433)],
    "rain-hot": [(147, 287, 453, 427), (180, 440, 300, 493), (740, 80, 1113, 147),
                 (900, 207, 1027, 267), (900, 313, 1133, 373), (900, 380, 993, 437)],
    "windy-freezing": [(207, 287, 380, 420), (180, 433, 347, 487), (733, 80, 1113, 147),
                       (904, 207, 1027, 267), (904, 313, 1133, 373), (904, 380, 993, 437)],
}

# Icons to move to the icon layer: the weather icon, then the drop and wind icons.
ICONS = {
    "snow-cold": [(190, 30, 440, 255), (735, 200, 865, 410)],
    "storm-cold": [(90, 40, 330, 265), (825, 200, 935, 400)],
    "rain-cold": [(115, 25, 390, 260), (770, 195, 895, 410)],
    "cloudy-cold": [(185, 55, 545, 245), (735, 215, 870, 430)],
    "partly-cloudy-cold": [(85, 25, 435, 260), (765, 195, 900, 400)],
    "windy-cool": [(140, 30, 445, 265), (770, 190, 885, 410)],
    "snow-freezing": [(135, 35, 405, 270), (770, 190, 885, 400)],
    "clear-warm": [(195, 35, 450, 268), (770, 195, 885, 405)],
    "storm-cool": [(120, 25, 410, 270), (770, 190, 885, 410)],
    "rain-mild": [(110, 40, 400, 268), (770, 190, 890, 410)],
    "clear-night-cold": [(85, 30, 520, 235), (770, 190, 890, 410)],
    "cloudy-cool": [(115, 50, 425, 225), (770, 190, 890, 410)],
    "partly-cloudy-mild": [(140, 20, 465, 255), (770, 190, 890, 410)],
    "clear-hot": [(190, 25, 460, 268), (770, 190, 890, 410)],
    "fog-cool": [(115, 55, 440, 245), (770, 190, 890, 410)],
    "windy-mild": [(140, 20, 510, 262), (760, 190, 885, 418)],
    "clear-freezing": [(135, 20, 395, 262), (765, 190, 885, 412)],
    "clear-night-warm": [(70, 40, 680, 360), (770, 185, 885, 405)],
    "rain-warm": [(175, 35, 460, 268), (770, 185, 885, 405)],
    "windy-night-cool": [(45, 40, 570, 320), (770, 190, 885, 410)],
    "clear-night-hot": [(60, 40, 640, 330), (770, 190, 885, 410)],
    "cloudy-freezing": [(115, 45, 470, 250), (770, 190, 885, 410)],
    "storm-warm": [(120, 25, 410, 268), (770, 190, 885, 410)],
    "fog-freezing": [(120, 75, 430, 265), (770, 190, 885, 410)],
    "windy-night-freezing": [(80, 45, 390, 250), (770, 190, 885, 410)],
    "rain-freezing": [(135, 35, 460, 268), (770, 190, 885, 410)],
    "rain-night-cool": [(125, 40, 460, 280), (770, 190, 885, 410)],
    "snow-night-freezing": [(120, 40, 405, 275), (770, 190, 885, 410)],
    "rain-hot": [(125, 45, 445, 280), (770, 190, 885, 410)],
    "windy-freezing": [(115, 45, 490, 288), (770, 190, 885, 410)],
}


def load_scene(path):
    """Crops the square scene out of its rounded frame and scales it to SIZE."""
    img = Image.open(path).convert("RGB")
    a = np.asarray(img).astype(int)
    h, w, _ = a.shape
    inside = np.abs(a - a[5, 5]).sum(2) > 60
    rows = np.where(inside[:, w // 2])[0]
    cols = np.where(inside[h // 2, :])[0]
    box = (cols.min() + INSET, rows.min() + INSET, cols.max() + 1 - INSET, rows.max() + 1 - INSET)
    scene = img.crop(box).resize((SIZE, SIZE), Image.LANCZOS)
    return cv2.cvtColor(np.asarray(scene), cv2.COLOR_RGB2BGR)


def ring_median(roi):
    return np.median(np.concatenate([roi[0], roi[-1], roi[:, 0], roi[:, -1]]))


def light_mask(img, boxes, threshold, pad=0, yellow=False):
    """Pixels inside boxes that are clearly lighter (or, optionally, yellower) than the box's edge."""
    lum = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(int)
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV).astype(int)
    mask = np.zeros(lum.shape, np.uint8)
    for i, (x1, y1, x2, y2) in enumerate(boxes):
        x1, y1, x2, y2 = x1 - pad, y1 - pad, x2 + pad, y2 + pad
        roi = lum[y1:y2, x1:x2]
        t = threshold(i)
        hit = (roi - ring_median(roi)) > t
        if yellow:
            sat, hue = hsv[y1:y2, x1:x2, 1], hsv[y1:y2, x1:x2, 0]
            hit |= (sat > 90) & (hue > 12) & (hue < 40)
        mask[y1:y2, x1:x2] |= hit.astype(np.uint8) * 255
    return mask


def corner_mask(img, radius=130):
    """Leftovers of the rounded frame in the corners: near-black or near-white pixels."""
    mask = np.zeros(img.shape[:2], np.uint8)
    for cy, cx in [(0, 0), (0, SIZE - radius), (SIZE - radius, 0), (SIZE - radius, SIZE - radius)]:
        block = img[cy:cy + radius, cx:cx + radius].astype(int)
        frame = (block.max(2) < 30) | (block.min(2) > 235)
        mask[cy:cy + radius, cx:cx + radius] = frame.astype(np.uint8) * 255
    return cv2.dilate(mask, np.ones((7, 7), np.uint8))


def fill(img, mask, reach=160, rows=3):
    """Fills masked runs with the median colour of the nearby unmasked pixels in the same rows.

    These skies change from top to bottom but hardly from left to right, so a
    wide median matches well and ignores rain streaks and snowflakes. The
    patched areas are then re-pixelated on the art's grid."""
    src = img.astype(float)
    out = src.copy()
    m = mask > 0
    for y in range(SIZE):
        if not m[y].any():
            continue
        y1, y2 = max(0, y - rows), min(SIZE, y + rows + 1)
        x = 0
        while x < SIZE:
            if not m[y, x]:
                x += 1
                continue
            start = x
            while x < SIZE and m[y, x]:
                x += 1
            x1, x2 = max(0, start - reach), min(SIZE, x + reach)
            band = src[y1:y2, x1:x2][~m[y1:y2, x1:x2]]
            if len(band):
                out[y, start:x] = np.median(band, 0)
    out = out.clip(0, 255).astype(np.uint8)
    small = cv2.resize(out, (SIZE // GRID, SIZE // GRID), interpolation=cv2.INTER_AREA)
    blocky = cv2.resize(small, (SIZE, SIZE), interpolation=cv2.INTER_NEAREST)
    grow = cv2.dilate(mask, np.ones((GRID + 1, GRID + 1), np.uint8)) > 0
    out[grow] = blocky[grow]
    return out


def icon_mask(img, boxes):
    # The weather icon has soft, shaded clouds, so it needs a lower threshold.
    mask = light_mask(img, boxes, threshold=lambda i: 14 if i == 0 else 28, yellow=True)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    for k in range(1, count):
        if stats[k, cv2.CC_STAT_AREA] < 40:
            mask[labels == k] = 0
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
    # Fill holes, such as a cloud's shaded middle.
    outside = mask.copy()
    cv2.floodFill(outside, np.zeros((SIZE + 2, SIZE + 2), np.uint8), (0, 0), 255)
    mask |= cv2.bitwise_not(outside)
    return cv2.dilate(mask, np.ones((5, 5), np.uint8))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name in TEXT:
        raw = load_scene(SOURCE / f"{name}.webp")
        text = cv2.dilate(light_mask(raw, TEXT[name], threshold=lambda i: 45, pad=8), np.ones((9, 9), np.uint8))
        clean = fill(raw, text | corner_mask(raw))

        icons = icon_mask(clean, ICONS[name])
        plain = fill(clean, cv2.dilate(icons, np.ones((7, 7), np.uint8)))

        Image.fromarray(cv2.cvtColor(plain, cv2.COLOR_BGR2RGB)).save(OUT / f"{name}.webp", quality=90, method=6)
        layer = cv2.cvtColor(clean, cv2.COLOR_BGR2RGBA)
        layer[:, :, 3] = icons
        Image.fromarray(layer).save(OUT / f"{name}-icons.webp", lossless=True, method=6)
        print(f"{name}: done")
    print(f"Wrote {OUT}")


if __name__ == "__main__":
    main()
