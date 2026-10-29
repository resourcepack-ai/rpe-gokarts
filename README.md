# Go Karts

Go-kart racing for Minecraft servers, built on
[RP Engine](https://github.com/resourcepack-ai/rp-engine). Eight karts on a
grid, three red lights and green, and three laps of a circuit the plugin builds
for you: drift through the corners for a boost, drive through item boxes for
bananas, nitro and homing rockets, and try to be first over the line.

It is an RP Engine addon, and it uses nothing but the engine's public API
(`ai.resourcepack.engine.api`). **Every kart is an RP Engine vehicle**, so the
driving itself is the engine's: grip and the handbrake slide, the body rolling
into corners and squatting under power, karts bumping into each other and into
walls. Everything a player sees, from the kart in eight paints to the whole HUD,
is ordinary RP Engine content shipped inside the jar.

## Requirements

- **Paper 1.21.4 or newer** for A/D steering (Spigot works too, steering by
  looking), on Java 21.
- **[RP Engine](https://resourcepack.ai/rp-engine) 0.2.40 or newer.** Go Karts
  checks the engine's version as it starts, and on anything older says so in the
  console and disables itself.

## Installing

1. Put `RPEngine.jar` and `RPE-gokarts-<version>.jar` in `plugins/` and start
   the server.
2. Go Karts installs its content into `plugins/RPEngine/content/gokarts/` and
   reloads the engine once, so the resource pack the engine serves now carries
   it.
3. As an op, stand somewhere open and flat, facing the way the start straight
   should run, and run `/kart track generate main confirm`. The circuit is built
   forward and to your right, in a few seconds.
4. `/kart join`, then `/kart start`. Alone is fine.

## Racing

| | |
|---|---|
| **W / S** | Throttle, and brake then reverse |
| **A / D** | Steer |
| **Space** | The handbrake. Held through a corner at speed it is a **drift**: blue sparks, then orange, then purple, and letting go is a boost worth the level reached |
| **F** or a click | Use your item |
| **Q** | Back on the track at the last checkpoint |
| `/kart join [track]` | Into the lobby (the glass box over the infield) |
| `/kart colour <colour>` | The kart you'd like: red, blue, green, yellow, orange, purple, pink or cyan |
| `/kart leave` | Out, with your inventory and position back |
| `/kart results` | The last race's results |

**The start.** Three red lights a second apart, then green. Hold W when the
second light comes on and you stall; hold it as the lights go green, and not
before, and it is a rocket start.

**The circuit.** Asphalt with red and white kerbs and a tyre wall both sides,
boost pads on the two straightest stretches, two rows of item boxes. Off the
road is grass, which holds a kart to walking pace: cutting a corner is a choice,
and it costs. Laps are counted checkpoint by checkpoint, so cutting across the
infield gets you nowhere.

**Items.** Drive through a box for a spin of the roulette. What comes out
depends on your place: the leader gets bananas to defend with, the back of the
field nitro and rockets to catch up with.

| | |
|---|---|
| Nitro | A boost |
| Triple nitro | Three |
| Banana | Dropped behind you. Whoever drives over it spins out |
| Rocket | Flies over the walls at the kart one place ahead and throws it in the air. The leader's flies straight on |

Falling off, ending up in water, or not passing a checkpoint for twelve seconds
puts you back on the track. When the winner is home everybody else has thirty
seconds to finish, then the results come up with every driver's time, best lap
and face, and the track's lap record.

**Outside a race** a kart is still a kart: `/kart give [colour]`, right-click
the ground to park it, right-click it to get in, and drift, boost pads and the
speed line above your hotbar all work anywhere.

## Tracks (staff)

`/kart track generate <id> confirm [name]` builds a whole circuit, about 330
blocks a lap. From the console: `/kart track generate <id> at <world> <x> <y>
<z> <yaw> [name]`.

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
| `gokarts.stop` | op | `/kart stop` |
| `gokarts.reload` | op | `/kart reload` |
| `gokarts.give` | op | `/kart give [colour]` |
| `gokarts.give.others` | op | `/kart give <colour> <player>` |
| `gokarts.as` | op | `/kart as <player> ...`; what they run is checked against *their* permissions |
| `gokarts.track` | op | Track setup |
| `gokarts.track.generate` | op | `/kart track generate`, which replaces every block in a box about 80 by 130 |
| `gokarts.track.delete` | op | `/kart track delete` |
| `gokarts.bypass.commands` | op | Run any command during a race |

## Everything you see is RP Engine content

The jar carries a content folder (`src/main/resources/content/gokarts/`) and
installs it into `plugins/RPEngine/content/gokarts/` on enable:

- **The kart** is one model made in [ResourcePack AI Studio](https://resourcepack.ai)
  and rigged there in the vehicle editor's "Animate with AI": its wheels roll
  and steer, and its driver's four emotes (`emotes/gokarts.json`) sit them low
  in the seat with their hands on the wheel. `tools/make_assets.py` paints it in
  eight colours, each its own item and its own vehicle (`vehicles/karts.yml`).
- **The item box, banana, nitro and rocket** are Studio models; **the sounds**
  (engine, idle, boost, sparks, the lights, the finish...) are Studio's,
  trimmed, levelled and, for the two engine notes, looped by the tool.
- **The HUD** is `huds/hud.json`, a full-screen shader object: the item card top
  left, lap, clock and best lap top right, the standings down the right edge
  with your own line in yellow, speed, drift charge and your place bottom right,
  and the start lights and the big words in the middle. The plugin only sets its
  values (`race/Hud.java`); the pictures in them (the lettering, the lights, the
  item cards, drawn from Studio's own thumbnails of the models) are
  `tools/hud_art.py`'s.
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
against its SHA-256.

### The art

`tools/` rebuilds the models, textures, glyphs, sounds, emotes and the item and
vehicle YAML from what Studio made (`tools/src/`). It needs Python 3 with
Pillow, NumPy and soundfile, and nothing else.

```
python tools/make_assets.py
```

The HUD and the results screen are kept as Studio documents in `dialogs-src/`
(`hud.json`, the shader object; `dialogs.json`, the Dialog-style document) and
built into the content folder with ResourcePack AI Studio's exporter.

## Licence

GPL-3.0-or-later. See [`LICENSE`](LICENSE).

The jar bundles [Adventure](https://github.com/KyoriPowered/adventure) (MIT),
relocated so it cannot meet Paper's copy.
