"""Génère toutes les images de marque KoalaTV à partir de brand/logo.png (logo détouré, fond transparent).
Usage : python3 brand/make.py  (depuis la racine du dépôt)"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGO = Image.open(os.path.join(ROOT, "brand", "logo.png")).convert("RGBA")
BG, ACCENT, TEXT = (14, 17, 22, 255), (61, 220, 151, 255), (237, 239, 242, 255)
NAVY = (2, 26, 85, 255)  # couleur du bord du logo, fond de l'icône adaptative
FONT = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"


def logo(px):
    return LOGO.resize((px, px), Image.LANCZOS)


def save(im, path):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    im.save(full, optimize=True)
    print("ok", path)


def icon(px):
    return logo(px)


def adaptive_fg(px):
    """Premier plan d'icône adaptative (108 dp) : le logo occupe la zone visible (72 dp)."""
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    k = round(px * 74 / 108)
    out.alpha_composite(logo(k), ((px - k) // 2, (px - k) // 2))
    return out


def banner(w, h, with_text=True):
    out = Image.new("RGBA", (w, h), BG)
    k = round(h * 0.62)
    if not with_text:
        out.alpha_composite(logo(k), ((w - k) // 2, (h - k) // 2))
        return out
    fs = round(h * 0.2)
    font = ImageFont.truetype(FONT, fs)
    gap = round(h * 0.07)
    w1, w2 = font.getlength("Koala"), font.getlength("TV")
    total = k + gap + w1 + w2
    x0 = round((w - total) / 2)
    out.alpha_composite(logo(k), (x0, (h - k) // 2))
    d = ImageDraw.Draw(out)
    tx = x0 + k + gap
    d.text((tx, h / 2), "Koala", font=font, fill=TEXT, anchor="lm")
    d.text((tx + w1, h / 2), "TV", font=font, fill=ACCENT, anchor="lm")
    return out


# Android : icône classique, icône adaptative (Android 8+), bannière TV, logo dans l'appli
save(icon(96), "app/src/main/res/mipmap-xhdpi/ic_launcher.png")
save(icon(192), "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
save(adaptive_fg(216), "app/src/main/res/mipmap-xhdpi/ic_launcher_fg.png")
save(adaptive_fg(432), "app/src/main/res/mipmap-xxxhdpi/ic_launcher_fg.png")
save(banner(640, 360), "app/src/main/res/drawable-xhdpi/banner.png")
for folder, px in (("drawable-xhdpi", 128), ("drawable-xxhdpi", 192), ("drawable-xxxhdpi", 256)):
    save(logo(px), f"app/src/main/res/{folder}/koala.png")
# webOS
save(icon(80), "webos/app/icon.png")
save(icon(130), "webos/app/largeIcon.png")
save(banner(1920, 1080, with_text=False), "webos/app/splash.png")
save(logo(256), "webos/app/koala.png")
# Dépôt Homebrew
save(icon(256), "docs/icon.png")
