"""Places where a car can stop: gas stations and public parking lots.

Gas stations: the 석유판매업 licence register (LOCALDATA, 업태 '주유소', status 영업/정상)
is the backbone because it records openings and closures; OSM amenity=fuel adds brand,
LPG and opening hours, and contributes stations missing from the register (mostly LPG
charging stations, which are licensed elsewhere). OSM stations next to a closed register
entry and no active one are dropped as stale.

Parking: 전국주차장정보표준데이터 (data.go.kr). Lots without coordinates are geocoded with
the same offline gazetteer as restrooms.
"""
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

BRANDS = [  # (canonical, patterns) - checked in order
    ("SK가스", r"SK가스|SK ?Gas"),
    ("E1", r"\bE1\b|E1충전소"),
    ("S-OIL", r"S-?OIL|S-?Oil|에쓰-?오일|에스오일|S오일"),
    ("HD현대오일뱅크", r"현대오일뱅크|오일뱅크|HD현대|에이치디현대|Hyundai ?Oilbank"),
    ("GS칼텍스", r"GS칼텍스|지에스칼텍스|GS ?Caltex|^GS|\bGS\b"),
    ("SK에너지", r"SK에너지|에스케이에너지|엔크린|^SK|\bSK\b|에스케이"),
    ("알뜰주유소", r"알뜰"),
    ("NH농협", r"농협|NH"),
]
_RESTRICTED = re.compile(r"(정기|거주자|관계자|입주|직원|공무|회원|업무).{0,6}전용|전용.{0,4}(정기|거주자)|월주차\s*전용")


def brand_of(*names):
    for n in names:
        n = common.clean(n)
        if not n:
            continue
        for canon, pat in BRANDS:
            if re.search(pat, n):
                return canon
    return None


def _osm_fuel(raw):
    with open(os.path.join(raw, "osm", "fuel.json"), encoding="utf-8") as f:
        els = json.load(f)["elements"]
    out = []
    for e in els:
        t = e.get("tags", {})
        la, lo = (e["center"]["lat"], e["center"]["lon"]) if "center" in e else (e.get("lat"), e.get("lon"))
        if not common.in_korea(la, lo):
            continue
        if t.get("disused") == "yes" or t.get("abandoned") == "yes" or t.get("access") in ("private", "no"):
            continue
        out.append((la, lo, e))
    return out


def wanted_keys(raw, org_map):
    """Address keys of register stations and parking lots that lack coordinates."""
    keys, roads = set(), set()
    p = os.path.join(raw, "localdata", "oil_retailers.csv")
    for r in common.iter_csv(p, ["개방자치단체코드", "업태구분명", "영업상태명", "도로명주소", "지번주소", "좌표정보(X)"]):
        if r["업태구분명"] != "주유소" or not r["영업상태명"].startswith("영업") or common.to_float(r["좌표정보(X)"]):
            continue
        hint = org_map.get(common.clean(r["개방자치단체코드"]), (None, None))
        for a in (r["도로명주소"], r["지번주소"]):
            for k in common.addr_keys(common.parse_address(a, *hint)):
                keys.add(k)
                if k[0] == "R":
                    roads.add(k[1:5])
    with open(os.path.join(raw, "datagokr", "parking.json"), encoding="utf-8") as f:
        for r in json.load(f)["records"]:
            if common.in_korea(common.to_float(r.get("LATITUDE")), common.to_float(r.get("LONGITUDE"))):
                continue
            hint = org_map.get(common.clean(r.get("INSTT_CODE")), (None, None))
            for a in (r.get("RDNMADR"), r.get("LNMADR")):
                for k in common.addr_keys(common.parse_address(a, *hint)):
                    keys.add(k)
                    if k[0] == "R":
                        roads.add(k[1:5])
    return keys, roads


def _geocode(gz, ridx, road, lot, hint):
    cands = []
    for a in (road, lot):
        for k in common.addr_keys(common.parse_address(a, *hint)):
            v = gz.resolved.get(k)
            if v:
                cands.append((v[4], "r" if k[0] == "R" else "l", v[0], v[1]))
            elif k[0] == "R" and ridx:
                x = ridx.lookup(k)
                if x:
                    cands.append((x[2], x[3], x[0], x[1]))
    return min(cands) if cands else None


def build_fuel(raw, org_map, gz, ridx, rep):
    reg_active, reg_closed = [], []
    nocoord = 0
    for r in common.iter_csv(os.path.join(raw, "localdata", "oil_retailers.csv")):
        if r["업태구분명"] != "주유소":
            continue
        st = r["영업상태명"]
        x, y = common.to_float(r["좌표정보(X)"]), common.to_float(r["좌표정보(Y)"])
        lat = lon = None
        gm = "x"
        if x and y:
            lat, lon = common.epsg5174_to_wgs84(x, y)
            if not common.in_korea(lat, lon):
                lat = lon = None
        if st.startswith("영업"):
            if lat is None:
                g = _geocode(gz, ridx, r["도로명주소"], r["지번주소"],
                             org_map.get(common.clean(r["개방자치단체코드"]), (None, None)))
                if g:
                    lat, lon, gm = g[2], g[3], g[1]
                else:
                    nocoord += 1
                    continue
            reg_active.append({"r": r, "lat": lat, "lon": lon, "gm": gm})
        elif st.startswith("폐업") and lat is not None:
            reg_closed.append((lat, lon))
    ga, gc = common.GridIndex(0.01), common.GridIndex(0.01)
    for i, s in enumerate(reg_active):
        ga.add(s["lat"], s["lon"], i)
    for la, lo in reg_closed:
        gc.add(la, lo, None)
    osm = _osm_fuel(raw)
    osm_for = collections.defaultdict(list)
    osm_only, stale = [], 0
    ev_mistag = 0
    for la, lo, e in osm:
        t = e.get("tags", {})
        nm = t.get("name", "") + " " + t.get("name:ko", "")
        if re.search(r"전기차|전기\s*충전|EV\s*충전|슈퍼차저|supercharger", nm, re.I):
            ev_mistag += 1          # EV chargers mis-tagged as amenity=fuel
            continue
        near = ga.near(la, lo, 150)
        if near:
            osm_for[near[0][3]].append(e)
            continue
        liquid = any(t.get(k) == "yes" for k in ("fuel:diesel", "fuel:octane_91", "fuel:octane_92", "fuel:octane_95",
                                                 "fuel:octane_98", "fuel:gasoline"))
        if (t.get("fuel:h70") == "yes" or t.get("fuel:h35") == "yes" or "수소" in nm) and not liquid and t.get("fuel:lpg") != "yes":
            kind = "h2"
        elif (t.get("fuel:lpg") == "yes" and not liquid) or re.search(r"LPG|가스\s*충전|충전소", nm):
            kind = "lpg"
        else:
            kind = "fuel"
        if kind == "fuel" and gc.near(la, lo, 100) and not ga.near(la, lo, 300):
            stale += 1
            continue
        osm_only.append((la, lo, e, kind))
    out = []
    for i, s in enumerate(reg_active):
        r = s["r"]
        tags = [e.get("tags", {}) for e in osm_for.get(i, [])]
        name = common.clean(r["사업장명"])
        br = brand_of(*(t.get("brand") for t in tags), name, *(t.get("name") for t in tags), *(t.get("operator") for t in tags))
        rec = {"id": common.short_id("f", common.clean(r["관리번호"]), name, common.clean(r["도로명주소"])),
               "k": "fuel", "pk": 1, "n": name,
               "a": common.clean(r["도로명주소"]) or common.clean(r["지번주소"]) or None,
               "lat": round(s["lat"], 5), "lon": round(s["lon"], 5)}
        if br:
            rec["br"] = br
        if any(t.get("fuel:lpg") == "yes" for t in tags):
            rec["lpg"] = 1
        if any(t.get("opening_hours") in ("24/7", "Mo-Su 00:00-24:00") for t in tags):
            rec["h24"] = 1
        if any(t.get("toilets") == "yes" for t in tags):
            rec["wc"] = 1
        if s["gm"] != "x":
            rec["gm"] = s["gm"]
        rec["src"] = "lic+osm" if tags else "lic"
        out.append(rec)
    for la, lo, e, kind in osm_only:
        t = e.get("tags", {})
        name = t.get("name") or t.get("name:ko") or {"lpg": "LPG충전소", "h2": "수소충전소"}.get(kind, "주유소")
        rec = {"id": {"node": "n", "way": "w", "relation": "r"}[e["type"]] + str(e["id"]),
               "k": kind, "pk": 1, "n": name, "a": None,
               "lat": round(la, 5), "lon": round(lo, 5)}
        br = brand_of(t.get("brand"), name, t.get("operator"))
        if kind == "lpg" and br == "SK에너지":
            br = "SK가스"
        if br:
            rec["br"] = br
        if t.get("fuel:lpg") == "yes" or kind == "lpg":
            rec["lpg"] = 1
        if t.get("opening_hours") in ("24/7", "Mo-Su 00:00-24:00"):
            rec["h24"] = 1
        if t.get("toilets") == "yes":
            rec["wc"] = 1
        rec["src"] = "osm"
        out.append(rec)
    # the register sometimes lists one station twice (re-registration); OSM may hold
    # a node and an area for one station
    rank = {"lic+osm": 0, "lic": 1, "osm": 2}
    out.sort(key=lambda x: rank[x["src"]])
    g, dedup, dups = common.GridIndex(0.01), [], 0
    for x in out:
        nn = re.sub(r"\s|\(주\)|주식회사|㈜", "", x["n"])
        if any(re.sub(r"\s|\(주\)|주식회사|㈜", "", y["n"]) == nn for _, _, _, y in g.near(x["lat"], x["lon"], 60)):
            dups += 1
            continue
        g.add(x["lat"], x["lon"], x)
        dedup.append(x)
    out = dedup
    rep["fuel_duplicates_removed"] = dups
    rep["fuel_register_active"] = len(reg_active) + nocoord
    rep["fuel_register_geocoded_from_address"] = sum(1 for s in reg_active if s["gm"] != "x")
    rep["fuel_register_no_coordinates"] = nocoord
    rep["fuel_osm_total"] = len(osm)
    rep["fuel_osm_matched_register"] = sum(len(v) for v in osm_for.values())
    rep["fuel_osm_dropped_as_closed"] = stale
    rep["fuel_osm_only_kept"] = len(osm_only)
    rep["fuel_osm_only_lpg"] = sum(1 for x in osm_only if x[3] == "lpg")
    rep["fuel_osm_only_h2"] = sum(1 for x in osm_only if x[3] == "h2")
    rep["fuel_osm_ev_mistag_dropped"] = ev_mistag
    rep["fuel_with_brand"] = sum(1 for x in out if x.get("br"))
    return out


def _hm(s):
    return common.hhmm(s)


def build_parking(raw, org_map, gz, ridx, rep):
    with open(os.path.join(raw, "datagokr", "parking.json"), encoding="utf-8") as f:
        recs = json.load(f)["records"]
    out, nocoord, seen = [], 0, common.GridIndex(0.01)
    geocoded = dups = 0
    for r in recs:
        pid = common.clean(r.get("PRKPLCE_NO"))
        lat, lon = common.to_float(r.get("LATITUDE")), common.to_float(r.get("LONGITUDE"))
        gm = None
        if not common.in_korea(lat, lon):
            g = _geocode(gz, ridx, r.get("RDNMADR"), r.get("LNMADR"), org_map.get(common.clean(r.get("INSTT_CODE")), (None, None)))
            if not g:
                nocoord += 1
                continue
            lat, lon, gm = g[2], g[3], g[1]
            geocoded += 1
        name = common.clean(r.get("PRKPLCE_NM"))
        if any(y == name for _, _, _, y in seen.near(lat, lon, 30)):
            dups += 1         # the same lot published twice (e.g. by 시 and 구)
            continue
        seen.add(lat, lon, name)
        se, ty = common.clean(r.get("PRKPLCE_SE")), common.clean(r.get("PRKPLCE_TYPE"))
        fee = common.clean(r.get("PARKINGCHRGE_INFO"))
        spc = common.clean(r.get("SPCMNT"))
        wd = (_hm(r.get("WEEKDAY_OPER_OPEN_HHMM")), _hm(r.get("WEEKDAY_OPER_COLSE_HHMM")))
        sa = (_hm(r.get("SAT_OPER_OPER_OPEN_HHMM")), _hm(r.get("SAT_OPER_CLOSE_HHMM")))
        ho = (_hm(r.get("HOLIDAY_OPER_OPEN_HHMM")), _hm(r.get("HOLIDAY_CLOSE_OPEN_HHMM")))
        days = common.clean(r.get("OPER_DAY"))
        full = lambda t: t[0] in ("00:00", "00:01") and t[1] in ("23:59", "24:00", "00:00")
        rec = {"id": common.short_id("p", common.clean(r.get("INSTT_CODE")), pid, name, round(lat, 4), round(lon, 4)),
               "k": "park",
               "pk": 0 if _RESTRICTED.search(spc) else 1, "n": name,
               "a": common.clean(r.get("RDNMADR")) or common.clean(r.get("LNMADR")) or None,
               "lat": round(lat, 5), "lon": round(lon, 5),
               "st": ({"공영": "P", "민영": "V"}.get(se, "") + {"노외": "O", "노상": "S", "부설": "A"}.get(ty, "")) or None,
               "cap": common.to_int(r.get("PRKCMPRT")),
               "fee": {"무료": "free", "유료": "paid", "혼합": "mixed"}.get(fee, "mixed" if "+" in fee else None)}
        if all(full(t) for t in (wd, sa, ho)) and days == "평일+토요일+공휴일":
            rec["h24"] = 1
        else:
            if wd[0] and wd[1]:
                rec["oh"] = f"{wd[0]}-{wd[1]}"
            if sa[0] and sa[1] and "토요일" in days:
                rec["ohs"] = f"{sa[0]}-{sa[1]}"
            if ho[0] and ho[1] and "공휴일" in days:
                rec["ohh"] = f"{ho[0]}-{ho[1]}"
            rec["days"] = "".join(c for w, c in (("평일", "w"), ("토요일", "s"), ("공휴일", "h")) if w in days) or None
        bt, bc = common.to_int(r.get("BASIC_TIME")), common.to_int(r.get("BASIC_CHARGE"))
        if rec["fee"] != "free" and bt and bc is not None:
            rec["bt"], rec["bc"] = bt, bc
        if common.to_bool(r.get("PWDBS_PPK_ZONE_YN")):
            rec["dis"] = 1
        if gm:
            rec["gm"] = gm
        rec["src"] = "std"
        out.append(rec)
    rep["parking_source_rows"] = len(recs)
    rep["parking_kept"] = len(out)
    rep["parking_duplicates_removed"] = dups
    rep["parking_geocoded_from_address"] = geocoded
    rep["parking_no_coordinates"] = nocoord
    rep["parking_restricted_pk0"] = sum(1 for x in out if x["pk"] == 0)
    return out


_AT_PARKING = re.compile(r"주유소|충전소|휴게소|졸음쉼터|주차장|오일뱅크|칼텍스|에너지|S-?OIL")


def link_restrooms(parkables, restrooms, rest_areas, shelters, rep, radius=150, pk_radius=100, wc_radius=300):
    """Cross-link restrooms and places to park.

    restroom.pkd  distance (m) to the nearest parkable gas station / parking lot (<= radius)
    restroom.pk   1 = a car can park there: parkable place within pk_radius, or the
                  restroom belongs to a gas station / rest area / parking lot by name
    fuel.wc       1 = restroom evidence (OSM toilets=yes, or a registered open restroom
                  with the station's name or '주유소' within 60 m)
    parking.wcd   distance (m) from the lot to the nearest restroom (<= wc_radius)
    """
    g = common.GridIndex(0.01)
    for p in parkables:
        if p["pk"]:
            g.add(p["lat"], p["lon"], p)
    for x in rest_areas + shelters:
        g.add(x["lat"], x["lon"], {"k": "rest", "n": x["n"], "pk": 1})
    for x in restrooms:
        near = g.near(x["lat"], x["lon"], radius)
        if near:
            x["pkd"] = int(round(near[0][0]))
        if (near and near[0][0] <= pk_radius) or _AT_PARKING.search(x["n"]):
            x["pk"] = 1
        for d, _, _, p in near:
            if p["k"] in ("fuel", "lpg", "h2") and d <= 60 and ("주유소" in x["n"] or "충전소" in x["n"] or
                                                       common.clean(p["n"]).replace(" ", "")[:4] in x["n"].replace(" ", "")):
                p["wc"] = 1
                break
    gr = common.GridIndex(0.01)
    for x in restrooms:
        gr.add(x["lat"], x["lon"], x)
    for p in parkables:
        if p["k"] == "park":
            near = gr.near(p["lat"], p["lon"], wc_radius)
            if near:
                p["wcd"] = int(round(near[0][0]))
    rep["restrooms_parkable_pk1"] = sum(1 for x in restrooms if x.get("pk"))
    rep["restrooms_with_parking_within_%dm" % radius] = sum(1 for x in restrooms if "pkd" in x)
    rep["fuel_with_restroom_evidence"] = sum(1 for p in parkables if p["k"] in ("fuel", "lpg", "h2") and p.get("wc"))
    rep["parking_with_restroom_within_%dm" % wc_radius] = sum(1 for p in parkables if p.get("wcd") is not None)
