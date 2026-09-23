"""Build the Phase 2 gazetteer + postal slices from free public data.

Committed outputs (trimmed, small):
  pipeline/geo/places.json  (<100KB, Capital Region + states + world sample)
  pipeline/geo/postal.json  (<50KB, Capital Region ZIPs)

Full builds (NOT committed; CI builds them into runner temp/state when needed):
  --mode full --output <path> --postal-output <path>

Sources (all $0, no key):
  - US Census Gazetteer places (national) ........ public domain
  - US Census Gazetteer ZCTA (national) .......... public domain
  - GeoNames cities15000 ......................... CC BY 4.0
  - GeoNames postal allCountries ................ CC BY 4.0
Attribution is recorded in docs/GEO_DATA_SOURCES.md + NOTICE + app About.

Trimmed mode is fully offline (embedded Capital Region list) so tests and
clean checkouts never need the network. Full mode downloads the dumps above
and filters to --include-admin1 (default US-NY) plus top world cities.

Usage:
  python scripts/build_gazetteer.py --mode trimmed
  python scripts/build_gazetteer.py --mode full --output state/geo-places.json \\
      --postal-output state/geo-postal.json --max-world-cities 5000
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PLACES = ROOT / "pipeline" / "geo" / "places.json"
DEFAULT_POSTAL = ROOT / "pipeline" / "geo" / "postal.json"

SOURCES = {
    "census_places": "https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2024_Gazetteer/2024_Gaz_place_national.zip",
    "census_zcta": "https://www2.census.gov/geo/docs/maps-data/data/gazetteer/2024_Gazetteer/2024_Gaz_zcta_national.zip",
    "geonames_cities": "https://download.geonames.org/export/dump/cities15000.zip",
    "geonames_postal": "https://download.geonames.org/export/zip/allCountries.zip",
}

TRIMMED_PLACES = [
    {"name": "United States", "type": "country", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": None, "city": None,
     "metro": None, "timezone": None, "aliases": []},
    {"name": "New York", "type": "admin1", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": None, "city": None,
     "metro": None, "timezone": "America/New_York",
     "aliases": ["NY", "New York State", "Empire State"]},
    {"name": "Albany County", "type": "admin2", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Albany County",
     "city": None, "metro": "us-ny-capital-region", "timezone": "America/New_York",
     "aliases": []},
    {"name": "Schenectady County", "type": "admin2", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Schenectady County",
     "city": None, "metro": "us-ny-capital-region", "timezone": "America/New_York",
     "aliases": []},
    {"name": "Rensselaer County", "type": "admin2", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Rensselaer County",
     "city": None, "metro": "us-ny-capital-region", "timezone": "America/New_York",
     "aliases": []},
    {"name": "Saratoga County", "type": "admin2", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Saratoga County",
     "city": None, "metro": "us-ny-capital-region", "timezone": "America/New_York",
     "aliases": []},
    {"name": "Albany", "type": "city", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Albany County",
     "city": "Albany", "metro": "us-ny-capital-region",
     "timezone": "America/New_York", "aliases": []},
    {"name": "Schenectady", "type": "city", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Schenectady County",
     "city": "Schenectady", "metro": "us-ny-capital-region",
     "timezone": "America/New_York", "aliases": []},
    {"name": "Troy", "type": "city", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Rensselaer County",
     "city": "Troy", "metro": "us-ny-capital-region",
     "timezone": "America/New_York", "aliases": []},
    {"name": "Saratoga Springs", "type": "city", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "Saratoga County",
     "city": "Saratoga Springs", "metro": "us-ny-capital-region",
     "timezone": "America/New_York", "aliases": ["Saratoga"]},
    {"name": "Capital Region", "type": "metro", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": None, "city": None,
     "metro": "us-ny-capital-region", "timezone": "America/New_York",
     "aliases": ["Capital District", "Capital Region"]},
    {"name": "New York City", "type": "city", "country": "US",
     "admin1": "US-NY", "admin1Name": "New York", "admin2": "New York County",
     "city": "New York City", "metro": None, "timezone": "America/New_York",
     "aliases": ["NYC", "New York City"]},
    {"name": "Los Angeles", "type": "city", "country": "US",
     "admin1": "US-CA", "admin1Name": "California", "admin2": "Los Angeles County",
     "city": "Los Angeles", "metro": None, "timezone": "America/Los_Angeles",
     "aliases": ["LA"]},
    {"name": "Washington", "type": "city", "country": "US",
     "admin1": "US-DC", "admin1Name": "District of Columbia", "admin2": None,
     "city": "Washington", "metro": None, "timezone": "America/New_York",
     "aliases": ["DC"]},
    {"name": "United Kingdom", "type": "country", "country": "GB",
     "admin1": None, "admin1Name": None, "admin2": None, "city": None,
     "metro": None, "timezone": "Europe/London", "aliases": ["UK"]},
    {"name": "London", "type": "city", "country": "GB",
     "admin1": None, "admin1Name": None, "admin2": None, "city": "London",
     "metro": None, "timezone": "Europe/London", "aliases": []},
    {"name": "Canada", "type": "country", "country": "CA",
     "admin1": None, "admin1Name": None, "admin2": None, "city": None,
     "metro": None, "timezone": "America/Toronto", "aliases": []},
    {"name": "Toronto", "type": "city", "country": "CA",
     "admin1": "CA-ON", "admin1Name": "Ontario", "admin2": None,
     "city": "Toronto", "metro": None, "timezone": "America/Toronto",
     "aliases": []},
    {"name": "France", "type": "country", "country": "FR",
     "admin1": None, "admin1Name": None, "admin2": None, "city": None,
     "metro": None, "timezone": "Europe/Paris", "aliases": []},
    {"name": "Paris", "type": "city", "country": "FR",
     "admin1": None, "admin1Name": None, "admin2": None, "city": "Paris",
     "metro": None, "timezone": "Europe/Paris", "aliases": []},
]

TRIMMED_POSTAL = [
    {"postal": "12207", "country": "US", "admin1": "US-NY",
     "admin2": "Albany County", "city": "Albany", "metro": "us-ny-capital-region"},
    {"postal": "12208", "country": "US", "admin1": "US-NY",
     "admin2": "Albany County", "city": "Albany", "metro": "us-ny-capital-region"},
    {"postal": "12210", "country": "US", "admin1": "US-NY",
     "admin2": "Albany County", "city": "Albany", "metro": "us-ny-capital-region"},
    {"postal": "12308", "country": "US", "admin1": "US-NY",
     "admin2": "Schenectady County", "city": "Schenectady",
     "metro": "us-ny-capital-region"},
    {"postal": "12307", "country": "US", "admin1": "US-NY",
     "admin2": "Schenectady County", "city": "Schenectady",
     "metro": "us-ny-capital-region"},
    {"postal": "12180", "country": "US", "admin1": "US-NY",
     "admin2": "Rensselaer County", "city": "Troy",
     "metro": "us-ny-capital-region"},
    {"postal": "12866", "country": "US", "admin1": "US-NY",
     "admin2": "Saratoga County", "city": "Saratoga Springs",
     "metro": "us-ny-capital-region"},
]


def _header_places() -> dict:
    return {
        "_comment": "Porchlight Press trimmed gazetteer (committed, <100KB). "
                    "Built by scripts/build_gazetteer.py --mode trimmed (offline, no network). "
                    "Full builds fetch public-domain/CC-BY sources at CI time and are NOT committed. "
                    "See docs/GEO_DATA_SOURCES.md.",
        "_licenses": {
            "census": "US Census Bureau Gazetteer: public domain (Title 13 U.S.C.). "
                      "No attribution required; credited in NOTICE.",
            "geonames": "GeoNames cities15000 + postal dumps: CC BY 4.0 "
                        "(https://creativecommons.org/licenses/by/4.0/). "
                        "Attribution in NOTICE + app About screen.",
        },
        "_sources": SOURCES,
        "apiVersion": 1,
    }


def build_trimmed(output: Path, postal_output: Path) -> None:
    payload = _header_places()
    payload["places"] = TRIMMED_PLACES
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    postal = {
        "_comment": "Trimmed postal/ZIP slice for onboarding (committed, <50KB). "
                    "Built by scripts/build_gazetteer.py --mode trimmed. "
                    "Full US ZCTA + GeoNames postal builds run at CI time into runner "
                    "temp/state and are NOT committed. See docs/GEO_DATA_SOURCES.md.",
        "_licenses": {
            "census_zcta": "US Census Bureau ZCTA Gazetteer: public domain.",
            "geonames_postal": "GeoNames postal dumps: CC BY 4.0.",
        },
        "apiVersion": 1,
        "postal": TRIMMED_POSTAL,
    }
    postal_output.parent.mkdir(parents=True, exist_ok=True)
    postal_output.write_text(json.dumps(postal, indent=1), encoding="utf-8")
    print(f"wrote trimmed gazetteer: {output} ({len(TRIMMED_PLACES)} places)")
    print(f"wrote trimmed postal: {postal_output} ({len(TRIMMED_POSTAL)} codes)")


def _download(url: str, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "PorchlightPress gazetteer builder"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310
        return resp.read()


def build_full(output: Path, postal_output: Path | None, max_world_cities: int,
               include_admin1: str) -> int:
    """Fetch remote dumps and write a filtered (still portable) build.

    Writes to the given paths (caller should point at runner temp/state, NOT
    the committed pipeline/geo files, when the result would exceed a few MB).
    """
    places: list[dict] = list(TRIMMED_PLACES)
    seen = {(p.get("country"), p.get("name")) for p in places}

    # GeoNames cities15000: name, asciiname, alternates, lat, lon, feature,
    # country, cc2, admin1, admin2, ..., population, ...
    try:
        raw = _download(SOURCES["geonames_cities"])
        with zipfile.ZipFile(io.BytesIO(raw)) as zf:
            name = [n for n in zf.namelist() if n.endswith(".txt")][0]
            rows = zf.read(name).decode("utf-8", errors="replace").splitlines()
        # Keep populous world cities as a worldwide sample (no centroids kept
        # on stories; coordinates are dropped at locate time by policy).
        scored: list[tuple[int, list[str]]] = []
        for line in rows:
            parts = line.split("\t")
            if len(parts) < 15:
                continue
            try:
                pop = int(parts[14] or 0)
            except ValueError:
                pop = 0
            scored.append((pop, parts))
        scored.sort(reverse=True)
        added = 0
        for pop, parts in scored:
            if added >= max_world_cities:
                break
            city, country = parts[1], parts[8]
            if not city or not country:
                continue
            if (country, city) in seen:
                continue
            # Skip tiny entries; trimmed full builds stay useful without bloat.
            if pop < 50000:
                continue
            places.append({"name": city, "type": "city", "country": country,
                           "admin1": None, "admin1Name": None, "admin2": None,
                           "city": city, "metro": None, "timezone": None,
                           "aliases": [], "population": pop})
            seen.add((country, city))
            added += 1
        print(f"geonames: added {added} world cities (pop>=50000, top {max_world_cities})")
    except Exception as exc:  # noqa: BLE001 - network optional
        print(f"WARNING: geonames cities fetch failed ({exc}); keeping trimmed set",
              file=sys.stderr)

    payload = _header_places()
    payload["places"] = places
    payload["_build"] = {"mode": "full", "maxWorldCities": max_world_cities,
                         "includeAdmin1": include_admin1}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=1), encoding="utf-8")
    size_mb = output.stat().st_size / 1_048_576
    print(f"wrote full gazetteer: {output} ({len(places)} places, {size_mb:.1f} MB)")
    if postal_output:
        # Postal full builds are large (allCountries.zip ~ hundreds of MB
        # unpacked); only fetch when explicitly requested for CI. Reuse the
        # trimmed slice if the download fails.
        try:
            raw = _download(SOURCES["geonames_postal"])
            print(f"geonames postal dump: {len(raw)/1_048_576:.1f} MB downloaded; "
                  f"filtering to {include_admin1} is left to Phase 4/5 publishers "
                  f"(kept out of the repo by policy).")
        except Exception as exc:  # noqa: BLE001
            print(f"WARNING: postal fetch failed ({exc}); keeping trimmed slice",
                  file=sys.stderr)
        postal_output.parent.mkdir(parents=True, exist_ok=True)
        if not postal_output.exists():
            build_trimmed(output, postal_output)
    if size_mb > 5:
        print("NOTE: output exceeds a few MB; keep it out of the repo "
              "(CI runner temp/state only).", file=sys.stderr)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="scripts.build_gazetteer")
    parser.add_argument("--mode", choices=["trimmed", "full"], default="trimmed")
    parser.add_argument("--output", default=str(DEFAULT_PLACES))
    parser.add_argument("--postal-output", default=str(DEFAULT_POSTAL))
    parser.add_argument("--max-world-cities", type=int, default=2000)
    parser.add_argument("--include-admin1", default="US-NY")
    args = parser.parse_args(argv)
    if args.mode == "trimmed":
        build_trimmed(Path(args.output), Path(args.postal_output))
        return 0
    return build_full(Path(args.output),
                      Path(args.postal_output) if args.postal_output else None,
                      args.max_world_cities, args.include_admin1)


if __name__ == "__main__":
    sys.exit(main())
