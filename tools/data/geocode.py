"""Offline, keyless address geocoder for Korean addresses.

Public-restroom records lost their coordinates in Feb 2025 (source policy change at
행정안전부). Several other public datasets from the same LOCALDATA system still publish
WGS84 or EPSG:5174 coordinates together with road-name and lot-number addresses. This
module builds an address -> coordinate gazetteer from those records and resolves each
address key by clustering its reference points:

  * points from one key that agree within CLUSTER_M metres form a cluster;
  * a key resolves only if its best cluster holds at least half of the distinct points
    (and at least 2 points when the key has 2 or more points);
  * the coordinate is the cluster median, `acc` the cluster radius (min 15 m).

Road-name keys identify one building entrance; lot-number keys identify a parcel, which
can be large (parks, mountains), so they are used only when no road key resolves.
"""
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

CLUSTER_M = 150.0
SINGLE_ACC_ROAD = 60      # assumed accuracy (m) of a road key with one reference point
SINGLE_ACC_LOT = 150      # same for a lot-number key
LICENCE_REFS = ("oil_retailers.csv", "pharmacies.csv", "rest_cafes.csv", "general_restaurants.csv")


class Gazetteer:
    def __init__(self, wanted=None, wanted_roads=None):
        self.pts = {}       # key -> {(lat5, lon5): (lat, lon, src)}
        self.resolved = {}  # key -> (lat, lon, n_points, n_cluster, acc_m)
        self.wanted = wanted  # optional set of keys worth storing (saves memory)
        self.wanted_roads = wanted_roads or set()  # (sido, sgg, road, ug): keep every number

    def add(self, keys, lat, lon, src):
        if not common.in_korea(lat, lon):
            return
        for k in keys:
            if self.wanted is not None and k not in self.wanted and not (k[0] == "R" and k[1:5] in self.wanted_roads):
                continue
            d = self.pts.setdefault(k, {})
            # identical coordinates (e.g. five bells in one restroom) count once
            d.setdefault((round(lat, 5), round(lon, 5)), (lat, lon, src))

    def add_address(self, road_addr, lot_addr, lat, lon, src, hint=(None, None)):
        keys = []
        for a in (road_addr, lot_addr):
            p = common.parse_address(a, hint[0], hint[1])
            if p:
                keys.extend(common.addr_keys(p))
        self.add(set(keys), lat, lon, src)
        return keys

    def finalize(self):
        res = {}
        for k, d in self.pts.items():
            pts = list(d.values())
            n = len(pts)
            if n == 1:
                la, lo, _ = pts[0]
                res[k] = (la, lo, 1, 1, SINGLE_ACC_ROAD if k[0] == "R" else SINGLE_ACC_LOT)
                continue
            if n > 400:      # huge parcels / mis-keyed blocks: sample deterministically
                pts = sorted(pts)[:: max(1, n // 400)]
            best, best_c = None, -1
            for i, (la, lo, _) in enumerate(pts):
                c = sum(1 for (lb, lob, _) in pts if common.haversine_m(la, lo, lb, lob) <= CLUSTER_M)
                if c > best_c:
                    best, best_c = i, c
            sla, slo, _ = pts[best]
            cl = [(lb, lob) for (lb, lob, _) in pts if common.haversine_m(sla, slo, lb, lob) <= CLUSTER_M]
            if len(cl) < 2 or len(cl) * 2 < len(pts):
                continue  # references disagree: leave the key unresolved
            mla, mlo = common.median_point(cl)
            rad = max(common.haversine_m(mla, mlo, a, b) for a, b in cl)
            res[k] = (mla, mlo, len(pts), len(cl), int(max(15, rad)))
        self.resolved = res
        return res

    def lookup(self, keys):
        """Best resolution for a list of keys: road keys first, then lot keys."""
        best = None
        for k in keys:
            r = self.resolved.get(k)
            if not r:
                continue
            rank = (0 if k[0] == "R" else 1, r[4])
            if best is None or rank < best[0]:
                best = (rank, k, r)
        return best


def load_references(gz, raw_dir, org_map, log=print):
    """Fill the gazetteer from every downloaded reference dataset that has coordinates."""
    ld = os.path.join(raw_dir, "localdata")
    wgs = [  # file, road addr col, lot addr col, lat col, lon col
        ("emergency_call_box_info.csv", "소재지도로명주소", "소재지지번주소", "WGS84위도", "WGS84경도"),
        ("free_wifi_info.csv", "소재지도로명주소", "소재지지번주소", "WGS84위도", "WGS84경도"),
        ("civil_defense_shelter_info.csv", "도로명전체주소", "소재지전체주소", "위도(EPSG4326)", "경도(EPSG4326)"),
        ("cctv_info.csv", "소재지도로명주소", "소재지지번주소", "WGS84위도", "WGS84경도"),
    ]
    for fn, ra, ja, la, lo in wgs:
        p = os.path.join(ld, fn)
        if not os.path.exists(p):
            log(f"  (missing {fn})")
            continue
        n = 0
        for r in common.iter_csv(p, ["개방자치단체코드", ra, ja, la, lo]):
            lat, lon = common.to_float(r.get(la)), common.to_float(r.get(lo))
            if not common.in_korea(lat, lon):
                continue
            gz.add_address(r.get(ra), r.get(ja), lat, lon, fn.split("_")[0],
                           org_map.get(common.clean(r.get("개방자치단체코드")), (None, None)))
            n += 1
        log(f"  ref {fn}: {n} points")
    # licence registers (EPSG:5174 = Korean 1985 Modified Central Belt, verified against
    # WGS84 references: median 38 m vs 261 m for EPSG:2097)
    cols = ["개방자치단체코드", "영업상태명", "도로명주소", "지번주소", "좌표정보(X)", "좌표정보(Y)"]
    for fn in LICENCE_REFS:
        p = os.path.join(ld, fn)
        if not os.path.exists(p):
            log(f"  (missing {fn})")
            continue
        n = 0
        for r in common.iter_csv(p, cols):
            x, y = common.to_float(r.get("좌표정보(X)")), common.to_float(r.get("좌표정보(Y)"))
            if not x or not y:
                continue
            lat, lon = common.epsg5174_to_wgs84(x, y)
            if not common.in_korea(lat, lon):
                continue
            # closed businesses still mark a valid building; only the road address is
            # used for them because old lot numbers are often re-parcelled
            closed = common.clean(r.get("영업상태명")).startswith("폐업")
            gz.add_address(r.get("도로명주소"), None if closed else r.get("지번주소"), lat, lon,
                           fn.split(".")[0], org_map.get(common.clean(r.get("개방자치단체코드")), (None, None)))
            n += 1
        log(f"  ref {fn}: {n} points")
    p = os.path.join(raw_dir, "datagokr", "parking.json")
    if os.path.exists(p):
        import json
        with open(p, encoding="utf-8") as f:
            recs = json.load(f)["records"]
        n = 0
        for r in recs:
            lat, lon = common.to_float(r.get("LATITUDE")), common.to_float(r.get("LONGITUDE"))
            if not common.in_korea(lat, lon):
                continue
            gz.add_address(r.get("RDNMADR"), r.get("LNMADR"), lat, lon, "parking",
                           org_map.get(common.clean(r.get("INSTT_CODE")), (None, None)))
            n += 1
        log(f"  ref parking: {n} points")


def _freeze(v):
    return tuple(_freeze(x) for x in v) if isinstance(v, list) else v


def cached_gazetteer(raw_dir, wanted, wanted_roads, org_map, log=print):
    """Build (or reuse) the resolved gazetteer. The cache file lives in RAW_DIR and is keyed by
    the sha256 of every reference file (manifest), the geocoder source and the wanted keys."""
    man = common.load_manifest(raw_dir)
    h = hashlib.sha256()
    for k in sorted(man):
        if k.startswith(("localdata/", "datagokr/parking")):
            h.update((k + str(man[k].get("sha256"))).encode())
    here = os.path.dirname(os.path.abspath(__file__))
    for fn in ("common.py", "geocode.py"):
        with open(os.path.join(here, fn), "rb") as f:
            h.update(f.read())
    h.update(repr(sorted(map(repr, wanted))).encode())
    h.update(repr(sorted(map(repr, wanted_roads))).encode())
    key = h.hexdigest()
    path = os.path.join(raw_dir, "gazetteer_cache.json")
    gz = Gazetteer(wanted, wanted_roads)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        if doc.get("key") == key:
            gz.resolved = {_freeze(k): tuple(v) for k, v in doc["resolved"]}
            gz.pts = {k: None for k in gz.resolved}
            gz.n_keys = doc["n_keys"]
            log(f"  gazetteer cache hit ({len(gz.resolved)} keys)")
            return gz
    load_references(gz, raw_dir, org_map, log=log)
    gz.finalize()
    gz.n_keys = len(gz.pts)
    with open(path + ".part", "w", encoding="utf-8") as f:
        json.dump({"key": key, "n_keys": gz.n_keys, "resolved": [[list(k), list(v)] for k, v in gz.resolved.items()]},
                  f, ensure_ascii=False)
    os.replace(path + ".part", path)
    return gz
