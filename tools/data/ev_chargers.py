#!/usr/bin/env python3
"""KECO EvCharger snapshot. Credentials stay in memory; errors never include URLs/bodies."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import time
from urllib.parse import urlencode, unquote
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
ENDPOINT = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo"
PAGE_SIZE = 9999
# v1.25: 07 is AC-only, 08 explicitly slow DC, 11 bus-only. 09 NACS needs
# a declared fast DC output (NACS itself also supports AC).
DC_TYPES = {"01", "03", "04", "05", "06", "10"}
ATTRIBUTION = "전기자동차 충전소 정보: 한국환경공단 (공공데이터포털, data.go.kr/data/15076352/openapi.do), 제3자 권리 포함 : 저작권 표시, 공공저작물 : 출처표시 (제 1유형)"


def dump(path, obj):
    path.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


def page_data(raw):
    obj = json.loads(raw)
    root = obj.get("response", obj)
    header = root.get("header", root)
    if str(header.get("resultCode")) not in {"00", "0"}:
        raise ValueError("API result is not successful")
    body = root.get("body", root)
    items = body.get("items") or {}
    rows = items.get("item", []) if isinstance(items, dict) else items
    if isinstance(rows, dict):
        rows = [rows]
    return int(body["totalCount"]), int(body["pageNo"]), rows


def fetch_pages(raw_dir, offline=False):
    raw_dir.mkdir(parents=True, exist_ok=True)
    key = None
    page, total, seen = 1, None, 0
    manifest = []
    while total is None or seen < total:
        path = raw_dir / f"ev-{page:04d}.json"
        if path.exists():
            raw = path.read_bytes()
        else:
            if offline:
                raise ValueError(f"Missing cached page {page}")
            if key is None:
                key_path = HERE / ".datagokr-key"
                if key_path.stat().st_mode & 0o077:
                    raise ValueError("Key file must have mode 600")
                key = unquote(key_path.read_text().strip())
            query = urlencode(dict(serviceKey=key, pageNo=page, numOfRows=PAGE_SIZE, dataType="JSON"))
            for attempt in range(5):
                time.sleep(0.5 if attempt == 0 else min(2 ** attempt, 16))
                try:
                    with urlopen(Request(ENDPOINT + "?" + query, headers={"User-Agent": "Shimpyo-data/1.0"}), timeout=90) as response:
                        raw = response.read()
                    # Do not persist a reflected credential, even in an API error response.
                    if key.encode() in raw or query.split("&", 1)[0].split("=", 1)[1].encode() in raw:
                        raise ValueError("Credential reflected")
                    page_data(raw)
                    break
                except Exception:
                    # urllib exceptions may contain the secret URL. Never log their text.
                    print(f"page {page}: attempt {attempt + 1} failed", flush=True)
            else:
                raise ValueError(f"Page {page} failed after retries")
            pending = path.with_suffix(".tmp")
            pending.write_bytes(raw)
            pending.replace(path)
        count, actual_page, rows = page_data(raw)
        if actual_page != page or not rows or len(rows) > PAGE_SIZE:
            raise ValueError(f"Invalid pagination on page {page}")
        if total is None:
            total = count
        if count != total:
            raise ValueError("totalCount changed during snapshot; use a fresh raw directory")
        if len(rows) != min(PAGE_SIZE, total - (page - 1) * PAGE_SIZE):
            raise ValueError(f"Unexpected row count on page {page}")
        seen += len(rows)
        manifest.append(dict(file=path.name, rows=len(rows), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(),
                             fetched=datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat()))
        print(f"page {page}: {len(rows)} chargers ({seen}/{total})", flush=True)
        yield rows, manifest, total
        page += 1
    if seen != total:
        raise ValueError("Fetched row count differs from totalCount")


def number(value):
    try:
        n = float(value)
        return n if math.isfinite(n) else None
    except (TypeError, ValueError):
        return None


def is_fast(row):
    code = str(row.get("chgerType", "")).zfill(2)
    return code in DC_TYPES or (code == "09" and (number(row.get("output")) or 0) >= 50)


def distance(a, b):
    y = math.radians(a[0] - b[0])
    x = math.radians(a[1] - b[1])
    h = math.sin(y / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(x / 2) ** 2
    return 12742000 * math.asin(min(1, math.sqrt(h)))


def site_name(name):
    # Ignore operator/connector suffixes, but retain the site's distinguishing name.
    name = re.sub(r"^(?:워터|SK엔크린|HK하이웨이|E1)\s+", "", name)
    name = re.sub(r"\([^)]*\)|\[[^]]*\]", "", name)
    name = re.sub(r"\s+", "", name)
    name = name.replace("만남의광장", "만남")
    return name.split("휴게소")[0] if "휴게소" in name else name.split("졸음쉼터")[0]


def matching_site(station, sites):
    name = station["n"]
    if "휴게소" not in name and "졸음쉼터" not in name:
        return None
    same_name = []
    for site in sites:
        key = site_name(site["n"])
        station_key = site_name(name)
        if len(key) < 2 or station_key != key:
            continue
        same_name.append(site)
    # If the API names a destination direction, never attach to the opposite carriageway
    # merely because its representative point happens to be closer.
    named_directions = {s.get("dir") for s in same_name if s.get("dir") and s["dir"] != "양방향" and
                        (s["dir"] in name or "(" + s["dir"].removesuffix("방향") + ")" in name)}
    matches = []
    for site in same_name:
        if named_directions and site.get("dir") not in named_directions:
            continue
        d = distance((station["lat"], station["lon"]), (site["lat"], site["lon"]))
        if d <= 150:
            matches.append((d, site["id"]))
    return min(matches)[1] if matches else None


def aggregate(rows, sites):
    counts = Counter(charger_rows=len(rows))
    counts["stations_fetched"] = len({r.get("statId") for r in rows if r.get("statId")})
    stations = defaultdict(dict)
    unique = {}
    for r in rows:
        if not r.get("statId") or not r.get("chgerId"):
            counts["missing_id_chargers"] += 1
            continue
        key = (r["statId"], r["chgerId"])
        if key in unique:
            counts["duplicate_charger_rows"] += 1
        unique[key] = r
    for r in unique.values():
        if str(r.get("delYn", "")).upper() == "Y":
            counts["deleted_chargers"] += 1
            continue
        if str(r.get("limitYn", "")).upper() == "Y":
            counts["restricted_chargers"] += 1
            continue
        lat, lon = number(r.get("lat")), number(r.get("lng"))
        # Match tools/data/common.py's South Korea bbox (the JVM decoder uses a looser corruption guard).
        if lat is None or lon is None or not (33 <= lat <= 38.75 and 124.5 <= lon <= 132):
            counts["invalid_coordinate_chargers"] += 1
            continue
        station = stations[r["statId"]]
        station[r["chgerId"]] = r
    out = []
    for sid, chargers in sorted(stations.items()):
        fast = [r for r in chargers.values() if is_fast(r)]
        if not fast:
            counts["stations_without_fast_dc"] += 1
            if all(str(r.get("chgerType", "")).zfill(2) in {"02", "08"} for r in chargers.values()):
                counts["slow_only_stations"] += 1
            continue
        first = fast[0]
        lat = statistics.median(float(r["lat"]) for r in fast)
        lon = statistics.median(float(r["lng"]) for r in fast)
        # A statId should identify one site. Do not invent a midpoint across bad coordinates.
        if any(distance((lat, lon), (float(r["lat"]), float(r["lng"]))) > 150 for r in fast):
            counts["inconsistent_coordinate_stations"] += 1
            continue
        hours = sorted({str(r.get("useTime") or "").strip() for r in fast})
        station = dict(id="ev" + sid, n=first.get("statNm") or "전기차 충전소", lat=round(lat, 6), lon=round(lon, 6),
                       ev=1, dc=len(fast), kw=max(0, max(number(r.get("output")) or 0 for r in fast)),
                       useTime=" / ".join(hours), a=first.get("addr", ""), src="keco")
        if hours and all(re.fullmatch(r"24\s*시간(?:\s*(?:이용\s*가능|운영|개방))?|24[hH]|00:00\s*[~\-]\s*24:00", h) for h in hours):
            station["h24"] = 1
        elif len(hours) == 1 and re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d\s*[~\-]\s*(?:[0-3]\d|4[0-7]):[0-5]\d", hours[0]):
            station["oh"] = re.sub(r"\s", "", hours[0]).replace("~", "-")
        target = matching_site(station, sites)
        if target:
            station["at"] = target
            counts["stations_attached_to_existing_site"] += 1
        else:
            counts["new_energy_candidates"] += 1
        counts["fast_chargers_kept"] += len(fast)
        out.append(station)
    counts["fast_stations_kept"] = len(out)
    counts["existing_sites_enriched"] = len({r["at"] for r in out if "at" in r})
    return out, dict(sorted(counts.items()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, default=HERE / "raw")
    parser.add_argument("--out", type=Path, default=HERE.parents[1] / "app/src/main/assets/data")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    rows = []
    for batch, manifest, total in fetch_pages(args.raw, args.offline):
        rows.extend(batch)
    sites = []
    for name in ("rest_areas.json", "drowsy_shelters.json"):
        sites.extend(json.loads((args.out / name).read_text()))
    stations, counts = aggregate(rows, sites)
    dump(args.out / "ev_chargers.json", stations)
    summary = dict(endpoint=ENDPOINT, totalCount=total, pages=manifest, counts=counts,
                   built=datetime.now(timezone.utc).isoformat())
    dump(HERE / "ev-summary.json", summary)
    meta_path = args.out / "meta.json"
    meta = json.loads(meta_path.read_text())
    meta["counts"]["ev_chargers.json"] = len(stations)
    meta["ev_downloaded"] = manifest[0]["fetched"][:10]
    if ATTRIBUTION not in meta["attribution"]:
        meta["attribution"].append(ATTRIBUTION)
    dump(meta_path, meta)
    print(json.dumps(counts, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # No exception text or traceback: network errors can carry serviceKey URLs.
        raise SystemExit("EV import failed (" + type(error).__name__ + "); check cache/pagination/key permissions") from None
