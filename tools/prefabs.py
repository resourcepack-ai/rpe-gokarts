"""
The four prefab tracks' geometry, read from src/main/resources/prefabs/*.json
exactly as the plugin reads it (track/Prefab.java and track/Road.java hold the
same arithmetic): used to draw each track's minimap for the HUD, and to check a
layout before anybody builds it.

  python tools/prefabs.py preview      # previews/<id>.png: the layout from above, coloured by height
  python tools/prefabs.py check        # how close the roads come to each other, and the lap length

A road is a Catmull-Rom spline through (u, v, h) points: u to the right of the
start line, v forward along it, h up, all in blocks. The main road is a loop; a
branch leaves the loop at one fraction of a lap and rejoins it at another.
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
PREFABS = os.path.join(ROOT, "..", "src", "main", "resources", "prefabs")
STEP = 0.25


def cr(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)


def spline(points, closed, per=48):
    """Dense points along a Catmull-Rom spline. An open one runs from the second point to the second-last:
    the first and last only steer its ends."""
    n = len(points)
    dense = []
    segs = range(n) if closed else range(1, n - 2)
    for i in segs:
        p0, p1, p2, p3 = (points[(i - 1) % n], points[i % n], points[(i + 1) % n], points[(i + 2) % n])
        for k in range(per):
            t = k / per
            dense.append(tuple(cr(p0[c], p1[c], p2[c], p3[c], t) for c in range(3)))
    if not closed:
        dense.append(tuple(points[n - 2]))
    return dense


def resample(dense, closed):
    """Even samples (u, v, h, s, du, dv) every STEP blocks along the line."""
    at = [0.0]
    count = len(dense)
    last = count if closed else count - 1
    for i in range(1, last + 1):
        a, b = dense[i - 1], dense[i % count]
        at.append(at[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = at[-1]
    out = []
    j = 0
    s = 0.0
    while s < total - (0 if closed else 1e-9):
        while at[j + 1] < s:
            j += 1
        a, b = dense[j], dense[(j + 1) % count]
        seg = at[j + 1] - at[j]
        f = 0 if seg <= 0 else (s - at[j]) / seg
        du, dv = b[0] - a[0], b[1] - a[1]
        ln = math.hypot(du, dv) or 1
        out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, s, du / ln, dv / ln))
        s += STEP
    return out, total


class Road:
    def __init__(self, samples, length, closed):
        self.samples, self.length, self.closed = samples, length, closed

    def at(self, s):
        if self.closed:
            s %= self.length
        s = max(0, min(self.length, s))
        return self.samples[min(len(self.samples) - 1, int(round(s / STEP)))]


def load(prefab_id):
    with open(os.path.join(PREFABS, prefab_id + ".json"), encoding="utf-8") as f:
        p = json.load(f)
    main_s, main_len = resample(spline(p["main"], True), True)
    main = Road(main_s, main_len, True)
    roads = [main]
    for b in p.get("branches", []):
        a, z = main.at(b["from"] * main_len), main.at(b["to"] * main_len)
        a0 = main.at(b["from"] * main_len - 6)
        z1 = main.at(b["to"] * main_len + 6)
        pts = [(a0[0], a0[1], a0[2]), (a[0], a[1], a[2])] + [tuple(q) for q in b["points"]] + [(z[0], z[1], z[2]), (z1[0], z1[1], z1[2])]
        s, ln = resample(spline(pts, False), False)
        roads.append(Road(s, ln, False))
    return p, roads


def smoothstep(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def features(p, roads, ri, sample):
    """What a sample of road `ri` is: its height with any ramp added, and whether it is a gap, in a tunnel, on ice."""
    r = roads[ri]
    s = sample[3]
    h = sample[2]
    gap = False
    for ramp in p.get("ramps", []):
        if ramp.get("on", 0) != ri:
            continue
        lip = ramp["lip"] * r.length
        ahead = s - lip
        if r.closed:
            ahead = (ahead + r.length / 2) % r.length - r.length / 2
        if -ramp["length"] <= ahead <= 0:
            h += ramp["rise"] * smoothstep((ahead + ramp["length"]) / ramp["length"])
        elif 0 < ahead <= ramp["gap"]:
            gap = True
    tunnel = False
    for t in p.get("tunnels", []):
        if t.get("on", 0) == ri and t["from"] * r.length <= s <= t["to"] * r.length:
            tunnel = True
    if ri > 0 and p["branches"][ri - 1].get("tunnel"):
        tunnel = 6 < s < r.length - 6
    ice = ri > 0 and p["branches"][ri - 1].get("surface") == "ice"
    return h, gap, tunnel, ice


def preview(prefab_id, size=900):
    p, roads = load(prefab_id)
    pts = [(x[0], x[1]) for r in roads for x in r.samples]
    pad = 20
    u0, u1 = min(q[0] for q in pts) - pad, max(q[0] for q in pts) + pad
    v0, v1 = min(q[1] for q in pts) - pad, max(q[1] for q in pts) + pad
    k = size / max(u1 - u0, v1 - v0)
    img = Image.new("RGB", (int((u1 - u0) * k), int((v1 - v0) * k)), (34, 40, 34))
    d = ImageDraw.Draw(img)
    hmin = min(x[2] for r in roads for x in r.samples)
    hmax = max(x[2] for r in roads for x in r.samples) or 1
    half = p.get("half", 5)
    order = sorted(range(len(roads)), key=lambda i: sum(x[2] for x in roads[i].samples) / len(roads[i].samples))
    for ri in order:
        r = roads[ri]
        for x in r.samples[::2]:
            h, gap, tunnel, ice = features(p, roads, ri, x)
            t = (h - hmin) / max(1e-6, hmax - hmin + 3)
            col = (int(90 + 140 * t), int(90 + 60 * (1 - t)), int(110 + 100 * (1 - t)))
            if gap:
                col = (40, 120, 220)
            elif tunnel:
                col = (90, 70, 50)
            elif ice:
                col = (190, 230, 255)
            elif ri > 0:
                col = (col[0], min(255, col[1] + 60), col[2])
            px, py = (x[0] - u0) * k, (v1 - x[1]) * k
            rad = half * k
            d.ellipse((px - rad, py - rad, px + rad, py + rad), fill=col)
    # start line
    sx = roads[0].at(p.get("start", 0) * roads[0].length)
    px, py = (sx[0] - u0) * k, (v1 - sx[1]) * k
    d.line((px - half * k * sx[5], py - half * k * sx[4], px + half * k * sx[5], py + half * k * sx[4]), fill=(255, 255, 255), width=4)
    for kind, colour in (("pads", (255, 60, 220)), ("boxes", (255, 230, 60))):
        for on, frac in p.get(kind, []):
            r = roads[on]
            x = r.at(frac * r.length)
            px, py = (x[0] - u0) * k, (v1 - x[1]) * k
            d.rectangle((px - 3 * k, py - 3 * k, px + 3 * k, py + 3 * k), fill=colour)
    lob = p.get("lobby")
    if lob:
        px, py = (lob[0] - u0) * k, (v1 - lob[1]) * k
        d.rectangle((px - 4 * k, py - 4 * k, px + 4 * k, py + 4 * k), outline=(255, 210, 63), width=3)
    os.makedirs(os.path.join(ROOT, "previews"), exist_ok=True)
    out = os.path.join(ROOT, "previews", prefab_id + ".png")
    img.save(out)
    return out


def check(prefab_id):
    p, roads = load(prefab_id)
    half = p.get("half", 5)
    edge = half + 1.5 + 1
    worst = (1e9, None)
    for i, r in enumerate(roads):
        for j, q in enumerate(roads):
            if j < i:
                continue
            for a in r.samples[::6]:
                for b in q.samples[::6]:
                    if i == j:
                        along = abs(a[3] - b[3])
                        if r.closed:
                            along = min(along, r.length - along)
                        if along < 40:
                            continue
                    elif i == 0 and j > 0:
                        # a branch meets the main road at both ends, on purpose
                        if min(math.dist(b[:2], q.samples[0][:2]), math.dist(b[:2], q.samples[-1][:2])) < 30:
                            continue
                    flat = math.hypot(a[0] - b[0], a[1] - b[1])
                    if abs(a[2] - b[2]) >= 6:
                        continue
                    if flat < worst[0]:
                        worst = (flat, (i, round(a[0]), round(a[1]), j, round(b[0]), round(b[1])))
    lens = [round(r.length) for r in roads]
    print(f"{prefab_id}: lap {lens[0]}, branches {lens[1:]}, closest {worst[0]:.1f} (need > {2 * edge + 1}) at {worst[1]}")
    return worst[0] > 2 * edge + 1


def fractions(prefab_id):
    """Where each main control point falls, as a fraction of a lap: what features are placed by."""
    p, roads = load(prefab_id)
    main = roads[0]
    out = []
    for q in p["main"]:
        best = min(main.samples, key=lambda x: (x[0] - q[0]) ** 2 + (x[1] - q[1]) ** 2)
        out.append(f"{q[0]},{q[1]}:{best[3] / main.length:.3f}")
    print(prefab_id, " ".join(out))


if __name__ == "__main__":
    ids = [f[:-5] for f in sorted(os.listdir(PREFABS)) if f.endswith(".json") and f != "minimap.json"]
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    for i in ids:
        if cmd == "preview":
            print(preview(i))
        elif cmd == "fractions":
            fractions(i)
        else:
            check(i)
