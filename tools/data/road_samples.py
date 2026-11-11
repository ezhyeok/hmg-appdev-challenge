"""Test fixture for the app's map matching (rest-engine MotorwayIndexTest): car positions on OSM expressways.

    python3 -I -B tools/data/road_samples.py RAW_DIR rest-engine/src/test/resources/stops/road_samples.csv

Positions are sampled uniformly along the full-resolution OSM carriageways (the app only has the 10 m
simplification), then moved by GPS-like noise (8 m position, 8 degrees heading). `road` is the OSM name of the
carriageway. Negative samples (`road` empty) are 150-2000 m to the side of a carriageway with no motorway
within 150 m: the matcher must answer "not on an expressway" there. Deterministic (fixed seed).
"""
import bisect
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import motorway  # noqa: E402

POSITIVE, NEGATIVE = 1500, 300


def main():
    raw, out = sys.argv[1], sys.argv[2]
    mw = motorway.Motorways(os.path.join(raw, "osm", "motorways.json"))
    segs, cum = [], [0.0]
    for w in sorted(mw.geom):
        if not mw.ways[w]["name"]:
            continue
        g = mw.geom[w]
        for i in range(len(g) - 1):
            L = common.haversine_m(*g[i], *g[i + 1])
            if L >= 1:
                segs.append((w, i))
                cum.append(cum[-1] + L)
    rnd = random.Random(20261111)

    def at(x):
        k = bisect.bisect_right(cum, x) - 1
        w, i = segs[k]
        g = mw.geom[w]
        t = (x - cum[k]) / (cum[k + 1] - cum[k])
        return w, i, t, g[i][0] + (g[i + 1][0] - g[i][0]) * t, g[i][1] + (g[i + 1][1] - g[i][1]) * t

    def jitter(lat, lon, m):
        kx = 111320.0 * math.cos(math.radians(lat))
        return lat + rnd.gauss(0, m) / 110540.0, lon + rnd.gauss(0, m) / kx

    rows = []
    while len(rows) < POSITIVE:
        w, i, t, lat, lon = at(rnd.random() * cum[-1])
        h = mw.heading_along(w, i, t, 100)
        if mw.ways[w]["oneway"] == 0 and rnd.random() < 0.5:
            h += 180
        lat, lon = jitter(lat, lon, 8)
        rows.append((lat, lon, (h + rnd.gauss(0, 8)) % 360, mw.ways[w]["name"]))
    neg = 0
    while neg < NEGATIVE:
        w, i, t, lat, lon = at(rnd.random() * cum[-1])
        h = mw.heading_along(w, i, t, 100)
        off = rnd.uniform(150, 2000)
        side = math.radians(h + rnd.choice((90, -90)))
        kx = 111320.0 * math.cos(math.radians(lat))
        la, lo = lat + off * math.cos(side) / 110540.0, lon + off * math.sin(side) / kx
        if mw.segments_near(la, lo, 150):
            continue
        rows.append((la, lo, h % 360, ""))
        neg += 1
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("lat,lon,heading,road\n")
        for lat, lon, h, r in rows:
            f.write(f"{lat:.6f},{lon:.6f},{h:.1f},{r}\n")
    print(f"{out}: {POSITIVE} on expressways, {NEGATIVE} off")


if __name__ == "__main__":
    main()
