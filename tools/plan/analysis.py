"""Original analyses for the planning document, computed from the app's bundled data.

    python3 -I -B tools/plan/analysis.py      # writes docs/plan/data/*.json

1. gapmap: restroom opportunities along an expressway direction and the gaps between them.
   Kilometre positions come from the drowsy-shelter installation data (이정, km from the
   route's origin; for 경부선 0 km = 부산). Rest areas have no 이정, so they are projected
   onto the polyline the shelters form (both directions). Gap minutes are distance / speed.
2. restroom_facts: share of public restrooms with each property in restrooms.json, and the
   share that is known to be open at 23:00.
Everything here is derived from app/src/main/assets/data (see tools/data/README.md).
"""
import json
import math
import os
import sys

sys.dont_write_bytecode = True
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
DATA = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'data')
OUT = os.path.join(ROOT, 'docs', 'plan', 'data')


def load(name):
    with open(os.path.join(DATA, name + '.json'), encoding='utf-8') as f:
        return json.load(f)


def dist_m(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def project(poly, p):
    """poly: [(km, lat, lon)] sorted by km. Returns (km at p, distance from the line in m)."""
    best = None
    for (k1, a1, o1), (k2, a2, o2) in zip(poly, poly[1:]):
        kx = math.cos(math.radians(a1))
        ax, ay, bx, by = o1 * kx, a1, o2 * kx, a2
        px, py = p[1] * kx, p[0]
        dx, dy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy or 1e-12)))
        q = (ay + dy * t, (ax + dx * t) / kx)
        d = dist_m(p, q)
        if best is None or d < best[1]:
            best = (k1 + (k2 - k1) * t, d, t)
    km, d, t = best
    # beyond the ends of the polyline: extend by straight-line distance along the end segment
    if t in (0.0, 1.0):
        end = poly[0] if abs(km - poly[0][0]) < 1e-9 else poly[-1] if abs(km - poly[-1][0]) < 1e-9 else None
        if end is not None:
            nb = poly[1] if end is poly[0] else poly[-2]
            along = dist_m(p, (end[1], end[2]))
            sign = -1 if end is poly[0] else 1
            if along > 500:
                km = end[0] + sign * along / 1000.0
                d = 0.0
    return km, d


def gapmap(route_no=1, toward='부산방향', origin_km=416.0, end_km=0.0, label='경부고속도로 서울→부산'):
    ra = [r for r in load('rest_areas') if r.get('rno') == route_no]
    ds = [r for r in load('drowsy_shelters') if r.get('rno') == route_no]
    poly = sorted({(r['km'], r['lat'], r['lon']) for r in ds if r.get('km') is not None})
    # collapse points with the same km (two directions) into their midpoint
    merged = {}
    for km, la, lo in poly:
        merged.setdefault(km, []).append((la, lo))
    poly = [(km, sum(a for a, _ in v) / len(v), sum(o for _, o in v) / len(v)) for km, v in sorted(merged.items())]
    stops = []
    for r in ra:
        if r.get('dir') not in (toward, '양방향'):
            continue
        km, off = project(poly, (r['lat'], r['lon']))
        stops.append({'name': r['n'], 'kind': 'rest_area', 'km': round(km, 1), 'offM': round(off),
                      'truck': r.get('k') == 'truck', 'lat': r['lat'], 'lon': r['lon']})
    for r in ds:
        if r.get('dir') != toward or r.get('wc') != 1:
            continue
        stops.append({'name': r['n'], 'kind': 'shelter', 'km': r['km'], 'offM': 0, 'truck': False,
                      'lat': r['lat'], 'lon': r['lon']})
    descending = origin_km > end_km
    stops.sort(key=lambda s: -s['km'] if descending else s['km'])
    for s in stops:
        s['fromOriginKm'] = round(abs(origin_km - s['km']), 1)
    gaps = []
    for a, b in zip(stops, stops[1:]):
        g = abs(a['km'] - b['km'])
        gaps.append({'from': a['name'], 'to': b['name'], 'km': round(g, 1),
                     'startKm': a['fromOriginKm'], 'endKm': b['fromOriginKm']})
    kms = [g['km'] for g in gaps]
    def minutes(km, kph):
        return km / kph * 60
    stats = {
        'stops': len(stops), 'restAreas': sum(s['kind'] == 'rest_area' for s in stops),
        'shelters': sum(s['kind'] == 'shelter' for s in stops),
        'meanGapKm': round(sum(kms) / len(kms), 1), 'maxGapKm': round(max(kms), 1),
        'medianGapKm': round(sorted(kms)[len(kms) // 2], 1),
        'meanMin100': round(minutes(sum(kms) / len(kms), 100)), 'maxMin100': round(minutes(max(kms), 100)),
        'meanMin20': round(minutes(sum(kms) / len(kms), 20)), 'maxMin20': round(minutes(max(kms), 20)),
        'gapsOver60minAt20': sum(minutes(k, 20) > 60 for k in kms),
        'gapsOver45minAt20': sum(minutes(k, 20) > 45 for k in kms),
        'gapsOver30minAt20': sum(minutes(k, 20) > 30 for k in kms),
        'gaps': len(kms),
    }
    return {'label': label, 'route': route_no, 'toward': toward, 'originKm': origin_km, 'endKm': end_km,
            'lengthKm': abs(origin_km - end_km), 'stops': stops, 'gaps': gaps, 'stats': stats,
            'note': 'km = 한국도로공사 이정(경부선은 부산 0km). 휴게소는 졸음쉼터 이정으로 만든 선형에 투영. '
                    '화물차 휴게소와 양방향 휴게소 포함, 졸음쉼터는 화장실 있는 곳만'}


def hm(s):
    try:
        h, m = s.split(':')
        return int(h) * 60 + int(m)
    except Exception:
        return None


def open_at(r, minute):
    if r.get('h24') == 1:
        return True
    oh = r.get('oh')
    if not oh or '-' not in oh:
        return None
    a, b = (hm(x) for x in oh.split('-', 1))
    if a is None or b is None:
        return None
    if b == 0:
        b = 24 * 60
    return a <= minute < b if a <= b else (minute >= a or minute < b)


def restroom_facts():
    rs = load('restrooms')
    n = len(rs)
    def share(pred):
        k = sum(1 for r in rs if pred(r))
        return {'count': k, 'pct': round(100 * k / n, 1)}
    at23 = [open_at(r, 23 * 60) for r in rs]
    known = [x for x in at23 if x is not None]
    return {
        'total': n,
        'parkable': share(lambda r: r.get('pk') == 1),
        'h24': share(lambda r: r.get('h24') == 1),
        'diaper': share(lambda r: r.get('dp') == 1),
        'bell': share(lambda r: r.get('bell') == 1),
        'cctv': share(lambda r: r.get('cctv') == 1),
        'hoursKnown': {'count': len(known), 'pct': round(100 * len(known) / n, 1)},
        'openAt23': {'count': sum(known), 'pct': round(100 * sum(known) / n, 1)},
        'closedAt23Known': {'count': len(known) - sum(known), 'pct': round(100 * (len(known) - sum(known)) / n, 1)},
        'parkableAndOpenAt23': share(lambda r: r.get('pk') == 1 and open_at(r, 23 * 60) is True),
        'note': 'restrooms.json(좌표 있는 공중화장실) 기준. 값이 없으면 모르는 것으로 셈',
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    out = {'gyeongbu_south': gapmap(), 'restroom_facts': restroom_facts()}
    path = os.path.join(OUT, 'analysis.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(out['gyeongbu_south']['stats'], ensure_ascii=False))
    print(json.dumps(out['restroom_facts'], ensure_ascii=False))
    for s in out['gyeongbu_south']['stops']:
        print('%6.1f %-12s %s off=%dm%s' % (s['fromOriginKm'], s['name'], s['kind'], s['offM'], ' (truck)' if s['truck'] else ''))
    print(path)


if __name__ == '__main__':
    main()
