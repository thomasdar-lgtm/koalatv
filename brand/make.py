"""Génère toutes les images de marque KoalaTV à partir de koala.svg.
Usage : python3 brand/make.py  (depuis la racine du dépôt)"""
import os
import re
import cairosvg
from PIL import ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KOALA = open(os.path.join(ROOT, "brand", "koala.svg"), encoding="utf-8").read()
INNER = re.search(r"<svg[^>]*>(.*)</svg>", KOALA, re.S).group(1)
BG, CARD, ACCENT, TEXT = "#0E1116", "#161B22", "#3DDC97", "#EDEFF2"


def koala(x, y, size):
    s = size / 200.0
    return f'<g transform="translate({x},{y}) scale({s})">{INNER}</g>'


def icon(size, radius_ratio=0.22):
    r = size * radius_ratio
    pad = size * 0.08
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}">'
            f'<rect width="{size}" height="{size}" rx="{r}" fill="{CARD}"/>'
            f'{koala(pad, pad, size - 2 * pad)}</svg>')


def banner(w, h, with_text=True):
    k = h * 0.58
    if not with_text:
        return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}">'
                f'<rect width="{w}" height="{h}" fill="{BG}"/>{koala((w - k) / 2, (h - k) / 2, k)}</svg>')
    fs = h * 0.19
    font = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Bold.otf", 200)
    text_w = font.getlength("KoalaTV") / 200 * fs * 0.985
    total = k + h * 0.06 + text_w
    x0 = (w - total) / 2
    tx = x0 + k + h * 0.06
    ty = h / 2 + fs * 0.36
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}">'
            f'<rect width="{w}" height="{h}" fill="{BG}"/>{koala(x0, (h - k) / 2, k)}'
            f'<text x="{tx}" y="{ty}" font-family="Inter" font-weight="700" font-size="{fs}" '
            f'letter-spacing="{-fs * 0.02}" fill="{TEXT}">Koala<tspan fill="{ACCENT}">TV</tspan></text></svg>')


def png(svg, path):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=full)
    print("ok", path)


# Android TV
png(banner(640, 360), "app/src/main/res/drawable-xhdpi/banner.png")
png(icon(96), "app/src/main/res/mipmap-xhdpi/ic_launcher.png")
png(icon(192), "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
# webOS
png(icon(80), "webos/app/icon.png")
png(icon(130), "webos/app/largeIcon.png")
png(banner(1920, 1080, with_text=False), "webos/app/splash.png")
# Dépôt Homebrew
png(icon(256), "docs/icon.png")
# Logo vectoriel utilisé dans l'appli webOS
with open(os.path.join(ROOT, "webos/app/koala.svg"), "w", encoding="utf-8") as f:
    f.write(KOALA)
print("ok webos/app/koala.svg")
# Logo seul pour l'appli Android (64 dp)
for folder, px in (("drawable-xhdpi", 128), ("drawable-xxhdpi", 192), ("drawable-xxxhdpi", 256)):
    full = os.path.join(ROOT, "app/src/main/res", folder, "koala.png")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    cairosvg.svg2png(bytestring=KOALA.encode("utf-8"), write_to=full, output_width=px, output_height=px)
    print("ok", folder, "koala.png")
