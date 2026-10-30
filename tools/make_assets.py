"""
Builds every asset in the gokarts content folder from what ResourcePack AI
Studio made for it (tools/src), offline:

  python tools/make_assets.py

- the kart: one Studio model (kart_mk2), rigged in Studio's vehicle editor
  ("Animate with AI": wheels, steering, five driving states and the driver's
  emotes) and given a trick, a spin-out and a boost of its own. White bodywork,
  red accents; the accents are repainted here in eight colours, and each colour
  is its own item and its own vehicle;
- the helmet: a Studio model, turned to face the way a rig does and painted in
  the same eight colours. Every driver emote carries it on the head, as a prop;
- the item box, the banana, the nitro and the rocket: Studio models;
- the driver's emotes (Studio's emote manifest, tools/src/emotes.json): every
  state, the win and the daze, once per colour, each wearing its helmet;
- the VFX (tools/src/vfx/*.json, Studio's recipes): copied, and the drift's
  mini-turbo painted in its three levels' colours;
- the sounds: Studio's, trimmed, faded and levelled, the engine loops crossfaded;
- the HUD's art (tools/hud_art.py), with a minimap for each prefab track drawn
  from src/main/resources/prefabs by tools/prefabs.py, and the minimaps'
  frames written to prefabs/minimap.json for the plugin to put dots on.

Deterministic: a re-run writes the same files.
"""
import colorsys
import json
import os
import shutil

import numpy as np
import soundfile as sf
from PIL import Image

import hud_art
import prefabs

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "src")
RES = os.path.join(ROOT, "..", "src", "main", "resources")
OUT = os.path.join(RES, "content", "gokarts")
A = os.path.join(OUT, "assets")
NS = "gokarts"

# The plugin's race/KartColour.java has the same list and the same colours.
COLOURS = dict(hud_art.KART_COLOURS)
CODES = {"red": "c", "blue": "9", "green": "a", "yellow": "e", "orange": "6", "purple": "5", "pink": "d", "cyan": "b"}

KART_DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, -1.5, 0], "scale": [0.36, 0.36, 0.36]},
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
    "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}

# What Studio's vehicle editor worked out for kart_mk2, converted as model-subtypes.ts'
# vehicleOffsetToBlocks does: x is -(px-8)/16, y px/16, z (px-8)/16.
SEAT = (0.0, 10 / 16, (6 - 8) / 16)          # the seat at model pixel (8, 10, 6)
EXHAUST = (-(14 - 8) / 16, 9 / 16, (-9.5 - 8) / 16)
HITBOX = (1.5, 1.25, 2.25)
STATES = ["airborne", "turning", "reversing", "moving", "idle"]


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


def clean():
    """Everything this script writes, gone first: a file it no longer makes must not linger in the jar."""
    for rel in ("assets/models", "assets/textures", "assets/sounds", "emotes", "vfx", "vehicles", "items", "sounds", "fonts"):
        shutil.rmtree(os.path.join(OUT, rel), ignore_errors=True)


# ---------------------------------------------------------------------------
# Paint
# ---------------------------------------------------------------------------

def is_red(r, g, b):
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    return (h < 0.06 or h > 0.92) and s > 0.4 and v > 0.2


def paint(atlas, rgb, base=COLOURS["red"]):
    """The atlas with its red parts repainted in `rgb`, keeping every pixel's shading."""
    if rgb == base:
        return atlas.copy()
    th, ts, tv = colorsys.rgb_to_hsv(*(c / 255 for c in rgb))
    base_v = colorsys.rgb_to_hsv(*(c / 255 for c in base))[2]
    out = atlas.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0 or not is_red(r, g, b):
                continue
            _, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            nv = min(1.0, v / base_v * tv)
            ns = min(1.0, s * (ts / 0.73) if ts < 0.73 else s)
            nr, ng, nb = colorsys.hsv_to_rgb(th, ns, nv)
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return out


# ---------------------------------------------------------------------------
# The karts
# ---------------------------------------------------------------------------

VEHICLES_HEADER = """# Go karts: one vehicle per paint, every one the same Studio kart (kart_mk2).
# Rigged in ResourcePack AI Studio's vehicle editor: the wheels, the steering,
# five driving states, and the driver's emotes (emotes/gokarts.json), which
# carry the driver's helmet in the kart's colour. Generated by tools/make_assets.py.
#
# `speed` is the CEILING a boost may reach. The plugin holds a kart to
# config.yml's karts.cruise-speed the rest of the time (and to offroad-speed on
# grass), so change that rather than this to make karts faster.
#
# Space is the handbrake (jump: false), which is what lets the rear step out:
# the plugin turns holding it through a corner into a drift boost.

"""


def icon(name, rgb=None):
    """
    An inventory icon (tools/src/icons/<name>.png, Studio's 16x16 item model,
    drawn red where it has a colour) into textures/item, repainted in `rgb`.
    The items name it as `gui:`, so a slot shows it rather than the 3D model
    shrunk to a smudge; held, placed or worn, the model is still drawn.
    """
    img = Image.open(os.path.join(SRC, "icons", f"{name}.png")).convert("RGBA")
    file = name if rgb is None else f"{name}_{next(c for c, v in COLOURS.items() if v == rgb)}"
    (img if rgb is None else paint(img, rgb)).save(path("textures", "item", f"icon_{file}.png"))
    return f"  gui: {{texture: icon_{file}}}"


def karts():
    model = load("kart_mk2_generated.json")
    atlas = Image.open(os.path.join(SRC, "kart_mk2_atlas.png")).convert("RGBA")
    items, vehicles = [], []
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
{icon("kart", rgb)}
  name: "&{CODES[colour]}&l{name} Kart"
  lore:
    - "&7Right-click the ground to park it,"
    - "&7then right-click it to get in."
""")
        vehicles.append(vehicle_yaml(colour, name))
    write_text("# The karts you carry. Right-click the ground with one and RP Engine parks\n"
               "# the vehicle, because each is the item its vehicle's `model:` names.\n"
               "# Generated by tools/make_assets.py.\n\n" + "\n".join(items), "items", "karts.yml")
    write_text(VEHICLES_HEADER + "\n".join(vehicles), "vehicles", "karts.yml")


def vehicle_yaml(colour, name):
    sx, sy, sz = SEAT
    ex, ey, ez = EXHAUST
    w, h, l = HITBOX
    seat = "\n".join(f"        {s}: gokarts_{colour}_{s}" for s in STATES)
    body = "\n".join(f"    {s}: {s}" for s in STATES)
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
  # would take turns with it. Out of a race the plugin writes its own.
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
      y: {sy:.4f}
      z: {sz:.4f}
      pose: sitting
      animations:
{seat}
  animations:
{body}
  sounds:
    idle: {NS}:idle
    moving: {NS}:engine
  particles:
    - effect: smoke
      states: [moving, reversing, idle]
      x: {ex:.4f}
      y: {ey:.4f}
      z: {ez:.4f}
      count: 1
      interval: 3
      spread: 0.04
      speed: 0.01
"""


# ---------------------------------------------------------------------------
# The helmet
# ---------------------------------------------------------------------------

# A player's head is 8 pixels on a side, its centre 12 above the rig's origin
# (a block above the feet). The helmet's hollow is 4..12 across and 0..9 up, so
# the head sits in it centred at y 4.5; the model's own centre is (8, 8, 8).
HELMET_OFFSET = [0, 12 + (8 - 4.5), 0]


def turn_half(model):
    """The model turned 180 degrees about the vertical: Studio drew the visor on +z, and a rig faces -z."""
    swap = {"north": "south", "south": "north", "east": "west", "west": "east"}
    for e in model["elements"]:
        f, t = e["from"], e["to"]
        e["from"], e["to"] = [16 - t[0], f[1], 16 - t[2]], [16 - f[0], t[1], 16 - f[2]]
        faces = {}
        for k, face in e.get("faces", {}).items():
            if k in ("up", "down"):
                face = dict(face)
                face["rotation"] = (face.get("rotation", 0) + 180) % 360
                faces[k] = face
            else:
                faces[swap[k]] = face
        e["faces"] = faces
        rot = e.get("rotation")
        if rot and rot.get("axis") in ("x", "z"):
            rot["angle"] = -rot["angle"]
        if rot and "origin" in rot:
            o = rot["origin"]
            rot["origin"] = [16 - o[0], o[1], 16 - o[2]]
    return model


# How opaque the visor is: tinted glass. The driver's eye is inside the helmet,
# and a solid visor filled the first-person view; through this one the track is
# there, a shade darker, and from outside the driver's face shows behind it.
VISOR_ALPHA = 80


# Where the visor's lower edge is, in the helmet's pixels. A seated driver's
# camera is about 2.4 up the helmet (eye height 25.9px over a head that starts
# at 24, the helmet's 0 half a pixel below it), and Studio drew the visor from
# 4: looking ahead, a driver looked through the chin bar. Brought down to here,
# the eye line and a little below it are glass, and the chin is what is left.
VISOR_BOTTOM = 1.5
VISOR_PARTS = ("visor_seal", "tinted_visor", "visor_left_wrap", "visor_right_wrap")
CHIN_PARTS = ("lower_face", "chin_guard", "chin_left_wrap", "chin_right_wrap")


def shorter_chin(model):
    """The visor brought down to VISOR_BOTTOM and the chin squeezed under it, keeping every face's art."""
    for e in model["elements"]:
        name = e.get("name", "")
        if name in VISOR_PARTS:
            e["from"][1] = VISOR_BOTTOM
        elif name in CHIN_PARTS:
            e["from"][1] = min(e["from"][1], 0)
            e["to"][1] = VISOR_BOTTOM
        else:
            continue
        rot = e.get("rotation")
        if rot and "origin" in rot:
            rot["origin"][1] = (e["from"][1] + e["to"][1]) / 2
    return model


def visor_pixels(model, atlas):
    """Every atlas texel a face of a visor element samples (the seal, the visor and its wraps)."""
    w, h = atlas.size
    out = set()
    for e in model["elements"]:
        if "visor" not in e.get("name", ""):
            continue
        for face in e.get("faces", {}).values():
            u0, v0, u1, v1 = face["uv"]
            x0, x1 = sorted((u0 * w / 16, u1 * w / 16))
            y0, y1 = sorted((v0 * h / 16, v1 * h / 16))
            for x in range(int(x0), max(int(x0) + 1, round(x1))):
                for y in range(int(y0), max(int(y0) + 1, round(y1))):
                    out.add((x, y))
    return out


def helmets():
    base = shorter_chin(turn_half(load("kart_helmet_generated.json")))
    glass = visor_pixels(base, Image.open(os.path.join(SRC, "kart_helmet_atlas.png")))
    base.pop("groups", None)
    atlas = Image.open(os.path.join(SRC, "kart_helmet_atlas.png")).convert("RGBA")
    lines = []
    for colour, rgb in COLOURS.items():
        painted = paint(atlas, rgb)
        for x, y in glass:
            r, g, b, a = painted.getpixel((x, y))
            if a:
                painted.putpixel((x, y), (r, g, b, min(a, VISOR_ALPHA)))
        painted.save(path("textures", "block", f"helmet_{colour}.png"))
        m = json.loads(json.dumps(base))
        m["textures"] = {"atlas": f"{NS}:block/helmet_{colour}", "particle": f"{NS}:block/helmet_{colour}"}
        m["display"] = PROP_DISPLAY
        save_json(m, "models", f"helmet_{colour}.json")
        lines += [f"helmet_{colour}:", "  material: PAPER", f"  model: helmet_{colour}", icon("helmet", rgb),
                  f'  name: "&{CODES[colour]}&l{colour.capitalize()} Racing Helmet"', ""]
    return lines


# ---------------------------------------------------------------------------
# The props
# ---------------------------------------------------------------------------

# Each prop's inventory icon, in tools/src/icons.
PROP_ICONS = {"item_box": "box", "banana": "banana", "nitro": "nitro", "rocket": "rocket"}

# The items drawn flat: in a hand, in a slot and as a thing on the track (a
# sprite that always faces you), from their icon in tools/src/icons, which
# Studio's 16x16 item model drew. Item id: (icon, name, lore).
FLAT_ITEMS = {
    "orb": ("orb", "&3&lBumper Orb", ["&7Rolls along the road, off the walls, into somebody."]),
    "drone": ("drone", "&5&lStorm Drone", ["&7Flies to the leader and strikes."]),
    "overdrive": ("overdrive", "&6&lOverdrive", ["&7A boost every press, for a few seconds."]),
    "shield": ("shield", "&d&lHyper Shield", ["&7Untouchable for a few seconds."]),
    "smoke_bomb": ("smoke", "&8&lSmoke Bomb", ["&7Blinds everybody ahead of you."]),
    "horn": ("horn", "&e&lShockwave Horn", ["&7Blows everything round you away."]),
    "surge": ("surge", "&b&lStatic Surge", ["&7Everybody else loses their item and slows down."]),
    "decoy_box": ("decoy", "&c&lDecoy Box", ["&7Looks like an item box. Is not one."]),
}

PROPS = {
    "item_box": ("kart_item_box", "&d&lItem Box", ["&7Drive through it for an item."]),
    "banana": ("kart_banana", "&e&lBanana", ["&7Drop it behind you. Whoever hits it spins out."]),
    "nitro": ("kart_nitro2", "&9&lNitro", ["&7A boost, whenever you like."]),
    "rocket": ("kart_rocket", "&c&lRocket", ["&7Fired at the kart ahead of you."]),
}


def props(helmet_lines):
    lines = ["# What the plugin draws in the world and puts in a racer's hand: item boxes,",
             "# bananas, nitro and rockets, and the racing helmets the driver emotes wear.",
             "# Studio models. Generated by tools/make_assets.py.", ""]
    for pid, (src, name, lore) in PROPS.items():
        m = load(f"{src}_generated.json")
        Image.open(os.path.join(SRC, f"{src}_atlas.png")).convert("RGBA").save(path("textures", "block", f"{pid}.png"))
        m["textures"] = {"atlas": f"{NS}:block/{pid}", "particle": f"{NS}:block/{pid}"}
        m["display"] = PROP_DISPLAY
        m.pop("groups", None)
        save_json(m, "models", f"{pid}.json")
        lines += [f"{pid}:", "  material: PAPER", f"  model: {pid}", icon(PROP_ICONS[pid]), f'  name: "{name}"', "  lore:"]
        lines += [f'    - "{line}"' for line in lore] + [""]
    for iid, (icon_name, name, lore) in FLAT_ITEMS.items():
        icon(icon_name)
        lines += [f"{iid}:", "  material: PAPER", f"  texture: item/icon_{icon_name}", f'  name: "{name}"', "  lore:"]
        lines += [f'    - "{line}"' for line in lore] + [""]
    # Nothing in hand: an invisible item, so the hotbar slot a racer's item lives
    # in always holds SOMETHING, which is what makes Q and the clicks reach the plugin.
    blank = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    blank.putpixel((15, 0), (255, 255, 255, 1))
    blank.save(path("textures", "item", "blank.png"))
    lines += ["no_item:", "  material: PAPER", '  name: "&7No item"', "  lore:",
              '    - "&7Drive through an item box."', "  texture: item/blank", ""]
    lines += helmet_lines
    write_text("\n".join(lines), "items", "props.yml")


# ---------------------------------------------------------------------------
# Emotes
# ---------------------------------------------------------------------------

UPPER_BODY = {"head", "body", "rightArm", "leftArm", "rightForearm", "leftForearm"}

# Studio's emote ids, and what each is here (the colour goes in front).
EMOTES = {
    "driver_idle-3": "idle", "driver_moving-3": "moving", "driver_reversing-3": "reversing",
    "driver_turning-3": "turning", "driver_airborne-2": "airborne",
    "kart_victory": "victory", "kart_dazed": "dazed",
}


def emotes():
    src = load("emotes.json")
    out = {}
    for studio_id, state in EMOTES.items():
        e = src[studio_id]
        # A driver is sitting: only the upper body may move, or the emote stands
        # them up in the kart (a bone an emote animates replaces the seat's pose).
        animators = {bone: ch for bone, ch in e["animators"].items() if bone in UPPER_BODY}
        root = {ch: keys for ch, keys in (e.get("root") or {}).items() if ch == "rotation"}
        e = {**e, "animators": animators, "root": root}
        for colour in COLOURS:
            out[f"gokarts_{colour}_{state}"] = {
                "name": f"Go kart - {state} ({colour})",
                "length": e["length"],
                "loop": e.get("loop", True),
                **({"root": e["root"]} if e.get("root") else {}),
                "animators": e["animators"],
                "props": [{"id": "helmet", "modelId": f"{NS}:helmet_{colour}", "attach": "head",
                           "offset": HELMET_OFFSET, "scale": 1}],
            }
    full = os.path.join(OUT, "emotes", "gokarts.json")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)


# ---------------------------------------------------------------------------
# VFX
# ---------------------------------------------------------------------------

MINITURBO = {1: {"deep": "#155cb9", "mid": "#36acff", "bright": "#b4f3ff"},
             2: {"deep": "#b4400c", "mid": "#ff8a2e", "bright": "#ffe0a8"},
             3: {"deep": "#5a1fb9", "mid": "#b05cff", "bright": "#f0c8ff"}}


def vfx():
    folder = os.path.join(SRC, "vfx")
    os.makedirs(os.path.join(OUT, "vfx"), exist_ok=True)
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(folder, name), encoding="utf-8") as f:
            recipe = json.load(f)
        if name == "gk_miniturbo.json":
            for level, ramp in MINITURBO.items():
                r = json.loads(json.dumps(recipe))
                for key in r.get("palettes", {}):
                    r["palettes"][key] = ramp
                with open(os.path.join(OUT, "vfx", f"gk_miniturbo_{level}.json"), "w", encoding="utf-8", newline="\n") as f:
                    json.dump(r, f, indent=1)
            continue
        with open(os.path.join(OUT, "vfx", name), "w", encoding="utf-8", newline="\n") as f:
            json.dump(recipe, f, indent=1)
    # Recipes written by hand rather than made in Studio, in place of Studio's:
    # the boost, black and white and fixed to the kart, after the generated one
    # (a long blue and orange flame) was too much and the wrong colours.
    hand = os.path.join(ROOT, "vfx")
    for name in sorted(os.listdir(hand)) if os.path.isdir(hand) else []:
        if name.endswith(".json"):
            with open(os.path.join(hand, name), encoding="utf-8") as f:
                recipe = json.load(f)
            with open(os.path.join(OUT, "vfx", name), "w", encoding="utf-8", newline="\n") as f:
                json.dump(recipe, f, indent=1)


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
            n = int(0.12 * sr)
            head, tail = mono[:n].copy(), mono[-n:]
            ramp = np.linspace(0, 1, n)
            mono = mono[n:].copy()
            mono[-n:] = tail * (1 - ramp) + head * ramp
        else:
            env = np.abs(mono)
            loud = np.nonzero(env > 0.02 * max(1e-6, env.max()))[0]
            if len(loud):
                mono = mono[: min(len(mono), loud[-1] + int(0.08 * sr))]
            fade = min(len(mono), int(0.12 * sr))
            mono[-fade:] *= np.linspace(1, 0, fade)
        peak = np.abs(mono).max()
        if peak > 0:
            mono = mono / peak * 0.9
        sf.write(path("sounds", sid + ".ogg"), mono.astype(np.float32), sr, format="OGG", subtype="VORBIS")
        lines += [f"{sid}:", f"  file: {sid}", "  category: neutral", f'  subtitle: "{subtitle}"']
        if volume != 1.0:
            lines.append(f"  volume: {volume}")
        if loop:
            lines.append(f"  length: {round(len(mono) / sr, 3)}")
        lines.append("")
    write_text("\n".join(lines), "sounds", "sounds.yml")


# ---------------------------------------------------------------------------
# The HUD's art and the minimaps
# ---------------------------------------------------------------------------

def icons():
    thumbs = {pid: os.path.join(SRC, "thumbs", f"{src}.png") for pid, (src, _, _) in PROPS.items()}
    # The flat items' windows show their icon, by the item id the HUD names them by.
    for iid, (icon_name, _, _) in FLAT_ITEMS.items():
        thumbs[iid] = os.path.join(SRC, "icons", f"{icon_name}.png")
    maps = {}
    for pid in sorted(f[:-5] for f in os.listdir(prefabs.PREFABS) if f.endswith(".json") and f != "minimap.json"):
        p, roads = prefabs.load(pid)
        pts, gaps = [], []
        for ri, r in enumerate(roads):
            pts.append([(x[0], x[1]) for x in r.samples[::4]])
            # (gap, height, tunnel): the minimap draws bridges over what they cross.
            gaps.append([(lambda f: (f[1], f[0], bool(f[2])))(prefabs.features(p, roads, ri, x)) for x in r.samples[::4]])
        main = roads[0]
        s = main.at(p.get("start", 0) * main.length)
        maps[pid] = (pts, gaps, ((s[0], s[1]), (s[4], s[5])))
    out = ["# Glyphs the plugin writes into the HUD (huds/hud.json). Generated by",
           "# tools/make_assets.py and tools/hud_art.py: edit those, not this.", ""]
    lines, frames = hud_art.build(os.path.join(A, "textures", "font"), thumbs, maps)
    out += lines
    write_text("\n".join(out), "fonts", "icons.yml")
    frames_out = {pid: {**f, "size": hud_art.MAP, "dotStep": hud_art.DOT_STEP} for pid, f in frames.items()}
    with open(os.path.join(prefabs.PREFABS, "minimap.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(frames_out, f, indent=1)


def pack_yml():
    write_text("""name: Go Karts
author: ResourcePack AI
version: 0.2.0
# Installed by the RPEGoKarts addon from its own jar. Edit freely - a newer
# plugin build overwrites its own files and leaves anything you add.
""", "pack.yml")


if __name__ == "__main__":
    clean()
    pack_yml()
    karts()
    props(helmets())
    emotes()
    vfx()
    sounds()
    icons()
    print("Wrote", os.path.normpath(OUT))
