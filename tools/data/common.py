"""Shared helpers for the offline POI dataset scripts (Python 3.9+, standard library only).

The scripts run with `python3 -I`, so they add their own directory to sys.path before
`import common`. Downloaded files are treated as untrusted data: they are parsed, never
executed, and raw files live outside the repository.
"""
import csv
import datetime
import hashlib
import http.cookiejar
import io
import json
import math
import os
import re
import statistics
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

DOWNLOAD_DATE = "2026-11-11"

# Korean government portals reject non-browser clients (HTTP 403 from a WAF), so the
# downloader identifies as a generic desktop browser. No personal or project identifiers.
BROWSER_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/130.0 Safari/537.36")
OSM_UA = "offline-poi-dataset-builder/1.0 (python-urllib)"

# South Korea incl. Jeju, Ulleungdo, Dokdo; excludes North Korea only roughly (lat cap).
KR_LAT = (33.0, 38.75)
KR_LON = (124.5, 132.0)


# ---------------------------------------------------------------- HTTP

class Http:
    """Small urllib wrapper with a cookie jar, retries and a politeness delay."""

    def __init__(self, ua=BROWSER_UA, delay=1.5, extra_headers=None):
        self.ua = ua
        self.delay = delay
        self.extra = extra_headers or {}
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self._last = 0.0

    def _wait(self):
        dt = time.time() - self._last
        if dt < self.delay:
            time.sleep(self.delay - dt)
        self._last = time.time()

    def request(self, url, data=None, headers=None, timeout=300, retries=4, backoff=20):
        h = {"User-Agent": self.ua, "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.5"}
        h.update(self.extra)
        h.update(headers or {})
        body = None
        if data is not None:
            body = data if isinstance(data, bytes) else urllib.parse.urlencode(data, doseq=True).encode()
        last_err = None
        for attempt in range(retries + 1):
            self._wait()
            try:
                req = urllib.request.Request(url, data=body, headers=h)
                with self.opener.open(req, timeout=timeout) as r:
                    return r.status, dict(r.headers), r.read()
            except urllib.error.HTTPError as e:
                last_err = e
                if e.code in (429, 502, 503, 504) and attempt < retries:
                    wait = backoff * (2 ** attempt)
                    print(f"  HTTP {e.code} for {url[:90]}... retry in {wait}s", flush=True)
                    time.sleep(wait)
                    continue
                raise
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                last_err = e
                if attempt < retries:
                    wait = backoff * (2 ** attempt)
                    print(f"  {type(e).__name__} for {url[:90]}... retry in {wait}s", flush=True)
                    time.sleep(wait)
                    continue
                raise
        raise last_err


    def download(self, url, path, headers=None, timeout=1800, retries=3, backoff=30):
        """Stream a (possibly large) response to `path`; returns (headers, bytes, sha256)."""
        h = {"User-Agent": self.ua, "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.5"}
        h.update(self.extra)
        h.update(headers or {})
        os.makedirs(os.path.dirname(path), exist_ok=True)
        for attempt in range(retries + 1):
            self._wait()
            try:
                req = urllib.request.Request(url, headers=h)
                with self.opener.open(req, timeout=timeout) as r, open(path + ".part", "wb") as f:
                    sha = hashlib.sha256()
                    n = 0
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
                        sha.update(chunk)
                        n += len(chunk)
                    hdr = dict(r.headers)
                os.replace(path + ".part", path)
                return hdr, n, sha.hexdigest()
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt < retries:
                    wait = backoff * (2 ** attempt)
                    print(f"  {type(e).__name__} for {url[:90]}... retry in {wait}s", flush=True)
                    time.sleep(wait)
                    continue
                raise


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def write_bytes(path, b):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(b)
    os.replace(tmp, path)


def load_manifest(raw_dir):
    p = os.path.join(raw_dir, "manifest.json")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_manifest(raw_dir, m):
    """Merge `m` into the manifest on disk (several fetch runs may write concurrently)."""
    p = os.path.join(raw_dir, "manifest.json")
    disk = load_manifest(raw_dir)
    disk.update(m)
    m.update(disk)
    tmp = p + f".{os.getpid()}.part"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(disk, f, ensure_ascii=False, indent=1, sort_keys=True)
    os.replace(tmp, p)


# ---------------------------------------------------------------- text

_WS = re.compile(r"\s+")
_CTRL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f​﻿]")


def clean(s):
    """NFC-normalise, drop control chars, collapse whitespace. None/'' -> ''."""
    if s is None:
        return ""
    if not isinstance(s, str):
        s = str(s)
    s = unicodedata.normalize("NFKC", s) if any(ord(c) >= 0xFF00 for c in s) else unicodedata.normalize("NFC", s)
    s = s.replace("　", " ").replace("\xa0", " ")
    s = _CTRL.sub("", s)
    s = _WS.sub(" ", s).strip()
    if s.lower() in ("null", "none", "nan", "-", "--", "없음정보"):
        return ""
    return s


def decode_bytes(b):
    """Decode a CSV/text download: UTF-8 (with/without BOM) first, else CP949 (superset of EUC-KR)."""
    for enc in ("utf-8-sig", "cp949"):
        try:
            return b.decode(enc), enc
        except UnicodeDecodeError:
            pass
    return b.decode("cp949", errors="replace"), "cp949-replace"


def sniff_encoding(path):
    with open(path, "rb") as f:
        head = f.read(1 << 20)
    try:
        head.decode("utf-8")
        return "utf-8-sig"
    except UnicodeDecodeError as e:
        if e.start > len(head) - 4:   # multi-byte char cut at the buffer end
            return "utf-8-sig"
        return "cp949"


def iter_csv(path, columns=None):
    """Stream rows of a large CSV as dicts (optionally only `columns`)."""
    enc = sniff_encoding(path)
    with open(path, encoding=enc, errors="replace", newline="") as f:
        rd = csv.reader(f)
        hdr = next(rd)
        idx = [(c, hdr.index(c)) for c in (columns or hdr) if c in hdr]
        for row in rd:
            yield {c: (row[i] if i < len(row) else "") for c, i in idx}


def read_csv_rows(path):
    with open(path, "rb") as f:
        text, enc = decode_bytes(f.read())
    rows = list(csv.DictReader(io.StringIO(text)))
    return rows, enc


_TRUE = {"y", "yes", "true", "t", "1", "o", "유", "있음", "보유", "설치", "가능", "운영", "해당", "○", "●"}
_FALSE = {"n", "no", "false", "f", "0", "x", "무", "없음", "미보유", "미설치", "불가", "불가능", "미운영", "해당없음", "×"}


def to_bool(s):
    """Normalise Y/N, 있음/없음, 유/무, O/X ... to True/False; unknown -> None."""
    s = clean(s).lower()
    if not s:
        return None
    if s in _TRUE:
        return True
    if s in _FALSE:
        return False
    if s.startswith(("y", "있", "유(", "설치")):
        return True
    if s.startswith(("n", "없", "무(", "미설치")):
        return False
    return None


def to_int(s):
    s = clean(s).replace(",", "")
    if not s:
        return None
    try:
        v = float(s)
    except ValueError:
        m = re.match(r"^(\d+)", s)
        return int(m.group(1)) if m else None
    if math.isnan(v) or math.isinf(v):
        return None
    return int(round(v))


def to_float(s):
    s = clean(s).replace(",", "")
    if not s:
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    if math.isnan(v) or math.isinf(v):
        return None
    return v


def hhmm(s):
    """'09:00', '0900', '9:00', '9시', '24:00' -> 'HH:MM' (24:00 allowed). Else None."""
    s = clean(s).replace("시", ":").replace("분", "").replace(" ", "")
    if not s:
        return None
    m = re.match(r"^(\d{1,2}):(\d{2})(?::\d{2})?$", s) or re.match(r"^(\d{2})(\d{2})$", s) \
        or re.match(r"^(\d{1,2}):?()$", s)
    if not m:
        return None
    h = int(m.group(1))
    mi = int(m.group(2) or 0)
    if h > 24 or mi > 59 or (h == 24 and mi):
        return None
    return f"{h:02d}:{mi:02d}"


# ---------------------------------------------------------------- geo

def in_korea(lat, lon):
    return lat is not None and lon is not None and KR_LAT[0] <= lat <= KR_LAT[1] and KR_LON[0] <= lon <= KR_LON[1]


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def bearing_deg(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def median_point(pts):
    return statistics.median(p[0] for p in pts), statistics.median(p[1] for p in pts)


class GridIndex:
    """Uniform lat/lon grid for radius queries (cell ~ 0.01 deg ~ 1.1 km)."""

    def __init__(self, cell=0.01):
        self.cell = cell
        self.g = {}

    def _k(self, lat, lon):
        return int(math.floor(lat / self.cell)), int(math.floor(lon / self.cell))

    def add(self, lat, lon, item):
        self.g.setdefault(self._k(lat, lon), []).append((lat, lon, item))

    def near(self, lat, lon, radius_m):
        r = int(math.ceil(radius_m / (self.cell * 111000.0))) + 1
        ci, cj = self._k(lat, lon)
        out = []
        for i in range(ci - r, ci + r + 1):
            for j in range(cj - r, cj + r + 1):
                for (la, lo, it) in self.g.get((i, j), ()):
                    d = haversine_m(lat, lon, la, lo)
                    if d <= radius_m:
                        out.append((d, la, lo, it))
        out.sort(key=lambda t: t[0])
        return out


# Transverse Mercator inverse (Snyder 1987, USGS PP 1395, eqs. 8-12 .. 8-25) and a
# 7-parameter Helmert datum shift, so Korean projected coordinates can be converted
# without pyproj.
_BESSEL = (6377397.155, 1 / 299.1528128)
_GRS80 = (6378137.0, 1 / 298.257222101)
_WGS84 = (6378137.0, 1 / 298.257223563)


def _meridian_arc(phi, a, e2):
    return a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi
                - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * phi)
                + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * phi)
                - (35 * e2 ** 3 / 3072) * math.sin(6 * phi))


def tm_inverse(x, y, ell, lat0_deg, lon0_deg, k0, fe, fn):
    a, f = ell
    e2 = f * (2 - f)
    ep2 = e2 / (1 - e2)
    lat0 = math.radians(lat0_deg)
    m = _meridian_arc(lat0, a, e2) + (y - fn) / k0
    mu = m / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256))
    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu)
            + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    s1, c1, t1 = math.sin(phi1), math.cos(phi1), math.tan(phi1)
    C1 = ep2 * c1 ** 2
    T1 = t1 ** 2
    N1 = a / math.sqrt(1 - e2 * s1 ** 2)
    R1 = a * (1 - e2) / (1 - e2 * s1 ** 2) ** 1.5
    D = (x - fe) / (N1 * k0)
    lat = phi1 - (N1 * t1 / R1) * (D ** 2 / 2
                                   - (5 + 3 * T1 + 10 * C1 - 4 * C1 ** 2 - 9 * ep2) * D ** 4 / 24
                                   + (61 + 90 * T1 + 298 * C1 + 45 * T1 ** 2 - 252 * ep2 - 3 * C1 ** 2) * D ** 6 / 720)
    lon = math.radians(lon0_deg) + (D - (1 + 2 * T1 + C1) * D ** 3 / 6
                                    + (5 - 2 * C1 + 28 * T1 - 3 * C1 ** 2 + 8 * ep2 + 24 * T1 ** 2) * D ** 5 / 120) / c1
    return math.degrees(lat), math.degrees(lon)


def _geo_to_ecef(lat, lon, ell, h=0.0):
    a, f = ell
    e2 = f * (2 - f)
    p, l = math.radians(lat), math.radians(lon)
    n = a / math.sqrt(1 - e2 * math.sin(p) ** 2)
    return ((n + h) * math.cos(p) * math.cos(l), (n + h) * math.cos(p) * math.sin(l), (n * (1 - e2) + h) * math.sin(p))


def _ecef_to_geo(x, y, z, ell):
    a, f = ell
    e2 = f * (2 - f)
    lon = math.atan2(y, x)
    p = math.hypot(x, y)
    lat = math.atan2(z, p * (1 - e2))
    for _ in range(6):
        n = a / math.sqrt(1 - e2 * math.sin(lat) ** 2)
        h = p / math.cos(lat) - n
        lat = math.atan2(z, p * (1 - e2 * n / (n + h)))
    return math.degrees(lat), math.degrees(lon)


def helmert(lat, lon, src_ell, dst_ell, tx, ty, tz, rx=0.0, ry=0.0, rz=0.0, s_ppm=0.0):
    """Position-vector 7-parameter shift (PROJ +towgs84 convention); rotations in arc-seconds."""
    x, y, z = _geo_to_ecef(lat, lon, src_ell)
    k = math.pi / (180 * 3600)
    rx, ry, rz = rx * k, ry * k, rz * k
    sc = 1 + s_ppm * 1e-6
    x2 = tx + sc * (x - rz * y + ry * z)
    y2 = ty + sc * (rz * x + y - rx * z)
    z2 = tz + sc * (-ry * x + rx * y + z)
    return _ecef_to_geo(x2, y2, z2, dst_ell)


def epsg5174_to_wgs84(x, y):
    """Korean 1985 / Modified Central Belt (Bessel, lon0 127°00'10.405"), +towgs84 7-param."""
    lat, lon = tm_inverse(x, y, _BESSEL, 38.0, 127.0028902777778, 1.0, 200000.0, 500000.0)
    return helmert(lat, lon, _BESSEL, _WGS84, -115.80, 474.99, 674.11, 1.16, -2.31, -1.63, 6.43)


def epsg2097_to_wgs84(x, y):
    """Korean 1985 / Central Belt (Bessel, lon0 127°), 3-param shift as published on epsg.io."""
    lat, lon = tm_inverse(x, y, _BESSEL, 38.0, 127.0, 1.0, 200000.0, 500000.0)
    return helmert(lat, lon, _BESSEL, _WGS84, -146.43, 507.89, 681.46)


def epsg5179_to_wgs84(x, y):
    """Korea 2000 / Unified CS (UTM-K, GRS80). GRS80 ~ WGS84 at this precision."""
    return tm_inverse(x, y, _GRS80, 38.0, 127.5, 0.9996, 1000000.0, 2000000.0)


# ---------------------------------------------------------------- addresses

# Canonical province keys. In this period 광주광역시 + 전라남도 appear as
# 전남광주통합특별시 in local-government data, so all three map to one key; renamed
# provinces (강원도/강원특별자치도, 전라북도/전북특별자치도) map to one key each.
SIDO_ALIASES = {
    "서울특별시": "서울", "서울시": "서울", "서울": "서울",
    "부산광역시": "부산", "부산시": "부산", "부산": "부산",
    "대구광역시": "대구", "대구시": "대구", "대구": "대구",
    "인천광역시": "인천", "인천시": "인천", "인천": "인천",
    "광주광역시": "전남광주", "광주시": None, "광주": "전남광주",   # 광주시 = 경기도 광주시 ambiguity
    "대전광역시": "대전", "대전시": "대전", "대전": "대전",
    "울산광역시": "울산", "울산시": "울산", "울산": "울산",
    "세종특별자치시": "세종", "세종시": "세종", "세종": "세종",
    "경기도": "경기", "경기": "경기",
    "강원도": "강원", "강원특별자치도": "강원", "강원": "강원",
    "충청북도": "충북", "충북": "충북",
    "충청남도": "충남", "충남": "충남",
    "전라북도": "전북", "전북특별자치도": "전북", "전북": "전북",
    "전라남도": "전남광주", "전남": "전남광주", "전남광주통합특별시": "전남광주", "전남광주특별시": "전남광주",
    "경상북도": "경북", "경북": "경북",
    "경상남도": "경남", "경남": "경남",
    "제주특별자치도": "제주", "제주도": "제주", "제주": "제주",
}
SIDO_FULL = {
    "서울": "서울특별시", "부산": "부산광역시", "대구": "대구광역시", "인천": "인천광역시",
    "전남광주": "전남광주통합특별시", "대전": "대전광역시", "울산": "울산광역시", "세종": "세종특별자치시",
    "경기": "경기도", "강원": "강원특별자치도", "충북": "충청북도", "충남": "충청남도", "전북": "전북특별자치도",
    "경북": "경상북도", "경남": "경상남도", "제주": "제주특별자치도",
}
# Full province names that may be glued to the next token ('경상남도창원시...').
_SIDO_GLUED = sorted((k for k, v in SIDO_ALIASES.items()
                      if v and len(k) >= 3 and k.endswith(("특별시", "광역시", "특별자치시", "특별자치도", "통합특별시", "도"))),
                     key=len, reverse=True)
_SIDO_SPACED = sorted((k for k, v in SIDO_ALIASES.items() if v), key=len, reverse=True)

# First-level districts (자치구/시/군) per canonical province, from the local-government code
# list (set_known_districts). Used to validate tokens and to split glued addresses.
KNOWN_SGG = {}
_SGG_SIDOS = {}

# Renamed/split districts: old and new names share one matching group. Wrong merges are
# caught later because conflicting reference points fail the spread check.
SGG_GROUP = {
    ("인천", "중구"): "중구·동구", ("인천", "동구"): "중구·동구", ("인천", "영종구"): "중구·동구", ("인천", "제물포구"): "중구·동구",
    ("인천", "서구"): "서구", ("인천", "서해구"): "서구", ("인천", "검단구"): "서구", ("인천", "남구"): "미추홀구",
    ("경기", "여주군"): "여주시", ("충남", "당진군"): "당진시", ("충북", "청원군"): "청주시",
    ("경남", "마산시"): "창원시", ("경남", "진해시"): "창원시",
}
_LEGACY_SGG = {"여주군": "경기", "당진군": "충남", "청원군": "충북", "마산시": "경남", "진해시": "경남"}


def set_known_districts(org_map):
    """org_map: {org_code: (sido_key, sgg or None)} from the LOCALDATA region list."""
    KNOWN_SGG.clear()
    _SGG_SIDOS.clear()
    for sido, sgg in org_map.values():
        if sgg:
            KNOWN_SGG.setdefault(sido, set()).add(sgg)
            _SGG_SIDOS.setdefault(sgg, set()).add(sido)
    for sgg, sido in _LEGACY_SGG.items():
        KNOWN_SGG.setdefault(sido, set()).add(sgg)
        _SGG_SIDOS.setdefault(sgg, set()).add(sido)
    for (sido, sgg) in SGG_GROUP:
        KNOWN_SGG.setdefault(sido, set()).add(sgg)
        _SGG_SIDOS.setdefault(sgg, set()).add(sido)


_PAREN = re.compile(r"\([^()]*\)|\[[^\[\]]*\]|（[^（）]*）")
_ROAD_TOK = re.compile(r"^(?=[^ ]*[가-힣])[가-힣A-Za-z0-9·.]*[가-힣0-9](로|길)$")
_ROAD_GLUED = re.compile(r"^(?=[^ ]*[가-힣])([가-힣A-Za-z0-9·.]*[가-힣0-9](?:로|길))(지하)?(\d+)(?:-(\d+))?$")
_NUM_TOK = re.compile(r"^(지하)?(\d+)(?:-(\d+))?(?:호)?$")
_EMD_TOK = re.compile(r"^[가-힣0-9·.]*[가-힣0-9](동|가|리|읍|면)$")
_JIBUN_GLUED = re.compile(r"^([가-힣0-9·.]*[가-힣][가-힣0-9·.]*(?:동|가|리))(산)?(\d+)(?:-(\d+))?$")
_SAN_NUM = re.compile(r"^산(\d+)(?:-(\d+))?$")
_EMD_ROAD_GLUED = re.compile(r"^([가-힣]{1,4}[읍면])([가-힣0-9·.]{2,}(?:로|길))$")
_EMD_SAN_GLUED = re.compile(r"^([가-힣0-9·.]*[가-힣](?:동|가|리))(산\d*(?:-\d+)?)$")
_ROAD_NUM_GLUE = re.compile(r"(?<=[가-힣0-9](?:로|길))(지하)?(\d+(?:-\d+)?)(?![\d\-번길로가])")
_SPLIT_ROAD = re.compile(r"(?<=[가-힣0-9](?:로|길)) (?=\d+번?(?:로|길)(?:\s|\d|$))")
_GU_PREFIX = re.compile(r"^([가-힣]{1,4}?구)(?=\s|[가-힣])")


def _peel_sgg(rest, sido):
    """Return (sgg, remainder) when `rest` starts with a known district name."""
    cands = KNOWN_SGG.get(sido) if sido else _SGG_SIDOS.keys()
    if not cands:
        return None, rest
    best = None
    for name in cands:
        if rest.startswith(name) and (best is None or len(name) > len(best)):
            nxt = rest[len(name):len(name) + 1]
            # glued ('창원시의창구...') is fine, but not when the name starts a road ('XX구로 1')
            if nxt in ("", " ") or ("가" <= nxt <= "힣" and not re.match(r"(?:대?로|길)(?:\s|\d|$)", rest[len(name):])):
                best = name
    if not best:
        return None, rest
    return best, rest[len(best):].strip()


def parse_address(addr, hint_sido=None, hint_sgg=None):
    """Parse a Korean road-name or lot-number address into matching keys.

    Returns a dict with sido (canonical key) and sgg (first-level 시/군/구), plus either
    road/ug/bmain/bsub (road-name address) or emd/san/jmain/jsub (lot-number address).
    Missing province or district falls back to the hints (publishing local government).
    """
    s = clean(addr)
    if not s:
        return None
    s = _PAREN.sub(" ", s)
    s = s.split(",")[0]
    s = s.replace("번지", " ")
    s = re.sub(r"\s*-\s*", "-", s)
    s = re.sub(r"(?<=\d)\s+번길", "번길", s)
    s = re.sub(r"(?<=\d)\s+(?=(?:로|길)(?:\s|\d|$))", "", s)
    s = _SPLIT_ROAD.sub("", s)
    s = _WS.sub(" ", s).strip()
    out = {"sido": None, "sgg": None, "sgg2": None}
    rest = s
    for p in _SIDO_SPACED:
        if rest.startswith(p + " ") or rest == p:
            out["sido"] = SIDO_ALIASES[p]
            rest = rest[len(p):].strip()
            break
    else:
        for p in _SIDO_GLUED:
            if rest.startswith(p):
                out["sido"] = SIDO_ALIASES[p]
                rest = rest[len(p):].strip()
                break
        else:
            first = rest.split(" ")[0]
            # unknown province-like token (typo such as '서을특별시'): drop it, use the hint
            if len(first) >= 4 and first.endswith(("특별시", "광역시", "자치시", "자치도")) and "로" not in first:
                rest = rest[len(first):].strip()
    sgg, rest = _peel_sgg(rest, out["sido"])
    if sgg and not out["sido"]:
        sidos = _SGG_SIDOS.get(sgg, set())
        if len(sidos) == 1:
            out["sido"] = next(iter(sidos))
        elif hint_sido in sidos:
            out["sido"] = hint_sido
        else:
            sgg = None
    if sgg:
        out["sgg"] = sgg
        if sgg.endswith("시"):
            m = _GU_PREFIX.match(rest)
            if m:
                out["sgg2"] = m.group(1)
                rest = rest[len(m.group(1)):].strip()
    if not out["sido"]:
        out["sido"] = hint_sido
    if not out["sgg"] and (hint_sido == out["sido"] or not hint_sido):
        out["sgg"] = hint_sgg
    if out["sido"] == "세종":
        out["sgg"] = ""
    if out["sgg"] == "군위군":       # moved from 경북 to 대구 in 2023
        out["sido"] = "대구"
    rest = _ROAD_NUM_GLUE.sub(lambda m: " " + (m.group(1) or "") + m.group(2), rest)
    toks = []
    for t in rest.split(" "):
        m = _EMD_ROAD_GLUED.match(t) or _EMD_SAN_GLUED.match(t)
        toks.extend([m.group(1), m.group(2)] if m else [t])
    toks = [t for t in toks if t]
    emd = []
    for j, t in enumerate(toks):
        nxt = toks[j + 1] if j + 1 < len(toks) else ""
        nxt2 = toks[j + 2] if j + 2 < len(toks) else ""
        if _ROAD_TOK.match(t):
            ug, n = False, nxt
            if n == "지하":
                ug, n = True, nxt2
            m = _NUM_TOK.match(n)
            if m:
                out.update(road=t, ug=ug or bool(m.group(1)), bmain=int(m.group(2)), bsub=int(m.group(3) or 0))
                return out
            continue
        m = _ROAD_GLUED.match(t)
        if m:
            out.update(road=m.group(1), ug=bool(m.group(2)), bmain=int(m.group(3)), bsub=int(m.group(4) or 0))
            return out
        if _EMD_TOK.match(t):
            emd.append(t)
            san, n = False, nxt
            if n == "산":
                san, n = True, nxt2
            m = _SAN_NUM.match(n)
            if m and t[-1] in "동가리":
                out.update(emd=tuple(emd[-2:]), san=True, jmain=int(m.group(1)), jsub=int(m.group(2) or 0))
                return out
            m = _NUM_TOK.match(n)
            if m and not m.group(1) and t[-1] in "동가리":
                out.update(emd=tuple(emd[-2:]), san=san, jmain=int(m.group(2)), jsub=int(m.group(3) or 0))
                return out
            continue
        m = _JIBUN_GLUED.match(t)
        if m:
            emd.append(m.group(1))
            out.update(emd=tuple(emd[-2:]), san=bool(m.group(2)), jmain=int(m.group(3)), jsub=int(m.group(4) or 0))
            return out
    return out


def addr_keys(p):
    """Matching keys for a parsed address: ('R', ...) road key and/or ('J', ...) lot key."""
    keys = []
    if not p or not p.get("sido") or p.get("sgg") is None:
        return keys
    sgg = SGG_GROUP.get((p["sido"], p["sgg"]), p["sgg"])
    base = (p["sido"], sgg)
    if p.get("road"):
        keys.append(("R",) + base + (p["road"], p["ug"], p["bmain"], p["bsub"]))
    if p.get("emd"):
        emd = p["emd"]
        # keep 읍/면 + 리 (리 names repeat across 면); for 동/가 only the last token
        e = emd if (len(emd) == 2 and emd[-1].endswith("리") and emd[0][-1] in "읍면") else (emd[-1],)
        keys.append(("J",) + base + (e, p["san"], p["jmain"], p["jsub"]))
    return keys


def parse_org_regions(html_text):
    """{org_code: (sido_key, sgg or None)} from the LOCALDATA region <select> options."""
    up = dict(re.findall(r'<option value="(\d+_ALL)"[^>]*>([^<]+)</option>', html_text))
    out = {}
    abbr = {"서울특별시": "서울", "부산광역시": "부산", "대구광역시": "대구", "인천광역시": "인천",
            "전남광주통합특별시": "전남광주", "대전광역시": "대전", "울산광역시": "울산", "세종특별자치시": "세종",
            "경기도": "경기", "강원특별자치도": "강원", "충청북도": "충북", "충청남도": "충남",
            "전북특별자치도": "전북", "경상북도": "경북", "경상남도": "경남", "제주특별자치도": "제주"}
    for upper, code, name in re.findall(r'<option data-upper="(\d+_ALL)"\s+value="(\d+)"[^>]*>([^<]+)</option>', html_text):
        full = clean(up.get(upper, ""))
        sido = SIDO_ALIASES.get(full)
        name = clean(name)
        if not sido:
            continue
        if name.endswith("본청"):
            out[code] = (sido, None)
            continue
        a = abbr.get(full, sido)
        sgg = name[len(a):] if name.startswith(a) else name
        out[code] = (sido, sgg or None)
    return out


_B62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def short_id(prefix, *parts, n=6):
    """Stable short id: prefix + n base62 digits of sha1(parts) (5.7e10 values for n=6;
    build.check_ids resolves the rare collision deterministically)."""
    v = int.from_bytes(hashlib.sha1("|".join(str(x) for x in parts).encode("utf-8")).digest()[:8], "big")
    out = []
    for _ in range(n):
        v, r = divmod(v, 62)
        out.append(_B62[r])
    return prefix + "".join(out)


def dumps_compact(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
