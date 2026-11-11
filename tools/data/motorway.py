"""Motorway carriageway index (OpenStreetMap highway=motorway ways).

Korean expressways are mapped as two one-way carriageways. Traffic keeps right, so a
rest area serving one direction lies to the right of the carriageway that serves it. The
heading of that carriageway at the rest area is the travel direction of vehicles that can
enter it; a rest area between the carriageways (both directions) is on the left of both.
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

KEC = "한국도로공사"


class Motorways:
    def __init__(self, path):
        with open(path, encoding="utf-8") as f:
            d = json.load(f)
        self.grid = common.GridIndex(cell=0.01)
        self.ways = {}
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
            for a, b in zip(g, g[1:]):
                mla, mlo = (a["lat"] + b["lat"]) / 2, (a["lon"] + b["lon"]) / 2
                self.grid.add(mla, mlo, (e["id"], a["lat"], a["lon"], b["lat"], b["lon"]))

    def segments_near(self, lat, lon, radius_m=600):
        """[(dist_m, side, bearing, way_id)] for segments within radius; side>0 = right."""
        out = []
        kx = 111320.0 * math.cos(math.radians(lat))
        ky = 110540.0
        for _, _, _, (wid, la1, lo1, la2, lo2) in self.grid.near(lat, lon, radius_m + 1500):
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
            out.append((dist, side, common.bearing_deg(la1, lo1, la2, lo2), wid))
        out.sort()
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
