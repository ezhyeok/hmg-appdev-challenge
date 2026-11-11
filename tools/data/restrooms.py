"""Public restrooms: 행정안전부 공중화장실정보 (LOCALDATA) geocoded offline, plus OSM toilets.

The MOIS file has no coordinates since Feb 2025. Each record is geocoded from its
road-name / lot-number address with the gazetteer in geocode.py, using (best estimated
accuracy first):

  r   exact road-name address            acc = reference cluster radius (single point 60 m)
  rm  same main building number          acc + 20 m
  ri  interpolated between numbers on the same road (Korean building numbers grow by
      ~10 m per number, odd/even per side; cross-validated median error 10 m same side)
  l   exact lot-number address           acc = cluster radius (single point 150 m)
  b   emergency bell inside a restroom with the same name and street/dong
  o   OpenStreetMap amenity=toilets (native coordinates; only where no MOIS record nearby)

A district sanity check drops geocodes far outside their 시군구.
"""
import bisect
import collections
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

TYPE = {"공중화장실": "p", "개방화장실": "o", "간이화장실": "s", "이동화장실": "m"}
_RANGE = re.compile(r"(\d{1,2})\s*(?::|시|：)?\s*(\d{2})?\s*(?:분)?\s*[~∼\-–〜]\s*(\d{1,2})\s*(?::|시|：)?\s*(\d{2})?")
_24H = re.compile(r"24\s*시간|^\s*24\s*$|상시|00\s*:?\s*00\s*[~∼\-–]\s*(24|00)\s*:?\s*00|^0?0\s*[~∼\-]\s*24")


def parse_hours(kind, detail):
    """(h24, 'HH:MM-HH:MM' or None, keep_raw) from 개방시간 + 개방시간상세."""
    kind = common.clean(kind)
    d = common.clean(detail)
    if d in (":~:", "~", "-", ":"):
        d = ""
    rng = None
    m = _RANGE.search(d)
    if m:
        h1, m1, h2, m2 = int(m.group(1)), int(m.group(2) or 0), int(m.group(3)), int(m.group(4) or 0)
        if h1 <= 24 and h2 <= 24 and m1 < 60 and m2 < 60:
            rng = f"{h1:02d}:{m1:02d}-{h2:02d}:{m2:02d}"
            if rng in ("00:00-24:00", "00:00-00:00", "00:00-23:59", "24:00-24:00"):
                return 1, None, False
    if _24H.search(d) and not (rng and rng not in ("00:00-24:00",)):
        return 1, None, False
    if kind == "상시":
        if rng:
            return 0, rng, True
        return 1, None, bool(d) and not _24H.search(d)
    if kind in ("정시", "불규칙") or d:
        return 0 if (rng or kind) else None, rng, bool(d) and (not rng or len(d) > len(m.group(0)) + 2)
    return None, None, False


class RoadIndex:
    """Resolved road keys grouped per road, for main-number lookup and interpolation."""

    def __init__(self, resolved):
        road = collections.defaultdict(dict)
        for k, v in resolved.items():
            if k[0] != "R":
                continue
            _, sido, sgg, rd, ug, bm, _bs = k
            cur = road[(sido, sgg, rd, ug)].get(bm)
            if cur is None or v[4] < cur[2]:
                road[(sido, sgg, rd, ug)][bm] = (v[0], v[1], v[4])
        self.idx = {rk: sorted((n,) + t for n, t in d.items()) for rk, d in road.items()}

    def lookup(self, key, maxgap=20):
        """key = ('R', sido, sgg, road, ug, main, sub) -> (lat, lon, acc, method) or None."""
        _, sido, sgg, rd, ug, n, _sub = key
        arr = self.idx.get((sido, sgg, rd, ug))
        if not arr:
            return None
        for same_side in (True, False):
            cand = [a for a in arr if (a[0] % 2 == n % 2) or not same_side]
            nums = [a[0] for a in cand]
            i = bisect.bisect_left(nums, n)
            lo = cand[i - 1] if i > 0 else None
            hi = cand[i] if i < len(cand) else None
            if hi and hi[0] == n:
                return hi[1], hi[2], hi[3] + 20, "rm"
            if lo and hi and n - lo[0] <= maxgap and hi[0] - n <= maxgap:
                span = hi[0] - lo[0]
                d = common.haversine_m(lo[1], lo[2], hi[1], hi[2])
                if d <= span * 25 + 100:     # neighbours consistent with ~10 m per number
                    t = (n - lo[0]) / span
                    gap = min(n - lo[0], hi[0] - n)
                    acc = (20 if same_side else 80) + 6 * gap
                    return lo[1] + t * (hi[1] - lo[1]), lo[2] + t * (hi[2] - lo[2]), min(acc, 200), "ri"
            if same_side:
                near = [a for a in (lo, hi) if a and abs(a[0] - n) <= 4]
                if near:
                    a = min(near, key=lambda a: abs(a[0] - n))
                    return a[1], a[2], min(60 + 15 * abs(a[0] - n), 200), "ri"
        return None


def _norm_name(s):
    s = common.clean(s)
    s = re.sub(r"\(.*?\)|\[.*?\]", "", s)
    for w in ("공중화장실", "개방화장실", "간이화장실", "화장실", "공중", "남자", "여자", "남녀", "장애인"):
        s = s.replace(w, "")
    return re.sub(r"[\s\-_.·,]", "", s)


def _bell_index(raw, org_map):
    """Emergency bells installed in restrooms: (sido, sgg) -> [(name, road, dong, lat, lon)]."""
    p = os.path.join(raw, "localdata", "emergency_call_box_info.csv")
    out = collections.defaultdict(list)
    if not os.path.exists(p):
        return out
    for r in common.iter_csv(p, ["개방자치단체코드", "설치장소유형", "설치위치", "소재지도로명주소", "소재지지번주소", "WGS84위도", "WGS84경도"]):
        if r["설치장소유형"] != "화장실" and "화장실" not in r["설치위치"]:
            continue
        lat, lon = common.to_float(r["WGS84위도"]), common.to_float(r["WGS84경도"])
        if not common.in_korea(lat, lon):
            continue
        hint = org_map.get(common.clean(r["개방자치단체코드"]), (None, None))
        p1 = common.parse_address(r["소재지도로명주소"], *hint) or {}
        p2 = common.parse_address(r["소재지지번주소"], *hint) or {}
        sido, sgg = p1.get("sido") or p2.get("sido"), p1.get("sgg") or p2.get("sgg")
        if not sido or sgg is None:
            continue
        nm = _norm_name(r["설치위치"])
        if len(nm) < 2:
            continue
        out[(sido, sgg)].append((nm, p1.get("road"), (p2.get("emd") or p1.get("emd") or (None,))[-1], lat, lon))
    return out


def _district_bounds(points):
    """(sido, sgg) -> (lat, lon, radius_m): median centre and 99th-percentile radius."""
    by = collections.defaultdict(list)
    for (sido, sgg), la, lo in points:
        by[(sido, sgg)].append((la, lo))
    out = {}
    for k, pts in by.items():
        if len(pts) < 20:
            continue
        c = common.median_point(pts)
        ds = sorted(common.haversine_m(c[0], c[1], a, b) for a, b in pts)
        out[k] = (c[0], c[1], ds[int(len(ds) * 0.99) - 1])
    return out


def build(raw, org_map, gz, rep, log=print):
    rows, enc = common.read_csv_rows(os.path.join(raw, "localdata", "public_restroom_info.csv"))
    rep["restroom_source_rows"] = len(rows)
    rep["restroom_source_encoding"] = enc
    ridx = RoadIndex(gz.resolved)
    bells = _bell_index(raw, org_map)
    geo, nocoord, closed = [], [], 0
    method = collections.Counter()
    seen_ids = set()
    for r in rows:
        rid = common.clean(r.get("관리번호"))
        if rid in seen_ids:
            continue
        seen_ids.add(rid)
        kind = common.clean(r.get("개방시간"))
        detail = common.clean(r.get("개방시간상세"))
        if kind == "미개방" or (not kind and detail == "미개방"):
            closed += 1
            continue
        hint = org_map.get(common.clean(r.get("개방자치단체코드")), (None, None))
        pr = common.parse_address(r.get("소재지도로명주소"), *hint) or {}
        pj = common.parse_address(r.get("소재지지번주소"), *hint) or {}
        keys = common.addr_keys(pr) + common.addr_keys(pj)
        cands = []
        for k in keys:
            v = gz.resolved.get(k)
            if v:
                cands.append((v[4], "r" if k[0] == "R" else "l", v[0], v[1]))
            elif k[0] == "R":
                x = ridx.lookup(k)
                if x:
                    cands.append((x[2], x[3], x[0], x[1]))
        name = common.clean(r.get("화장실명"))
        if not cands:
            sido = pr.get("sido") or pj.get("sido")
            sgg = pr.get("sgg") if pr.get("sgg") is not None else pj.get("sgg")
            nm = _norm_name(name)
            if sido and sgg is not None and len(nm) >= 2:
                road = pr.get("road")
                dong = (pj.get("emd") or pr.get("emd") or (None,))[-1]
                hits = [(la, lo) for (bn, br, bd, la, lo) in bells.get((sido, sgg), ())
                        if (nm in bn or bn in nm) and ((road and br == road) or (dong and bd == dong))]
                if hits:
                    c = common.median_point(hits)
                    if max(common.haversine_m(c[0], c[1], a, b) for a, b in hits) <= 100:
                        cands.append((50, "b", c[0], c[1]))
        h24, oh, keep_raw = parse_hours(kind, detail)
        male_dis = (common.to_int(r.get("남성용-장애인용대변기수")) or 0) + (common.to_int(r.get("남성용-장애인용소변기수")) or 0)
        fem_dis = common.to_int(r.get("여성용-장애인용대변기수")) or 0
        rec = {
            "id": common.short_id("t", rid),
            "n": name,
            "t": TYPE.get(common.clean(r.get("구분명")), "u"),
            "a": common.clean(r.get("소재지도로명주소")) or common.clean(r.get("소재지지번주소")) or None,
        }
        if h24 is not None:
            rec["h24"] = h24
        if oh:
            rec["oh"] = oh
        if keep_raw and detail:
            rec["ohr"] = detail[:60]
        if common.to_bool(r.get("기저귀교환대유무")):
            rec["dp"] = 1
            loc = common.clean(r.get("기저귀교환대장소"))
            if loc:
                rec["dpl"] = loc[:30]
        if common.to_bool(r.get("비상벨설치여부")):
            rec["bell"] = 1
        if common.to_bool(r.get("화장실입구CCTV설치유무")):
            rec["cctv"] = 1
        if male_dis + fem_dis > 0:
            rec["dis"] = 1
        if common.clean(r.get("화장실소유구분명")) == "민간":
            rec["pv"] = 1
        org = common.clean(r.get("관리기관명"))
        if org:
            rec["org"] = org[:40]
        rec["_d"] = (pr.get("sido") or pj.get("sido"), pr.get("sgg") if pr.get("sgg") is not None else pj.get("sgg"))
        if cands:
            cands.sort(key=lambda c: c[0])
            acc, m, la, lo = cands[0]
            # a road and a lot geocode far apart means inconsistent source addresses
            others = [c for c in cands if c[1][0] != m[0]]
            if others and common.haversine_m(la, lo, others[0][2], others[0][3]) > 500:
                acc = max(acc, 150)
            rec["lat"], rec["lon"] = round(la, 5), round(lo, 5)
            rec["ga"], rec["gm"] = int(acc), m
            geo.append(rec)
        else:
            nocoord.append(rec)
    geo, m1 = merge_duplicates(geo)
    nocoord, m2 = merge_duplicates(nocoord)
    rep["restroom_duplicates_merged"] = m1 + m2
    # district sanity check (catches wrong district hints / mis-parsed addresses)
    pts = [(x["_d"], x["lat"], x["lon"]) for x in geo if x["_d"][0]]
    bounds = _district_bounds(pts)
    kept, dropped = [], 0
    for x in geo:
        b = bounds.get(x["_d"])
        if b and common.haversine_m(b[0], b[1], x["lat"], x["lon"]) > max(b[2] * 1.3, 8000):
            dropped += 1
            for k in ("lat", "lon", "ga", "gm"):
                x.pop(k, None)
            nocoord.append(x)
            continue
        kept.append(x)
    for x in kept:
        method[x["gm"]] += 1
    rep["restroom_closed_excluded"] = closed
    rep["restroom_district_check_dropped"] = dropped
    rep["restroom_geocoded"] = len(kept)
    rep["restroom_geocode_methods"] = dict(method)
    rep["restroom_geocode_acc_median_m"] = statistics.median(x["ga"] for x in kept) if kept else None
    rep["restroom_no_coordinates"] = len(nocoord)
    by_t = collections.Counter(x["t"] for x in kept)
    by_t_all = collections.Counter(x["t"] for x in kept + nocoord)
    rep["restroom_coverage_by_type"] = {t: f"{by_t[t]}/{by_t_all[t]}" for t in by_t_all}
    by_sido = collections.Counter(x["_d"][0] for x in kept)
    by_sido_all = collections.Counter(x["_d"][0] for x in kept + nocoord)
    rep["restroom_coverage_by_sido"] = {str(s): f"{by_sido[s]}/{by_sido_all[s]}" for s in sorted(by_sido_all, key=str)}
    return kept, nocoord


_FLAGS = ("dp", "bell", "cctv", "dis")


def merge_duplicates(rows):
    """One record per (name, address): several restrooms registered at one address
    (e.g. campus buildings, floors with identical names) share the geocode anyway.
    Amenity flags are OR-ed, `cnt` keeps the number of merged source records."""
    out, by = [], {}
    merged = 0
    for x in rows:
        k = (re.sub(r"\s", "", x["n"]), x.get("a"))
        y = by.get(k)
        if y is None or (x.get("lat") is not None and y.get("lat") is not None
                         and common.haversine_m(x["lat"], x["lon"], y["lat"], y["lon"]) > 50):
            by[k] = x
            out.append(x)
            continue
        merged += 1
        y["cnt"] = y.get("cnt", 1) + 1
        for f in _FLAGS:
            if x.get(f):
                y[f] = 1
        if x.get("dpl") and not y.get("dpl"):
            y["dpl"] = x["dpl"]
        if x.get("h24") == 1:
            y["h24"] = 1
            y.pop("oh", None)
        if x.get("ga") is not None and y.get("ga") is not None and x["ga"] < y["ga"]:
            for f in ("lat", "lon", "ga", "gm"):
                y[f] = x[f]
    return out, merged


_GENERIC = re.compile(r"^(public\s+)?(toilets?|wc|restrooms?|lavatory|dry toilets.*|toilet\s*\d+|parking lot restroom|화장실|공중화장실)$", re.I)


def add_osm_toilets(raw, geo, rep, radius=80):
    """OSM toilets with no MOIS record within `radius` m; also reports agreement stats."""
    with open(os.path.join(raw, "osm", "toilets.json"), encoding="utf-8") as f:
        els = json.load(f)["elements"]
    g = common.GridIndex(0.01)
    for x in geo:
        g.add(x["lat"], x["lon"], x)
    added, matched, skipped = [], 0, 0
    named_d = []
    for e in els:
        t = e.get("tags", {})
        la, lo = (e["center"]["lat"], e["center"]["lon"]) if "center" in e else (e.get("lat"), e.get("lon"))
        if not common.in_korea(la, lo):
            continue
        if t.get("access") in ("private", "no", "customers", "restricted") or t.get("disused") == "yes":
            skipped += 1
            continue
        near = g.near(la, lo, radius)
        nm = t.get("name") or t.get("name:ko") or ""
        if nm:
            nn = _norm_name(nm)
            for d, _, _, x in g.near(la, lo, 1000):
                xn = _norm_name(x["n"])
                if len(nn) >= 2 and (nn in xn or xn in nn):
                    named_d.append(d)
                    break
        if near:
            matched += 1
            continue
        oh = t.get("opening_hours", "")
        ko = t.get("name:ko") or ""
        disp = nm if nm and not _GENERIC.match(nm.strip()) else (ko if ko and not _GENERIC.match(ko.strip()) else "공중화장실")
        rec = {"id": {"node": "n", "way": "w", "relation": "r"}[e["type"]] + str(e["id"]),
               "n": disp, "t": "p", "a": None,
               "lat": round(la, 5), "lon": round(lo, 5), "ga": 10, "gm": "o"}
        if oh in ("24/7", "Mo-Su 00:00-24:00"):
            rec["h24"] = 1
        else:
            m = re.match(r"^(?:Mo-Su\s+)?(\d{2}:\d{2})-(\d{2}:\d{2})$", oh)
            if m:
                rec["h24"], rec["oh"] = 0, f"{m.group(1)}-{m.group(2)}"
            elif oh:
                rec["ohr"] = oh[:60]
        if t.get("changing_table") == "yes":
            rec["dp"] = 1
        if t.get("wheelchair") in ("yes", "designated"):
            rec["dis"] = 1
        if t.get("emergency") == "phone" or t.get("emergency_button") == "yes":
            rec["bell"] = 1
        if t.get("surveillance") in ("yes", "public", "indoor", "outdoor"):
            rec["cctv"] = 1
        if t.get("operator"):
            rec["org"] = t["operator"][:40]
        rec["_d"] = (None, None)
        added.append(rec)
    named_d.sort()
    rep["restroom_osm_toilets"] = len(els)
    rep["restroom_osm_within_%dm_of_mois" % radius] = matched
    rep["restroom_osm_excluded_private"] = skipped
    rep["restroom_osm_added"] = len(added)
    if named_d:
        rep["restroom_osm_named_match_distance_m"] = {
            "n": len(named_d), "median": round(statistics.median(named_d)),
            "p75": round(named_d[int(len(named_d) * 0.75)]), "p90": round(named_d[int(len(named_d) * 0.9)])}
    return added


def wanted_keys(raw, org_map):
    """All address keys of restroom records (plus every road they lie on, for interpolation)."""
    rows, _ = common.read_csv_rows(os.path.join(raw, "localdata", "public_restroom_info.csv"))
    keys, roads = set(), set()
    for r in rows:
        hint = org_map.get(common.clean(r.get("개방자치단체코드")), (None, None))
        for a in (r.get("소재지도로명주소"), r.get("소재지지번주소")):
            p = common.parse_address(a, *hint)
            for k in common.addr_keys(p):
                keys.add(k)
                if k[0] == "R":
                    roads.add(k[1:5])
    return keys, roads
