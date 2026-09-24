"""Static publisher tests (offline, fixtures only)."""
import json
from pathlib import Path


def _story(sid, headline="Schenectady council approves downtown project in Schenectady",
           city="Schenectady", admin1="US-NY", breaking=False, ai=True):
    story = {
        "apiVersion": 1,
        "id": sid,
        "headline": headline,
        "dek": "The council voted in Schenectady.",
        "body": ("The council voted on Monday to approve the downtown project in Schenectady. "
                 "Officials said work begins in spring in Schenectady with crews on site."),
        "category": "local",
        "publishedAt": "2026-09-23T09:00:00Z",
        "updatedAt": "2026-09-23T10:00:00Z",
        "generatedAt": "2026-09-23T12:00:00Z",
        "aiGenerated": ai,
        "version": 1,
        "revisions": [{"version": 1, "updatedAt": "2026-09-23T12:00:00Z"}],
        "confidenceTier": "medium",
        "breaking": breaking,
        "locations": [{"country": "US", "admin1": admin1, "city": city}],
        "sources": [{"publisher": "Gazette", "headline": headline,
                     "url": f"https://example.com/{sid}",
                     "publishedAt": "2026-09-23T09:00:00Z",
                     "rightsMode": "RSS_EXCERPT_ALLOWED"}],
    }
    if ai:
        story["aiModel"] = "test-model"
    else:
        story["excerpt"] = "Council approved the downtown project."
    return story


def _write_inputs(tmp_path, stories, clusters=None):
    clusters = clusters if clusters is not None else [
        {"eventId": s["id"], "score": 0.5, "section": "local", "members": []}
        for s in stories
    ]
    (tmp_path / "stories.json").write_text(
        json.dumps({"generatedAt": "2026-09-23T12:00:00Z", "stories": stories}), encoding="utf-8")
    (tmp_path / "clusters.json").write_text(
        json.dumps({"generatedAt": "2026-09-23T12:00:00Z", "clusters": clusters}), encoding="utf-8")
    return tmp_path / "stories.json", tmp_path / "clusters.json"


def _run_publish(tmp_path, stories_path, clusters_path, generated_at, out_name="public"):
    from pipeline import publish as pub

    out = tmp_path / out_name
    rc = pub.main(["--stories", str(stories_path), "--clusters", str(clusters_path),
                   "--out", str(out), "--sources-dir", "sources",
                   "--generated-at", generated_at])
    assert rc == 0
    return out


def _validate_against(schema_name, doc):
    import jsonschema

    schema = json.loads(Path(f"schemas/{schema_name}").read_text(encoding="utf-8"))
    validator = jsonschema.validators.validator_for(schema)(schema)
    assert not list(validator.iter_errors(doc)), schema_name


def test_outputs_validate_against_schemas(tmp_path):
    stories = [_story("a1b2c3d4e5f60001"), _story("a1b2c3d4e5f60002", breaking=True)]
    sp, cp = _write_inputs(tmp_path, stories)
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z")
    for edition_file in sorted((out / "feeds").rglob("*.json")):
        _validate_against("edition.schema.json", json.loads(edition_file.read_text(encoding="utf-8")))
    _validate_against("index.schema.json", json.loads((out / "index.json").read_text(encoding="utf-8")))
    for story_file in (out / "stories").glob("*.json"):
        _validate_against("story.schema.json", json.loads(story_file.read_text(encoding="utf-8")))


def test_expected_feed_paths_and_viewer(tmp_path):
    stories = [_story("a1b2c3d4e5f60001")]
    sp, cp = _write_inputs(tmp_path, stories)
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z")
    assert (out / "feeds/us/ny/schenectady/latest.json").exists()
    assert (out / "feeds/us/ny/state/latest.json").exists()
    assert (out / "feeds/us/national/latest.json").exists()
    assert (out / "stories/a1b2c3d4e5f60001.json").exists()
    assert (out / "s/a1b2c3d4e5f60001.html").exists()
    assert (out / "viewer.html").exists() and (out / "404.html").exists()
    index = json.loads((out / "index.json").read_text(encoding="utf-8"))
    assert any(e["path"] == "feeds/us/ny/schenectady/latest.json" for e in index["editions"])


def test_section_caps_respected(tmp_path):
    stories = [_story(f"cap-local-{i:04d}", headline=f"Schenectady local story number {i} in Schenectady")
               for i in range(25)]
    clusters = [{"eventId": s["id"], "score": 1.0 - i * 0.001, "section": "local", "members": []}
                for i, s in enumerate(stories)]
    sp, cp = _write_inputs(tmp_path, stories, clusters)
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z")
    edition = json.loads((out / "feeds/us/ny/schenectady/latest.json").read_text(encoding="utf-8"))
    assert len(edition["stories"]) <= 20  # maxLocalArticles
    assert len(edition["stories"]) <= 30  # maxStoriesPerEdition


def test_edition_window_timezone_logic(tmp_path):
    stories = [_story("a1b2c3d4e5f60001")]
    sp, cp = _write_inputs(tmp_path, stories)
    # 10:17 UTC = 06:17 EDT -> morning.
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z", "pub-morning")
    assert (out / "feeds/us/ny/schenectady/morning.json").exists()
    assert not (out / "feeds/us/ny/schenectady/afternoon.json").exists()
    # 14:17 UTC = 10:17 EDT -> afternoon.
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T14:17:00Z", "pub-afternoon")
    assert (out / "feeds/us/ny/schenectady/afternoon.json").exists()
    # 22:17 UTC = 18:17 EDT -> evening.
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T22:17:00Z", "pub-evening")
    assert (out / "feeds/us/ny/schenectady/evening.json").exists()
    # 03:17 UTC = 23:17 EDT (previous day) -> outside all windows: latest only.
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T03:17:00Z", "pub-night")
    assert (out / "feeds/us/ny/schenectady/latest.json").exists()
    assert not (out / "feeds/us/ny/schenectady/morning.json").exists()
    assert not (out / "feeds/us/ny/schenectady/afternoon.json").exists()
    assert not (out / "feeds/us/ny/schenectady/evening.json").exists()


def test_breaking_subset_and_share_pages(tmp_path):
    plain = _story("a1b2c3d4e5f60001", ai=False)
    hot = _story("a1b2c3d4e5f60002", breaking=True,
                 headline="Breaking fire on Central Avenue in Albany NewYork")
    hot["locations"] = [{"country": "US", "admin1": "US-NY", "city": "Albany"}]
    hot["image"] = {"url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/x.jpg",
                    "attribution": "File photo: X — John Doe / Wikimedia Commons (CC BY-SA 4.0)",
                    "license": "CC BY-SA 4.0"}
    sp, cp = _write_inputs(tmp_path, [plain, hot])
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z")
    breaking = json.loads((out / "feeds/us/ny/albany/breaking.json").read_text(encoding="utf-8"))
    assert {s["id"] for s in breaking["stories"]} == {"a1b2c3d4e5f60002"}
    card_html = (out / "s/a1b2c3d4e5f60001.html").read_text(encoding="utf-8")
    assert "Source card" in card_html and "Get the Porchlight Press app" in card_html
    assert "play.google.com" in card_html and "og:title" in card_html
    assert "admob" not in card_html.lower() and "adsbygoogle" not in card_html.lower()
    ai_html = (out / "s/a1b2c3d4e5f60002.html").read_text(encoding="utf-8")
    assert "AI-written brief" in ai_html and "File photo:" in ai_html
    assert 'property="og:image"' in ai_html  # licensed image only
    assert 'property="og:image"' not in card_html  # no image -> no OG image
    assert "example.com/a1b2c3d4e5f60002" in ai_html  # source link


def test_locations_slices_written(tmp_path):
    stories = [_story("a1b2c3d4e5f60001")]
    sp, cp = _write_inputs(tmp_path, stories)
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z")
    places = json.loads((out / "locations/us.json").read_text(encoding="utf-8"))
    assert places["country"] == "US" and isinstance(places["places"], list) and places["places"]
    postal = json.loads((out / "locations/us-postal.json").read_text(encoding="utf-8"))
    assert postal["country"] == "US" and isinstance(postal["postal"], list) and postal["postal"]


def test_invalid_story_aborts_deploy(tmp_path):
    bad = _story("bad-story-01")
    bad["headline"] = "x"  # too short for story.schema (min 8)
    sp, cp = _write_inputs(tmp_path, [bad])
    from pipeline import publish as pub

    out = tmp_path / "public-bad"
    rc = pub.main(["--stories", str(sp), "--clusters", str(cp),
                   "--out", str(out), "--sources-dir", "sources",
                   "--generated-at", "2026-09-23T10:17:00Z"])
    assert rc == 1
    assert not (out / "index.json").exists()


def _heli_cluster_for_story(sid):
    return {
        "eventId": sid,
        "score": 0.6,
        "section": "regional",
        "locations": [{"country": "US", "admin1": "US-NY", "metro": "us-ny-capital-region"}],
        "members": [{
            "id": "h1",
            "sourceId": "wten-news10",
            "publisher": "WTEN News10 ABC",
            "headline": "NY Army National Guard crew chief injured in helicopter crash takes 'final flight'",
            "excerpt": "The crew chief was injured when the helicopter went down during a training flight, according to Guard officials. He was taken to the hospital for treatment.",
            "url": "https://example.com/heli",
            "publishedAt": "2026-09-23T19:44:55Z",
            "rightsMode": "RSS_EXCERPT_ALLOWED",
        }],
    }


def test_stale_ai_brief_failing_validator_downgraded_to_card(tmp_path):
    # Production story f50f7807554b049d: a "died" brief generated under the
    # old validator must not be republished -- publish revalidates cached AI
    # briefs against the current validator and falls back to the source card.
    # An honest brief on the same sources still ships as AI.
    bad = _story("f50f7807554b049d")
    bad.update({
        "headline": "Military veteran dies in helicopter crash",
        "dek": "WTEN News10 reports a military veteran died in a helicopter crash.",
        "body": ("According to Guard officials, a helicopter went down during a training "
                 "flight and the crew chief was injured. He was taken to the hospital for "
                 "treatment, WTEN News10 ABC reported. The crash occurred in the Capital "
                 "Region, according to the source. A military veteran died in the helicopter "
                 "crash on the training flight with the Guard crew chief."),
        "category": "public-safety",
        "locations": [{"country": "US", "admin1": "US-NY", "metro": "us-ny-capital-region"}],
    })
    good = _story("aa50f7807554b049d")
    good.update({
        "headline": "Guard crew chief injured in helicopter crash",
        "dek": "WTEN News10 reports a Guard crew chief was injured in a helicopter crash.",
        "body": ("According to Guard officials, a helicopter went down during a training "
                 "flight and the crew chief was injured. He was taken to the hospital for "
                 "treatment, WTEN News10 ABC reported. The Guard crew chief was injured in "
                 "the helicopter crash on the training flight."),
        "category": "public-safety",
        "locations": [{"country": "US", "admin1": "US-NY", "metro": "us-ny-capital-region"}],
    })
    clusters = [_heli_cluster_for_story("f50f7807554b049d"),
                _heli_cluster_for_story("aa50f7807554b049d")]
    sp, cp = _write_inputs(tmp_path, [bad, good], clusters)
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z")
    stories = {s["id"]: s for s in
               json.loads((out / "feeds/us/ny/regions/us-ny-capital-region/latest.json")
                          .read_text(encoding="utf-8"))["stories"]}
    assert stories["f50f7807554b049d"]["aiGenerated"] is False
    assert "injured in helicopter crash" in stories["f50f7807554b049d"]["headline"]
    assert stories["aa50f7807554b049d"]["aiGenerated"] is True


def test_share_carry_forward_and_prune(tmp_path):
    from pipeline import publish as pub

    stories = [_story("a1b2c3d4e5f60001")]
    sp, cp = _write_inputs(tmp_path, stories)
    prev = tmp_path / "prev-share"
    prev.mkdir()
    fresh = ("<html><head><meta name=\"porchlight-generated\" "
             "content=\"2026-09-23T10:00:00Z\" /></head><body>fresh</body></html>")
    stale = ("<html><head><meta name=\"porchlight-generated\" "
             "content=\"2026-01-01T00:00:00Z\" /></head><body>stale</body></html>")
    (prev / "carried-abc12345.html").write_text(fresh, encoding="utf-8")
    (prev / "stale-abc12345.html").write_text(stale, encoding="utf-8")
    out = tmp_path / "public-carry"
    rc = pub.main(["--stories", str(sp), "--clusters", str(cp), "--out", str(out),
                   "--sources-dir", "sources", "--prev-share-dir", str(prev),
                   "--generated-at", "2026-09-23T10:17:00Z"])
    assert rc == 0
    assert (out / "s/carried-abc12345.html").exists()  # <30d kept
    assert not (out / "s/stale-abc12345.html").exists()  # >30d pruned
    assert (out / "s/a1b2c3d4e5f60001.html").exists()
