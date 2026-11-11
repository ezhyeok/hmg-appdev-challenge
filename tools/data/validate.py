"""Sanity-check the exported JSON files (types, required keys, coordinates, ids).

    python3 -I -B tools/data/validate.py app/src/main/assets/data
"""
import json
import os
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


def main():
    d = sys.argv[1]
    bad = 0
    total = 0
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
        bad += len(errs)
        print(f"{fn}: {len(rows)} rows, {size/1e6:.2f} MB, {len(errs)} problems")
        for e in errs[:10]:
            print("   ", e)
    with open(os.path.join(d, "meta.json"), encoding="utf-8") as f:
        meta = json.load(f)
    print(f"meta.json: schema {meta['schema']}, {len(meta['attribution'])} attribution lines; total {total/1e6:.2f} MB")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
