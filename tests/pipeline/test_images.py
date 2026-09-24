"""Image license filter, Commons fixture parsing, relevance guard (offline)."""
import json
from pathlib import Path

from pipeline.images import (
    CommonsProvider,
    candidate_queries,
    enrich_stories,
    title_matches_query,
)
from pipeline.images.commons import (
    clean_title,
    format_attribution,
    is_allowed_license,
    parse_commons_response,
)

FIXTURE = Path("tests/fixtures/commons_sample.json")


def _payload():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def _story(**kwargs):
    base = {
        "apiVersion": 1,
        "id": "a1b2c3d4e5f60001",
        "headline": "Schenectady City Hall approves budget vote",
        "dek": "The council voted in Schenectady City Hall.",
        "body": "Officials met at Schenectady City Hall to approve the budget in Schenectady.",
        "category": "local",
        "publishedAt": "2026-09-23T10:00:00Z",
        "generatedAt": "2026-09-23T12:00:00Z",
        "aiGenerated": True,
        "aiModel": "test",
        "version": 1,
        "revisions": [{"version": 1, "updatedAt": "2026-09-23T12:00:00Z"}],
        "confidenceTier": "medium",
        "breaking": False,
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Schenectady"}],
        "sources": [{"publisher": "Gazette", "headline": "Hall vote",
                     "url": "https://example.com/hall-vote"}],
    }
    base.update(kwargs)
    return base


# --- license filter ---

def test_allowed_licenses():
    for name in ("CC0", "CC0 1.0", "Public domain", "Public Domain",
                 "CC BY 4.0", "CC-BY 3.0", "CC BY-SA 4.0", "CC-BY-SA 3.0"):
        assert is_allowed_license(name), name


def test_rejected_licenses():
    for name in ("CC BY-NC 4.0", "CC BY-ND 4.0", "CC BY-NC-SA 4.0",
                 "GFDL", "GFDL 1.2", "Fair use", "Copyrighted",
                 "", None, "CC SAMPLING 1.0"):
        assert not is_allowed_license(name), name


# --- fixture parsing ---

def test_fixture_parsing_keeps_only_allowed_licenses():
    parsed = parse_commons_response(_payload())
    titles = {p["title"] for p in parsed}
    assert "File:Schenectady City Hall at dusk.jpg" in titles
    assert "File:Eiffel Tower at night.jpg" in titles
    assert "File:Schenectady City Hall interior.jpg" not in titles  # CC BY-NC


def test_fixture_captures_attribution_fields():
    parsed = parse_commons_response(_payload())
    hall = next(p for p in parsed if "Schenectady City Hall at dusk" in p["title"])
    assert hall["url"].startswith("https://upload.wikimedia.org/")
    assert hall["creator"] == "John Doe"  # HTML stripped
    assert hall["license"] == "CC BY-SA 4.0"
    assert hall["licenseUrl"] == "https://creativecommons.org/licenses/by-sa/4.0/"
    assert hall["sourceUrl"].startswith("https://commons.wikimedia.org/wiki/File:")
    assert hall["attribution"].startswith("File photo:")
    assert "John Doe" in hall["attribution"] and "CC BY-SA 4.0" in hall["attribution"]


def test_malformed_payload_yields_no_candidates():
    assert parse_commons_response({}) == []
    assert parse_commons_response({"query": {}}) == []


def test_provider_transport_failure_returns_empty():
    def _boom(url):
        raise ConnectionError("down")

    assert CommonsProvider(fetch_fn=_boom).search("Albany") == []


def test_provider_never_hits_network_in_tests():
    calls: list[str] = []
    provider = CommonsProvider(fetch_fn=lambda url: (calls.append(url), _payload())[1])
    assert provider.search("Schenectady City Hall")
    assert calls and calls[0].startswith("https://commons.wikimedia.org/w/api.php")
    assert "User-Agent" not in calls[0]  # UA goes in headers, not the URL


# --- relevance guard ---

def test_title_match_exact_and_unrelated():
    assert title_matches_query("File:Schenectady City Hall at dusk.jpg", "Schenectady City Hall")
    assert title_matches_query("File:Albany skyline.jpg", "Albany")
    assert not title_matches_query("File:Eiffel Tower at night.jpg", "Schenectady City Hall")
    assert not title_matches_query("File:Schenectady City Hall at dusk.jpg", "Albany")
    assert not title_matches_query("File:Albany skyline.jpg", "")
    assert not title_matches_query("", "Albany")


def test_candidate_queries_come_from_story_only():
    queries = candidate_queries(_story())
    assert queries[0] == "Schenectady"  # primary city first
    assert any("Schenectady City Hall" in q for q in queries)
    # Nothing invented: every query appears in the story text or locations.
    haystack = "schenectady city hall approves budget vote officials met".lower()
    for query in queries:
        assert query.lower() in haystack or query in ("Schenectady County", "Capital Region", "Schenectady"), query


def test_pick_confident_match_and_no_match():
    provider = CommonsProvider(fetch_fn=lambda url: _payload())
    # Wrap: provider.search returns allowed candidates; pick filters by title.
    from pipeline.images import pick_commons_image

    found = pick_commons_image(_story(), provider)
    assert found and "Schenectady" in found["attribution"]

    albany_story = _story(id="a1b2c3d4e5f60002",
                          headline="Albany road closure on Central Avenue",
                          dek="Crews closed the street in Albany.",
                          body="Crews closed Central Avenue in Albany while officials worked the scene in Albany.",
                          locations=[{"country": "US", "admin1": "US-NY", "city": "Albany"}])
    assert pick_commons_image(albany_story, provider) is None  # no Albany-titled file


def test_never_hotlink_publisher_images():
    member = {"imageCandidate": "https://example.com/publisher-photo.jpg",
              "rightsMode": "RSS_EXCERPT_ALLOWED", "sourceId": "gazette",
              "publisher": "Gazette"}
    cluster = {"eventId": "a1b2c3d4e5f60001", "members": [member]}
    sources = {"gazette": {"id": "gazette", "name": "Gazette",
                           "rightsMode": "RSS_EXCERPT_ALLOWED",
                           "imageRules": {"allowReuse": False, "requireAttribution": True}}}
    provider = CommonsProvider(fetch_fn=lambda url: {"query": {"pages": {}}})
    stories = [_story()]
    stats = enrich_stories(stories, provider, clusters_by_id={"a1b2c3d4e5f60001": cluster},
                           sources_by_id=sources)
    assert "image" not in stories[0]  # RSS_EXCERPT photo never reused
    assert stats["none"] == 1


def test_supplied_pd_image_used_when_allowed():
    member = {"imageCandidate": "https://example.gov/photo.jpg",
              "rightsMode": "PUBLIC_DOMAIN", "sourceId": "city",
              "publisher": "City of Schenectady"}
    cluster = {"eventId": "a1b2c3d4e5f60001", "members": [member]}
    sources = {"city": {"id": "city", "name": "City of Schenectady",
                        "rightsMode": "PUBLIC_DOMAIN",
                        "imageRules": {"allowReuse": True, "requireAttribution": False}}}
    provider = CommonsProvider(fetch_fn=lambda url: (_ for _ in ()).throw(AssertionError("no network needed")))
    stories = [_story()]
    stats = enrich_stories(stories, provider, clusters_by_id={"a1b2c3d4e5f60001": cluster},
                           sources_by_id=sources)
    assert stories[0]["image"]["url"] == "https://example.gov/photo.jpg"
    assert stats["supplied"] == 1


def test_existing_image_left_untouched():
    story = _story(image={"url": "https://upload.wikimedia.org/kept.jpg",
                          "attribution": "File photo: kept"})
    provider = CommonsProvider(fetch_fn=lambda url: _payload())
    enrich_stories([story], provider)
    assert story["image"]["url"] == "https://upload.wikimedia.org/kept.jpg"


def test_clean_title_and_attribution_helpers():
    assert clean_title("File:Schenectady_City_Hall.jpg") == "Schenectady City Hall"
    assert format_attribution("File:X.jpg", "", "CC0").startswith("File photo:")
