#!/usr/bin/env python3
"""Frame raw store screenshots for CarrierPony: brand gradient, localized
headline, screenshot in a phone frame. Output keeps the input's pixel size, so
each store's required dimensions carry through.

  python3 tools/frame_screenshots.py android   (CarrierPonyAndroid)
  python3 fastlane/frame_screenshots.py ios    (CarrierPony iOS)

android: reads  fastlane/metadata/android/<locale>/images/phoneScreenshots/*.png
              (the raw captures F-Droid shows)
         writes fastlane/play/<locale>/images/phoneScreenshots/  (Play's set)
         captions tools/screenshot-captions.json
ios:     reads  fastlane/screenshots/<locale>/*.png  (fastlane snapshot output)
         writes fastlane/screenshots_framed/<locale>/  (what deliver uploads)
         captions fastlane/screenshot-captions.json

A screenshot's caption key is the screen name in its file name: 1_inbox.png
(Android) or "iPhone 17 Pro Max-01Inbox.png" (iOS) both map to "inbox".

Needs Pillow (python3 -m pip install --user pillow) and fonts: run
tools/fetch-fonts.sh once (Noto Sans and Noto Sans CJK, SIL OFL, gitignored),
or pass --fonts DIR.
"""

import argparse
import json
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# Brand gradient (ui/theme/Color.kt: CPGold, CPCoral, CPRed).
STOPS = [(0.0, (0xEB, 0xC2, 0x89)), (0.5, (0xF2, 0x8B, 0x80)), (1.0, (0xF1, 0x66, 0x7B))]
BEZEL = (0x1C, 0x1C, 0x1E)
TEXT = (255, 255, 255)

FONT_FILES = {
    "latin": ["NotoSans-Bold.ttf"],
    "ja": ["NotoSansCJKjp-Bold.otf"],
    "zh": ["NotoSansCJKsc-Bold.otf"],
}
# Fallbacks if tools/fonts is missing (macOS system fonts).
SYSTEM_FONTS = {
    "latin": ["/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/Library/Fonts/Arial Bold.ttf"],
    "ja": ["/System/Library/Fonts/ヒラギノ角ゴシック W6.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc"],
    "zh": ["/System/Library/Fonts/PingFang.ttc", "/System/Library/Fonts/Hiragino Sans GB.ttc"],
}


def script_for(locale):
    l = locale.lower()
    if l.startswith("ja"):
        return "ja"
    if l.startswith("zh"):
        return "zh"
    return "latin"


def find_font(kind, font_dirs):
    for d in font_dirs:
        for name in FONT_FILES[kind]:
            p = os.path.join(d, name)
            if os.path.exists(p):
                return p
    for p in SYSTEM_FONTS[kind]:
        if os.path.exists(p):
            return p
    sys.exit(f"No {kind} font found. Run tools/fetch-fonts.sh or pass --fonts.")


def gradient(w, h):
    small = Image.new("RGB", (256, 256))
    px = []
    for y in range(256):
        for x in range(256):
            t = (x / 255 + y / 255) / 2
            for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
                if t <= t1:
                    f = (t - t0) / (t1 - t0)
                    px.append(tuple(round(c0[i] + (c1[i] - c0[i]) * f) for i in range(3)))
                    break
    small.putdata(px)
    return small.resize((w, h), Image.BICUBIC)


def wrap(text, font, max_w, cjk, draw):
    units = list(text) if cjk else text.split(" ")
    joiner = "" if cjk else " "
    lines, cur = [], ""
    for u in units:
        trial = (cur + joiner + u) if cur else u
        if draw.textlength(trial, font=font) <= max_w or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = u
    if cur:
        lines.append(cur)
    return lines


def frame(src, dst, caption, font_path, cjk):
    shot = Image.open(src).convert("RGB")
    W, H = shot.size
    canvas = gradient(W, H).convert("RGBA")
    draw = ImageDraw.Draw(canvas)

    # Headline: as large as fits in three lines across 86% of the width.
    max_w = W * 0.86
    size = int(W * 0.068)
    while True:
        font = ImageFont.truetype(font_path, size)
        lines = wrap(caption, font, max_w, cjk, draw)
        if len(lines) <= 3 or size < W * 0.04:
            break
        size = int(size * 0.92)
    line_h = int(size * 1.22)
    top = int(H * 0.055)
    for i, line in enumerate(lines):
        lw = draw.textlength(line, font=font)
        draw.text(((W - lw) / 2, top + i * line_h), line, font=font, fill=TEXT)
    text_bottom = top + len(lines) * line_h

    # Phone: the screenshot inside a rounded bezel, as big as the space allows.
    bezel = max(8, int(W * 0.022))
    avail_h = H - (text_bottom + int(H * 0.035)) - int(H * 0.03)
    scale = min(W * 0.74 / W, (avail_h - 2 * bezel) / H)
    sw, sh = int(W * scale), int(H * scale)
    phone_w, phone_h = sw + 2 * bezel, sh + 2 * bezel
    px = (W - phone_w) // 2
    py = text_bottom + int(H * 0.035)
    radius = int(phone_w * 0.09)

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [px, py + int(H * 0.008), px + phone_w, py + phone_h + int(H * 0.008)], radius, fill=(60, 20, 30, 110))
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(int(W * 0.02))))
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle([px, py, px + phone_w, py + phone_h], radius, fill=BEZEL + (255,))

    screen = shot.resize((sw, sh), Image.LANCZOS)
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sw, sh], max(1, radius - bezel), fill=255)
    canvas.paste(screen, (px + bezel, py + bezel), mask)

    os.makedirs(os.path.dirname(dst), exist_ok=True)
    canvas.convert("RGB").save(dst, "PNG", optimize=True)


def screen_key(filename):
    stem = os.path.splitext(os.path.basename(filename))[0]
    m = re.search(r"\d+_?([A-Za-z]+)$", stem.split("-")[-1])
    return m.group(1).lower() if m else stem.lower()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("platform", choices=["android", "ios"])
    ap.add_argument("--fonts", action="append", default=[])
    args = ap.parse_args()

    here = os.path.dirname(os.path.abspath(__file__))
    font_dirs = args.fonts + [os.path.join(here, "fonts"), os.path.join(here, "..", "tools", "fonts")]

    if args.platform == "android":
        src_root, dst_root = "fastlane/metadata/android", "fastlane/play"
        captions = json.load(open("tools/screenshot-captions.json", encoding="utf-8"))
        sub = os.path.join("images", "phoneScreenshots")
    else:
        src_root, dst_root = "fastlane/screenshots", "fastlane/screenshots_framed"
        captions = json.load(open("fastlane/screenshot-captions.json", encoding="utf-8"))
        sub = ""

    done = 0
    for locale in sorted(os.listdir(src_root)):
        src_dir = os.path.join(src_root, locale, sub)
        if not os.path.isdir(src_dir) or locale not in captions:
            continue
        kind = script_for(locale)
        font_path = find_font(kind, font_dirs)
        dst_dir = os.path.join(dst_root, locale, sub)
        os.makedirs(dst_dir, exist_ok=True)
        for old in os.listdir(dst_dir):
            if old.endswith(".png"):
                os.remove(os.path.join(dst_dir, old))
        for name in sorted(os.listdir(src_dir)):
            if not name.endswith(".png"):
                continue
            if name.startswith("iPad"):
                continue  # CarrierPony iOS is iPhone only
            key = screen_key(name)
            caption = captions[locale].get(key)
            if caption is None:
                print(f"  skipping {locale}/{name}: no caption for '{key}'")
                continue
            frame(os.path.join(src_dir, name), os.path.join(dst_dir, name), caption, font_path, kind != "latin")
            done += 1
        print(f"{locale}: framed")
    if done == 0:
        sys.exit("Nothing framed: no raw screenshots found.")
    print(f"{done} screenshots framed into {dst_root}")


if __name__ == "__main__":
    main()
