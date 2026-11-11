"""Expressway rest areas (휴게소) and drowsiness shelters (졸음쉼터).

Primary source: 한국도로공사 standard datasets via data.go.kr. Supplement: OpenStreetMap
highway=services / highway=rest_area next to a motorway carriageway (covers private
expressways, which the 한국도로공사 datasets do not include). Every record gets the travel
heading it serves, derived from OSM motorway geometry (see motorway.py).
"""
import collections
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
from motorway import KEC, angle_diff  # noqa: E402

# Approximate centres of direction-sign destinations, only used to sanity-check that a
# "X방향" label points the same way as the carriageway heading (tolerance 100 degrees).
DEST = {
    "서울": (37.5665, 126.9780), "부산": (35.1796, 129.0756), "서부산": (35.15, 128.95), "인천": (37.4563, 126.7052),
    "대전": (36.3504, 127.3845), "대구": (35.8714, 128.6014), "광주": (35.1595, 126.8526), "울산": (35.5384, 129.3114),
    "세종": (36.48, 127.289), "수원": (37.2636, 127.0286), "순천": (34.9507, 127.4872), "목포": (34.8118, 126.3922),
    "창원": (35.228, 128.6811), "마산": (35.214, 128.58), "양평": (37.4917, 127.4875), "춘천": (37.8813, 127.7298),
    "강릉": (37.7519, 128.8761), "천안": (36.8151, 127.1139), "영덕": (36.415, 129.3656), "통영": (34.8544, 128.4331),
    "포항": (36.019, 129.3435), "청주": (36.6424, 127.489), "완주": (35.9046, 127.1622), "평택": (36.9921, 127.1129),
    "제천": (37.1326, 128.191), "하남": (37.5393, 127.2149), "남이": (36.57, 127.41), "함양": (35.5205, 127.7251),
    "속초": (38.207, 128.5918), "당진": (36.8898, 126.6458), "삼척": (37.4499, 129.1652), "양양": (38.0754, 128.619),
    "무안": (34.9904, 126.4817), "장수": (35.6474, 127.5212), "익산": (35.9483, 126.9577), "서천": (36.0803, 126.6919),
    "공주": (36.4466, 127.119), "영암": (34.8001, 126.6969), "논산": (36.1872, 127.0987), "전주": (35.8242, 127.148),
    "새만금": (35.8, 126.55), "일산": (37.6584, 126.77), "판교": (37.3947, 127.1112), "회덕": (36.395, 127.437),
    "김해": (35.2285, 128.8894), "진주": (35.18, 128.1076), "원주": (37.3422, 127.9202), "여주": (37.2983, 127.6375),
    "이천": (37.2722, 127.435), "안성": (37.008, 127.2797), "용인": (37.2411, 127.1776), "구리": (37.5943, 127.1296),
    "포천": (37.8949, 127.2003), "문산": (37.859, 126.785), "상주": (36.4109, 128.159), "영천": (35.9733, 128.9386),
    "김천": (36.1398, 128.1136), "군산": (35.9676, 126.7366), "고창": (35.4358, 126.7019), "담양": (35.3211, 126.9882),
    "밀양": (35.5038, 128.7464), "동해": (37.5247, 129.1143), "홍천": (37.697, 127.8886), "서산": (36.7845, 126.4503),
    "아산": (36.7898, 127.0018), "보령": (36.3333, 126.6127), "거창": (35.6866, 127.9095), "남원": (35.4164, 127.3904),
    "진천": (36.8553, 127.4357), "음성": (36.94, 127.6905), "충주": (36.991, 127.9259), "경주": (35.8562, 129.2247),
    "안동": (36.5684, 128.7294), "영주": (36.8057, 128.624), "단양": (36.9845, 128.3655), "문경": (36.5865, 128.1867),
    "구미": (36.1195, 128.3446), "칠곡": (35.9956, 128.4017), "해남": (34.5733, 126.599), "광양": (34.9407, 127.6959),
    "여수": (34.7604, 127.6622), "장흥": (34.6817, 126.9071), "보성": (34.7715, 127.08), "나주": (35.016, 126.7108),
    "정읍": (35.5699, 126.856), "김제": (35.8036, 126.8809), "퇴계원": (37.6481, 127.1438), "시흥": (37.38, 126.8029),
    "화성": (37.1995, 126.8312), "오산": (37.1498, 127.0772), "광명": (37.4786, 126.8646), "파주": (37.7599, 126.7802),
    "김포": (37.6153, 126.7156), "가평": (37.8315, 127.5105), "인제": (38.0697, 128.1707), "거제": (34.8806, 128.6211),
    "청도": (35.6473, 128.7341), "고령": (35.7261, 128.2629), "성주": (35.9192, 128.2829), "의성": (36.3527, 128.697),
    "군위": (36.2428, 128.5728), "예천": (36.6577, 128.4528), "봉화": (36.8931, 128.7325), "울진": (36.993, 129.4004),
    "고흥": (34.6111, 127.285), "곡성": (35.282, 127.2919), "구례": (35.2025, 127.4629), "화순": (35.0645, 126.9865),
    "영광": (35.2772, 126.512), "장성": (35.3018, 126.7848), "부안": (35.7318, 126.7334), "임실": (35.6178, 127.289),
    "진안": (35.7917, 127.4248), "무주": (36.0068, 127.6608), "금산": (36.1088, 127.488), "옥천": (36.3063, 127.5713),
    "영동": (36.175, 127.7834), "보은": (36.4895, 127.7295), "괴산": (36.8154, 127.7866), "증평": (36.7853, 127.5814),
    "홍성": (36.6012, 126.6608), "예산": (36.6826, 126.8449), "청양": (36.4592, 126.8022), "부여": (36.2757, 126.9097),
    "태안": (36.7456, 126.2979), "횡성": (37.4917, 127.985), "평창": (37.3708, 128.3903), "정선": (37.3807, 128.6608),
    "영월": (37.1837, 128.4617), "태백": (37.164, 128.9856), "철원": (38.1467, 127.3133), "화천": (38.1062, 127.7082),
    "양구": (38.1099, 127.9897), "의정부": (37.738, 127.0337), "남양주": (37.636, 127.2165), "양주": (37.7853, 127.0458),
    "동두천": (37.9036, 127.0606), "연천": (38.0966, 127.0749), "안산": (37.3219, 126.8309), "안양": (37.3943, 126.9568),
    "성남": (37.42, 127.1265), "부천": (37.5034, 126.766), "고양": (37.6584, 126.832), "의왕": (37.3447, 126.9683),
    "군포": (37.3617, 126.9352), "과천": (37.4292, 126.9876), "송도": (37.38, 126.66), "영종": (37.49, 126.49),
    "사천": (35.0037, 128.0642), "하동": (35.0674, 127.7513), "남해": (34.8376, 127.8924), "산청": (35.4156, 127.8734),
    "합천": (35.5666, 128.1658), "의령": (35.3222, 128.2617), "함안": (35.2725, 128.4064), "창녕": (35.5444, 128.4924),
    "양산": (35.335, 129.0373), "기장": (35.2445, 129.2222), "경산": (35.8251, 128.7415), "영암목포": (34.8, 126.5),
    "경기광주": (37.4294, 127.2551), "동탄": (37.2003, 127.096), "근덕": (37.38, 129.24), "광양": (34.9407, 127.6959),
    "과천": (37.4292, 126.9876), "언양": (35.565, 129.125), "산인": (35.283, 128.429), "산내": (35.731, 129.072),
    "달서": (35.8298, 128.5326), "동대구": (35.8794, 128.6286),
}
# frequent typos / variants in OSM names
_DEST_FIX = {"부상": "부산", "서울방": "서울", "양앙": "양양", "광주광역시": "광주"}

_WS = re.compile(r"\s+")


def fix_dest(d):
    d = common.clean(d)
    if d.endswith("방향"):
        d = d[:-2]
    return _DEST_FIX.get(d, d) or None


def stable_id(prefix, *parts):
    h = hashlib.sha1("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()[:8]
    return prefix + h


def kec_name_dir(name):
    """'횡성(인천)' -> ('횡성', '인천'); '옥천만남' -> ('옥천만남', None)."""
    name = common.clean(name)
    m = re.match(r"^(.*?)\s*\(([^()]+)\)\s*$", name)
    if m:
        return m.group(1).strip(), fix_dest(m.group(2))
    return name, None


_OSM_DIR = re.compile(r"[\(\[]?\s*([가-힣]{1,6}?)\s*(?:방향|방면|행)\s*[\)\]]?")


def osm_name_dir(name):
    """'안성 휴게소 (부산방면)' -> ('안성', '부산', None); '가평휴게소(상행)' -> ('가평', None, '상행')."""
    name = common.clean(name)
    dest, updn = None, None
    m = None
    for m in _OSM_DIR.finditer(name):
        pass
    if m:
        d = _DEST_FIX.get(m.group(1), m.group(1))
        if d in ("상", "하"):
            updn = d + "행"
        else:
            dest = d
        name = name[:m.start()] + name[m.end():]
    base = re.sub(r"\(.*?\)", "", name)
    base = base.replace("휴게소", "").replace("졸음쉼터", "").replace("쉼터", "")
    base = _WS.sub("", base).strip()
    return base, dest, updn


def norm_base(s):
    s = common.clean(s)
    s = re.sub(r"\(.*?\)", "", s)
    for w in ("휴게소", "졸음쉼터", "쉼터", "(주)", "주식회사"):
        s = s.replace(w, "")
    return _WS.sub("", s)


def names_match(a, b):
    a, b = norm_base(a), norm_base(b)
    if not a or not b:
        return False
    return a == b or (len(a) >= 2 and a in b) or (len(b) >= 2 and b in a)


def route_no(ref):
    """'1;55' -> 1 ; '' -> None."""
    m = re.match(r"^\s*(\d+)", ref or "")
    return int(m.group(1)) if m else None


def osm_point(e):
    if "center" in e:
        return e["center"]["lat"], e["center"]["lon"]
    return e.get("lat"), e.get("lon")


def osm_ref(e):
    return {"node": "n", "way": "w", "relation": "r"}[e["type"]] + str(e["id"])


def load_osm(raw, name):
    with open(os.path.join(raw, "osm", name + ".json"), encoding="utf-8") as f:
        return json.load(f)["elements"]


_NOT_REST = re.compile(r"경비실|서비스센터|지정점|정비|카센터|센터|아파트|대리점|AS|A/S|수리|매장|공업사|주유소$|임시")
LEGACY_NODE_ID = 1_000_000_000   # OSM nodes created before ~2011 (bulk POI imports, often stale)


def label_heading(mw, lat, lon, dest, max_m=500):
    """(heading, decisive) of the carriageway that a direction label ('부산방향') selects.

    The straight-line bearing to the destination is only a proxy for the route direction;
    the label is `decisive` when that bearing is within 60 degrees of one carriageway (and
    so >120 from the other). Near-perpendicular cases (e.g. 경부선 near 건천 runs east
    before turning south to 부산) are left to geometry.
    """
    if not dest or dest not in DEST:
        return None, False
    dla, dlo = DEST[dest]
    if common.haversine_m(lat, lon, dla, dlo) < 15000:
        return None, False
    tb = common.bearing_deg(lat, lon, dla, dlo)
    h = mw.heading_toward(lat, lon, tb, max_m)
    if not h:
        return None, False
    return h, angle_diff(h["hdg"], tb) <= 60


def resolve_heading(mw, lat, lon, dest, both, max_m=500):
    """(heading, method, geometry_conflict, motorway_hit).

    A decisive direction label picks the carriageway; otherwise the site is assigned to
    the carriageway it lies right of (traffic keeps right). `geometry_conflict` means the
    point lies beside the carriageway opposite to its label.
    """
    geo = mw.heading_for(lat, lon, max_m=min(max_m, 350))
    if both:
        return None, None, False, geo
    lab, decisive = label_heading(mw, lat, lon, dest, max_m)
    geo_h = geo["hdg"] if geo and not geo["both"] else None
    if lab and decisive:
        conflict = geo_h is not None and angle_diff(geo_h, lab["hdg"]) > 90
        return lab["hdg"], "label", conflict, lab
    if geo_h is not None:
        return geo_h, "geometry", False, geo
    if lab:
        return lab["hdg"], "label-weak", False, lab
    return None, None, False, geo


def _same_direction(lat, lon, d1, d2):
    if not d1 or not d2 or d1 == d2:
        return True
    if d1 in DEST and d2 in DEST:
        b1 = common.bearing_deg(lat, lon, *DEST[d1])
        b2 = common.bearing_deg(lat, lon, *DEST[d2])
        return angle_diff(b1, b2) <= 60
    return False


def _fuel_points(raw):
    """Fuel stations (OSM + active licence register) to flag a gas station at OSM-only rest areas."""
    pts = []
    for e in load_osm(raw, "fuel"):
        la, lo = osm_point(e)
        if common.in_korea(la, lo):
            pts.append((la, lo))
    p = os.path.join(raw, "localdata", "oil_retailers.csv")
    if os.path.exists(p):
        for r in common.iter_csv(p, ["영업상태명", "업태구분명", "좌표정보(X)", "좌표정보(Y)"]):
            if r["업태구분명"] != "주유소" or not r["영업상태명"].startswith("영업"):
                continue
            x, y = common.to_float(r["좌표정보(X)"]), common.to_float(r["좌표정보(Y)"])
            if x and y:
                la, lo = common.epsg5174_to_wgs84(x, y)
                if common.in_korea(la, lo):
                    pts.append((la, lo))
    g = common.GridIndex(0.01)
    for la, lo in pts:
        g.add(la, lo, None)
    return g


def _b(v):
    b = common.to_bool(v)
    return None if b is None else int(b)


def resolve_pairs(mw, pre, keyf, issues, label, max_m):
    """Joint heading assignment for the two directions of one facility.

    When the two direction labels point to clearly different sides (>=120 degrees apart),
    each record takes the carriageway closest to its own label bearing even if that alone
    would not be decisive. When the labels do not separate the directions (ring roads
    such as 대구외곽순환선: '달서방향'/'동대구방향' both lie south-east) and the two nearby
    records got the same carriageway, the heading is left empty rather than guessed.
    """
    groups = collections.defaultdict(list)
    for p in pre:
        if p["dest"] and not p["both"]:
            groups[keyf(p)].append(p)

    def usable(q):
        return q["dest"] in DEST and common.haversine_m(q["lat"], q["lon"], *DEST[q["dest"]]) >= 15000

    def assign(q, h, why):
        if q["hdg"] is not None and angle_diff(q["hdg"], h["hdg"]) > 60:
            issues.append(f"{label} {q['name']} {q['dest']}방향: heading {q['hdg']} -> {h['hdg']} ({why})")
        q["hdg"], q["hit"] = h["hdg"], h
        geo = mw.heading_for(q["lat"], q["lon"], max_m=min(max_m, 350))
        q["conflict"] = bool(geo and geo["hdg"] is not None and not geo["both"] and angle_diff(geo["hdg"], h["hdg"]) > 90)

    for ps in groups.values():
        if len(ps) != 2 or ps[0]["dest"] == ps[1]["dest"]:
            continue
        a, b = ps
        close = common.haversine_m(a["lat"], a["lon"], b["lat"], b["lon"]) < 2000
        ua, ub = usable(a), usable(b)
        if ua and ub:
            tba = common.bearing_deg(a["lat"], a["lon"], *DEST[a["dest"]])
            tbb = common.bearing_deg(b["lat"], b["lon"], *DEST[b["dest"]])
            if angle_diff(tba, tbb) >= 120:
                ha = mw.heading_toward(a["lat"], a["lon"], tba, max_m)
                hb = mw.heading_toward(b["lat"], b["lon"], tbb, max_m)
                if ha and hb and (not close or angle_diff(ha["hdg"], hb["hdg"]) > 120):
                    assign(a, ha, "decided by the pair of labels")
                    assign(b, hb, "decided by the pair of labels")
                continue
            if close and a["hdg"] is not None and b["hdg"] is not None and angle_diff(a["hdg"], b["hdg"]) < 120:
                issues.append(f"{label} {a['name']}: labels {a['dest']}/{b['dest']} do not separate the carriageways "
                              f"and both points got heading ~{a['hdg']}; heading left empty")
                a["hdg"] = b["hdg"] = None
                a["conflict"] = b["conflict"] = False
        elif (ua or ub) and close:
            # one label is usable (the other destination is too close, e.g. 송탄 is inside 평택):
            # the other record takes the opposite carriageway
            f, o = (a, b) if ua else (b, a)
            hf, decisive = label_heading(mw, f["lat"], f["lon"], f["dest"], max_m)
            if hf and decisive:
                ho = mw.heading_toward(o["lat"], o["lon"], (hf["hdg"] + 180) % 360, max_m)
                if ho:
                    assign(f, hf, "label")
                    assign(o, ho, f"opposite of the {f['dest']}방향 record")
    # final consistency: two directions of one facility cannot share a heading
    for ps in groups.values():
        if len(ps) != 2 or ps[0]["dest"] == ps[1]["dest"]:
            continue
        a, b = ps
        if (a["hdg"] is not None and b["hdg"] is not None and angle_diff(a["hdg"], b["hdg"]) < 60
                and common.haversine_m(a["lat"], a["lon"], b["lat"], b["lon"]) < 3000):
            # retry with nearby destinations (>= 3 km) when their bearings are clearly opposite
            if all(q["dest"] in DEST and common.haversine_m(q["lat"], q["lon"], *DEST[q["dest"]]) >= 3000 for q in (a, b)):
                tba = common.bearing_deg(a["lat"], a["lon"], *DEST[a["dest"]])
                tbb = common.bearing_deg(b["lat"], b["lon"], *DEST[b["dest"]])
                if angle_diff(tba, tbb) >= 120:
                    ha = mw.heading_toward(a["lat"], a["lon"], tba, max_m)
                    hb = mw.heading_toward(b["lat"], b["lon"], tbb, max_m)
                    if ha and hb and angle_diff(ha["hdg"], hb["hdg"]) > 120:
                        assign(a, ha, "pair of nearby destinations")
                        assign(b, hb, "pair of nearby destinations")
                        continue
            issues.append(f"{label} {a['name']}: {a['dest']}/{b['dest']} records got the same heading ~{a['hdg']} "
                          f"and the labels cannot tell which is wrong; heading left empty")
            a["hdg"] = b["hdg"] = None
            a["conflict"] = b["conflict"] = False


def build_rest_areas(raw, mw, rep):
    with open(os.path.join(raw, "datagokr", "rest_areas.json"), encoding="utf-8") as f:
        doc = json.load(f)
    og = common.GridIndex(0.01)
    osm_pts = []
    for e in load_osm(raw, "services"):
        la, lo = osm_point(e)
        if common.in_korea(la, lo):
            osm_pts.append((la, lo, e))
            og.add(la, lo, e)
    out, used_osm, issues = [], set(), []
    pre = []
    for r in doc["records"]:
        base, dest = kec_name_dir(r.get("ENTRPS_NM"))
        lat, lon = common.to_float(r.get("LATITUDE")), common.to_float(r.get("LONGITUDE"))
        if not common.in_korea(lat, lon):
            issues.append(f"KEC {base}: invalid coordinates {lat},{lon}")
            continue
        both = dest is None
        name = base + ("휴게소" if not base.endswith("휴게소") else "")
        hdg, method, conflict, hit = resolve_heading(mw, lat, lon, dest, both)
        pre.append({"r": r, "base": base, "dest": dest, "lat": lat, "lon": lon, "both": both, "name": name,
                    "hdg": hdg, "conflict": conflict, "hit": hit})
    resolve_pairs(mw, pre, lambda p: (p["r"].get("ROAD_ROUTE_NO"), p["base"]), issues, "KEC", 500)
    for p in pre:
        r, base, dest, lat, lon, both, name = p["r"], p["base"], p["dest"], p["lat"], p["lon"], p["both"], p["name"]
        hdg, conflict, hit = p["hdg"], p["conflict"], p["hit"]
        updn = common.clean(r.get("ROAD_ROUTE_DRC"))
        dirn = "양방향" if both else dest + "방향"
        if conflict:
            issues.append(f"KEC {name} {dirn}: point lies beside the opposite carriageway; heading taken from the label")
        if not hit:
            issues.append(f"KEC {name} {dirn}: no motorway within 500 m")
        elif not both and hdg is None:
            issues.append(f"KEC {name} {dirn}: no carriageway matches the direction label")
        # OSM counterpart (services or rest_area; OSM often tags rest areas as rest_area)
        best = None
        for d, la, lo, e in og.near(lat, lon, 2000):
            if osm_ref(e) in used_osm:
                continue
            ob, od, _ = osm_name_dir(e["tags"].get("name:ko") or e["tags"].get("name", ""))
            if not names_match(base, ob):
                continue
            if not both and od and not _same_direction(lat, lon, od, dest):
                continue
            if not both and not od and hdg is not None:
                oh = mw.heading_for(la, lo)
                if oh and oh["hdg"] is not None and angle_diff(oh["hdg"], hdg) > 90:
                    continue
            score = (0 if e["tags"].get("highway") == "services" else 1, d)
            if best is None or score < best[0]:
                best = (score, d, e)
        pos = None
        if best:
            used_osm.add(osm_ref(best[2]))
            if conflict:
                bla, blo = osm_point(best[2])
                og_ = mw.heading_for(bla, blo)
                if og_ and og_["hdg"] is not None and angle_diff(og_["hdg"], hdg) <= 60:
                    issues.append(f"KEC {name} {dirn}: position replaced by OSM {osm_ref(best[2])} "
                                  f"({int(best[1])} m away, beside the labelled carriageway)")
                    lat, lon, pos = bla, blo, "osm"
        way = mw.way_info(hit["way"]) if hit else {}
        open_, close_ = common.hhmm(r.get("OPER_OPEN_HHMM")), common.hhmm(r.get("OPER_CLOSE_HHMM"))
        h24 = 1 if (open_ == "00:00" and close_ in ("23:59", "24:00", "00:00")) else 0
        out.append({
            "id": stable_id("k", r.get("ROAD_ROUTE_NO"), base, dirn),
            "n": name,
            "rt": common.clean(r.get("ROAD_ROUTE_NM")),
            "hw": way.get("name") or None,
            "rno": common.to_int(r.get("ROAD_ROUTE_NO")),
            "dir": dirn,
            "dirRaw": updn or None,
            "hdg": hdg,
            "lat": round(lat, 6), "lon": round(lon, 6),
            "k": {"일반휴게소": "general", "화물차휴게소": "truck", "간이휴게소": "simple"}.get(common.clean(r.get("REST_AREA_TYPE")), "general"),
            "wc": 1,
            "nr": _b(r.get("NRSG_YN")), "ev": _b(r.get("ELCTY_YN")), "gas": _b(r.get("OLT_YN")),
            "lpg": _b(r.get("LPG_YN")), "ph": _b(r.get("PARMACY_YN")),
            "food": _b(r.get("RSTRT_YN")), "shop": _b(r.get("SHOP_YN")),
            "h24": h24, "open": open_, "close": close_,
            "park": common.to_int(r.get("PRKPLCE_CO")),
            "op": KEC, "pv": 0, "src": "kec",
            "osm": osm_ref(best[2]) if best else None,
            "xm": int(round(best[1])) if best else None,
            "pos": pos,
        })
    # identical coordinates for different directions = position of one side is approximate
    seen = {}
    for x in out:
        seen.setdefault((x["lat"], x["lon"]), []).append(x)
    for k, xs in seen.items():
        if len(xs) > 1:
            for x in xs:
                x["pos"] = x["pos"] or "shared"
            issues.append(f"KEC {xs[0]['n']}: {len(xs)} directions share one coordinate {k}")
    rep["rest_areas_kec"] = len(out)
    rep["rest_areas_kec_osm_matched"] = sum(1 for x in out if x["osm"])
    rep["rest_areas_kec_osm_distance_m"] = sorted(x["xm"] for x in out if x["xm"] is not None)

    # OSM-only services on private (non-한국도로공사) motorways; KEC data is authoritative
    # for KEC-operated routes, so OSM extras there are treated as stale duplicates.
    fuel = _fuel_points(raw)
    extra = []
    for la, lo, e in osm_pts:
        t = e["tags"]
        if osm_ref(e) in used_osm or t.get("highway") not in ("services", "rest_area"):
            continue
        if e["type"] == "node" and e["id"] < LEGACY_NODE_ID:
            continue
        name = t.get("name:ko") or t.get("name") or ""
        if "휴게소" not in name or _NOT_REST.search(name):
            continue
        geo = mw.heading_for(la, lo)
        if not geo or geo["dist"] > 350:
            continue
        way = mw.way_info(geo["way"])
        if way.get("operator") == KEC or not way.get("operator"):
            continue
        ob, od, updn = osm_name_dir(name)
        if any(names_match(ob, x["n"]) and common.haversine_m(la, lo, x["lat"], x["lon"]) < 2500 for x in out):
            continue
        hdg, method, conflict, hit = resolve_heading(mw, la, lo, od, False)
        if conflict:
            issues.append(f"OSM {name}: lies beside the opposite carriageway of its label")
        gas = None
        if hdg is not None:
            for _, fla, flo, _ in fuel.near(la, lo, 350):
                fh = mw.heading_for(fla, flo)
                if fh and fh["hdg"] is not None and angle_diff(fh["hdg"], hdg) <= 60:
                    gas = 1
                    break
        oh = t.get("opening_hours", "")
        extra.append({
            "id": stable_id("o", osm_ref(e)),
            "n": (ob or name) + "휴게소",
            "rt": way.get("name") or None,
            "hw": way.get("name") or None,
            "rno": route_no(way.get("ref")),
            "dir": (od + "방향") if od else None,
            "dirRaw": updn or (name if od else None),
            "hdg": hdg,
            "lat": round(la, 6), "lon": round(lo, 6),
            "k": "general", "wc": 1,
            "nr": 1 if t.get("baby_feeding") in ("yes", "room") else None,
            "ev": None, "gas": gas, "lpg": None, "ph": None,
            "h24": 1 if oh in ("24/7", "Mo-Su 00:00-24:00") else None, "open": None, "close": None,
            "park": None,
            "op": way.get("operator"), "pv": 1,
            "src": "osm", "osm": osm_ref(e), "xm": None,
        })
    extra.sort(key=lambda x: (x["osm"][0] != "w", x["osm"]))
    dedup = []
    for x in extra:
        if any(names_match(x["n"], y["n"]) and common.haversine_m(x["lat"], x["lon"], y["lat"], y["lon"]) < 600
               and (x["hdg"] is None or y["hdg"] is None or angle_diff(x["hdg"], y["hdg"]) < 90) for y in dedup):
            continue
        dedup.append(x)
    rep["rest_areas_osm_private"] = len(dedup)
    rep["rest_area_issues"] = issues
    allr = out + dedup
    allr.sort(key=lambda x: (x["rno"] if x["rno"] is not None else 9999, x["n"], x["dir"] or ""))
    return allr


def kec_shelter_dir(s):
    """'서울기점 + 부산종점' -> ('부산', '서울기점 + 부산종점')."""
    s = common.clean(s)
    m = re.search(r"([가-힣]+?)종점", s)
    return (fix_dest(m.group(1)) if m else None), (s or None)


def _load_install(raw):
    """한국도로공사_졸음쉼터 설치현황 (name, route, milepost, direction), if downloaded."""
    p = os.path.join(raw, "datagokr", "drowsy_shelters_install.csv")
    if not os.path.exists(p):
        return []
    rows, _ = common.read_csv_rows(p)
    return [{"n": common.clean(r.get("명칭")), "rt": common.clean(r.get("노선")),
             "km": common.to_float(r.get("이정")), "dest": fix_dest(r.get("방향")),
             "year": common.to_int(r.get("설치년도"))} for r in rows]


def _rt_norm(s):
    return re.sub(r"[\s()]|선$|고속도로$", "", common.clean(s)).replace("광주광역시", "광주")


def build_drowsy(raw, mw, rest_areas, rep):
    with open(os.path.join(raw, "datagokr", "drowsy_shelters.json"), encoding="utf-8") as f:
        doc = json.load(f)
    install = _load_install(raw)
    og = common.GridIndex(0.01)
    for e in load_osm(raw, "services"):
        la, lo = osm_point(e)
        if common.in_korea(la, lo) and e["tags"].get("highway") == "rest_area":
            og.add(la, lo, e)
    out, issues, used = [], [], set()
    pre = []
    for r in doc["records"]:
        lat, lon = common.to_float(r.get("LATITUDE")), common.to_float(r.get("LONGITUDE"))
        name = common.clean(r.get("SHLTR_NM"))
        disp = name + ("졸음쉼터" if not name.endswith("쉼터") else "")
        if not common.in_korea(lat, lon):
            issues.append(f"KEC shelter {disp}: invalid coordinates")
            continue
        dest, raw_dir = kec_shelter_dir(r.get("ROAD_ROUTE_DRC"))
        rt = common.clean(r.get("ROAD_ROUTE_NM")) or None
        # second KEC source: installation list (name, route, milepost, direction)
        cands = [i for i in install if i["n"] == name and _rt_norm(i["rt"]) == _rt_norm(rt)]
        inst = next((i for i in cands if _same_direction(lat, lon, i["dest"], dest)), None)
        if cands and not inst:
            alt = {i["dest"] for i in cands}
            geo = mw.heading_for(lat, lon, max_m=300)
            if len(alt) == 1 and geo and geo["hdg"] is not None:
                a = next(iter(alt))
                lab, decisive = label_heading(mw, lat, lon, a, 300)
                if lab and decisive and angle_diff(lab["hdg"], geo["hdg"]) <= 60:
                    issues.append(f"KEC shelter {disp}: direction {dest} -> {a} (installation list and geometry agree)")
                    dest, inst = a, cands[0]
            if not inst:
                issues.append(f"KEC shelter {disp}: direction '{dest}' not in the installation list ({sorted(alt)})")
        hdg, method, conflict, hit = resolve_heading(mw, lat, lon, dest, False, max_m=300)
        pre.append({"r": r, "name": disp, "base": name, "dest": dest, "raw_dir": raw_dir, "rt": rt, "inst": inst,
                    "lat": lat, "lon": lon, "both": False, "hdg": hdg, "conflict": conflict, "hit": hit})
    resolve_pairs(mw, pre, lambda p: (p["r"].get("ROAD_ROUTE_NO"), p["base"]), issues, "KEC shelter", 300)
    for p in pre:
        r, disp, name, dest, raw_dir, rt, inst = p["r"], p["name"], p["base"], p["dest"], p["raw_dir"], p["rt"], p["inst"]
        lat, lon, hdg, conflict, hit = p["lat"], p["lon"], p["hdg"], p["conflict"], p["hit"]
        if not hit:
            issues.append(f"KEC shelter {disp} {dest}방향: no motorway within 300 m")
        # OSM counterpart (same name, same carriageway) - also used to fix shared/wrong points
        best = None
        for d, la, lo, e in og.near(lat, lon, 1500):
            if osm_ref(e) in used:
                continue
            ob, od, _ = osm_name_dir(e["tags"].get("name:ko") or e["tags"].get("name", ""))
            if not names_match(name, ob):
                continue
            oh = mw.heading_for(la, lo, max_m=300)
            if hdg is not None and (not oh or oh["hdg"] is None or angle_diff(oh["hdg"], hdg) > 60):
                continue
            if hdg is None and dest in DEST:   # no heading at the KEC point: check the OSM site against the label
                tb = common.bearing_deg(la, lo, *DEST[dest])
                if not oh or oh["hdg"] is None or angle_diff(oh["hdg"], tb) >= 90:
                    continue
            if best is None or d < best[0]:
                best = (d, e)
        pos = None
        if best:
            used.add(osm_ref(best[1]))
        out.append({
            "id": stable_id("d", r.get("ROAD_ROUTE_NO"), name, raw_dir, round(lat, 4), round(lon, 4)),
            "n": disp, "rt": rt, "hw": mw.way_info(hit["way"]).get("name") if hit else None,
            "rno": common.to_int(r.get("ROAD_ROUTE_NO")),
            "dir": (dest + "방향") if dest else None, "dirRaw": raw_dir,
            "hdg": hdg, "km": inst["km"] if inst else None,
            "lat": round(lat, 6), "lon": round(lon, 6),
            "k": "drowsy", "wc": _b(r.get("TOILET_YN")),
            "park": common.to_int(r.get("PRKPLCE_CO")), "cctv": common.to_int(r.get("CCTV_CO")),
            "etc": common.clean(r.get("ETC_CVNTL")) or None,
            "a": common.clean(r.get("RDNMADR")) or common.clean(r.get("LNMADR")) or None,
            "op": KEC, "pv": 0, "src": "kec",
            "osm": osm_ref(best[1]) if best else None, "xm": int(round(best[0])) if best else None,
            "pos": pos, "_conflict": conflict or not hit, "_osm_e": best[1] if best else None,
            "_dest": dest,
        })
    # shared coordinates / point beside the wrong carriageway: take the OSM position
    seen = {}
    for x in out:
        seen.setdefault((x["lat"], x["lon"]), []).append(x)
    shared = 0
    for k, xs in seen.items():
        if len(xs) > 1:
            shared += len(xs)
            for x in xs:
                x["pos"] = "shared"
    fixed = 0
    for x in out:
        e = x.pop("_osm_e")
        conflict = x.pop("_conflict")
        dest = x.pop("_dest")
        if (conflict or x["pos"] == "shared") and e is not None:
            la, lo = osm_point(e)
            x["lat"], x["lon"], x["pos"] = round(la, 6), round(lo, 6), "osm"
            fixed += 1
            issues.append(f"KEC shelter {x['n']} {x['dir']}: position taken from OSM {x['osm']} ({x['xm']} m from the KEC point)")
            if x["hdg"] is None:
                h2, _m, _c, hit2 = resolve_heading(mw, la, lo, dest, False, max_m=300)
                x["hdg"] = h2
                if hit2 and not x.get("hw"):
                    x["hw"] = mw.way_info(hit2["way"]).get("name")
        elif conflict:
            issues.append(f"KEC shelter {x['n']} {x['dir']}: point lies beside the opposite carriageway; heading from the label")
    rep["drowsy_kec_position_from_osm"] = fixed
    rep["drowsy_kec"] = len(out)
    rep["drowsy_kec_shared_coordinate"] = shared
    rep["drowsy_kec_with_milepost"] = sum(1 for x in out if x["km"] is not None)
    # OSM rest_area on private motorways (the KEC list covers KEC-operated routes only)
    known = [(x["lat"], x["lon"], x["hdg"]) for x in out] + [(x["lat"], x["lon"], x["hdg"]) for x in rest_areas]
    extra = []
    for e in load_osm(raw, "services"):
        t = e["tags"]
        if t.get("highway") != "rest_area" or (e["type"] == "node" and e["id"] < LEGACY_NODE_ID):
            continue
        la, lo = osm_point(e)
        if not common.in_korea(la, lo):
            continue
        geo = mw.heading_for(la, lo)
        if not geo or geo["dist"] > 300:
            continue
        way = mw.way_info(geo["way"])
        if way.get("operator") == KEC or not way.get("operator"):
            continue
        name = t.get("name:ko") or t.get("name") or ""
        if _NOT_REST.search(name) or "휴게소" in name:
            continue
        ob, od, updn = osm_name_dir(name)
        hdg, method, conflict, hit = resolve_heading(mw, la, lo, od, False, max_m=300)
        if any(common.haversine_m(la, lo, a, b) < 500 and (hdg is None or kh is None or angle_diff(hdg, kh) < 90)
               for a, b, kh in known):
            continue
        toilets = t.get("toilets")
        extra.append({
            "id": stable_id("o", osm_ref(e)),
            "n": (ob + "졸음쉼터") if ob else "졸음쉼터",
            "rt": way.get("name") or None, "hw": way.get("name") or None,
            "rno": route_no(way.get("ref")),
            "dir": (od + "방향") if od else None, "dirRaw": updn or (name if od else None),
            "hdg": hdg, "km": None,
            "lat": round(la, 6), "lon": round(lo, 6),
            "k": "drowsy",
            "wc": 1 if toilets == "yes" else (0 if toilets == "no" else None),
            "park": None, "cctv": None, "etc": None, "a": None,
            "op": way.get("operator"), "pv": 1, "src": "osm", "osm": osm_ref(e),
        })
    dedup = []
    for x in sorted(extra, key=lambda x: (x["osm"][0] != "w", x["osm"])):
        if any(common.haversine_m(x["lat"], x["lon"], y["lat"], y["lon"]) < 300 and
               (x["hdg"] is None or y["hdg"] is None or angle_diff(x["hdg"], y["hdg"]) < 90) for y in dedup):
            continue
        dedup.append(x)
    rep["drowsy_osm_private"] = len(dedup)
    rep["drowsy_osm_private_with_toilets"] = sum(1 for x in dedup if x["wc"] == 1)
    rep["drowsy_issues"] = issues
    allr = out + dedup
    allr.sort(key=lambda x: (x["rno"] if x["rno"] is not None else 9999, x["n"], x["dir"] or ""))
    return allr
