"""Location tests: baseline, boost, no centroid coords, section mapping."""
from pipeline.locate import classify_category, locate_item, lookup_postal, section_for_location


def _src(**kw):
    base = {"coverage": {"country": "US"}, "type": "rss"}
    base.update(kw)
    return base


def test_source_coverage_baseline_without_mentions():
    item = {"headline": "Council meets Tuesday", "excerpt": "Routine agenda."}
    src = {"coverage": {"country": "US", "admin1": "US-NY",
                        "admin2": ["Schenectady County"], "cities": ["Schenectady"],
                        "metro": "us-ny-capital-region"}, "type": "rss"}
    info = locate_item(item, src, places=[])
    assert info["locations"][0]["city"] == "Schenectady"
    assert info["section"] == "local"


def test_place_mention_boosts_over_baseline():
    item = {"headline": "Fire on Central Avenue in Albany prompts closures",
            "excerpt": "Crews in Albany responded."}
    src = _src(coverage={"country": "US"})
    info = locate_item(item, src)
    cities = [l.get("city") for l in info["locations"]]
    assert "Albany" in cities
    assert info["section"] == "local"
    assert info["category"] == "public-safety"


def test_never_invents_coordinates():
    item = {"headline": "Albany fire on Central Avenue", "excerpt": "Albany crews on scene."}
    info = locate_item(item, _src(coverage={"country": "US", "admin1": "US-NY"}))
    for loc in info["locations"]:
        assert "lat" not in loc and "lon" not in loc


def test_section_mapping_ladder():
    assert section_for_location({"country": "US", "city": "Albany"}) == "local"
    assert section_for_location({"country": "US", "admin2": "Albany County"}) == "regional"
    assert section_for_location({"country": "US", "metro": "us-ny-capital-region"}) == "regional"
    assert section_for_location({"country": "US", "admin1": "US-NY"}) == "state"
    assert section_for_location({"country": "US"}) == "national"
    assert section_for_location({"country": "GB"}) == "world"
    assert section_for_location({}) == "world"


def test_category_defaults_and_nws():
    assert classify_category("Library opens new wing Tuesday", "Routine agenda.", "rss") == "local"
    assert classify_category("Winter storm warning", "Snow expected.", "nws-alerts") == "weather"
    assert classify_category("M 5.1 - 10km away", "Quake strikes.", "json-api") == "science"


def test_postal_lookup_capital_region():
    hit = lookup_postal("12308")
    assert hit and hit["city"] == "Schenectady"
    assert lookup_postal("99999") is None
