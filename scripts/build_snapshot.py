#!/usr/bin/env python3
"""
Builds a static snapshot of family-relevant places from OpenStreetMap.

Output (default ./site):
  v1/index.json              list of regions with download URL, size and generation time
  v1/regions/<id>.json.gz    every place in the region (gzip), downloaded whole by the app
                             and stored on the phone for offline use

Only the standard library is used so it runs on a bare GitHub Actions runner.
"""
import argparse
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

AMENITIES = ["cafe", "restaurant", "ice_cream", "fast_food"]
ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
USER_AGENT = "Cortado-snapshot/1.0 (+https://github.com/raghavian/cortado)"

# Tags the app actually reads. Everything else is dropped to keep tiles small.
KEEP_PREFIXES = ("addr:",)
KEEP_TAGS = {
    "name", "amenity", "opening_hours", "website", "contact:website", "phone", "contact:phone",
    "changing_table", "diaper", "toilets:changing_table",
    "stroller", "wheelchair", "entrance:step_count", "step_count", "ramp",
    "highchair", "kids_area", "kids_area:indoor", "playground",
    "baby_feeding", "breastfeeding",
    "toilets", "toilets:wheelchair", "outdoor_seating",
}


def build_query(area_filter: str) -> str:
    parts = "".join(f'nwr["amenity"="{a}"]["name"](area.a);' for a in AMENITIES)
    return f"[out:json][timeout:600];{area_filter}->.a;({parts});out center tags qt;"


def overpass(query: str, attempts: int = 4) -> dict:
    last = None
    for i in range(attempts):
        endpoint = ENDPOINTS[i % len(ENDPOINTS)]
        try:
            data = urllib.parse.urlencode({"data": query}).encode()
            req = urllib.request.Request(endpoint, data=data, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=900) as resp:
                body = json.load(resp)
            if body.get("remark") and "error" in body["remark"].lower():
                raise RuntimeError(body["remark"])
            return body
        except (urllib.error.URLError, TimeoutError, RuntimeError, json.JSONDecodeError) as e:
            last = e
            wait = 30 * (i + 1)
            print(f"  attempt {i + 1} on {endpoint} failed: {e}; retrying in {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise SystemExit(f"Overpass failed after {attempts} attempts: {last}")


def compact(el: dict):
    tags = el.get("tags") or {}
    if "name" not in tags:
        return None
    pos = el.get("center") or el
    if "lat" not in pos or "lon" not in pos:
        return None
    kept = {k: v for k, v in tags.items() if k in KEEP_TAGS or k.startswith(KEEP_PREFIXES)}
    return {
        "id": f'{el["type"]}/{el["id"]}',
        "lat": round(pos["lat"], 6),
        "lon": round(pos["lon"], 6),
        "tags": kept,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site")
    ap.add_argument("--regions", default=os.path.join(os.path.dirname(__file__), "regions.json"))
    args = ap.parse_args()

    with open(args.regions) as f:
        regions = json.load(f)["regions"]

    generated = datetime.now(timezone.utc).isoformat(timespec="seconds")
    attribution = "© OpenStreetMap contributors, ODbL 1.0. https://www.openstreetmap.org/copyright"
    region_dir = os.path.join(args.out, "v1", "regions")
    os.makedirs(region_dir, exist_ok=True)
    entries = []
    for region in regions:
        print(f"Fetching {region['name']}...", file=sys.stderr)
        started = time.time()
        elements = overpass(build_query(region["area"]))["elements"]
        print(f"  {len(elements)} elements in {time.time() - started:.0f}s", file=sys.stderr)
        places, seen = [], set()
        for el in elements:
            p = compact(el)
            if p is None or p["id"] in seen:
                continue
            seen.add(p["id"])
            places.append(p)
        if len(places) < 100:
            raise SystemExit(f"Only {len(places)} places for {region['id']}; refusing to publish a broken snapshot.")
        places.sort(key=lambda p: p["id"])

        payload = json.dumps(
            {"region": region["id"], "generated": generated, "attribution": attribution, "places": places},
            ensure_ascii=False, separators=(",", ":"),
        ).encode()
        path = os.path.join(region_dir, f"{region['id']}.json.gz")
        with open(path, "wb") as f:
            f.write(gzip.compress(payload, compresslevel=9, mtime=0))
        entries.append({
            "id": region["id"],
            "name": region["name"],
            "url": f"regions/{region['id']}.json.gz",
            "bytes": os.path.getsize(path),
            "placeCount": len(places),
            "generated": generated,
        })
        print(f"  wrote {len(places)} places, {os.path.getsize(path) / 1e6:.2f} MB", file=sys.stderr)

    index = {"version": 1, "generated": generated, "amenities": AMENITIES, "regions": entries,
             "attribution": attribution}
    with open(os.path.join(args.out, "v1", "index.json"), "w") as f:
        json.dump(index, f, separators=(",", ":"))
    total = sum(e["placeCount"] for e in entries)
    with open(os.path.join(args.out, "index.html"), "w") as f:
        f.write(
            "<!doctype html><meta charset=utf-8><title>Cortado data</title>"
            f"<p>Offline café data for the Cortado app: {total} places, generated {generated}.</p>"
            "<p>Data © <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap contributors</a>, ODbL.</p>"
        )


if __name__ == "__main__":
    main()
