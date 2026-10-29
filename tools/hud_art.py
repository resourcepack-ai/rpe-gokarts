"""
The HUD's art, drawn here: everything the plugin writes into the HUD's runs
that is a picture rather than words. make_assets.py calls build() and puts the
icon definitions it returns into fonts/icons.yml.

A run is one colour, and that colour is its address in the HUD's shader, so a
picture in more than one colour has to arrive already coloured, in a run drawn
white. That is why the lettering below comes in sheets per colour.

The rules every glyph here follows are Paintball's (rpe-addons/paintball/tools/
hud_art.py, whose lettering this is, with / : and . added for lap counts and
times):

- **Inked in its rightmost column.** The game measures a bitmap glyph's
  advance from its last non-transparent column and RP Engine measures it from
  the cell; the two agree only when the cell's last column has something in
  it, so a glyph that does not reach it gets a pixel nobody can see (FAINT).
- **Vertically centred.** Every glyph here uses ascent = h / 2 + 3, which puts
  its middle on the middle of the line, so the HUD places a picture by where
  its middle goes and pictures of different sizes in one run line up on their
  centres.
- **At most 256 texels on a side**, the font atlas's page.
"""
import math
import os

from PIL import Image, ImageDraw

FAINT = (255, 255, 255, 1)
OUTLINE = (10, 12, 16, 235)

WHITE = (255, 255, 255)
GOLD = (255, 210, 63)
SILVER = (200, 210, 222)
BRONZE = (222, 140, 82)
GREEN = (91, 227, 125)
RED = (255, 70, 85)
GREY = (170, 176, 184)

# ---------------------------------------------------------------------------
# Lettering: 7x11 capitals with two-pixel strokes and corners cut, drawn with a
# one-pixel outline, so 9x13 cells.
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
}

# Every character a lettering sheet holds, in sheet order. The plugin names a
# glyph by character (Hud.java's LETTERS), so this string is the contract.
CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ!-'/:."
CELL_W, CELL_H = 9, 13


def shade(rgb, k):
    return tuple(max(0, min(255, int(round(c * k)))) for c in rgb)


def letter(ch, colour):
    """One 9x13 cell: the character in `colour`, lighter at the top, outlined."""
    rows = DIGITS.get(ch) or CAPS[ch]
    img = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
    px = img.load()
    ink = set()
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            if c == "#":
                ink.add((x + 1, y + 1))
    for x, y in ink:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                q = (x + dx, y + dy)
                if q not in ink and 0 <= q[0] < CELL_W and 0 <= q[1] < CELL_H:
                    px[q] = OUTLINE
    for x, y in ink:
        k = 1.06 - (y - 1) / 10 * 0.24
        px[x, y] = shade(colour, k) + (255,)
    if not any(px[CELL_W - 1, y][3] for y in range(CELL_H)):
        px[CELL_W - 1, 0] = FAINT
    return img


# (sheet, colour) and the scales each is used at (Hud.java asks for exactly these).
LETTERING = {
    "white": (WHITE, (1, 2, 3, 4)),
    "gold": (GOLD, (2, 3, 4)),
    "silver": (SILVER, (4,)),
    "bronze": (BRONZE, (4,)),
    "green": (GREEN, (3, 4, 5)),
    "red": (RED, (4, 5)),
    "grey": (GREY, (1, 2)),
}


def char_id(ch):
    return {"!": "bang", "-": "dash", "'": "apos", "/": "slash", ":": "colon", ".": "dot"}.get(ch, ch.lower())


def centred_ascent(h):
    """The ascent that puts a glyph h tall with its middle on the line's middle."""
    return h // 2 + 3


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


def ink_right(img):
    if not any(img.getpixel((img.width - 1, y))[3] for y in range(img.height)):
        img.putpixel((img.width - 1, 0), FAINT)
    return img


# ---------------------------------------------------------------------------
# The start lights: a lens in a dark housing, off, red or green. Drawn at four
# times the pixel and brought down, so the circle is round.
# ---------------------------------------------------------------------------

LIGHT = 22


def light(colour, lit):
    ss = 4
    s = LIGHT * ss
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((0, 0, s - 1, s - 1), fill=(14, 16, 20, 235))
    pad = 3 * ss
    if lit:
        d.ellipse((pad, pad, s - 1 - pad, s - 1 - pad), fill=colour + (255,))
        hi = shade(colour, 1.35)
        d.ellipse((pad + 3 * ss, pad + 2 * ss, pad + 8 * ss, pad + 6 * ss), fill=hi + (255,))
    else:
        d.ellipse((pad, pad, s - 1 - pad, s - 1 - pad), fill=(52, 56, 64, 255))
    img = img.resize((LIGHT, LIGHT), Image.LANCZOS)
    return ink_right(img)


# ---------------------------------------------------------------------------
# The item card, top left: a rounded dark card with a white edge and the item
# in it, drawn from the item's own Studio thumbnail.
# ---------------------------------------------------------------------------

CARD = 36
CARD_FILL = (14, 17, 22, 190)


def card_base():
    ss = 4
    s = CARD * ss
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, s - 1, s - 1), radius=7 * ss, fill=(255, 255, 255, 235))
    d.rounded_rectangle((2 * ss, 2 * ss, s - 1 - 2 * ss, s - 1 - 2 * ss), radius=5 * ss, fill=CARD_FILL)
    return img.resize((CARD, CARD), Image.LANCZOS)


def thumb(path, size):
    src = Image.open(path).convert("RGBA")
    box = src.getbbox() or (0, 0, src.width, src.height)
    src = src.crop(box)
    k = size / max(src.width, src.height)
    return src.resize((max(1, round(src.width * k)), max(1, round(src.height * k))), Image.LANCZOS)


def card(item_path=None, count=0):
    img = card_base()
    if item_path:
        if count > 1:
            small = thumb(item_path, 15)
            spots = [(4, 13), (17, 13), (10, 5)][:count]
            for x, y in spots:
                img.alpha_composite(small, (x + (15 - small.width) // 2, y + (15 - small.height) // 2))
        else:
            t = thumb(item_path, 26)
            img.alpha_composite(t, ((CARD - t.width) // 2, (CARD - t.height) // 2))
    return ink_right(img)


def build(font_dir, thumbs):
    """Writes the textures into `font_dir` and returns the icons.yml lines for them."""
    os.makedirs(font_dir, exist_ok=True)

    def save(img, name):
        img.save(os.path.join(font_dir, name + ".png"))

    out = ["# ---- The HUD's art, from tools/hud_art.py ----", ""]

    def icon(name, file, h, grid=None):
        out.append(f"{name}:")
        out.append(f"  file: {file}")
        if grid:
            rows, cols, cell = grid
            out.append(f"  grid: {{ rows: {rows}, columns: {cols}, cell: {cell} }}")
        out.append(f"  height: {h}")
        out.append(f"  ascent: {centred_ascent(h)}")
        out.append("")

    out += ["# Lettering, centred on the line: t_<colour><scale>_<char>, e.g. t_gold4_1.", ""]
    for sheet, (colour, scales) in LETTERING.items():
        img, rows, cols = pack([letter(ch, colour) for ch in CHARSET])
        save(img, f"type_{sheet}")
        for s in scales:
            for i, ch in enumerate(CHARSET):
                icon(f"t_{sheet}{s}_{char_id(ch)}", f"type_{sheet}", CELL_H * s, (rows, cols, i + 1))

    out += ["# The start lights.", ""]
    for name, colour, lit in (("light_off", (0, 0, 0), False), ("light_red", RED, True), ("light_green", GREEN, True)):
        save(light(colour, lit), name)
        icon(name, name, LIGHT)

    out += ["# The item card (top left), one per item and per nitro left.", ""]
    cards = {
        "card_empty": (None, 0),
        "card_box": (thumbs["item_box"], 1),
        "card_banana": (thumbs["banana"], 1),
        "card_nitro": (thumbs["nitro"], 1),
        "card_rocket": (thumbs["rocket"], 1),
        "card_triple_3": (thumbs["nitro"], 3),
        "card_triple_2": (thumbs["nitro"], 2),
    }
    for name, (path, count) in cards.items():
        save(card(path, count), name)
        icon(name, name, CARD)
    return out
