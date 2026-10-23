"""Détoure les icônes de tuiles : brand/icons/src/*.png -> brand/icons/*.png (fond transparent).
Le carré arrondi est mesuré sur l'image (bords nets plus clairs que le fond), puis découpé géométriquement."""
import os
import numpy as np
from PIL import Image, ImageDraw

SS = 4  # suréchantillonnage pour des bords lisses


def measure(a):
    b = a[:, :, 2].astype(int)
    bg = np.median(np.concatenate([b[0], b[-1], b[:, 0], b[:, -1]]))
    def first(line):
        return int(np.where(line > bg + 30)[0][0])
    h, w = b.shape
    L = first(b[h // 2]); R = w - first(b[h // 2][::-1])
    T = first(b[:, w // 2]); B = h - first(b[:, w // 2][::-1])
    return L, T, R, B


def corner_radius(a, L, T):
    b = a[:, :, 2].astype(int)
    bg = np.median(np.concatenate([b[0], b[-1], b[:, 0], b[:, -1]]))
    best = None
    for r in range(60, 200):
        err = 0
        for dy in range(6, 100, 6):
            x = int(np.where(b[T + dy] > bg + 30)[0][0]) - L
            exp = r - np.sqrt(max(0, r * r - (r - dy) ** 2)) if dy < r else 0
            err += abs(exp - x)
        if best is None or err < best[0]:
            best = (err, r)
    return best[1]


def cut(src, dst):
    im = Image.open(src).convert('RGBA'); a = np.asarray(im)
    L, T, R, B = measure(a)
    r = corner_radius(a, L, T)
    w, h = im.size
    m = Image.new('L', (w * SS, h * SS), 0)
    inset = 0.6  # léger retrait pour ne garder aucun pixel du fond
    ImageDraw.Draw(m).rounded_rectangle(
        [(L + inset) * SS, (T + inset) * SS, (R - inset) * SS, (B - inset) * SS], radius=r * SS, fill=255)
    m = m.resize((w, h), Image.LANCZOS)
    im.putalpha(m)
    c = im.crop((L, T, R, B))
    s = max(c.size)
    out = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    out.paste(c, ((s - c.width) // 2, (s - c.height) // 2))
    out.save(dst)
    return L, T, R, B, r


if __name__ == '__main__':
    d = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icons')
    for f in sorted(os.listdir(os.path.join(d, 'src'))):
        print(f, cut(os.path.join(d, 'src', f), os.path.join(d, f)))
