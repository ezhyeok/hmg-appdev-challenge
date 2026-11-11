"""Sanity-check the exported JSON files (types, required keys, coordinates, ids, hours) and the
lane headings of rest areas and drowsiness shelters against their same-road neighbours.

    python3 -I -B tools/data/validate.py app/src/main/assets/data
"""
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

REQUIRED = {
    "rest_areas.json": {"id": str, "n": str, "lat": float, "lon": float, "wc": int, "src": str},
    "drowsy_shelters.json": {"id": str, "n": str, "lat": float, "lon": float, "src": str},
    "restrooms.json": {"id": str, "n": str, "t": str, "lat": float, "lon": float, "ga": int},
    "restrooms_nocoord.json": {"id": str, "n": str, "t": str},
    "parkables.json": {"id": str, "k": str, "pk": int, "n": str, "lat": float, "lon": float},
}
ENUMS = {
    ("restrooms.json", "t"): {"p", "o", "s", "m", "u"},
    ("restrooms_nocoord.json", "t"): {"p", "o", "s", "m", "u"},
    ("parkables.json", "k"): {"fuel", "lpg", "h2", "park"},
    ("rest_areas.json", "k"): {"general", "truck", "simple"},
    ("rest_areas.json", "src"): {"kec", "osm"},
    ("drowsy_shelters.json", "src"): {"kec", "osm"},
}
FLAGS = {"wc", "nr", "ev", "gas", "lpg", "ph", "food", "shop", "h24", "pv", "dp", "bell", "cctv", "dis", "pk"}
# 'HH:MM-HH:MM'; closing may pass midnight ('05:00-24:20' = 00:20 next day), so hours go up to 47
_RANGE = re.compile(r"^([0-4]\d):([0-5]\d)-([0-4]\d):([0-5]\d)$")
_HHMM = re.compile(r"^([0-4]\d):([0-5]\d)$")
NEIGHBOUR_KM = (1.0, 25.0)     # same-road, same-direction neighbours used as the reference heading
TWIN_KM = 3.0                  # two directions of one facility are usually < 1 km apart
TWIN_SAME_SPOT_M = 500         # closer than this they face the same stretch of road: >= 120 degrees apart;
                               # farther apart the road may bend in between (평창휴게소 1.1 km, 창녕 1.9 km): >= 90


def angle_diff(a, b):
    d = abs((a - b) % 360.0)
    return min(d, 360.0 - d)


def lane_heading_problems(rows):
    """Lane headings (`hdg`) that contradict the road they are on.

    1. neighbours: the 1/d-weighted circular mean of the same-road (`hw`), same-direction (`dir`) rows
       1-25 km away is the travel direction there; a heading closer to the opposite (> 90 degrees) is wrong
       (2026-11-11 audit: 대신졸음쉼터 both directions, 동광주졸음쉼터 천안방향 were flipped).
    2. twins: the two directions of one facility (same name and road, < 3 km apart) must point in
       clearly different directions (>= 120 degrees apart within 500 m, >= 90 farther apart).
    """
    out = []
    dirl = [r for r in rows if r.get("hdg") is not None and r.get("dir") and r["dir"] != "양방향" and r.get("hw")]
    for f in dirl:
        sx = sy = 0.0
        n = 0
        for g in dirl:
            if g is f or g["hw"] != f["hw"] or g["dir"] != f["dir"]:
                continue
            d = common.haversine_m(f["lat"], f["lon"], g["lat"], g["lon"]) / 1000.0
            if NEIGHBOUR_KM[0] < d < NEIGHBOUR_KM[1]:
                sx += math.sin(math.radians(g["hdg"])) / d
                sy += math.cos(math.radians(g["hdg"])) / d
                n += 1
        if n >= 2 and math.hypot(sx, sy) > 1e-9:
            mean = math.degrees(math.atan2(sx, sy)) % 360.0
            if angle_diff(f["hdg"], mean) > 90:
                out.append(f"{f['id']} {f['n']} {f['hw']} {f['dir']}: hdg {f['hdg']} vs same-direction neighbours "
                           f"{mean:.0f} (n={n})")
    for i, a in enumerate(dirl):
        for b in dirl[i + 1:]:
            if a["n"] != b["n"] or a["hw"] != b["hw"] or a["dir"] == b["dir"]:
                continue
            d = common.haversine_m(a["lat"], a["lon"], b["lat"], b["lon"])
            if d > TWIN_KM * 1000:
                continue
            if angle_diff(a["hdg"], b["hdg"]) < (120 if d < TWIN_SAME_SPOT_M else 90):
                out.append(f"{a['id']}/{b['id']} {a['n']} {a['hw']}: {a['dir']} hdg {a['hdg']} and {b['dir']} hdg "
                           f"{b['hdg']} are not opposite ({d:.0f} m apart)")
    return out


def motorway_problems(d, roadside_rows):
    """motorways.json: simplified carriageways the app map-matches against. Every rest area / shelter road
    (`hw`) must have geometry, or the app could never tell that the car is on that road."""
    p = os.path.join(d, "motorways.json")
    with open(p, encoding="utf-8") as f:
        lines = json.load(f)
    errs = []
    for i, x in enumerate(lines):
        pts = x.get("p")
        if not isinstance(x.get("r"), str) or not x["r"] or x.get("ow") not in (0, 1):
            errs.append(f"line {i}: r={x.get('r')!r} ow={x.get('ow')!r}")
        if not isinstance(pts, list) or len(pts) < 4 or len(pts) % 2:
            errs.append(f"line {i}: {len(pts) if isinstance(pts, list) else pts!r} coordinates")
            continue
        for k in range(0, len(pts), 2):
            if not common.in_korea(pts[k], pts[k + 1]):
                errs.append(f"line {i}: point {k // 2} outside Korea")
                break
    roads = {x.get("r") for x in lines}
    for r in sorted({r["hw"] for r in roadside_rows if r.get("hw")} - roads):
        errs.append(f"road {r} has rest areas/shelters but no motorway geometry")
    n = sum(len(x.get("p") or []) // 2 for x in lines)
    print(f"motorways.json: {len(lines)} lines, {n} points, {os.path.getsize(p)/1e6:.2f} MB, {len(errs)} problems")
    return errs


def hours_problems(fn, i, r):
    out = []
    for k in ("oh", "ohs", "ohh"):
        if k in r and not _RANGE.match(r[k]):
            out.append(f"row {i}: {k}={r[k]!r}")
    for k in ("open", "close"):
        if k in r and not _HHMM.match(r[k]):
            out.append(f"row {i}: {k}={r[k]!r}")
    if "days" in r and (not r["days"] or set(r["days"]) - set("wsh")):
        out.append(f"row {i}: days={r['days']!r}")
    return out


def main():
    d = sys.argv[1]
    bad = 0
    total = 0
    roadside_rows = []
    for fn, req in REQUIRED.items():
        p = os.path.join(d, fn)
        size = os.path.getsize(p)
        total += size
        with open(p, encoding="utf-8") as f:
            rows = json.load(f)
        ids = set()
        errs = []
        for i, r in enumerate(rows):
            for k, t in req.items():
                v = r.get(k)
                if v is None or not (isinstance(v, t) or (t is float and isinstance(v, int))):
                    errs.append(f"row {i}: {k}={v!r}")
            if "lat" in r and not common.in_korea(r["lat"], r["lon"]):
                errs.append(f"row {i}: outside Korea {r['lat']},{r['lon']}")
            for (f2, k), allowed in ENUMS.items():
                if f2 == fn and k in r and r[k] not in allowed:
                    errs.append(f"row {i}: {k}={r[k]!r}")
            for k in FLAGS & set(r):
                if r[k] not in (0, 1):
                    errs.append(f"row {i}: flag {k}={r[k]!r}")
            if r.get("hdg") is not None and not (0 <= r["hdg"] <= 360):
                errs.append(f"row {i}: hdg {r['hdg']}")
            if any(v is None for v in r.values()):
                errs.append(f"row {i}: null value")
            if r.get("id") in ids:
                errs.append(f"row {i}: duplicate id {r['id']}")
            ids.add(r.get("id"))
            errs += hours_problems(fn, i, r)
        if fn in ("rest_areas.json", "drowsy_shelters.json"):
            roadside_rows += rows
        bad += len(errs)
        print(f"{fn}: {len(rows)} rows, {size/1e6:.2f} MB, {len(errs)} problems")
        for e in errs[:10]:
            print("   ", e)
    errs = motorway_problems(d, roadside_rows)
    bad += len(errs)
    for e in errs[:10]:
        print("   ", e)
    lane = lane_heading_problems(roadside_rows)
    bad += len(lane)
    print(f"lane headings (rest areas + shelters): {len(lane)} problems")
    for e in lane:
        print("   ", e)
    with open(os.path.join(d, "meta.json"), encoding="utf-8") as f:
        meta = json.load(f)
    print(f"meta.json: schema {meta['schema']}, {len(meta['attribution'])} attribution lines; total {total/1e6:.2f} MB")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
