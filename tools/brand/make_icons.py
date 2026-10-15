# Generates Zefiro's launcher icons from the mascot art in this folder.
#
#   uv run --no-project --with pillow python tools/brand/make_icons.py
#
# Inputs: zefiro.png (the swallow, colour, transparent) and zefiro-mono.png (its one-colour
# silhouette, for Android 13 themed icons). Outputs, under app/src:
#   main/res/mipmap-<density>/ic_launcher_{foreground,background,monochrome}.png   adaptive layers
#   main/res/mipmap-<density>/ic_launcher.png                                       pre-Android 8
#   beta/res/mipmap-<density>/ic_launcher{,_foreground}.png    the same with the BETA ribbon
#   main/res/drawable-xhdpi/atv_banner.png                     Android TV / Google TV banner
#
# Geometry follows the adaptive icon spec: a 108 dp canvas, of which the launcher shows the
# central 72 dp, and never cuts the central 66 dp circle. The subject fills 86% of the visible
# 72 dp (the size chosen on the mock), the ribbon crosses the bottom at -12 degrees.
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src")
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
SS = 4  # supersampling for the shapes drawn here

SUBJECT = 0.86            # of the visible 72 dp
BG_INNER = (0x1D, 0x59, 0x66)
BG_OUTER = (0x0B, 0x2C, 0x34)
BG_CENTER = (0.35, 0.30)  # of the visible area, like the mock's radial-gradient
BG_STOP = 0.75            # the outer colour is reached at 75% of the farthest-corner radius
RIBBON = (0xC9, 0x53, 0x2B)
FONT = "/System/Library/Fonts/Supplemental/Arial Black.ttf"
BANNER_FONT = "/System/Library/Fonts/Supplemental/Arial Black.ttf"


def load_square(name):
    im = Image.open(os.path.join(HERE, name)).convert("RGBA")
    im = im.crop(im.getchannel("A").getbbox())
    side = max(im.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    return sq


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def gradient(size, box):
    """Radial gradient over a canvas of `size`, laid out on the visible `box` (x, y, w, h)."""
    bx, by, bw, bh = box
    cx, cy = bx + bw * BG_CENTER[0], by + bh * BG_CENTER[1]
    far = max(math.hypot(cx - x, cy - y) for x in (bx, bx + bw) for y in (by, by + bh))
    small = max(64, size // 4)  # computed small, then scaled: a smooth gradient needs no detail
    k = size / small
    im = Image.new("RGB", (small, small))
    px = im.load()
    for y in range(small):
        for x in range(small):
            d = math.hypot((x + .5) * k - cx, (y + .5) * k - cy) / (far * BG_STOP)
            px[x, y] = lerp(BG_INNER, BG_OUTER, min(1.0, d))
    return im.resize((size, size), Image.BICUBIC).convert("RGBA")


def place(subject, canvas, scale, center=None):
    """The subject at `scale` of the canvas side, centred."""
    side = round(canvas * scale)
    s = subject.resize((side, side), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    cx, cy = center or (canvas / 2, canvas / 2)
    out.alpha_composite(s, (round(cx - side / 2), round(cy - side / 2)))
    return out


def ribbon(canvas, box):
    """The BETA band across the bottom of the visible box, drawn supersampled."""
    bx, by, bw, bh = box
    big = Image.new("RGBA", (canvas * SS, canvas * SS), (0, 0, 0, 0))
    w, h = 1.4 * bw * SS, 0.17 * bh * SS
    band = Image.new("RGBA", (round(w), round(h)), RIBBON + (255,))
    d = ImageDraw.Draw(band)
    size = round(0.108 * bh * SS)  # 13 px on a 120 px icon in the mock
    font = ImageFont.truetype(FONT, size)
    text, spacing = "BETA", 0.12 * size
    widths = [d.textlength(c, font=font) for c in text]
    total = sum(widths) + spacing * (len(text) - 1)
    x = (w - total) / 2
    top = (h - (font.getbbox("B")[3] - font.getbbox("B")[1])) / 2 - font.getbbox("B")[1]
    for c, cw in zip(text, widths):
        d.text((x, top), c, font=font, fill=(255, 255, 255, 255))
        x += cw + spacing
    band = band.rotate(12, resample=Image.BICUBIC, expand=True)
    # CSS: left -20%, bottom 9%, rotated around its own centre.
    ccx = (bx - 0.2 * bw) * SS + w / 2
    ccy = (by + bh - 0.09 * bh) * SS - h / 2
    shadow = Image.new("RGBA", band.size, (0, 0, 0, 0))
    shadow.putalpha(band.getchannel("A").point(lambda a: a * 0.28))
    shadow = shadow.filter(ImageFilter.GaussianBlur(1.5 * SS * canvas / 108))
    pos = (round(ccx - band.width / 2), round(ccy - band.height / 2))
    big.alpha_composite(shadow, (pos[0], pos[1] + round(SS * canvas / 108)))
    big.alpha_composite(band, pos)
    return big.resize((canvas, canvas), Image.LANCZOS)


def rounded(im, radius):
    mask = Image.new("L", (im.width * SS, im.height * SS), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, mask.width - 1, mask.height - 1), radius * SS, fill=255)
    mask = mask.resize(im.size, Image.LANCZOS)
    out = im.copy()
    out.putalpha(Image.composite(im.getchannel("A"), Image.new("L", im.size, 0), mask))
    return out


def save(im, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, optimize=True)


def main():
    color, mono = load_square("zefiro.png"), load_square("zefiro-mono.png")
    white = Image.new("RGBA", mono.size, (255, 255, 255, 255))
    white.putalpha(mono.getchannel("A"))
    for name, k in DENSITIES.items():
        canvas, visible = round(108 * k), 72 * k
        off = (canvas - visible) / 2
        box = (off, off, visible, visible)
        scale = SUBJECT * visible / canvas
        mip = "mipmap-" + name
        fg = place(color, canvas, scale)
        bg = gradient(canvas, box)
        rib = ribbon(canvas, box)
        fg_beta = fg.copy()
        fg_beta.alpha_composite(rib)
        save(fg, "main", "res", mip, "ic_launcher_foreground.png")
        save(bg.convert("RGB"), "main", "res", mip, "ic_launcher_background.png")
        save(place(white, canvas, scale), "main", "res", mip, "ic_launcher_monochrome.png")
        save(fg_beta, "beta", "res", mip, "ic_launcher_foreground.png")
        # Before Android 8: one flat image, 48 dp, the visible area in a rounded square.
        legacy = round(48 * k)
        for variant, front in (("main", fg), ("beta", fg_beta)):
            full = bg.copy()
            full.alpha_composite(front)
            v = full.crop((round(off), round(off), round(off + visible), round(off + visible)))
            v = v.resize((legacy, legacy), Image.LANCZOS)
            save(rounded(v, legacy * 0.2), variant, "res", mip, "ic_launcher.png")
    banner()


def banner():
    """320x180 (xhdpi) for Android TV and Google TV: the swallow and the name, on the gradient."""
    w, h = 320, 180
    big = Image.new("RGBA", (w * SS, h * SS))
    g = gradient(max(w, h) * SS, (0, 0, max(w, h) * SS, max(w, h) * SS))
    big.alpha_composite(g.crop((0, 0, w * SS, h * SS)))
    bird = load_square("zefiro.png").resize((round(150 * SS), round(150 * SS)), Image.LANCZOS)
    big.alpha_composite(bird, (round(14 * SS), round(15 * SS)))
    d = ImageDraw.Draw(big)
    font = ImageFont.truetype(BANNER_FONT, 40 * SS)
    d.text((168 * SS, 90 * SS), "Zefiro", font=font, fill=(0xFB, 0xF3, 0xE6, 255), anchor="lm")
    save(big.resize((w, h), Image.LANCZOS).convert("RGB"), "main", "res", "drawable-xhdpi", "atv_banner.png")


if __name__ == "__main__":
    main()
