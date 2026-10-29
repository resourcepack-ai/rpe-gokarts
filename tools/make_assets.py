"""
Builds every asset in the gokarts content folder from what ResourcePack AI
Studio made for it (tools/src):

  python tools/make_assets.py

- the kart: one Studio model, rigged in Studio's vehicle editor ("Animate with
  AI": wheels, steering and the driver's four emotes), painted here in eight
  colours. Each colour is its own item and its own vehicle, because a kart is
  told apart on the grid by its paint;
- the item box, the banana, the nitro and the rocket: Studio models, given
  namespaced textures;
- the driver's emotes, out of Studio's emote manifest (tools/src/
  studio_emotes_compact.json, keyframes packed one per "time x y z interp");
- the sounds: Studio's, trimmed, faded and levelled, the two engine loops
  crossfaded so they repeat without a click;
- the HUD's glyphs: tools/hud_art.py, the item cards drawn from Studio's own
  thumbnails of the models (tools/src/thumbs).

Deterministic: a re-run writes the same files.
"""
import colorsys
import json
import os

import numpy as np
import soundfile as sf
from PIL import Image

import hud_art

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "src")
OUT = os.path.join(ROOT, "..", "src", "main", "resources", "content", "gokarts")
A = os.path.join(OUT, "assets")
NS = "gokarts"

# The plugin's race/KartColour.java has the same list and the same colours.
COLOURS = {
    "red": (0xFF, 0x46, 0x55), "blue": (0x2E, 0x9B, 0xFF), "green": (0x5B, 0xE3, 0x7D), "yellow": (0xFF, 0xD2, 0x3F),
    "orange": (0xFF, 0x8A, 0x3D), "purple": (0xA4, 0x5C, 0xFF), "pink": (0xFF, 0x5C, 0xC8), "cyan": (0x3D, 0xE0, 0xE0),
}
CODES = {"red": "c", "blue": "9", "green": "a", "yellow": "e", "orange": "6", "purple": "5", "pink": "d", "cyan": "b"}

# How a kart is held and shown in an inventory: it is two blocks long.
KART_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, -1.5, 0], "scale": [0.38, 0.38, 0.38]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.45, 0.45, 0.45]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.25, 0.25, 0.25]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.3, 0.3, 0.3]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 14, 0], "scale": [0.6, 0.6, 0.6]},
}
PROP_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [2, 2, 0], "scale": [0.5, 0.5, 0.5]},
}


def path(*p):
    full = os.path.join(A, *p)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    return full


def save_json(obj, *p):
    with open(path(*p), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)


def write_text(text, *p):
    full = os.path.join(OUT, *p)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def load(name):
    with open(os.path.join(SRC, name), encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# The kart, in eight colours
# ---------------------------------------------------------------------------

def is_body_red(r, g, b):
    """The bodywork: Studio painted it red, and nothing else on the kart is."""
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    return (h < 0.06 or h > 0.92) and s > 0.45 and v > 0.25


def paint(atlas, rgb):
    """The atlas with its red bodywork repainted in `rgb`, keeping every pixel's shading."""
    if rgb == COLOURS["red"]:
        return atlas.copy()
    th, ts, tv = colorsys.rgb_to_hsv(*(c / 255 for c in rgb))
    base_v = colorsys.rgb_to_hsv(*(c / 255 for c in COLOURS["red"]))[2]
    out = atlas.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0 or not is_body_red(r, g, b):
                continue
            _, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            nv = min(1.0, v / base_v * tv)
            ns = min(1.0, s * (ts / 0.73) if ts < 0.73 else s)
            nr, ng, nb = colorsys.hsv_to_rgb(th, ns, nv)
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return out


def karts():
    model = load("go_kart_generated.json")
    atlas = Image.open(os.path.join(SRC, "go_kart_atlas.png")).convert("RGBA")
    items, vehicles = [], []
    rig = rig_settings()
    for colour, rgb in COLOURS.items():
        paint(atlas, rgb).save(path("textures", "block", f"kart_{colour}.png"))
        m = json.loads(json.dumps(model))
        m["textures"] = {"atlas": f"{NS}:block/kart_{colour}", "particle": f"{NS}:block/kart_{colour}"}
        m["display"] = KART_DISPLAY
        save_json(m, "models", f"kart_{colour}.json")
        name = colour.capitalize()
        items.append(f"""kart_{colour}_item:
  material: PAPER
  model: kart_{colour}
  name: "&{CODES[colour]}&l{name} Kart"
  lore:
    - "&7Right-click the ground to park it,"
    - "&7then right-click it to get in."
""")
        vehicles.append(vehicle_yaml(colour, name, rig))
    write_text("# The karts you carry. Right-click the ground with one and RP Engine parks\n"
               "# the vehicle, because each is the item its vehicle's `model:` names.\n"
               "# Generated by tools/make_assets.py.\n\n" + "\n".join(items), "items", "karts.yml")
    write_text(VEHICLES_HEADER + "\n".join(vehicles), "vehicles", "karts.yml")


def rig_settings():
    """What Studio's vehicle editor worked out for the kart, in blocks (model-subtypes.ts' vehicleOffsetToBlocks)."""
    return {
        # The seat at model pixel (8, 5, 6): x is -(px-8)/16, y px/16, z (px-8)/16.
        "seat": (0.0, 0.3125, -0.125),
        # The exhaust tip at (12, 5, -7.5).
        "exhaust": (-0.25, 0.3125, -0.969),
        "hitbox": (1.5, 0.75, 2.0),
    }


VEHICLES_HEADER = """# Go karts: one vehicle per paint, every one the same Studio kart. Rigged in
# ResourcePack AI Studio's vehicle editor (the wheels, the steering and the
# driver's emotes, emotes/gokarts.json). Generated by tools/make_assets.py.
#
# `speed` is the CEILING a boost may reach. The plugin holds a kart to
# config.yml's karts.cruise-speed the rest of the time (and to offroad-speed on
# grass), so change that rather than this to make karts faster.
#
# Space is the handbrake (jump: false), which is what lets the rear step out:
# the plugin turns holding it through a corner into a drift boost.

"""


def vehicle_yaml(colour, name, rig):
    sx, sy, sz = rig["seat"]
    ex, ey, ez = rig["exhaust"]
    w, h, l = rig["hitbox"]
    return f"""kart_{colour}:
  model: {NS}:kart_{colour}_item
  name: "&{CODES[colour]}{name} Kart"
  permission: gokarts.ride
  medium: land
  speed: 19
  acceleration: 9
  coast: 1.4
  turn-speed: 190
  weight: 8
  scale: 1
  jump: false
  capes: false
  # Off: in a race the HUD is in the action bar, and the engine's readout there
  # would take turns with it. Out of a race the plugin writes its own speed
  # line, with the drift charge in it.
  speedometer: false
  # The wheels turn because the kart moved: at a standstill they stop.
  animation-follows-speed: true
  hitbox:
    width: {w}
    height: {h}
    length: {l}
  seats:
    - role: driver
      x: {sx}
      y: {sy}
      z: {sz}
      pose: sitting
      animations:
        turning: gokarts_drive_turning
        reversing: gokarts_drive_reversing
        moving: gokarts_drive_moving
        idle: gokarts_drive_idle
  animations:
    turning: turning
    reversing: reversing
    moving: moving
    idle: idle
  sounds:
    idle: {NS}:idle
    moving: {NS}:engine
  particles:
    - effect: smoke
      states: [moving, reversing, idle]
      x: {ex}
      y: {ey}
      z: {ez}
      count: 1
      interval: 3
      spread: 0.04
      speed: 0.01
"""


# ---------------------------------------------------------------------------
# The props
# ---------------------------------------------------------------------------

PROPS = {
    # id: (Studio model, item name, lore)
    "item_box": ("kart_item_box", "&d&lItem Box", ["&7Drive through it for an item."]),
    "banana": ("kart_banana", "&e&lBanana", ["&7Drop it behind you. Whoever hits it spins out."]),
    "nitro": ("kart_nitro", "&9&lNitro", ["&7A boost, whenever you like."]),
    "rocket": ("kart_rocket", "&c&lRocket", ["&7Fired at the kart ahead of you."]),
}


def props():
    lines = ["# What the plugin draws in the world and puts in a racer's hand: item boxes,",
             "# bananas, nitro and rockets. Studio models. Generated by tools/make_assets.py.", ""]
    for pid, (src, name, lore) in PROPS.items():
        m = load(f"{src}_generated.json")
        Image.open(os.path.join(SRC, f"{src}_atlas.png")).convert("RGBA").save(path("textures", "block", f"{pid}.png"))
        m["textures"] = {"atlas": f"{NS}:block/{pid}", "particle": f"{NS}:block/{pid}"}
        m["display"] = PROP_DISPLAY
        m.pop("groups", None)
        save_json(m, "models", f"{pid}.json")
        lines.append(f"{pid}:")
        lines.append("  material: PAPER")
        lines.append(f"  model: {pid}")
        lines.append(f'  name: "{name}"')
        lines.append("  lore:")
        for line in lore:
            lines.append(f'    - "{line}"')
        lines.append("")
    # Nothing in hand: an invisible item, so the hotbar slot a racer's item lives
    # in always holds SOMETHING, which is what makes Q and the clicks reach the
    # plugin at all.
    blank = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    blank.putpixel((15, 0), (255, 255, 255, 1))
    blank.save(path("textures", "item", "blank.png"))
    lines += ["no_item:", "  material: PAPER", '  name: "&7No item"', "  lore:",
              '    - "&7Drive through an item box."', "  texture: item/blank", ""]
    write_text("\n".join(lines), "items", "props.yml")


# ---------------------------------------------------------------------------
# Emotes
# ---------------------------------------------------------------------------

EMOTE_IDS = {
    "driver_idle-2": "gokarts_drive_idle",
    "driver_moving-2": "gokarts_drive_moving",
    "driver_reversing-2": "gokarts_drive_reversing",
    "driver_turning-2": "gokarts_drive_turning",
}
INTERP = {"s": "smooth", "l": "linear", "t": "step"}


def channels(packed):
    out = {}
    for ch, frames in packed.items():
        keys = []
        for frame in frames.split("|"):
            t, x, y, z, i = frame.split(" ")
            keys.append({"time": float(t), "value": [float(x), float(y), float(z)], "interpolation": INTERP[i]})
        out[ch] = keys
    return out


def emotes():
    src = load("studio_emotes_compact.json")
    out = {}
    for e in src:
        out[EMOTE_IDS[e["id"]]] = {
            "name": "Go kart - " + e["n"].split("—")[-1].strip(),
            "length": e["l"],
            "loop": e["lp"],
            "root": channels(e["r"]),
            "animators": {bone: channels(ch) for bone, ch in e["a"].items()},
        }
    full = os.path.join(OUT, "emotes", "gokarts.json")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)


# ---------------------------------------------------------------------------
# Sounds
# ---------------------------------------------------------------------------

# id: (source, longest it may run in seconds, volume, subtitle, loop). An id is
# unique across the whole pack, items included, so none is an item's name.
SOUNDS = {
    "engine": ("engine", None, 0.55, "Kart engine revs", True),
    "idle": ("idle", None, 0.45, "Kart engine idles", True),
    "boost": ("boost", 1.6, 1.0, "Boost", False),
    "spark": ("drift_spark", 0.9, 0.7, "Drift sparks", False),
    "smash": ("item_box", 1.5, 0.9, "Item box smashes", False),
    "item_got": ("item_got", 0.7, 0.9, "Item ready", False),
    "spinout": ("spinout", 1.6, 1.0, "Kart spins out", False),
    "blast": ("explosion", 1.8, 1.0, "Rocket explodes", False),
    "launch": ("rocket_fire", 1.3, 0.9, "Rocket fired", False),
    "beep": ("beep", None, 1.0, "Start light", False),
    "go": ("go", None, 1.0, "Go!", False),
    "lap": ("lap", None, 0.9, "Lap complete", False),
    "finish": ("finish", None, 1.0, "Race finished", False),
    "bump": ("bump", 0.7, 0.8, "Karts bump", False),
}


def sounds():
    lines = ["# Generated in ResourcePack AI Studio; trimmed and levelled by tools/make_assets.py.", ""]
    for sid, (src, longest, volume, subtitle, loop) in SOUNDS.items():
        data, sr = sf.read(os.path.join(SRC, "sounds", src + ".ogg"), always_2d=True)
        mono = data.mean(axis=1)
        if longest:
            mono = mono[: int(longest * sr)]
        if loop:
            # Crossfade the tail into the head, so the repeat has no seam.
            n = int(0.12 * sr)
            head, tail = mono[:n].copy(), mono[-n:]
            ramp = np.linspace(0, 1, n)
            mono = mono[n:].copy()
            mono[-n:] = tail * (1 - ramp) + head * ramp
        else:
            # Trim trailing quiet, then a short fade so nothing ends in a click.
            env = np.abs(mono)
            loud = np.nonzero(env > 0.02 * max(1e-6, env.max()))[0]
            if len(loud):
                mono = mono[: min(len(mono), loud[-1] + int(0.08 * sr))]
            fade = min(len(mono), int(0.12 * sr))
            mono[-fade:] *= np.linspace(1, 0, fade)
        peak = np.abs(mono).max()
        if peak > 0:
            mono = mono / peak * 0.9
        out = path("sounds", sid + ".ogg")
        sf.write(out, mono.astype(np.float32), sr, format="OGG", subtype="VORBIS")
        length = round(len(mono) / sr, 3)
        lines.append(f"{sid}:")
        lines.append(f"  file: {sid}")
        lines.append("  category: neutral")
        lines.append(f'  subtitle: "{subtitle}"')
        if volume != 1.0:
            lines.append(f"  volume: {volume}")
        if loop:
            lines.append(f"  length: {length}")
        lines.append("")
    write_text("\n".join(lines), "sounds", "sounds.yml")


# ---------------------------------------------------------------------------

def icons():
    thumbs = {pid: os.path.join(SRC, "thumbs", f"{src}.png") for pid, (src, _, _) in PROPS.items()}
    out = ["# Glyphs the plugin writes into the HUD (huds/hud.json). Generated by",
           "# tools/make_assets.py and tools/hud_art.py: edit those, not this.", ""]
    out += hud_art.build(os.path.join(A, "textures", "font"), thumbs)
    write_text("\n".join(out), "fonts", "icons.yml")


def pack_yml():
    write_text("""name: Go Karts
author: ResourcePack AI
version: 0.1.0
# Installed by the RPEGoKarts addon from its own jar. Edit freely - a newer
# plugin build overwrites its own files and leaves anything you add.
""", "pack.yml")


if __name__ == "__main__":
    pack_yml()
    karts()
    props()
    emotes()
    sounds()
    icons()
    print("Wrote", os.path.normpath(OUT))
