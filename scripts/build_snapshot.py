#!/usr/bin/env python3
"""
Builds a static snapshot of family-relevant places from OpenStreetMap.

Output (default ./site):
  v1/index.json              list of regions with download URL, size and generation time
  v1/regions/<id>.json.gz          every café/restaurant in the region (gzip, ~0.5 MB for Denmark),
                                   rebuilt daily; downloaded whole by the app for offline use
  v1/regions/<id>.search.json.gz   offline search index of towns, neighbourhoods and streets
                                   (~1.7 MB), rebuilt weekly because it rarely changes, so phones
                                   only download it about once a week

Source: Geofabrik's daily country extract, filtered with osmium-tool. This puts no load on
OpenStreetMap's own servers. Geofabrik is retried for ~20 minutes; if it is still unavailable,
or its extract is more than 3 days old, nothing is published and the app keeps the previous data.

With --skip-if-current (scheduled runs) it first checks, in a few seconds, whether Geofabrik's
current extract is already published, and stops if so. That makes a second daily run nearly free:
it only does real work when the first one failed.

Requires osmium-tool (`apt install osmium-tool`); otherwise only the standard library.
"""
import argparse
import gzip
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

AMENITIES = ["cafe", "restaurant", "ice_cream", "fast_food"]
GEOFABRIK = "https://download.geofabrik.de"
# Pauses between attempts to reach Geofabrik (check + download + filter): ~21 minutes in all,
# enough to ride out a server restart or a network blip on the runner.
GEOFABRIK_WAITS_S = [60, 180, 420, 600]
PUBLISHED = "https://raghavian.github.io/cortado/v1"
USER_AGENT = "babylatte-snapshot/1.2 (+https://github.com/raghavian/cortado)"
ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0. https://www.openstreetmap.org/copyright"
MAX_EXTRACT_AGE_DAYS = 3
SEARCH_MAX_AGE_DAYS = 7  # republish the previous search file until it is this old

# Tags the app actually reads. Everything else is dropped to keep the download small.
KEEP_PREFIXES = ("addr:",)
KEEP_TAGS = {
    "name", "amenity", "opening_hours", "website", "contact:website", "phone", "contact:phone",
    "changing_table", "diaper", "toilets:changing_table",
    "stroller", "wheelchair", "entrance:step_count", "step_count", "ramp",
    "highchair", "kids_area", "kids_area:indoor", "playground",
    "baby_feeding", "breastfeeding",
    "toilets", "toilets:wheelchair", "outdoor_seating",
}

# Offline search: named places and streets. Kinds are passed to the app, which ranks and zooms by them.
PLACE_KINDS = ["city", "town", "village", "suburb", "quarter", "neighbourhood", "hamlet", "island", "square"]
# Places used to say where a street or neighbourhood is ("Jægersborggade, Nørrebro").
CONTEXT_KINDS = {"city", "town", "village", "suburb", "quarter"}
STREET_HIGHWAYS = ["residential", "living_street", "pedestrian", "unclassified", "tertiary",
                   "secondary", "primary", "trunk"]
STREET_MERGE_M = 1000  # pieces of a same-named street closer than this are one street


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------------------------
# Geofabrik + osmium


def http_get(url: str, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


class StaleExtract(RuntimeError):
    """Geofabrik answered, but its extract is too old. Retrying for a few minutes won't help."""


def geofabrik_urls(path: str):
    """(state.txt, latest .osm.pbf) for a region path like "europe/denmark".

    Geofabrik's layout since Nov 2026: europe/denmark/updates/state.txt and
    europe/denmark/denmark-latest.osm.pbf (the old europe/denmark-latest.osm.pbf redirects).
    """
    name = path.rstrip("/").rsplit("/", 1)[-1]
    return f"{GEOFABRIK}/{path}/updates/state.txt", f"{GEOFABRIK}/{path}/{name}-latest.osm.pbf"


def extract_timestamp(path: str) -> datetime:
    """Time of the last OSM edit included in Geofabrik's current extract."""
    state = http_get(geofabrik_urls(path)[0]).decode()
    m = re.search(r"timestamp=(\S+)", state)
    if not m:
        raise RuntimeError("no timestamp in Geofabrik state.txt")
    return datetime.strptime(m.group(1).replace("\\", ""), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def download(url: str, dest: str, attempts: int = 3) -> None:
    for i in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as f:
                shutil.copyfileobj(resp, f, 1 << 20)
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if i == attempts - 1:
                raise
            log(f"  download failed ({e}); retrying in 60s")
            time.sleep(60)


def osmium(*args: str) -> None:
    subprocess.run(["osmium", *args], check=True)


def features_from_geofabrik(path: str, work: str, pbf: str = None):
    """(GeoJSON features for every relevant object in the extract, ISO time of its last OSM edit or None)."""
    osm_until = None
    if pbf is None:
        ts = extract_timestamp(path)
        age = (datetime.now(timezone.utc) - ts).total_seconds() / 86400
        log(f"  Geofabrik extract has OSM edits up to {ts.isoformat()} ({age:.1f} days old)")
        if age > MAX_EXTRACT_AGE_DAYS:
            raise StaleExtract(f"Geofabrik's extract is {age:.1f} days old (edits up to {ts.isoformat()})")
        osm_until = ts.isoformat(timespec="seconds")
        pbf = os.path.join(work, "extract.osm.pbf")
        started = time.time()
        download(geofabrik_urls(path)[1], pbf)
        log(f"  downloaded {os.path.getsize(pbf) / 1e6:.0f} MB in {time.time() - started:.0f}s")
    filtered = os.path.join(work, "filtered.osm.pbf")
    osmium("tags-filter", pbf, "--overwrite", "-o", filtered,
           "nwr/amenity=" + ",".join(AMENITIES),
           "nwr/place=" + ",".join(PLACE_KINDS),
           "w/highway=" + ",".join(STREET_HIGHWAYS))
    out = os.path.join(work, "features.geojsonseq")
    osmium("export", filtered, "--overwrite", "-o", out, "-f", "geojsonseq",
           "-x", "print_record_separator=false", "--add-unique-id=type_id")
    return read_features(out), osm_until


def read_features(path: str):
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                yield json.loads(line)


def bbox_of(coords) -> list:
    """[south, west, north, east] of any GeoJSON coordinate nesting."""
    lats, lons = [], []

    def walk(c):
        if isinstance(c[0], (int, float)):
            lons.append(c[0])
            lats.append(c[1])
        else:
            for x in c:
                walk(x)

    walk(coords)
    return [min(lats), min(lons), max(lats), max(lons)]


OSM_TYPES = {"n": "node", "w": "way", "r": "relation"}


def osm_id(feature_id: str) -> str:
    """'n12' -> 'node/12'. osmium numbers areas separately: 'a<2*way id>' or 'a<2*relation id + 1>'."""
    kind, num = feature_id[0], int(feature_id[1:])
    if kind == "a":
        return f"way/{num // 2}" if num % 2 == 0 else f"relation/{num // 2}"
    return f"{OSM_TYPES[kind]}/{num}"


def split_features(features):
    """Sorts exported features into (cafés, places, streets) as plain dicts with a center and bbox."""
    cafes, places, streets = {}, {}, []
    for ft in features:
        tags = ft.get("properties") or {}
        name = tags.get("name")
        if not name or not ft.get("geometry"):
            continue
        bb = bbox_of(ft["geometry"]["coordinates"])
        obj = {"id": osm_id(ft["id"]), "tags": tags, "bbox": bb,
               "lat": (bb[0] + bb[2]) / 2, "lon": (bb[1] + bb[3]) / 2}  # like Overpass `out center`
        # A building-shaped café comes twice, as a line and as an area; both give the same center.
        if tags.get("amenity") in AMENITIES:
            cafes.setdefault(obj["id"], obj)
        if tags.get("place") in PLACE_KINDS:
            places.setdefault(obj["id"], obj)
        if tags.get("highway") in STREET_HIGHWAYS and ft["geometry"]["type"] == "LineString":
            streets.append(obj)
    return list(cafes.values()), list(places.values()), streets


# ---------------------------------------------------------------------------------------------
# What's already published

def published_index() -> dict:
    try:
        return {r["id"]: r for r in json.loads(http_get(f"{PUBLISHED}/index.json"))["regions"]}
    except Exception as e:  # noqa: BLE001 - first run, or the site is down: just rebuild everything
        log(f"  no previously published index ({e})")
        return {}


def previous_search(entry: dict):
    """(bytes, generated) of the published search file, or None."""
    if not entry or not entry.get("searchUrl"):
        return None
    try:
        data = http_get(f"{PUBLISHED}/{entry['searchUrl']}")
        json.loads(gzip.decompress(data))  # make sure it's intact before republishing it
        return data, entry["searchGenerated"]
    except Exception as e:  # noqa: BLE001
        log(f"  could not fetch the previous search file: {e}")
        return None


def age_days(iso: str) -> float:
    return (datetime.now(timezone.utc) - datetime.fromisoformat(iso)).total_seconds() / 86400


# ---------------------------------------------------------------------------------------------
# Building the region file


def compact_cafe(c: dict):
    tags = c["tags"]
    if not tags.get("name"):
        return None
    kept = {k: v for k, v in tags.items() if k in KEEP_TAGS or k.startswith(KEEP_PREFIXES)}
    return {"id": c["id"], "lat": round(c["lat"], 6), "lon": round(c["lon"], 6), "tags": kept}


def meters(lat1, lon1, lat2, lon2) -> float:
    k = math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot((lat2 - lat1) * 111_320, (lon2 - lon1) * 111_320 * k)


class Grid:
    """Tiny spatial index: nearest object within a radius."""

    def __init__(self, items, cell_deg=0.05):
        self.cell = cell_deg
        self.cells = {}
        for it in items:
            self.cells.setdefault(self._key(it["lat"], it["lon"]), []).append(it)

    def _key(self, lat, lon):
        return int(math.floor(lat / self.cell)), int(math.floor(lon / self.cell))

    def nearest(self, lat, lon, max_m, accept=lambda it: True):
        ky, kx = self._key(lat, lon)
        best, best_d = None, max_m
        r = int(math.ceil(max_m / (self.cell * 111_320 * 0.5))) + 1
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                for it in self.cells.get((ky + dy, kx + dx), ()):
                    d = meters(lat, lon, it["lat"], it["lon"])
                    if d < best_d and accept(it):
                        best, best_d = it, d
        return best


def context_label(grid: Grid, lat, lon, exclude_name=None, kinds=CONTEXT_KINDS, max_m=6000):
    near = grid.nearest(lat, lon, max_m,
                        lambda it: it["tags"]["place"] in kinds and it["tags"]["name"] != exclude_name)
    return near["tags"]["name"] if near else None


def r5(x: float) -> float:
    return round(x, 5)


def build_search(places: list, streets: list) -> list:
    context = Grid([p for p in places if p["tags"]["place"] in CONTEXT_KINDS])
    towns = Grid([p for p in places if p["tags"]["place"] in ("city", "town")])
    out = []
    for p in places:
        kind = p["tags"]["place"]
        entry = {"name": p["tags"]["name"], "kind": kind, "lat": r5(p["lat"]), "lon": r5(p["lon"])}
        if kind in ("suburb", "quarter", "neighbourhood", "square"):
            # A district belongs to the nearest city if there is one (Vesterbro → København, not the
            # neighbouring town Frederiksberg), otherwise to the nearest town.
            label = (context_label(towns, p["lat"], p["lon"], p["tags"]["name"], kinds={"city"}, max_m=12000)
                     or context_label(towns, p["lat"], p["lon"], p["tags"]["name"], kinds={"town"}, max_m=12000))
        elif kind in ("village", "hamlet"):
            label = context_label(towns, p["lat"], p["lon"], p["tags"]["name"], max_m=25000)
        else:
            label = None
        if label:
            entry["in"] = label
        if p["bbox"][0] != p["bbox"][2]:  # mapped as an area, not a point
            entry["bbox"] = [r5(x) for x in p["bbox"]]
        out.append(entry)

    # Streets are drawn as many short ways; merge same-named pieces that touch or nearly touch.
    by_name = {}
    for s in streets:
        by_name.setdefault(s["tags"]["name"], []).append(s["bbox"])
    margin_deg = STREET_MERGE_M / 111_320
    for name, boxes in by_name.items():
        clusters = []
        for bb in sorted(boxes):
            for c in clusters:
                if (bb[0] <= c[2] + margin_deg and bb[2] >= c[0] - margin_deg
                        and bb[1] <= c[3] + margin_deg * 1.8 and bb[3] >= c[1] - margin_deg * 1.8):
                    c[0], c[1], c[2], c[3] = min(c[0], bb[0]), min(c[1], bb[1]), max(c[2], bb[2]), max(c[3], bb[3])
                    break
            else:
                clusters.append(list(bb))
        for c in clusters:
            lat, lon = (c[0] + c[2]) / 2, (c[1] + c[3]) / 2
            # Just a point (~10 m precision): the app zooms in to street level. Outlines would triple the size.
            entry = {"name": name, "kind": "street", "lat": round(lat, 4), "lon": round(lon, 4)}
            label = context_label(context, lat, lon)
            if label:
                entry["in"] = label
            out.append(entry)
    out.sort(key=lambda e: (e["name"], e["kind"], e["lat"], e["lon"]))
    return out


TRANSIENT = (OSError, subprocess.CalledProcessError)  # network errors (URLError is an OSError), bad downloads


def build_region(region: dict, work: str, pbf: str = None):
    """Returns (cafés, search entries, time of the newest OSM edit), retrying Geofabrik for ~20 minutes."""
    attempts = 1 if pbf else len(GEOFABRIK_WAITS_S) + 1
    for i in range(attempts):
        try:
            features, osm_until = features_from_geofabrik(region["geofabrik"], work, pbf)
            cafes, places, streets = split_features(features)
            return cafes, build_search(places, streets), osm_until
        except StaleExtract:
            raise
        except TRANSIENT as e:
            if i == attempts - 1:
                raise RuntimeError(f"Geofabrik unavailable after {attempts} attempts (last error: {e})")
            log(f"::warning::Geofabrik attempt {i + 1}/{attempts} for {region['name']} failed ({e}); "
                f"retrying in {GEOFABRIK_WAITS_S[i] // 60} min")
            time.sleep(GEOFABRIK_WAITS_S[i])


def already_published(regions: list, published: dict) -> bool:
    """True if every region's current Geofabrik extract is the one already published."""
    for region in regions:
        try:
            current = with_retries(lambda: extract_timestamp(region["geofabrik"])).isoformat(timespec="seconds")
        except TRANSIENT as e:
            log(f"  couldn't check Geofabrik for {region['name']} ({e}); building anyway")
            return False
        have = (published.get(region["id"]) or {}).get("osmDataUntil")
        log(f"  {region['name']}: Geofabrik has edits up to {current}; published: {have}")
        if have != current:
            return False
    return True


def with_retries(fn, waits=(30, 60, 120)):
    for i in range(len(waits) + 1):
        try:
            return fn()
        except TRANSIENT:
            if i == len(waits):
                raise
            time.sleep(waits[i])


def set_output(name: str, value: str) -> None:
    """Passes a value to later workflow steps (no-op outside GitHub Actions)."""
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as f:
            f.write(f"{name}={value}\n")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site")
    ap.add_argument("--regions", default=os.path.join(os.path.dirname(__file__), "regions.json"))
    ap.add_argument("--skip-if-current", action="store_true",
                    help="stop early if Geofabrik's current extract is already published (scheduled runs)")
    ap.add_argument("--pbf", help="use this local .osm.pbf instead of downloading (testing, one region)")
    ap.add_argument("--fresh-search", action="store_true", help="rebuild the search files even if recent")
    args = ap.parse_args()

    with open(args.regions) as f:
        regions = json.load(f)["regions"]

    generated = datetime.now(timezone.utc).isoformat(timespec="seconds")
    region_dir = os.path.join(args.out, "v1", "regions")
    os.makedirs(region_dir, exist_ok=True)
    if shutil.which("osmium") is None:
        raise SystemExit("::error::osmium-tool is not installed. Nothing was published; the app keeps the previous data.")
    published = published_index()
    if args.skip_if_current and published and already_published(regions, published):
        log("Already up to date: this Geofabrik extract is published. Nothing to do.")
        set_output("skip", "true")
        return
    set_output("skip", "false")
    entries = []
    for region in regions:
        log(f"Building {region['name']}...")
        started = time.time()
        with tempfile.TemporaryDirectory() as work:
            try:
                raw, search, osm_until = build_region(region, work, args.pbf)
            except (RuntimeError, OSError, subprocess.CalledProcessError) as e:
                raise SystemExit(
                    f"::error::Could not get OpenStreetMap data for {region['name']} ({e}). Nothing was "
                    "published; the app keeps using the previous data until the next run succeeds."
                )
        places, seen = [], set()
        for c in raw:
            p = compact_cafe(c)
            if p is None or p["id"] in seen:
                continue
            seen.add(p["id"])
            places.append(p)
        if len(places) < 100:
            raise SystemExit(f"::error::Only {len(places)} places for {region['id']}; refusing to publish a broken snapshot.")
        places.sort(key=lambda p: p["id"])
        log(f"  {len(places)} places in {time.time() - started:.0f}s")

        payload = json.dumps(
            {"region": region["id"], "generated": generated, "attribution": ATTRIBUTION, "places": places},
            ensure_ascii=False, separators=(",", ":"),
        ).encode()
        path = os.path.join(region_dir, f"{region['id']}.json.gz")
        with open(path, "wb") as f:
            f.write(gzip.compress(payload, compresslevel=9, mtime=0))
        entry = {
            "id": region["id"],
            "name": region["name"],
            "url": f"regions/{region['id']}.json.gz",
            "bytes": os.path.getsize(path),
            "placeCount": len(places),
            "source": "geofabrik",
            "osmDataUntil": osm_until,  # the newest OpenStreetMap edit included
            "generated": generated,
        }
        log(f"  wrote {os.path.getsize(path) / 1e6:.2f} MB")

        # Search file: keep publishing last week's unless it's due, so phones download it ~weekly.
        search_name = f"{region['id']}.search.json.gz"
        prev = previous_search(published.get(region["id"]))
        if prev and not args.fresh_search and age_days(prev[1]) < SEARCH_MAX_AGE_DAYS:
            data, search_generated = prev
            log(f"  republished the search file from {search_generated}")
        else:
            search_generated = generated
            data = gzip.compress(json.dumps(
                {"region": region["id"], "generated": generated, "attribution": ATTRIBUTION, "search": search},
                ensure_ascii=False, separators=(",", ":"),
            ).encode(), compresslevel=9, mtime=0)
            log(f"  wrote a new search file: {len(search)} entries, {len(data) / 1e6:.2f} MB")
        with open(os.path.join(region_dir, search_name), "wb") as f:
            f.write(data)
        entry.update({"searchUrl": f"regions/{search_name}", "searchBytes": len(data),
                      "searchGenerated": search_generated})
        entries.append(entry)

    index = {"version": 1, "generated": generated, "amenities": AMENITIES, "regions": entries,
             "attribution": ATTRIBUTION}
    with open(os.path.join(args.out, "v1", "index.json"), "w") as f:
        json.dump(index, f, separators=(",", ":"))
    total = sum(e["placeCount"] for e in entries)
    with open(os.path.join(args.out, "index.html"), "w") as f:
        f.write(
            "<!doctype html><meta charset=utf-8><title>babylatte data</title>"
            f"<p>Offline café data for the babylatte app: {total} places, generated {generated}.</p>"
            "<p>Data © <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap contributors</a>, ODbL.</p>"
        )


if __name__ == "__main__":
    main()
