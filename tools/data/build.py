"""Clean, geocode and export the offline POI datasets bundled with the app.

    python3 -I -B tools/data/build.py RAW_DIR OUT_DIR [--report REPORT.json]

RAW_DIR is what fetch.py downloaded; OUT_DIR is normally app/src/main/assets/data.
The first run takes ~5 minutes (streaming 3.4 M reference rows into the geocoding
gazetteer); the resolved gazetteer is cached in RAW_DIR and reused while the inputs and
geocoder code are unchanged (~30 s). A JSON report with counts, coverage and data issues
is written to REPORT (default RAW_DIR/build_report.json). Output is deterministic.
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import geocode  # noqa: E402
import motorway  # noqa: E402
import parkables  # noqa: E402
import restrooms  # noqa: E402
import roadside  # noqa: E402

SCHEMA_VERSION = 1
CROSSCHECK = ["안성휴게소", "기흥휴게소", "망향휴게소", "서울만남휴게소", "죽전휴게소", "덕평휴게소", "함안휴게소",
              "밀양영남루휴게소", "건천휴게소", "평사휴게소"]

ATTRIBUTION = [
    "공중화장실·안전비상벨·CCTV·무료와이파이·민방위대피시설·석유판매업·약국·휴게음식점·일반음식점 정보: "
    "행정안전부 지방행정 인허가·생활편의 데이터(LOCALDATA, file.localdata.go.kr), 이용허락범위 제한 없음",
    "휴게소·졸음쉼터 정보: 한국도로공사, 전국휴게소정보표준데이터·전국졸음쉼터표준데이터·졸음쉼터 설치현황 "
    "(공공데이터포털 data.go.kr), 이용허락범위 제한 없음",
    "주차장 정보: 전국주차장정보표준데이터, 각 지방자치단체 (공공데이터포털 data.go.kr), 이용허락범위 제한 없음",
    "© OpenStreetMap contributors, ODbL 1.0 (openstreetmap.org/copyright): 고속도로 선형, 민자고속도로 휴게소·졸음쉼터, "
    "주유소·LPG충전소, 공중화장실 일부",
]


_SIDO_SHORT = {"서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천",
               "광주광역시": "광주", "대전광역시": "대전", "울산광역시": "울산", "세종특별자치시": "세종",
               "경기도": "경기", "강원특별자치도": "강원", "강원도": "강원", "충청북도": "충북", "충청남도": "충남",
               "전북특별자치도": "전북", "전라북도": "전북", "전라남도": "전남", "경상북도": "경북", "경상남도": "경남",
               "제주특별자치도": "제주", "전남광주통합특별시": "전남광주"}
_SIDO_ORDER = sorted(_SIDO_SHORT, key=len, reverse=True)


def short_addr(a):
    """Display address: short province name, no trailing '(동, 건물)' note."""
    a = common.clean(a)
    a = re.sub(r"\s*\([^()]*\)\s*$", "", a)
    for full in _SIDO_ORDER:
        if a.startswith(full):
            a = _SIDO_SHORT[full] + " " + a[len(full):].lstrip()
            break
    return a or None


def cut(s, n):
    return s if s is None or len(s) <= n else s[:n]


def export_restroom(x, with_coords=True):
    r = {"id": x["id"], "n": x["n"], "t": x["t"]}
    if x.get("a"):
        r["a"] = short_addr(x["a"])
    if with_coords:
        r["lat"], r["lon"], r["ga"] = x["lat"], x["lon"], x["ga"]
        fields = ("h24", "oh", "ohr", "dp", "dpl", "bell", "cctv", "dis", "org", "cnt", "pk", "pkd")
    else:
        # address-only rows: essentials, plus the region when the address does not name it
        d = x.get("_d") or (None, None)
        if d[0] and not (r.get("a") or "").startswith((d[0], common.SIDO_FULL.get(d[0], "~"))):
            r["rg"] = (d[0] + " " + (d[1] or "")).strip()
        fields = ("h24", "oh", "dp", "bell", "cctv", "dis")
    for k in fields:
        if x.get(k) is not None:
            r[k] = x[k]
    if "ohr" in r:
        r["ohr"] = cut(r["ohr"], 40)
    if "org" in r:
        r["org"] = cut(r["org"], 20)
    if "dpl" in r:
        r["dpl"] = cut(r["dpl"], 20)
    if x.get("gm") == "o":
        r["src"] = "osm"
    return r


def export_parkable(x):
    r = {k: v for k, v in x.items() if k not in ("a", "wcid") and v is not None}
    if x["k"] == "park" or r.get("src") == "lic":   # default source: parking std / licence register
        r.pop("src", None)
    return r


def check_ids(name, rows, rep):
    """Ids must be unique per file; a hash collision gets a deterministic suffix."""
    seen, fixed = set(), 0
    for r in rows:
        i, n = r["id"], 2
        while r["id"] in seen:
            r["id"] = f"{i}{n}"
            n += 1
        if r["id"] != i:
            fixed += 1
        seen.add(r["id"])
    rep[f"id_collisions_{name}"] = fixed


def strip(rec):
    """Drop None values and internal keys; the app treats a missing key as unknown/false."""
    return {k: v for k, v in rec.items() if v is not None and not k.startswith("_")}


def write_json(path, rows):
    data = common.dumps_compact([strip(r) for r in rows])
    with open(path + ".part", "w", encoding="utf-8") as f:
        f.write(data)
    os.replace(path + ".part", path)
    return len(data.encode("utf-8"))


def crosscheck(rest, restroom_geo, raw, rep):
    """Compare well-known rest areas across KEC data, OSM and geocoded MOIS restroom records."""
    with open(os.path.join(raw, "osm", "services.json"), encoding="utf-8") as f:
        osm = {roadside.osm_ref(e): e for e in json.load(f)["elements"]}
    g = common.GridIndex(0.01)
    for x in restroom_geo:
        g.add(x["lat"], x["lon"], x)
    rows = []
    for x in rest:
        if x["n"] not in CROSSCHECK or x["src"] != "kec":
            continue
        base = x["n"].replace("휴게소", "")
        o = osm.get(x["osm"]) if x["osm"] else None
        mois = None
        for d, _, _, r in g.near(x["lat"], x["lon"], 3000):
            if base in r["n"] and ("휴게소" in r["n"] or "(" in r["n"]):
                mois = (int(d), r["n"], r.get("gm"), r.get("ga"))
                break
        rows.append({
            "name": x["n"], "dir": x["dir"], "dirRaw": x["dirRaw"], "kec": [x["lat"], x["lon"]], "pos": x.get("pos"),
            "hdg": x["hdg"],
            "osm": x["osm"], "osm_name": o["tags"].get("name") if o else None, "osm_m": x["xm"],
            "mois_restroom": mois,
        })
    rep["crosscheck_rest_areas"] = rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--report")
    ap.add_argument("--no-nocoord", action="store_true", help="do not export restrooms without coordinates")
    a = ap.parse_args()
    raw, out = os.path.abspath(a.raw_dir), os.path.abspath(a.out_dir)
    os.makedirs(out, exist_ok=True)
    t0 = time.time()
    rep = {"built": common.now_iso(), "download_date": common.DOWNLOAD_DATE, "schema": SCHEMA_VERSION}
    man = common.load_manifest(raw)
    rep["sources"] = {k: {kk: v.get(kk) for kk in ("url", "endpoint", "downloaded", "bytes", "rows", "lines",
                                                     "elements", "osm_base")} for k, v in man.items()}

    with open(os.path.join(raw, "localdata", "public_restroom_info.info.html"), encoding="utf-8") as f:
        org_map = common.parse_org_regions(f.read())
    common.set_known_districts(org_map)
    print(f"[{time.time()-t0:5.0f}s] {len(org_map)} local-government codes", flush=True)

    k1, r1 = restrooms.wanted_keys(raw, org_map)
    k2, r2 = parkables.wanted_keys(raw, org_map)
    gz = geocode.cached_gazetteer(raw, k1 | k2, r1 | r2, org_map,
                                  log=lambda m: print(f"[{time.time()-t0:5.0f}s] {m}", flush=True))
    rep["gazetteer_keys"] = gz.n_keys
    rep["gazetteer_resolved"] = len(gz.resolved)
    ridx = restrooms.RoadIndex(gz.resolved)
    print(f"[{time.time()-t0:5.0f}s] gazetteer {len(gz.resolved)}/{len(gz.pts)} keys resolved", flush=True)

    rr_geo, rr_nocoord = restrooms.build(raw, org_map, gz, rep)
    rr_osm = restrooms.add_osm_toilets(raw, rr_geo, rep)
    rr_all = rr_geo + rr_osm
    print(f"[{time.time()-t0:5.0f}s] restrooms {len(rr_geo)} geocoded + {len(rr_osm)} OSM, {len(rr_nocoord)} without coordinates", flush=True)

    fuel = parkables.build_fuel(raw, org_map, gz, ridx, rep)
    parking = parkables.build_parking(raw, org_map, gz, ridx, rep)
    pk_all = fuel + parking
    print(f"[{time.time()-t0:5.0f}s] parkables {len(fuel)} fuel + {len(parking)} parking", flush=True)

    mw = motorway.Motorways(os.path.join(raw, "osm", "motorways.json"))
    rest = roadside.build_rest_areas(raw, mw, rep)
    shelters = roadside.build_drowsy(raw, mw, rest, rep)
    print(f"[{time.time()-t0:5.0f}s] rest areas {len(rest)}, shelters {len(shelters)}", flush=True)

    parkables.link_restrooms(pk_all, rr_all, rest, shelters, rep)
    crosscheck(rest, rr_geo, raw, rep)

    # stable order (region, name) so diffs between builds stay readable
    rkey = lambda x: ((x.get("_d") or (None,))[0] or "\uffff", x["n"], x.get("a") or "", x.get("id") or "")
    rr_all.sort(key=rkey)
    rr_nocoord.sort(key=rkey)
    pk_all.sort(key=lambda x: (x["k"], x["lat"], x["lon"]))
    for name, rows in (("rest_areas", rest), ("drowsy_shelters", shelters), ("restrooms", rr_all + rr_nocoord),
                       ("parkables", pk_all)):
        check_ids(name, rows, rep)
    sizes = {
        "rest_areas.json": write_json(os.path.join(out, "rest_areas.json"), rest),
        "drowsy_shelters.json": write_json(os.path.join(out, "drowsy_shelters.json"), shelters),
        "restrooms.json": write_json(os.path.join(out, "restrooms.json"), [export_restroom(x) for x in rr_all]),
        "parkables.json": write_json(os.path.join(out, "parkables.json"), [export_parkable(x) for x in pk_all]),
    }
    if not a.no_nocoord:
        sizes["restrooms_nocoord.json"] = write_json(os.path.join(out, "restrooms_nocoord.json"),
                                                     [export_restroom(x, False) for x in rr_nocoord])
    counts = {"rest_areas.json": len(rest), "drowsy_shelters.json": len(shelters), "restrooms.json": len(rr_all),
              "parkables.json": len(pk_all)}
    if not a.no_nocoord:
        counts["restrooms_nocoord.json"] = len(rr_nocoord)
    meta = {"schema": SCHEMA_VERSION, "built": rep["built"], "downloaded": common.DOWNLOAD_DATE,
            "counts": counts, "attribution": ATTRIBUTION}
    with open(os.path.join(out, "meta.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps(meta, ensure_ascii=False, indent=1))
    sizes["meta.json"] = os.path.getsize(os.path.join(out, "meta.json"))
    rep["output_counts"] = counts
    rep["output_bytes"] = sizes
    rep["output_total_mb"] = round(sum(sizes.values()) / 1e6, 2)
    rep_path = a.report or os.path.join(raw, "build_report.json")
    with open(rep_path, "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print(f"[{time.time()-t0:5.0f}s] wrote {counts} total {rep['output_total_mb']} MB; report {rep_path}")
    # 앱은 JSON이 아니라 같은 규칙으로 만든 stops.bin을 읽는다 (rest-engine StopRows/StopTable)
    print("next: ./gradlew -p rest-engine buildStopTable   (stops.bin; StopTableTest fails until it matches the JSON)")


if __name__ == "__main__":
    main()
