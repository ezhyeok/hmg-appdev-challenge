"""Test fixture for rest-engine/coach StopRepositoryTest: car positions on the real OSM carriageways.

    python3 -I -B tools/data/replay_positions.py RAW_DIR rest-engine/coach/src/test/resources/stops/replay_positions.csv

- gyeongbu-NN: 39 positions every ~3.4 km along the 경부고속도로 부산방향 carriageway from 기흥 (37.2351, 127.1046)
  to past 옥천, the same stretch as the 2026-11-11 audit's 39-position replay. The audit placed its positions on a
  13-vertex polyline (up to a few km off the road) with the polyline's bearing; these follow the road and its heading.
- audit-NN: the audit's own 39 positions (0.1/0.5/0.9 of each segment of its 13-vertex polyline, polyline bearing),
  kept for comparison; most are hundreds of metres to kilometres off the road, where map matching says "unknown".
- daesin-*, donggwangju-*: approaches to the shelters whose lane headings were wrong (audit #1).
Columns: name, lat, lon, heading (degrees). Deterministic.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import motorway  # noqa: E402


def on_carriageway(mw, lat, lon, heading, max_m=200):
    for s in mw.segments_near(lat, lon, max_m, with_pos=True):
        if mw.ways[s[3]]["oneway"] == 1 and motorway.angle_diff(s[2], heading) < 60:
            return s
    raise SystemExit(f"no carriageway heading {heading} near {lat},{lon}")


def walk(mw, s, dist, forward=True):
    """Point `dist` m along the carriageway from s (backward = upstream) and the travel heading there."""
    la, lo, walked = mw.trace(s[3], s[4], s[5], dist, forward)
    a = mw.trace(s[3], s[4], s[5], max(dist - 100, 0), forward)     # nearer the start of the walk
    b = mw.trace(s[3], s[4], s[5], dist + 100, forward)
    p, q = (a, b) if forward else (b, a)                            # cars drive upstream -> downstream
    return la, lo, common.bearing_deg(p[0], p[1], q[0], q[1]), walked


AUDIT_POLYLINE = [(37.235125, 127.104595), (37.156708, 127.087165), (37.096192, 127.120738), (37.013407, 127.144800),
                  (36.954311, 127.183007), (36.914432, 127.188465), (36.855650, 127.180929), (36.730226, 127.263879),
                  (36.657743, 127.369817), (36.486810, 127.429261), (36.425354, 127.416697), (36.328096, 127.540893),
                  (36.296863, 127.595223), (36.279148, 127.672231)]


def main():
    raw, out = sys.argv[1], sys.argv[2]
    mw = motorway.Motorways(os.path.join(raw, "osm", "motorways.json"))
    rows = []
    start = on_carriageway(mw, 37.235125, 127.104595, 190)
    for k in range(39):
        la, lo, h, walked = walk(mw, start, 1700 + k * 3400)
        if walked < 1700 + k * 3400 - 1:
            raise SystemExit("chain ended")
        rows.append((f"gyeongbu-{k:02d}", la, lo, h))
    g = AUDIT_POLYLINE
    for i in range(len(g) - 1):
        for j, f in enumerate((0.1, 0.5, 0.9)):
            la, lo = g[i][0] + (g[i + 1][0] - g[i][0]) * f, g[i][1] + (g[i + 1][1] - g[i][1]) * f
            rows.append((f"audit-{3 * i + j:02d}", la, lo, common.bearing_deg(*g[i], *g[i + 1])))
    # 대신졸음쉼터 (경부선, 36.1525, 128.1912): 부산방향 차로로 다가가는 1·5·15km 앞, 서울방향 차로로 다가가는 5km 앞
    busan = on_carriageway(mw, 36.152511, 128.191239, 30)
    seoul = on_carriageway(mw, 36.152819, 128.190756, 210)
    for km in (1, 5, 15):
        la, lo, h, _ = walk(mw, busan, km * 1000, forward=False)
        rows.append((f"daesin-busanbound-{km}km", la, lo, h))
    la, lo, h, _ = walk(mw, seoul, 5000, forward=False)
    rows.append(("daesin-seoulbound-5km", la, lo, h))
    # 동광주졸음쉼터 (호남선 천안방향, 35.2086, 126.9394): 천안방향 1·9km 앞, 순천방향 차로 5km 앞
    cheonan = on_carriageway(mw, 35.208551, 126.93939, 242)
    suncheon = on_carriageway(mw, 35.208551, 126.93939, 61)
    for km in (1, 9):
        la, lo, h, _ = walk(mw, cheonan, km * 1000, forward=False)
        rows.append((f"donggwangju-cheonanbound-{km}km", la, lo, h))
    la, lo, h, _ = walk(mw, suncheon, 5000, forward=False)
    rows.append(("donggwangju-suncheonbound-5km", la, lo, h))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("name,lat,lon,heading\n")
        for n, la, lo, h in rows:
            f.write(f"{n},{la:.6f},{lo:.6f},{h:.1f}\n")
    print(f"{out}: {len(rows)} positions")


if __name__ == "__main__":
    main()
