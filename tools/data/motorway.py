"""Motorway carriageway index (OpenStreetMap highway=motorway ways).

Korean expressways are mapped as two one-way carriageways. Traffic keeps right, so a
rest area serving one direction lies to the right of the carriageway that serves it. The
heading of that carriageway at the rest area is the travel direction of vehicles that can
enter it; a rest area between the carriageways (both directions) is on the left of both.

Carriageways are chained through shared end nodes (`trace`), so a direction label such as
'부산방향' can be checked against where the carriageway actually goes over tens of km
(`progress_toward`) instead of its local bearing: Korean expressways bend around hills and
cities (경부선 near 대신 runs north-east for 2 km on its way south-east to 부산; 호남선 at
동광주 runs west-south-west on its way north to 천안), and the old local-bearing test picked
the wrong carriageway there (2026-11-11 audit).
"""
import collections
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

KEC = "한국도로공사"


TRACE_MAX_TURN = 100.0   # a successor that turns more than this is not the same carriageway


def _node(p):
    return round(p[0], 7), round(p[1], 7)


class Motorways:
    def __init__(self, path):
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        self.grid = common.GridIndex(cell=0.01)
        self.ways = {}
        self.geom = {}                                  # way id -> [(lat, lon)] in travel direction
        # node -> [(way id, index)]: a ramp or a rest-area lane often merges into the middle of a
        # mainline way (OSM does not split the mainline there), so chains join at any node
        self.nodes = collections.defaultdict(list)
        for e in d["elements"]:
            if e.get("type") != "way" or "geometry" not in e:
                continue
            t = e.get("tags", {})
            ow = t.get("oneway")
            self.ways[e["id"]] = {
                "name": t.get("name:ko") or t.get("name") or "",
                "ref": t.get("ref") or "",
                "operator": t.get("operator") or "",
                "oneway": -1 if ow == "-1" else (1 if ow in ("yes", None) else 0),
            }
            g = e["geometry"]
            if ow == "-1":
                g = list(reversed(g))
            pts = [(p["lat"], p["lon"]) for p in g]
            self.geom[e["id"]] = pts
            for i, q in enumerate(pts):
                self.nodes[_node(q)].append((e["id"], i))
            for i, (a, b) in enumerate(zip(pts, pts[1:])):
                mla, mlo = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
                self.grid.add(mla, mlo, (e["id"], i, a[0], a[1], b[0], b[1]))

    def segments_near(self, lat, lon, radius_m=600, with_pos=False):
        """[(dist_m, side, bearing, way_id)] for segments within radius; side>0 = right.
        with_pos=True appends (segment index, t) of the closest point for `trace`."""
        out = []
        kx = 111320.0 * math.cos(math.radians(lat))
        ky = 110540.0
        for _, _, _, (wid, si, la1, lo1, la2, lo2) in self.grid.near(lat, lon, radius_m + 1500):
            ax, ay = (lo1 - lon) * kx, (la1 - lat) * ky
            bx, by = (lo2 - lon) * kx, (la2 - lat) * ky
            dx, dy = bx - ax, by - ay
            L2 = dx * dx + dy * dy
            if L2 == 0:
                continue
            t = max(0.0, min(1.0, -(ax * dx + ay * dy) / L2))
            px, py = ax + t * dx, ay + t * dy
            dist = math.hypot(px, py)
            if dist > radius_m:
                continue
            cross = dx * (-ay) - dy * (-ax)     # (B-A) x (P-A), P at origin
            side = 1 if cross < 0 else -1       # negative cross product = P on the right
            brg = common.bearing_deg(la1, lo1, la2, lo2)
            out.append((dist, side, brg, wid, si, t) if with_pos else (dist, side, brg, wid))
        out.sort()
        return out

    # ------------------------------------------------------------ carriageway chains

    def _successor(self, wid, forward):
        """(way, node index) where the same carriageway continues after the end of `wid` (before its
        start when walking backward): another one-way way through that node, same name preferred, then
        the smallest turn; None at a dead end or a sharp turn."""
        g = self.geom[wid]
        if forward:
            node, b0 = _node(g[-1]), common.bearing_deg(*g[-2], *g[-1])
        else:
            node, b0 = _node(g[0]), common.bearing_deg(*g[0], *g[1])
        name = self.ways[wid]["name"]
        best = None
        for c, i in self.nodes.get(node, ()):
            cg = self.geom[c]
            if c == wid or self.ways[c]["oneway"] == 0 or (forward and i == len(cg) - 1) or (not forward and i == 0):
                continue
            b1 = common.bearing_deg(*cg[i], *cg[i + 1]) if forward else common.bearing_deg(*cg[i - 1], *cg[i])
            turn = angle_diff(b0, b1)
            if turn > TRACE_MAX_TURN:
                continue
            key = (self.ways[c]["name"] != name, turn)
            if best is None or key < best[0]:
                best = (key, (c, i))
        return best[1] if best else None

    def trace(self, wid, si, t, dist_m, forward=True):
        """Walk `dist_m` along the carriageway from the point at fraction `t` of segment `si` of way
        `wid` (forward = travel direction). Returns (lat, lon, walked_m); walked_m < dist_m when the
        chain ends (gap in the data, junction without a same-direction successor)."""
        g = self.geom[wid]
        a, b = g[si], g[si + 1]
        cur = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        walked = 0.0
        seen = {wid}
        pts = g[si + 1:] if forward else list(reversed(g[:si + 1]))
        while True:
            for p in pts:
                seg = common.haversine_m(cur[0], cur[1], p[0], p[1])
                if walked + seg >= dist_m:
                    f = (dist_m - walked) / seg if seg > 0 else 0.0
                    return cur[0] + (p[0] - cur[0]) * f, cur[1] + (p[1] - cur[1]) * f, dist_m
                walked += seg
                cur = p
            nxt = self._successor(wid, forward)
            if nxt is None or nxt[0] in seen:
                return cur[0], cur[1], walked
            wid, i = nxt
            seen.add(wid)
            pts = self.geom[wid][i + 1:] if forward else list(reversed(self.geom[wid][:i]))

    def heading_along(self, wid, si, t, span_m=150):
        """Travel heading of the carriageway around a point: bearing from `span_m` behind to `span_m`
        ahead along the chain (one short segment can point anywhere, e.g. at a node snapped off-line)."""
        la0, lo0, _ = self.trace(wid, si, t, span_m, forward=False)
        la1, lo1, _ = self.trace(wid, si, t, span_m, forward=True)
        if (la0, lo0) == (la1, lo1):
            g = self.geom[wid]
            return common.bearing_deg(*g[si], *g[si + 1])
        return common.bearing_deg(la0, lo0, la1, lo1)

    def progress_toward(self, wid, si, t, dest, dist_m=30000):
        """How much driving this carriageway brings you closer to `dest` (lat, lon): (straight-line
        distance gained) / (distance driven) over up to `dist_m`, in [-1, 1]; 1 = straight at it.
        Returns (progress, driven_m). The walk stops at 80 % of the distance to `dest` so that
        driving past it does not count against the right carriageway."""
        g = self.geom[wid]
        a, b = g[si], g[si + 1]
        p = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        d0 = common.haversine_m(p[0], p[1], *dest)
        la, lo, walked = self.trace(wid, si, t, min(dist_m, 0.8 * d0), forward=True)
        if walked <= 0:
            return 0.0, 0.0
        return (d0 - common.haversine_m(la, lo, *dest)) / walked, walked

    def carriageway_pair(self, lat, lon, max_m=500):
        """The nearest one-way carriageway and the nearest one running the other way (> 120 degrees
        apart): [(dist, side, bearing, way, segment, t)], one or two entries, or [] if none is close."""
        out = []
        for s in self.segments_near(lat, lon, max_m, with_pos=True):
            if self.ways[s[3]]["oneway"] == 0:
                continue
            if not out:
                out.append(s)
            elif angle_diff(s[2], out[0][2]) > 120:
                out.append(s)
                break
        return out

    def heading_for(self, lat, lon, max_m=350):
        """Travel heading served by a roadside facility at (lat, lon).

        Returns dict(hdg, dist, way, both) or None when no motorway is close. `both` is True
        when the point lies on the left of every nearby one-way carriageway (median site
        serving both directions) or the nearest way is two-way.
        """
        segs = self.segments_near(lat, lon, max_m)
        if not segs:
            return None
        nearest = segs[0]
        w0 = self.ways[nearest[3]]
        if w0["oneway"] == 0:
            return {"hdg": None, "dist": nearest[0], "way": nearest[3], "both": True}
        right = [s for s in segs if s[1] > 0 and self.ways[s[3]]["oneway"] != 0]
        left = [s for s in segs if s[1] < 0 and self.ways[s[3]]["oneway"] != 0]
        if right and (not left or right[0][0] <= left[0][0] + 40):
            r = right[0]
            return {"hdg": round(r[2]), "dist": r[0], "way": r[3], "both": False}
        if left:
            return {"hdg": None, "dist": left[0][0], "way": left[0][3], "both": True}
        return None

    def heading_toward(self, lat, lon, target_bearing, max_m=500):
        """Heading of the nearest one-way carriageway whose direction is within 90 degrees of
        `target_bearing` (used when a direction label such as '부산방향' is known)."""
        for dist, _side, brg, wid in self.segments_near(lat, lon, max_m):
            if self.ways[wid]["oneway"] == 0:
                continue
            if angle_diff(brg, target_bearing) < 90:
                return {"hdg": round(brg), "dist": dist, "way": wid, "both": False}
        return None

    def way_info(self, wid):
        return self.ways.get(wid, {})


def angle_diff(a, b):
    d = abs((a - b) % 360.0)
    return min(d, 360.0 - d)
