"""Offline importer regression tests: python3 -I -B tools/data/test_ev_chargers.py."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ev_chargers import aggregate, is_fast, matching_site, page_data


class EvChargerTest(unittest.TestCase):
    def test_station_aggregation_and_filters(self):
        fixture = json.loads((Path(__file__).parent / "fixtures/ev_chargers.json").read_text())
        sites = [dict(id="rest", n="안성휴게소", lat=37, lon=127)]
        rows, counts = aggregate(fixture, sites)
        self.assertEqual(["evA", "evB", "evF", "evN"], [r["id"] for r in rows])
        a, b, f, n = rows
        self.assertEqual(2, a["dc"])
        self.assertEqual(200, a["kw"])
        self.assertEqual(1, a["h24"])
        self.assertEqual("rest", a["at"])
        self.assertNotIn("at", b)  # same coordinates, different site name
        self.assertEqual("09:00-18:00", b["oh"])
        self.assertNotIn("h24", f)  # weekday-only 24h is not unconditional 24h
        self.assertEqual(1, n["dc"])
        self.assertEqual(1, counts["duplicate_charger_rows"])
        self.assertEqual(1, counts["deleted_chargers"])
        self.assertEqual(1, counts["restricted_chargers"])
        self.assertEqual(2, counts["invalid_coordinate_chargers"])
        self.assertEqual(1, counts["slow_only_stations"])
        self.assertEqual(3, counts["stations_without_fast_dc"])
        self.assertEqual(1, counts["inconsistent_coordinate_stations"])

    def test_guide_codes(self):
        for code in ("01", "03", "04", "05", "06", "10"):
            self.assertTrue(is_fast(dict(chgerType=code)))
        for code in ("02", "07", "08", "11", "99", ""):
            self.assertFalse(is_fast(dict(chgerType=code, output=200)))
        self.assertFalse(is_fast(dict(chgerType="09", output=7)))
        self.assertFalse(is_fast(dict(chgerType="09", output="NaN")))
        self.assertTrue(is_fast(dict(chgerType="09", output=250)))

    def test_dedupe_needs_name_and_150m_distance(self):
        site = dict(id="rest", n="안성휴게소", lat=37, lon=127)
        self.assertEqual("rest", matching_site(dict(n="안성(부산)휴게소 충전소", lat=37.0005, lon=127), [site]))
        self.assertIsNone(matching_site(dict(n="안성맞춤휴게소", lat=37, lon=127), [site]))
        self.assertIsNone(matching_site(dict(n="안성휴게소", lat=37.002, lon=127), [site]))
        self.assertEqual("rest", matching_site(dict(n="워터 안성휴게소", lat=37, lon=127), [site]))
        self.assertIsNone(matching_site(dict(n="서안성휴게소", lat=37, lon=127), [site]))

    def test_api_shapes_and_error(self):
        page = dict(resultCode="00", totalCount=1, pageNo=1, items=dict(item=[dict(statId="A")]))
        self.assertEqual((1, 1, [dict(statId="A")]), page_data(json.dumps(page)))
        nested = dict(response=dict(header=dict(resultCode="00"), body=page))
        self.assertEqual(page_data(json.dumps(page)), page_data(json.dumps(nested)))
        with self.assertRaises(ValueError):
            page_data('{"resultCode":"99"}')

    def test_opposite_rest_area_is_not_used_even_when_closer(self):
        sites = [dict(id="north", n="안성휴게소", dir="서울방향", lat=37, lon=127),
                 dict(id="south", n="안성휴게소", dir="부산방향", lat=37.0005, lon=127)]
        self.assertEqual("south", matching_site(dict(n="안성(부산)휴게소", lat=37, lon=127), sites))

    def test_duplicate_deletion_does_not_resurrect_the_previous_row(self):
        row = dict(statId="A", chgerId="1", chgerType="04", lat=37, lng=127)
        stations, counts = aggregate([row, dict(row, delYn="Y")], [])
        self.assertEqual([], stations)
        self.assertEqual(1, counts["deleted_chargers"])

    def test_northern_coordinate_outside_the_shared_korea_bbox_is_dropped(self):
        stations, counts = aggregate([dict(statId="A", chgerId="1", chgerType="04", lat=38.98209, lng=127.06772)], [])
        self.assertEqual([], stations)
        self.assertEqual(1, counts["invalid_coordinate_chargers"])


if __name__ == "__main__":
    unittest.main()
