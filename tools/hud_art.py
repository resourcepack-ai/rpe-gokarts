"""
The HUD's art: everything the plugin writes into the HUD's runs that is a
picture rather than words. make_assets.py calls build() and puts the icon
definitions it returns into fonts/icons.yml; race/Hud.java names them, so a
glyph's id here and there is the contract.

A run is one colour, and that colour is its address in the HUD's shader, so a
picture in more than one colour arrives already coloured, in a run drawn
white. Three rules every glyph here follows:

- **Inked in its rightmost column.** The game measures a bitmap glyph's
  advance from its last non-transparent column and RP Engine measures it from
  the cell; the two agree only when the cell's last column has something in
  it, so a glyph that does not reach it gets a pixel nobody can see (FAINT).
- **Hung from the top of its line** (ascent 7, the line's top) unless said
  otherwise, so a picture is placed by where its top-left corner goes. The
  minimap's dots are the exception: one glyph per row, each hung lower.
- **At most 256 texels on a side**, the font atlas's page. Smooth art is drawn
  at twice the texel (`2x`: a 64 GUI-pixel glyph from a 128 texel image), so it
  stays crisp at the GUI scales people play at.
"""
import json
import math
import os

from PIL import Image, ImageChops, ImageDraw, ImageFilter

FAINT = (255, 255, 255, 1)
OUTLINE = (10, 12, 16, 240)

WHITE = (255, 255, 255)
GOLD = (255, 210, 63)
GREEN = (91, 227, 125)
RED = (255, 70, 85)
GREY = (170, 176, 184)

# The plugin's KartColour, in its order: the minimap's dots and the standings' chips.
KART_COLOURS = {
    "red": (0xFF, 0x46, 0x55), "blue": (0x2E, 0x9B, 0xFF), "green": (0x5B, 0xE3, 0x7D), "yellow": (0xFF, 0xD2, 0x3F),
    "orange": (0xFF, 0x8A, 0x3D), "purple": (0xA4, 0x5C, 0xFF), "pink": (0xFF, 0x5C, 0xC8), "cyan": (0x3D, 0xE0, 0xE0),
}

# ---------------------------------------------------------------------------
# Lettering: 7x11 capitals with two-pixel strokes (Paintball's, with / : . added)
# ---------------------------------------------------------------------------

DIGITS = {
    "0": [".#####.", "##...##", "##...##", "##..###", "##.####", "####.##", "###..##", "##...##", "##...##", "##...##", ".#####."],
    "1": ["...##..", "..###..", ".####..", "...##..", "...##..", "...##..", "...##..", "...##..", "...##..", "...##..", ".######"],
    "2": [".#####.", "##...##", ".....##", ".....##", "....##.", "...##..", "..##...", ".##....", "##.....", "##.....", "#######"],
    "3": [".#####.", "##...##", ".....##", ".....##", "..####.", ".....##", ".....##", ".....##", ".....##", "##...##", ".#####."],
    "4": ["....##.", "...###.", "..####.", ".##.##.", "##..##.", "##..##.", "#######", "....##.", "....##.", "....##.", "....##."],
    "5": ["#######", "##.....", "##.....", "######.", ".....##", ".....##", ".....##", ".....##", ".....##", "##...##", ".#####."],
    "6": [".#####.", "##...##", "##.....", "##.....", "######.", "##...##", "##...##", "##...##", "##...##", "##...##", ".#####."],
    "7": ["#######", ".....##", ".....##", "....##.", "....##.", "...##..", "...##..", "..##...", "..##...", "..##...", "..##..."],
    "8": [".#####.", "##...##", "##...##", "##...##", ".#####.", "##...##", "##...##", "##...##", "##...##", "##...##", ".#####."],
    "9": [".#####.", "##...##", "##...##", "##...##", "##...##", ".######", ".....##", ".....##", ".....##", "##...##", ".#####."],
}

CAPS = {
    "A": [".#####.", "##...##", "##...##", "##...##", "##...##", "#######", "##...##", "##...##", "##...##", "##...##", "##...##"],
    "B": ["######.", "##...##", "##...##", "##...##", "######.", "##...##", "##...##", "##...##", "##...##", "##...##", "######."],
    "C": [".#####.", "##...##", "##.....", "##.....", "##.....", "##.....", "##.....", "##.....", "##.....", "##...##", ".#####."],
    "D": ["######.", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "######."],
    "E": ["#######", "##.....", "##.....", "##.....", "######.", "##.....", "##.....", "##.....", "##.....", "##.....", "#######"],
    "F": ["#######", "##.....", "##.....", "##.....", "######.", "##.....", "##.....", "##.....", "##.....", "##.....", "##....."],
    "G": [".#####.", "##...##", "##.....", "##.....", "##.....", "##.####", "##...##", "##...##", "##...##", "##...##", ".#####."],
    "H": ["##...##"] * 5 + ["#######"] + ["##...##"] * 5,
    "I": [".#####."] + ["..###.."] * 9 + [".#####."],
    "J": ["..#####", ".....##", ".....##", ".....##", ".....##", ".....##", ".....##", ".....##", ".....##", "##...##", ".#####."],
    "K": ["##...##", "##...##", "##..##.", "##.##..", "####...", "###....", "####...", "##.##..", "##..##.", "##...##", "##...##"],
    "L": ["##....."] * 10 + ["#######"],
    "M": ["##...##", "###.###", "#######", "##.#.##", "##.#.##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##"],
    "N": ["##...##", "###..##", "###..##", "####.##", "##.####", "##..###", "##..###", "##...##", "##...##", "##...##", "##...##"],
    "O": [".#####."] + ["##...##"] * 9 + [".#####."],
    "P": ["######.", "##...##", "##...##", "##...##", "##...##", "######.", "##.....", "##.....", "##.....", "##.....", "##....."],
    "Q": [".#####.", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##...##", "##.#.##", "##..##.", ".###.##"],
    "R": ["######.", "##...##", "##...##", "##...##", "##...##", "######.", "####...", "##.##..", "##..##.", "##...##", "##...##"],
    "S": [".#####.", "##...##", "##.....", "##.....", ".#####.", ".....##", ".....##", ".....##", ".....##", "##...##", ".#####."],
    "T": ["#######"] + ["..###.."] * 10,
    "U": ["##...##"] * 10 + [".#####."],
    "V": ["##...##"] * 6 + [".##.##.", ".##.##.", ".##.##.", "..###..", "..###.."],
    "W": ["##...##"] * 6 + ["##.#.##", "##.#.##", "#######", "###.###", "##...##"],
    "X": ["##...##", "##...##", ".##.##.", ".##.##.", "..###..", "..###..", "..###..", ".##.##.", ".##.##.", "##...##", "##...##"],
    "Y": ["##...##", "##...##", "##...##", ".##.##.", ".##.##.", "..###.."] + ["..###.."] * 5,
    "Z": ["#######", ".....##", ".....##", "....##.", "...##..", "..##...", ".##....", "##.....", "##.....", "##.....", "#######"],
    "!": ["..###.."] * 7 + ["......."] * 2 + ["..###.."] * 2,
    "-": ["......."] * 4 + [".#####.", ".#####."] + ["......."] * 5,
    "'": ["..###..", "..###..", "...##..", "..##..."] + ["......."] * 7,
    "/": [".....##", ".....##", "....##.", "....##.", "...##..", "...##..", "..##...", ".##....", ".##....", "##.....", "##....."],
    ":": ["......."] * 2 + ["..##...", "..##..."] + ["......."] * 3 + ["..##...", "..##..."] + ["......."] * 2,
    ".": ["......."] * 9 + ["..##...", "..##..."],
    " ": ["......."] * 11,
}

# Every character a lettering sheet holds, in sheet order. Hud.java names a glyph by character.
CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-'/:."
CELL_W, CELL_H = 9, 13


def pattern(ch):
    return DIGITS.get(ch) or CAPS[ch]


def shade(rgb, k):
    return tuple(max(0, min(255, int(round(c * k)))) for c in rgb)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def ink_right(img):
    if not any(img.getpixel((img.width - 1, y))[3] for y in range(img.height)):
        img.putpixel((img.width - 1, 0), FAINT)
    return img


def letter(ch, colour):
    """One 9x13 cell: the character in `colour`, lighter at the top, outlined."""
    rows = pattern(ch)
    img = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
    px = img.load()
    ink = {(x + 1, y + 1) for y, row in enumerate(rows) for x, c in enumerate(row) if c == "#"}
    for x, y in ink:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                q = (x + dx, y + dy)
                if q not in ink and 0 <= q[0] < CELL_W and 0 <= q[1] < CELL_H:
                    px[q] = OUTLINE
    for x, y in ink:
        px[x, y] = shade(colour, 1.06 - (y - 1) / 10 * 0.24) + (255,)
    return ink_right(img)


LETTERING = {"white": (WHITE, (1, 2, 3)), "grey": (GREY, (1, 2)), "gold": (GOLD, (2, 3)), "green": (GREEN, (3,)),
             "red": (RED, (3,))}


def char_id(ch):
    return {"!": "bang", "-": "dash", "'": "apos", "/": "slash", ":": "colon", ".": "dot"}.get(ch, ch.lower())


# ---------------------------------------------------------------------------
# Big styled words: a gradient fill, a thick outline and a drop shadow, drawn
# from the lettering's patterns at `scale` texels a pixel.
# ---------------------------------------------------------------------------

def big_text(text, top, bottom, scale, outline=3, shadow=3, spacing=1, edge=(14, 16, 24)):
    cols = len(text) * 7 * scale + (len(text) - 1) * spacing * scale
    w, h = cols + 2 * outline + shadow, 11 * scale + 2 * outline + shadow
    fill = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(fill)
    x = outline
    for ch in text:
        for ry, row in enumerate(pattern(ch)):
            for rx, c in enumerate(row):
                if c == "#":
                    d.rectangle((x + rx * scale, outline + ry * scale, x + rx * scale + scale - 1, outline + ry * scale + scale - 1), fill=255)
        x += (7 + spacing) * scale
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    # MaxFilter(1) divides by zero inside Pillow and takes the interpreter with it.
    grown = fill.filter(ImageFilter.MaxFilter(outline * 2 + 1)) if outline > 0 else fill.copy()
    shadow_mask = ImageChops.offset(grown, shadow, shadow)
    out.paste((0, 0, 0, 110), (0, 0), shadow_mask)
    out.paste(edge + (255,), (0, 0), grown)
    grad = Image.new("RGBA", (w, h))
    gd = ImageDraw.Draw(grad)
    for y in range(h):
        t = max(0.0, min(1.0, (y - outline) / max(1, 11 * scale)))
        gd.line((0, y, w, y), fill=mix(top, bottom, t) + (255,))
    out.paste(grad, (0, 0), fill)
    # A bright edge on the top of every stroke.
    hi = ImageChops.subtract(fill, ImageChops.offset(fill, 0, scale // 2 + 1))
    out.paste(mix(top, (255, 255, 255), 0.6) + (255,), (0, 0), hi)
    return out


def at_half(img):
    """A 2x picture as a glyph twice as dense: keep it, and declare half its height."""
    return img


# ---------------------------------------------------------------------------
# Panels
# ---------------------------------------------------------------------------

def panel(w, h, radius=7, fill=(12, 16, 22, 190), edge=(255, 255, 255, 40), ss=4):
    img = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, w * ss - 1, h * ss - 1), radius=radius * ss, fill=fill, outline=edge, width=ss)
    return img.resize((w, h), Image.LANCZOS)


def thumb(path, size):
    src = Image.open(path).convert("RGBA")
    box = src.getbbox() or (0, 0, src.width, src.height)
    src = src.crop(box)
    k = size / max(src.width, src.height)
    return src.resize((max(1, round(src.width * k)), max(1, round(src.height * k))), Image.LANCZOS)


# ---- the item window (top left), drawn at 2x --------------------------------------

ITEM = 48          # GUI pixels


def item_frame(ss=2):
    s = ITEM * ss
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = 12 * ss
    # A drop shadow, a white bezel with a sheen, and a deep inner well.
    d.rounded_rectangle((3 * ss, 4 * ss, s - 1, s - 1), radius=r, fill=(0, 0, 0, 90))
    d.rounded_rectangle((0, 0, s - 4 * ss, s - 4 * ss), radius=r, fill=(240, 244, 250, 255))
    d.rounded_rectangle((0, 0, s - 4 * ss, (s - 4 * ss) // 2), radius=r, fill=(255, 255, 255, 255))
    inner = (4 * ss, 4 * ss, s - 8 * ss, s - 8 * ss)
    d.rounded_rectangle(inner, radius=r - 4 * ss, fill=(18, 24, 34, 235))
    well = Image.new("RGBA", img.size, (0, 0, 0, 0))
    wd = ImageDraw.Draw(well)
    for i in range(10 * ss):
        a = int(70 * (1 - i / (10 * ss)))
        wd.rounded_rectangle((inner[0] + i, inner[1] + i, inner[2] - i, inner[3] - i), radius=max(2, r - 4 * ss - i),
                             outline=(70, 120, 200, a), width=1)
    img.alpha_composite(well)
    return img


def item_window(item_path=None, count=0, offset=0.0, blur=False, key=True, ss=2):
    img = item_frame(ss)
    cx = (ITEM - 4) * ss // 2
    if item_path:
        if count > 1:
            small = thumb(item_path, 18 * ss)
            spots = [(-9, 4), (9, 4), (0, -6)][:count]
            for dx, dy in spots[::-1]:
                img.alpha_composite(small, (cx + dx * ss - small.width // 2, cx + dy * ss - small.height // 2))
        else:
            t = thumb(item_path, 30 * ss)
            if blur:
                t = t.filter(ImageFilter.GaussianBlur(ss * 0.8))
            y = int(cx - t.height // 2 + offset * ss)
            clip = Image.new("RGBA", img.size, (0, 0, 0, 0))
            clip.alpha_composite(t, (cx - t.width // 2, max(0, min(img.height - t.height, y))))
            mask = Image.new("L", img.size, 0)
            ImageDraw.Draw(mask).rounded_rectangle((6 * ss, 6 * ss, img.width - 10 * ss, img.height - 10 * ss), radius=8 * ss, fill=255)
            img.paste(clip, (0, 0), ImageChops.multiply(mask, clip.getchannel("A")))
        if key:
            # A key cap in the corner: F uses it.
            k = Image.new("RGBA", (16 * ss, 16 * ss), (0, 0, 0, 0))
            kd = ImageDraw.Draw(k)
            kd.rounded_rectangle((0, 0, k.width - 1, k.height - 1), radius=3 * ss, fill=(255, 210, 63, 255), outline=(30, 30, 30, 255), width=ss)
            f = big_text("F", (40, 30, 10), (40, 30, 10), 2, outline=0, shadow=0)
            k.alpha_composite(f, ((k.width - f.width) // 2 + ss // 2, (k.height - f.height) // 2))
            img.alpha_composite(k, (img.width - k.width - 2 * ss, img.height - k.height - 2 * ss))
    return ink_right(img)


# ---- the place (bottom right) --------------------------------------------------------

PLACE_STYLE = {
    1: ((255, 238, 120), (255, 150, 20)),
    2: ((240, 246, 255), (150, 170, 200)),
    3: ((255, 200, 140), (190, 95, 40)),
}


def ordinal(n):
    return {1: "ST", 2: "ND", 3: "RD"}.get(n, "TH")


def place_badge(n):
    top, bottom = PLACE_STYLE.get(n, ((255, 255, 255), (150, 210, 255)))
    digit = big_text(str(n), top, bottom, 5, outline=3, shadow=3)
    suffix = big_text(ordinal(n), top, bottom, 2, outline=2, shadow=2)
    w = digit.width + suffix.width - 2
    img = Image.new("RGBA", (w, digit.height), (0, 0, 0, 0))
    img.alpha_composite(digit, (0, 0))
    img.alpha_composite(suffix, (digit.width - 2, 2))
    return ink_right(img)


# ---- the lap panel (top right) -------------------------------------------------------

def flag(size=12):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 1, size - 1), fill=(200, 200, 200, 255))
    c = 3
    for y in range(0, 8):
        for x in range(2, size):
            if (((x - 2) // c) + (y // c)) % 2 == 0:
                d.point((x, y), fill=(255, 255, 255, 255))
            else:
                d.point((x, y), fill=(20, 20, 20, 255))
    return img


def lap_panel(lap, of):
    """'LAP' over a chequered flag on the left, the count big beside them."""
    label = big_text("LAP", (210, 220, 235), (160, 170, 190), 1, outline=1, shadow=1)
    count = big_text(f"{lap}/{of}", (255, 255, 255), (190, 220, 255), 2, outline=2, shadow=2)
    left = max(label.width, 12)
    w = 6 + left + 5 + count.width + 5
    h = 34
    img = panel(w, h, radius=6)
    img.alpha_composite(label, (6, 3))
    img.alpha_composite(flag(), (6 + (left - 12) // 2, 19))
    img.alpha_composite(count, (6 + left + 5, (h - count.height) // 2))
    return ink_right(img)


# ---- the start lights (centre) --------------------------------------------------------

def lights(lit, green=False, ss=2):
    w, h = 96 * ss, 36 * ss
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((2 * ss, 4 * ss, w - 1, h - 1), radius=8 * ss, fill=(0, 0, 0, 100))
    d.rounded_rectangle((0, 0, w - 3 * ss, h - 4 * ss), radius=8 * ss, fill=(22, 24, 30, 245), outline=(80, 86, 98, 255), width=ss * 2)
    for i in range(3):
        cx, cy, r = (16 + i * 30) * ss, 16 * ss, 11 * ss
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(8, 8, 10, 255))
        on = green or i < lit
        col = (91, 227, 125) if green else (255, 60, 70)
        if on:
            glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
            ImageDraw.Draw(glow).ellipse((cx - r - 6 * ss, cy - r - 6 * ss, cx + r + 6 * ss, cy + r + 6 * ss), fill=col + (120,))
            img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(5 * ss)))
            d = ImageDraw.Draw(img)
            d.ellipse((cx - r + 2 * ss, cy - r + 2 * ss, cx + r - 2 * ss, cy + r - 2 * ss), fill=col + (255,))
            d.ellipse((cx - r + 5 * ss, cy - r + 4 * ss, cx - 1 * ss, cy - 2 * ss), fill=mix(col, (255, 255, 255), 0.6) + (255,))
        else:
            d.ellipse((cx - r + 2 * ss, cy - r + 2 * ss, cx + r - 2 * ss, cy + r - 2 * ss), fill=(48, 50, 58, 255))
    return ink_right(img)


# ---- banners (centre) --------------------------------------------------------------------

def ribbon(text, colour, text_top, text_bottom, ss=2, chequer=False):
    words = big_text(text, text_top, text_bottom, 3 * ss, outline=2 * ss, shadow=2 * ss)
    slant = 10 * ss
    c = 6 * ss
    # A chequered ribbon keeps three squares of flag at each end, and the words
    # clear both the flag and the slant: they used to run over the squares.
    flag = 3 * c if chequer else 0
    pad = slant + flag + 4 * ss if chequer else 20 * ss
    w, h = words.width + 2 * pad, words.height + 14 * ss
    w = min(w, 254)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.polygon([(slant, 0), (w - 1, 0), (w - 1 - slant, h - 1), (0, h - 1)], fill=colour + (235,))
    if chequer:
        for y in range(0, h, c):
            for k in range(3):
                for x in (k * c, w - flag + k * c):
                    if (k + y // c) % 2 == 0:
                        d.rectangle((x, y, x + c - 1, y + c - 1), fill=(20, 20, 20, 200))
        # Squares outside the slanted ends are cut back to the ribbon.
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).polygon([(slant, 0), (w - 1, 0), (w - 1 - slant, h - 1), (0, h - 1)], fill=255)
        img.putalpha(ImageChops.darker(img.getchannel("A"), mask))
        d = ImageDraw.Draw(img)
    d.line([(slant, 0), (w - 1, 0)], fill=(255, 255, 255, 200), width=ss)
    if words.width > w - 8:
        k = (w - 8) / words.width
        words = words.resize((int(words.width * k), int(words.height * k)), Image.LANCZOS)
    img.alpha_composite(words, ((w - words.width) // 2, (h - words.height) // 2))
    return ink_right(img)


def countdown(text, top, bottom, scale):
    return ink_right(big_text(text, top, bottom, scale, outline=4, shadow=4))


# ---- the speed gauge (bottom right), drawn at 2x ------------------------------------------

GAUGE_STEPS = 12
DRIFT_RING = [None, (79, 195, 255), (255, 157, 46), (200, 107, 255)]


def gauge(step, drift, boost, ss=2):
    w, h = 72 * ss, 40 * ss
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx, cy, r = w // 2, h - 4 * ss, 32 * ss
    # The dial: a dark half disc with a bezel.
    d.pieslice((cx - r - 3 * ss, cy - r - 3 * ss, cx + r + 3 * ss, cy + r + 3 * ss), 180, 360, fill=(12, 16, 22, 200))
    ring = DRIFT_RING[drift] if drift else ((255, 120, 40) if boost else None)
    if ring:
        glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(glow).arc((cx - r - 3 * ss, cy - r - 3 * ss, cx + r + 3 * ss, cy + r + 3 * ss), 180, 360, fill=ring + (255,), width=5 * ss)
        img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(2 * ss)))
        d = ImageDraw.Draw(img)
        d.arc((cx - r - 3 * ss, cy - r - 3 * ss, cx + r + 3 * ss, cy + r + 3 * ss), 180, 360, fill=ring + (255,), width=2 * ss)
    # The track and the fill, green to yellow to red with speed.
    d.arc((cx - r, cy - r, cx + r, cy + r), 180, 360, fill=(50, 56, 66, 255), width=6 * ss)
    for k in range(step):
        a0 = 180 + 180 * k / GAUGE_STEPS
        a1 = 180 + 180 * (k + 1) / GAUGE_STEPS - 2
        t = k / max(1, GAUGE_STEPS - 1)
        col = mix((91, 227, 125), (255, 210, 63), min(1, t * 2)) if t < 0.5 else mix((255, 210, 63), (255, 70, 85), (t - 0.5) * 2)
        if boost:
            col = mix(col, (255, 150, 40), 0.5)
        d.arc((cx - r, cy - r, cx + r, cy + r), a0, a1, fill=col + (255,), width=6 * ss)
    # Ticks.
    for k in range(GAUGE_STEPS + 1):
        a = math.radians(180 + 180 * k / GAUGE_STEPS)
        x0, y0 = cx + math.cos(a) * (r - 9 * ss), cy + math.sin(a) * (r - 9 * ss)
        x1, y1 = cx + math.cos(a) * (r - 12 * ss), cy + math.sin(a) * (r - 12 * ss)
        d.line((x0, y0, x1, y1), fill=(200, 205, 215, 200), width=ss)
    return ink_right(img)


# ---- boost speed lines (screen edges) -------------------------------------------------------

def speed_lines(frame, side, ss=1):
    import random
    rnd = random.Random(frame * 31 + (0 if side == "l" else 7))
    w, h = 120, 240
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for _ in range(26):
        y = rnd.randrange(0, h)
        length = rnd.randrange(30, 100)
        x0 = 0 if side == "l" else w - length
        a = rnd.randrange(90, 200)
        col = (255, 255, 255, a) if rnd.random() < 0.7 else (255, 200, 120, a)
        for i in range(length):
            fade = i / length if side == "r" else 1 - i / length
            d.point((x0 + i, y), fill=col[:3] + (int(col[3] * fade),))
    return ink_right(img)


# ---- the minimap ------------------------------------------------------------------------------

MAP = 96            # GUI pixels
MAP_INNER = 88      # what the track is fitted into
DOT_STEP = 2        # a dot glyph per two rows
DOT = 6


MAP_ROAD = (244, 245, 247, 255)      # the lap
MAP_BRANCH = (168, 172, 180, 255)    # a second way round
MAP_EDGE = (6, 7, 9, 255)            # the outline every road is drawn inside
MAP_SHADOW = (0, 0, 0, 120)          # what a road higher than the one under it casts


def minimap(roads, feats, start, ss=4):
    """
    roads: per road, its (u, v) samples. feats: per road, per sample, a
    (gap, height, tunnel) triple, or a bare gap flag.

    Black and white, and drawn in three dimensions: every road once, then each
    stretch that passes OVER another road again on top of it, with a shadow, so
    a bridge reads as a bridge and not as a junction. A branch is grey, a
    tunnel dashed, and the start a chequered flag.
    """
    pts = [p for r in roads for p in r]
    u0, u1 = min(p[0] for p in pts), max(p[0] for p in pts)
    v0, v1 = min(p[1] for p in pts), max(p[1] for p in pts)
    k = MAP_INNER / max(u1 - u0, v1 - v0)
    ox = (MAP - (u1 - u0) * k) / 2
    oy = (MAP - (v1 - v0) * k) / 2
    frame = {"u0": u0, "v1": v1, "k": k, "ox": ox, "oy": oy}

    def at(p, dx=0.0, dy=0.0):
        return ((p[0] - u0) * k + ox + dx) * ss, ((v1 - p[1]) * k + oy + dy) * ss

    def feat(ri, i):
        f = feats[ri][i]
        return f if isinstance(f, tuple) else (bool(f), 0.0, False)

    s = MAP * ss
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=14 * ss, fill=(8, 9, 12, 175), outline=(255, 255, 255, 70), width=ss)
    d.rounded_rectangle((ss * 2, ss * 2, s - 1 - ss * 2, s - 1 - ss * 2), radius=12 * ss, outline=(255, 255, 255, 18), width=ss)

    def runs(ri, keep):
        """The unbroken stretches of road `ri` whose samples `keep` says yes to, as index lists."""
        out, cur = [], []
        for i in range(len(roads[ri])):
            if keep(i) and not feat(ri, i)[0]:
                cur.append(i)
            elif cur:
                out.append(cur)
                cur = []
        if cur:
            out.append(cur)
        return [r for r in out if len(r) > 1]

    def stroke(ri, run, width, colour, dx=0.0, dy=0.0):
        line = [at(roads[ri][i], dx, dy) for i in run]
        d.line(line, fill=colour, width=int(width * ss), joint="curve")
        r = width * ss / 2
        for x, y in (line[0], line[-1]):
            d.ellipse((x - r, y - r, x + r, y + r), fill=colour)

    def fill(ri, run, dx=0.0, dy=0.0):
        col = MAP_ROAD if ri == 0 else MAP_BRANCH
        tunnel = [i for i in run if feat(ri, i)[2]]
        if not tunnel:
            stroke(ri, run, 4, col, dx, dy)
            return
        # A tunnel is dashed, darker: road you cannot see.
        dim = (col[0] * 3 // 5, col[1] * 3 // 5, col[2] * 3 // 5, 255)
        for part in runs(ri, lambda i: i in run and not feat(ri, i)[2]):
            stroke(ri, part, 4, col, dx, dy)
        for part in runs(ri, lambda i: i in run and feat(ri, i)[2] and (i // 3) % 2 == 0):
            stroke(ri, part, 3, dim, dx, dy)

    everything = {ri: runs(ri, lambda i: True) for ri in range(len(roads))}
    for ri, rs in everything.items():
        for run in rs:
            stroke(ri, run, 7, MAP_EDGE)
    for ri in sorted(everything, reverse=True):  # the lap last, over its branches where they join
        for run in everything[ri]:
            fill(ri, run)

    # Over: a sample three blocks or more above another road's, within a road's width.
    reach = 9.0
    flat = [(ri, i, roads[ri][i], feat(ri, i)[1]) for ri in range(len(roads)) for i in range(len(roads[ri]))]
    over = {ri: set() for ri in range(len(roads))}
    for ri, i, p, h in flat:
        for rj, j, q, g in flat:
            if h - g < 3 or abs(p[0] - q[0]) > reach or abs(p[1] - q[1]) > reach:
                continue
            if rj == ri and min(abs(i - j), len(roads[ri]) - abs(i - j)) < 30:
                continue
            if (p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 <= reach * reach:
                over[ri].update(range(max(0, i - 6), min(len(roads[ri]), i + 7)))
                break
    for ri in over:
        bridge = runs(ri, lambda i: i in over[ri])
        for run in bridge:
            stroke(ri, run, 8, MAP_SHADOW, 1.0, 1.5)
        for run in bridge:
            stroke(ri, run, 7, MAP_EDGE)
        for run in bridge:
            fill(ri, run)

    # The start: a chequered flag across the road, two squares deep.
    sx, sy = at(start[0])
    du, dv = start[1]
    along = (du * ss, -dv * ss)          # the road's direction on the map (v runs up)
    across = (dv * ss, du * ss)
    sq = 1.6
    for row in (-1, 0):
        for col in range(-3, 3):
            c = (sx + (col + 0.5) * sq * across[0] + (row + 0.5) * sq * along[0],
                 sy + (col + 0.5) * sq * across[1] + (row + 0.5) * sq * along[1])
            h = sq / 2
            corners = [(c[0] + (x * across[0] + y * along[0]) * h, c[1] + (x * across[1] + y * along[1]) * h)
                       for x, y in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
            d.polygon(corners, fill=(255, 255, 255, 255) if (row + col) % 2 == 0 else (0, 0, 0, 255))
    img = img.resize((MAP * 2, MAP * 2), Image.LANCZOS)
    return ink_right(img), frame


def dot(colour, me):
    size = DOT + (2 if me else 0)
    ss = 4
    img = Image.new("RGBA", (size * ss, size * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((0, 0, size * ss - 1, size * ss - 1), fill=(255, 255, 255, 255) if me else (12, 14, 18, 255))
    inset = (2 if me else 1) * ss
    d.ellipse((inset, inset, size * ss - 1 - inset, size * ss - 1 - inset), fill=colour + (255,))
    return ink_right(img.resize((size, size), Image.LANCZOS))


# ---------------------------------------------------------------------------

def pack(cells):
    """Cells of one size into a sheet RP Engine can cut: at most 16 rows and columns."""
    n = len(cells)
    cols = min(16, math.ceil(math.sqrt(n)))
    rows = math.ceil(n / cols)
    assert rows <= 16, n
    w, h = cells[0].size
    sheet = Image.new("RGBA", (w * cols, h * rows), (0, 0, 0, 0))
    for i, c in enumerate(cells):
        sheet.paste(c, ((i % cols) * w, (i // cols) * h))
    return sheet, rows, cols


def build(font_dir, thumbs, maps):
    """Writes the textures into `font_dir` and returns the icons.yml lines and the minimap frames."""
    os.makedirs(font_dir, exist_ok=True)
    out = ["# ---- The HUD's art, from tools/hud_art.py ----", ""]

    def save(img, name):
        img.save(os.path.join(font_dir, name + ".png"))

    def icon(name, file, h, ascent=7, grid=None):
        out.append(f"{name}:")
        out.append(f"  file: {file}")
        if grid:
            rows, cols, cell = grid
            out.append(f"  grid: {{ rows: {rows}, columns: {cols}, cell: {cell} }}")
        out.append(f"  height: {h}")
        out.append(f"  ascent: {min(ascent, h)}")
        out.append("")

    def picture(name, img, h=None, ascent=7):
        save(img, name)
        icon(name, name, h or img.height, ascent)

    out += ["# Lettering, hung from the line's top: t_<colour><scale>_<char>.", ""]
    for sheet, (colour, scales) in LETTERING.items():
        img, rows, cols = pack([letter(ch, colour) for ch in CHARSET])
        save(img, f"type_{sheet}")
        for s in scales:
            for i, ch in enumerate(CHARSET):
                icon(f"t_{sheet}{s}_{char_id(ch)}", f"type_{sheet}", CELL_H * s, 7, (rows, cols, i + 1))

    out += ["# The item window: empty, each item, the roulette's frames.", ""]
    picture("item_empty", item_window(), ITEM)
    for name, path in (("banana", thumbs["banana"]), ("nitro", thumbs["nitro"]), ("rocket", thumbs["rocket"])):
        picture(f"item_{name}", item_window(path), ITEM)
    picture("item_triple_3", item_window(thumbs["nitro"], 3), ITEM)
    picture("item_triple_2", item_window(thumbs["nitro"], 2), ITEM)
    spin = [thumbs["item_box"], thumbs["banana"], thumbs["nitro"], thumbs["rocket"]]
    for i in range(8):
        picture(f"item_roll_{i}", item_window(spin[i % 4], offset=(i % 2) * 6 - 3, blur=True, key=False), ITEM)

    out += ["# The place, bottom right: 1ST to 8TH.", ""]
    for n in range(1, 9):
        img = place_badge(n)
        picture(f"place_{n}", img)

    out += ["# The lap panel, top right, for races of up to five laps.", ""]
    for of in range(1, 6):
        for lap in range(1, of + 1):
            picture(f"lap_{lap}_{of}", lap_panel(lap, of))

    out += ["# The start lights: 0 to 3 lit red, then green. Drawn at 2x.", ""]
    for lit in range(4):
        picture(f"lights_{lit}", lights(lit), 36)
    picture("lights_go", lights(0, True), 36)

    out += ["# Banners and the countdown, centre. Drawn at 2x.", ""]
    for name, text, colour, top, bottom, chequer in (
            ("banner_final", "FINAL LAP", (200, 30, 50), (255, 255, 255), (255, 210, 210), False),
            ("banner_finish", "FINISH!", (250, 250, 250), (40, 40, 40), (10, 10, 10), True),
            ("banner_wrong", "WRONG WAY", (230, 60, 30), (255, 255, 255), (255, 220, 200), False),
            ("banner_lap", "NEW LAP", (40, 120, 230), (255, 255, 255), (210, 230, 255), False),
            ("banner_best", "BEST LAP!", (240, 180, 20), (255, 255, 255), (255, 240, 200), False)):
        # FINISH! is drawn at 1x, shown at 1x: at 2x the 254-texel cap squeezed
        # its words onto the chequered ends. Twice as wide on screen, too.
        ss = 1 if chequer else 2
        img = ribbon(text, colour, top, bottom, ss=ss, chequer=chequer)
        picture(name, img, img.height // ss)
    for text, top, bottom in (("3", (255, 120, 120), (220, 30, 40)), ("2", (255, 200, 120), (230, 120, 20)),
                              ("1", (255, 240, 140), (230, 190, 20)), ("GO!", (180, 255, 190), (40, 190, 80))):
        for k, scale in enumerate((10, 8, 7)):
            img = countdown(text, top, bottom, scale)
            name = "count_" + ("go" if text == "GO!" else text) + f"_{k}"
            picture(name, img, img.height // 2)

    out += ["# The speed gauge: speed steps by drift level (0 none, 1-3) by boost. Drawn at 2x.", ""]
    for drift in range(4):
        for boost in (0, 1):
            if drift and boost:
                continue
            for step in range(GAUGE_STEPS + 1):
                picture(f"gauge_{step}_{drift}_{boost}", gauge(step, drift, bool(boost)), 40)

    out += ["# The speed's digits, hung 22 pixels down so they sit inside the gauge: g_<digit>.", ""]
    for i, ch in enumerate(CHARSET[:10]):
        rows, cols = 1, 1
        cell = letter(ch, WHITE)
        save(cell, f"gdigit_{ch}")
        icon(f"g_{ch}", f"gdigit_{ch}", CELL_H, 7 - 22)

    out += ["# Speed lines while boosting, at the screen's edges.", ""]
    for side in ("l", "r"):
        for k in range(3):
            picture(f"lines_{side}_{k}", speed_lines(k, side), 240)

    out += ["# Kart colour chips for the standings.", ""]
    for name, rgb in KART_COLOURS.items():
        chip = Image.new("RGBA", (6, 6), rgb + (255,))
        ImageDraw.Draw(chip).rectangle((0, 0, 5, 5), outline=(10, 12, 16, 255))
        picture(f"chip_{name}", chip, 6, 6)

    out += ["# The minimaps, one per prefab, and the dots: one glyph per colour and per two rows.", ""]
    frames = {}
    for prefab_id, (roads, gaps, start) in maps.items():
        img, frame = minimap(roads, gaps, start)
        frames[prefab_id] = frame
        save(img, f"map_{prefab_id}")
        # The map's top on the line's top + 4; a dot's ascent puts its centre on its row.
        icon(f"map_{prefab_id}", f"map_{prefab_id}", MAP, 3)
    for name, rgb in KART_COLOURS.items():
        for me in (False, True):
            img = dot(rgb, me)
            file = f"dot_{name}" + ("_me" if me else "")
            save(img, file)
            size = img.height
            for row in range(0, MAP, DOT_STEP):
                # Row `row` of the map is 4 + row below the line's top; the glyph's top is 7 - ascent below it.
                ascent = 3 - row + size // 2
                icon(f"{file}_{row // DOT_STEP}", file, size, ascent)
    return out, frames
