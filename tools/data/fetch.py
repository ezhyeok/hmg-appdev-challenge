"""Download raw source data for the offline POI datasets.

    python3 -I -B tools/data/fetch.py RAW_DIR [--only datagokr,localdata,osm] [--sets a,b] [--force]

RAW_DIR must be outside the repository (raw files are large and untrusted). Every
download is recorded in RAW_DIR/manifest.json (URL, time, bytes, sha256, rows).
No API key or login is needed for any source used here.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

# ------------------------------------------------------------------ data.go.kr
# Standard datasets (공공데이터 제공 표준). The portal's own download buttons call two JSON
# endpoints: /download/columList.json (columns, table name, row count) and
# /download/standard.json (rows, 10,000 per page). No key or login.
DATAGOKR = {
    "rest_areas": 15025446,       # 전국휴게소정보표준데이터 (provider: 한국도로공사)
    "drowsy_shelters": 15028203,  # 전국졸음쉼터표준데이터 (provider: 한국도로공사)
    "parking": 15012896,          # 전국주차장정보표준데이터 (providers: 지방자치단체)
}

# File datasets (파일데이터). Downloaded through the portal's own anonymous flow:
# selectFileDataDownload.do -> check-limit.json (stops if the portal asks for a CAPTCHA)
# -> fileDownload.do.
DATAGOKR_FILES = {
    "drowsy_shelters_install": 15043710,  # 한국도로공사_졸음쉼터 설치현황 (milepost, direction)
}

# ------------------------------------------------------------------ LOCALDATA
# file.localdata.go.kr (행정안전부 지방행정 인허가·생활편의 데이터). The data.go.kr page for
# 전국공중화장실표준데이터 links here as the official download. Nationwide CSV per dataset.
LOCALDATA = {
    "public_restroom_info": "공중화장실정보",
    "emergency_call_box_info": "안전비상벨위치정보",
    "cctv_info": "CCTV정보",
    "free_wifi_info": "무료와이파이정보",
    "civil_defense_shelter_info": "민방위대피시설",
    "oil_retailers": "자원환경_석유판매업 (주유소 인허가)",
    # licence registers used only as geocoding references (building coordinates, EPSG:5174)
    "pharmacies": "건강_약국",
    "rest_cafes": "식품_휴게음식점",
    "general_restaurants": "식품_일반음식점",
}

# ------------------------------------------------------------------ OpenStreetMap
OVERPASS = ["https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]
_AREA = 'area["ISO3166-1"="KR"][admin_level=2]->.kr;'
OSM_QUERIES = {
    "services": '[out:json][timeout:300];' + _AREA +
                'nwr["highway"~"^(services|rest_area)$"](area.kr);out center tags;',
    "fuel": '[out:json][timeout:300];' + _AREA + 'nwr["amenity"="fuel"](area.kr);out center tags;',
    "toilets": '[out:json][timeout:300];' + _AREA + 'nwr["amenity"="toilets"](area.kr);out center tags;',
    # carriageway geometry, used to derive the travel heading served by each rest area
    "motorways": '[out:json][timeout:600][maxsize:1073741824];' + _AREA +
                 'way["highway"="motorway"](area.kr);out tags geom qt;',
}


def fetch_datagokr(raw, man, force):
    http = common.Http(delay=1.0, extra_headers={"X-Requested-With": "XMLHttpRequest",
                                                 "Accept": "application/json, text/javascript, */*; q=0.01"})
    for name, pk in DATAGOKR.items():
        out = os.path.join(raw, "datagokr", name + ".json")
        if os.path.exists(out) and not force:
            print("skip", out)
            continue
        ref = f"https://www.data.go.kr/data/{pk}/standard.do"
        url = f"https://www.data.go.kr/download/columList.json?pk={pk}&ext=JSON"
        _, _, b = http.request(url, headers={"Referer": ref})
        head = json.loads(b.decode("utf-8"))
        if "error" in head:
            raise SystemExit(f"{name}: {head['error']}")
        tv = head["tableVO"]
        total = int(head["totalCount"])
        per = 10000
        recs = []
        for page in range(1, (total + per - 1) // per + 1):
            q = {"publicDataPk": str(pk), "colNmList": tv["colNmList"], "totalCount": total,
                 "svcTableNm": tv["svcTableNm"], "perPage": per, "page": page}
            u = "https://www.data.go.kr/download/standard.json?" + urllib.parse.urlencode(q, doseq=True)
            _, _, b = http.request(u, headers={"Referer": ref})
            part = json.loads(b.decode("utf-8"))
            if not isinstance(part, list):
                raise SystemExit(f"{name}: unexpected response {str(part)[:200]}")
            recs.extend(part)
            print(f"  {name} page {page}: {len(part)} rows", flush=True)
        doc = {"source": ref, "dataset": head.get("fileName"), "downloaded": common.now_iso(),
               "columns": head["columList"], "totalCount": total, "records": recs}
        data = json.dumps(doc, ensure_ascii=False).encode("utf-8")
        common.write_bytes(out, data)
        man["datagokr/" + name] = {"url": ref, "api": "https://www.data.go.kr/download/standard.json",
                                   "downloaded": doc["downloaded"], "bytes": len(data),
                                   "sha256": common.sha256_bytes(data), "rows": len(recs), "expected_rows": total}
        common.save_manifest(raw, man)
        print(f"{name}: {len(recs)}/{total} rows -> {out}")


def fetch_datagokr_files(raw, man, force):
    http = common.Http(delay=2.0)
    for name, pk in DATAGOKR_FILES.items():
        out = os.path.join(raw, "datagokr", name + ".csv")
        if os.path.exists(out) and not force:
            print("skip", out)
            continue
        page_url = f"https://www.data.go.kr/data/{pk}/fileData.do"
        _, _, page = http.request(page_url)
        m = re.search(r"fn_fileDataDown\('(\d+)',\s*'(uddi:[0-9a-f-]+)',\s*'[^']*',\s*'(\d+)'", page.decode("utf-8", "replace"))
        if not m:
            raise SystemExit(f"{name}: download link not found on {page_url}")
        ajax = {"X-Requested-With": "XMLHttpRequest", "Referer": page_url}
        _, _, b = http.request("https://www.data.go.kr/tcs/dss/selectFileDataDownload.do", headers=ajax,
                               data={"publicDataDetailPk": m.group(2), "publicDataPk": m.group(1), "atchFileId": "",
                                     "fileDetailSn": m.group(3), "publicDataTyCode": "PR0051"})
        info = json.loads(b.decode("utf-8"))
        if not info.get("status"):
            raise SystemExit(f"{name}: portal refused: {str(info)[:200]}")
        aid, sn = info["atchFileId"], info["fileDetailSn"]
        _, _, b = http.request("https://www.data.go.kr/cmm/cmm/check-limit.json", headers=ajax,
                               data={"atchFileId": aid, "fileDetailSn": sn})
        if json.loads(b.decode("utf-8")).get("needCaptcha"):
            print(f"{name}: the portal requires a CAPTCHA now; download it manually from {page_url}")
            continue
        url = f"https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId={aid}&fileDetailSn={sn}&dataNm=file"
        hdr, nbytes, sha = http.download(url, out, headers={"Referer": page_url})
        dname = info.get("dataSetFileDetailInfo", {}).get("dataNm")
        man["datagokr/" + name] = {"url": page_url, "dataset": dname, "downloaded": common.now_iso(),
                                   "bytes": nbytes, "sha256": sha, "encoding": common.sniff_encoding(out)}
        common.save_manifest(raw, man)
        print(f"{name}: {dname} {nbytes} bytes -> {out}")


def fetch_localdata(raw, man, force, only_sets=None):
    http = common.Http(delay=4.0, extra_headers={
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"})
    base = "https://file.localdata.go.kr"
    for ds, label in LOCALDATA.items():
        if only_sets and ds not in only_sets:
            continue
        out = os.path.join(raw, "localdata", ds + ".csv")
        if os.path.exists(out) and not force:
            if "localdata/" + ds not in man:   # file present but not recorded: record it now
                enc = common.sniff_encoding(out)
                sha, nbytes, lines = hashlib.sha256(), 0, 0
                with open(out, "rb") as f:
                    for chunk in iter(lambda: f.read(1 << 22), b""):
                        sha.update(chunk)
                        nbytes += len(chunk)
                        lines += chunk.count(b"\n")
                mtime = datetime.datetime.fromtimestamp(os.path.getmtime(out), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
                man["localdata/" + ds] = {"url": f"{base}/file/{ds}/info", "download": f"{base}/file/download/{ds}/info",
                                          "label": label, "downloaded": mtime, "bytes": nbytes,
                                          "sha256": sha.hexdigest(), "encoding": enc, "lines": lines}
                common.save_manifest(raw, man)
            print("skip", out)
            continue
        info = f"{base}/file/{ds}/info"
        _, _, page = http.request(info, headers={"Referer": "https://www.data.go.kr/"})
        if ds == "public_restroom_info":   # region code list (개방자치단체코드 -> 시도/시군구)
            common.write_bytes(os.path.join(raw, "localdata", ds + ".info.html"), page)
        st, _, _ = http.request(f"{base}/file/validate/download-count", headers={"Referer": info})
        url = f"{base}/file/download/{ds}/info"
        hdr, nbytes, sha = http.download(url, out, headers={"Referer": info})
        ctype = hdr.get("Content-Type", "")
        if "csv" not in ctype:
            os.replace(out, out + ".rejected")
            raise SystemExit(f"{ds}: unexpected content-type {ctype}")
        enc = common.sniff_encoding(out)
        with open(out, "rb") as f:
            lines = sum(chunk.count(b"\n") for chunk in iter(lambda: f.read(1 << 22), b""))
        man["localdata/" + ds] = {"url": info, "download": url, "label": label, "downloaded": common.now_iso(),
                                  "bytes": nbytes, "sha256": sha, "encoding": enc, "lines": lines}
        common.save_manifest(raw, man)
        print(f"{ds}: {nbytes/1e6:.1f} MB ({enc}, ~{lines} lines) -> {out}", flush=True)


def fetch_osm(raw, man, force, only_sets=None):
    http = common.Http(ua=common.OSM_UA, delay=10.0)
    for name, q in OSM_QUERIES.items():
        if only_sets and name not in only_sets:
            continue
        out = os.path.join(raw, "osm", name + ".json")
        if os.path.exists(out) and not force:
            print("skip", out)
            continue
        err = None
        for ep in OVERPASS:
            try:
                _, _, b = http.request(ep, data={"data": q}, timeout=900, retries=4, backoff=60)
                d = json.loads(b.decode("utf-8"))
                if d.get("remark") and "error" in d["remark"].lower():
                    raise RuntimeError(d["remark"])
                break
            except Exception as e:  # try the next public instance
                err = e
                print(f"  {ep}: {e}", flush=True)
                b = None
        if b is None:
            raise SystemExit(f"osm {name}: all endpoints failed: {err}")
        common.write_bytes(out, b)
        man["osm/" + name] = {"endpoint": ep, "query": q, "downloaded": common.now_iso(),
                              "osm_base": d.get("osm3s", {}).get("timestamp_osm_base"),
                              "bytes": len(b), "sha256": common.sha256_bytes(b), "elements": len(d["elements"])}
        common.save_manifest(raw, man)
        print(f"osm {name}: {len(d['elements'])} elements ({len(b)/1e6:.1f} MB) -> {out}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw_dir")
    ap.add_argument("--only", default="datagokr,localdata,osm")
    ap.add_argument("--sets", default="", help="comma list of dataset names within a group")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    raw = os.path.abspath(a.raw_dir)
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    if raw.startswith(repo + os.sep):
        raise SystemExit("RAW_DIR must be outside the repository")
    os.makedirs(raw, exist_ok=True)
    man = common.load_manifest(raw)
    groups = set(a.only.split(","))
    sets = set(filter(None, a.sets.split(","))) or None
    if "datagokr" in groups:
        fetch_datagokr(raw, man, a.force)
        fetch_datagokr_files(raw, man, a.force)
    if "localdata" in groups:
        fetch_localdata(raw, man, a.force, sets)
    if "osm" in groups:
        fetch_osm(raw, man, a.force, sets)


if __name__ == "__main__":
    main()
