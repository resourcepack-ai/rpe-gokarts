# Go Karts

Go-kart racing for Minecraft servers, built on
[RP Engine](https://github.com/resourcepack-ai/rp-engine). Eight karts on a
grid, three red lights and green, and three laps of one of four circuits the
plugin builds for you, from a sunny meadow to a neon city at night: drift
through the corners for a boost, fly off the ramps and trick in the air, take
the shortcuts, drive through item boxes for bananas, nitro and homing rockets,
and try to be first over the line.

It is an RP Engine addon, and it uses nothing but the engine's public API
(`ai.resourcepack.engine.api`). **Every kart is an RP Engine vehicle**, so the
driving itself is the engine's: grip and the handbrake slide, the body rolling
into corners and squatting under power, karts bumping into each other and into
walls. Everything a player sees, from the kart in eight paints to the whole HUD,
is ordinary RP Engine content shipped inside the jar.

## Requirements

- **Paper 1.21.4 or newer** for A/D steering (Spigot works too, steering by
  looking), on Java 21.
- **[RP Engine](https://resourcepack.ai/rp-engine) 0.2.45 or newer**, the first
  whose vehicles jump off a jump pad. Go Karts checks the engine's version as it
  starts, and on anything older says so in the console and disables itself.
  0.2.47 or newer draws the karts, helmets and items as icons in an inventory;
  on 0.2.45 and 0.2.46 a slot shows the 3D model, shrunk. 0.2.49 or newer turns
  the front wheels and the steering wheel with the steering; before it they stay
  straight.

## Installing

1. Put `RPEngine.jar` and `RPE-gokarts-<version>.jar` in `plugins/` and start
   the server.
2. Go Karts installs its content into `plugins/RPEngine/content/gokarts/` and
   reloads the engine once, so the resource pack the engine serves now carries
   it.
3. As an op, run `/kart track generate main`. It makes a new world for the
   track (`gokarts_main`), builds the Sunny Meadows circuit there in a few
   seconds and takes you to it. `/kart track generate night neon` builds the
   city one instead; see [Tracks](#tracks-staff).
4. `/kart join`, then `/kart start`. Alone is fine.

## Racing

| | |
|---|---|
| **W / S** | Throttle, and brake then reverse |
| **A / D** | Steer |
| **Space** | The handbrake. Held through a corner at speed it is a **drift**: blue sparks, then orange, then purple, and letting go is a boost worth the level reached |
| **F** or a click | Use your item |
| **Q** | Scrap the item you are holding, for the next box's. With empty hands, back on the track at the last checkpoint, if you are off the road or stuck |
| `/kart join [track]` | Into the lobby (the glass box over the infield), where you stay until the grid: step or fall off it and you are put back |
| `/kart colour <colour>` | The kart you'd like: red, blue, green, yellow, orange, purple, pink or cyan. Everybody in a race has a different one, and wears a helmet to match |
| `/kart leave` | Out, with your inventory and position back |
| `/kart results` | The last race's results |

**The start.** Three red lights a second apart, then green. Hold W when the
second light comes on and you stall; hold it as the lights go green, and not
before, and it is a rocket start.

**The circuit.** Kerbs and walls both sides, boost pads (magenta) on the
straights, rows of item boxes. Off the road holds a kart to walking pace:
cutting a corner is a choice, and it costs. Laps are counted checkpoint by
checkpoint, so cutting across the infield gets you nowhere.

**Jumps and shortcuts.** Every track has a ramp with a jump pad (lime) at its
lip, over a river, a canyon, a crevasse or a drop between buildings. Press space
in the air for a **trick**, and landing it is a boost. Every track also has a
second way round for part of the lap: a tunnel through a hill, the canyon floor,
a frozen lake (ice: the kart slides wide of where it is pointed) or a back
alley. Either way counts.

**Items.** Drive through a box for a spin of the roulette. What comes out
depends on your place: the front of the field gets things to defend with
(bananas, decoys, orbs, the horn), the back things to catch up with (nitro,
overdrive, rockets, the shield, the storm drone, the surge).

| | |
|---|---|
| Nitro | A boost |
| Triple nitro | Three |
| Overdrive | For six seconds, every press is a boost |
| Banana | Dropped behind you. Whoever drives over it spins out |
| Banana bunch | Three |
| Decoy box | Dropped behind you, at an item box's height. It looks like one, except that it does not turn, and whoever drives into it spins out |
| Bumper orb | Rolls along the road ahead of you, up ramps and off walls (five of them), and spins out whoever it meets. You too, once it has left you |
| Rocket | Flies over the walls at the kart one place ahead and throws it in the air. The leader's flies straight on |
| Storm drone | Climbs over everything and flies at the leader (never at you), hovers over them for a second, and strikes everybody within four blocks of them |
| Hyper shield | Six seconds in which nothing can hit you, the grass does not slow you, you run a little faster, and anybody you touch spins out |
| Smoke bomb | Blinds everybody ahead of you for three seconds |
| Shockwave horn | Throws back every kart within seven blocks, and clears the bananas, decoys, orbs and rockets round you, and a storm drone coming for you |
| Static surge | Everybody else loses their item and drives at two thirds of their speed for three seconds |

`items.odds` in config.yml sets how likely each is by place, and every one's
numbers are under `items:`. An item a newer version adds drops on a server
with odds of its own at its default weight, until that server's odds name it.

Falling off, ending up in water, or not passing a checkpoint for twelve seconds
(more where the next one is far away) puts you back on the track. When the winner is home everybody else has thirty
seconds to finish, then the results come up with every driver's time, best lap
and face, and the track's lap record.

**Bots.** `/kart start --withBots` fills the rest of the grid with computer
drivers, and `--withBots=3` adds three. They drive the same karts with the same
keys you have: they follow the road, drift through the bends for the boost,
trick off the jumps, use their items and back out of whatever they hit. They
show on the map and in the standings and take hits like anybody, and they're in
the results but never in the track's records. Each is a mannequin in one of
Minecraft's own skins wearing the helmet in its kart's colour (Minecraft 1.21.9
and newer; before that the karts drive with nobody to see in them). It needs RP
Engine 0.2.50. Their names are `bots.names` in `config.yml`.

**Outside a race** a kart is still a kart: `/kart give [colour]`, right-click
the ground to park it, right-click it to get in, and drift, boost pads and the
speed line above your hotbar all work anywhere.

## Tracks (staff)

`/kart track generate <id> [prefab] [name]` builds a whole circuit **in a new
world of its own** (`gokarts_<id>`: empty, peaceful, its biome and time of
day the theme's), so it replaces nothing. The circuit is an island in that
world: its whole infield and a margin round every road, the lobby and the
grandstand, and nothing beyond. The plugin loads that world again
every time the server starts. Generating the same track again makes a fresh
world (`gokarts_<id>_2`...) and leaves the old one alone.

| Prefab | | Lap | |
|---|---|---|---|
| `meadow` (default) | Sunny Meadows | 458 blocks | A jump over a river, a tunnel through a hill |
| `desert` | Dune Canyon | 528 | A jump over a canyon, a lower road along its floor, mesas |
| `frost` | Frostbite Peak | 544 | A figure of eight that crosses itself on a bridge, a frozen-lake shortcut |
| `neon` | Neon Nights | 742, two laps | Midnight, an elevated road over the streets, a tunnel, a back alley |

Each is a racing line in three dimensions with its branches, ramps, gaps,
tunnels, pads and item boxes, built in half-block steps with walls, kerbs, a
gantry over the start, a grandstand, scenery and the viewing deck the lobby
waits on. The prefabs are JSON in the jar (`src/main/resources/prefabs/`), so a
fork can add its own; `tools/prefabs.py` previews and checks one.

`/kart track generate <id> here confirm [prefab] [name]` builds it where you
stand instead, its start line under your feet and its straight running the way
you face, with the rest of the circuit round you, replacing every block in the
way; the reply first says how big a box. From
the console: `/kart track generate <id> at <world> <x> <y> <z> <yaw> [prefab]
[name]`.

**`/kart track undo <id> confirm` takes the last generate back.** A track in its
own world deletes that world; one built in place puts back every block it
replaced (their type and states, not what a chest or a sign held). Either way
the track goes back to what it was before, or is forgotten if it was new. One
level: it undoes the last generate of that track, and survives a restart
(`plugins/RPEGoKarts/undo/`).

By hand, on a track you built yourself: `create`, `setlobby`, `addgrid` (once
per grid slot, facing the way the karts should), `addcheckpoint [radius]` (the
start/finish line first, then round the lap in order, every fifteen blocks or
so), `addbox`, `laps`, `enable`. `/kart track list` shows every track and what
it still needs; `disable`, `tp`, `name` and `delete` do what they say.

## Configuration

`plugins/RPEGoKarts/config.yml` has the numbers (laps, how many it takes to
start, cruise and off-road speed, every boost, the drift levels, the item odds
by place, how fast a rocket flies) and every word a player reads, in MiniMessage
under `messages:`. `/kart reload` re-reads it. Options a newer version adds are
written into your existing file on start.

A kart's vehicle `speed:` (`vehicles/karts.yml`) is the most a boost can take it
to; `karts.cruise-speed` is what it does flat out the rest of the time. Change
that one to make karts faster.

## Permissions

One node per command. `gokarts.play` and `gokarts.admin` are only shorthand:
each grants every node listed under it, and nothing checks them directly.
`/kart leave` needs no permission at all, so nobody can be stuck in a race.

| Permission | Default | |
|---|---|---|
| `gokarts.play` | everyone | All four below |
| `gokarts.join` | everyone | `/kart join` |
| `gokarts.colour` | everyone | `/kart colour` |
| `gokarts.results` | everyone | `/kart results` |
| `gokarts.ride` | everyone | Getting into a kart at all, a parked one included |
| `gokarts.admin` | op | Every node below |
| `gokarts.start` | op | `/kart start` |
| `gokarts.start.bots` | op | `/kart start --withBots`, computer drivers on the grid |
| `gokarts.stop` | op | `/kart stop` |
| `gokarts.reload` | op | `/kart reload` |
| `gokarts.give` | op | `/kart give [colour]` |
| `gokarts.give.others` | op | `/kart give <colour> <player>` |
| `gokarts.item` | op | `/kart item <item> [player]`: hand a racer an item, as a box would, for testing or an event |
| `gokarts.as` | op | `/kart as <player> ...`; what they run is checked against *their* permissions |
| `gokarts.track` | op | Track setup |
| `gokarts.track.generate` | op | `/kart track generate`, in a new world, or with `here` in place, replacing every block in a box up to about 200 by 230, from fifteen below to fourteen above |
| `gokarts.track.undo` | op | `/kart track undo`, which deletes the world a generate made, or puts back the blocks it replaced |
| `gokarts.track.delete` | op | `/kart track delete` |
| `gokarts.bypass.commands` | op | Run any command during a race |

## Everything you see is RP Engine content

The jar carries a content folder (`src/main/resources/content/gokarts/`) and
installs it into `plugins/RPEngine/content/gokarts/` on enable:

- **The kart** is one model made in [ResourcePack AI Studio](https://resourcepack.ai)
  and rigged there in the vehicle editor: its wheels roll and steer, and it has
  a trick, a spin-out and a boost the plugin plays. Its driver's emotes
  (`emotes/`) sit them low in the seat with their hands on the wheel, and carry
  a racing **helmet** in the kart's colour as a prop; there are emotes for
  winning and for being dazed too. `tools/make_assets.py` paints the kart and the
  helmet in eight colours, each its own item and its own vehicle
  (`vehicles/karts.yml`). The helmet's visor is tinted glass, so a driver in
  first person sees the track through it.
- **The item box, banana, nitro and rocket** are Studio models; **the other
  items** are drawn flat, in the hand, in a slot and on the track, from 16x16
  icons drawn by Studio's item model (`tools/src/icons/`). **In an
  inventory** every one of them, the karts and the helmets is a 16x16 icon
  (`tools/src/icons/`, drawn by Studio's item model; the kart and the helmet are
  repainted in each colour), named by the item's `gui:`. **The sounds**
  (engine, idle, boost, sparks, the lights, the finish...) are Studio's,
  trimmed, levelled and, for the two engine notes, looped by the tool.
- **The effects** (`vfx/`) are Studio VFX (drift sparks for each level, pad
  bursts, item boxes breaking, explosions, dizzy stars, confetti at the finish,
  the respawn) and one written by hand, the black and white boost
  (`tools/vfx/`). Each rides along with its kart: they follow the kart itself,
  not the driver, so they turn with it whichever way its driver is looking.
- **The HUD** is `huds/hud.json`, a full-screen shader object: the item window
  (with its roulette) and a live minimap with a dot for every racer top left,
  lap, clock and best lap top right, the standings down the right edge with a
  chip of each kart's colour and your own line in yellow, a speed gauge that
  glows with the drift charge and your place bottom right, and the start
  lights, countdown and banners (final lap, wrong way, best lap, finish) in the
  middle, with speed lines while you boost. The plugin only sets its values
  (`race/Hud.java`); the pictures in them are `tools/hud_art.py`'s.
- **The results** are `dialogs/results.json`, a Studio dialog: every driver's
  place, face, time and best lap, and the lap record.

`config.yml`'s `ui.hud` and `ui.results` point at other content ids to replace
either; a replacement has to print the same values.

## Building

```
./gradlew build        # build/libs/RPE-gokarts-<version>.jar
```

Gradle 8.10.2 needs a JDK it can run on: 17 or 21 work, 24 does not. The jar
itself targets Java 21 through a toolchain Gradle downloads on its own.

RP Engine is compile-only (the server already has it). The build uses, in order,
`ENGINE_JAR`, an engine built beside this project in `../../rp-engine/build/libs`,
or RP Engine 0.2.40, downloaded once from cdn.resourcepack.ai and checked
against its SHA-256. Compiling needs only 0.2.40's API; running needs 0.2.45.

### The art

`tools/` rebuilds the models, textures, glyphs, sounds, emotes, VFX and the item
and vehicle YAML from what Studio made (`tools/src/`). It needs Python 3 with
Pillow, NumPy and soundfile, and nothing else.

```
python tools/make_assets.py      # the content folder, from tools/src/
python tools/hud_art.py          # the HUD's pictures
python tools/prefabs.py check    # every prefab: lap length, how close its roads come
python tools/prefabs.py preview  # tools/previews/<id>.png, from above
```

The HUD and the results screen were made and exported in
[ResourcePack AI Studio](https://resourcepack.ai). `dialogs-src/` keeps what was
exported (`hud.json`, the HUD's shader object; `dialogs.json`, the results
screen's Dialog-style document), and the content folder's `dialogs/`, `huds/`
and `resourcepack/` are the export. To change either, edit it in Studio and
export it again over those folders, or edit the exported JSON by hand.

## Licence

GPL-3.0-or-later. See [`LICENSE`](LICENSE).

The jar bundles [Adventure](https://github.com/KyoriPowered/adventure) (MIT),
relocated so it cannot meet Paper's copy.
