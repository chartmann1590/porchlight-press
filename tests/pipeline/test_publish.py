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


def _load_fixture_cluster(fname):
    from pathlib import Path as _Path

    return json.loads((_Path("tests/fixtures/ai_regression") / fname).read_text(encoding="utf-8"))


def _brief_for_fixture(headline, dek, body, cluster):
    member_id = cluster["members"][0]["id"]
    return {
        "headline": headline,
        "dek": dek,
        "body": body,
        "category": str(cluster.get("category") or "local"),
        "locations": [dict(l) for l in (cluster.get("locations", []) or [])],
        "people": [],
        "organizations": [],
        "sourceIds": [member_id],
        "aiModel": "test",
        "confidence": 0.7,
    }


def test_revalidate_keeps_accepted_fixture_briefs_downgrades_died_story():
    """Review proof for revalidate_ai_stories on real fixture data.

    Honest briefs on the PR #9 ACCEPT fixtures (02fd Troy arrest, 23cc
    trade war, 6549 Poestenkill custody, 9242 perimenopause) pass the FULL
    validator and -- built into published stories exactly the way
    newsroom/publish carry them -- survive the publish-time re-check. The
    f50f7807554b049d "died" story is downgraded in the same run.
    """
    from datetime import datetime, timezone

    from pipeline.ai.providers import build_ai_story
    from pipeline.ai.validate import validate_brief
    from pipeline.publish import revalidate_ai_stories

    now = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)
    drafts = {
        "02fdc27bec1619c6.json": (
            "Troy woman arrested on neglect charge",
            "Police acted Tuesday in Troy.",
            "Police arrested a Troy woman on Tuesday, according to News10. "
            "The arrest occurred in Rensselaer County, New York as a result of an animal abuse investigation. "
            "Police in Troy arrested the woman on Tuesday. News10 reported the Troy arrest on Tuesday.",
        ),
        "23ccda726f7e68ab.json": (
            "Northeast dealership unfazed by US Canada trade war",
            "WAMC reports tariffs and bans in the ongoing trade war.",
            "According to WAMC, President Donald Trump enacted tariffs on Canadian goods. The trade "
            "war between Canada and the United States is ongoing. An outright ban on some Canadian "
            "motorcycles has one brand, Can-Am, at the center of the bans. A Northeast motorcycle "
            "dealership is not feeling the pressure of the trade war.",
        ),
        "65497cffb2af21d4.json": (
            "Suspect taken into custody in Poestenkill",
            "State Police took an armed suspect into custody in Poestenkill.",
            "An armed man was taken into custody in Poestenkill, according to WTEN. Police issued "
            "a Wednesday afternoon public safety alert while searching for the man. The 39-year-old "
            "suspect was taken into custody Wednesday. State Police searched Poestenkill on Wednesday afternoon.",
        ),
        "92423aa1adf8fbab.json": (
            "September marks perimenopause awareness month",
            "NEWS10 notes the September health topic.",
            "September brings Perimenopause Awareness Month, NEWS10 noted Wednesday. Perimenopause is "
            "the stage of life for women before menopause. NEWS10 marked September as awareness month "
            "for the change before menopause in Albany.",
        ),
    }
    stories = []
    clusters_by_id = {}
    for fname, (headline, dek, body) in drafts.items():
        cluster = _load_fixture_cluster(fname)
        assert 30 <= len(body.split()) <= 220, fname
        brief = _brief_for_fixture(headline, dek, body, cluster)
        result = validate_brief(brief, cluster)
        assert result.ok, (fname, result.reasons)
        clusters_by_id[cluster["eventId"]] = cluster
        stories.append(build_ai_story(brief, cluster, model_name="Qwen3-4B-Q4_K_M", now=now))
    # The production failure, in published-story shape.
    f50 = _load_fixture_cluster("f50f7807554b049d.json")
    died = _brief_for_fixture(
        "Military veteran dies in helicopter crash",
        "WTEN News10 reports a military veteran died in a helicopter crash.",
        "According to Guard officials, a helicopter went down during a training "
        "flight and the crew chief was injured. He was taken to the hospital for "
        "treatment, WTEN News10 ABC reported. The crash occurred in the Capital "
        "Region, according to the source. A military veteran died in the helicopter "
        "crash on the training flight with the Guard crew chief.",
        f50,
    )
    assert not validate_brief(died, f50).ok
    clusters_by_id[f50["eventId"]] = f50
    stories.append(build_ai_story(died, f50, model_name="Qwen3-4B-Q4_K_M", now=now))

    out, n_downgraded, _log = revalidate_ai_stories(stories, clusters_by_id)
    assert n_downgraded == 1
    by_id = {s["id"]: s for s in out}
    for fname in drafts:
        assert by_id[fname[:-5]]["aiGenerated"] is True, fname
    assert by_id["f50f7807554b049d"]["aiGenerated"] is False


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


def _backfill_story(sid, headline, locations, score_section="local"):
    s = _story(sid, headline=headline, city="Schenectady", admin1="US-NY")
    s["locations"] = locations
    return s


def test_city_backfills_metro_state_national_in_tier_order(tmp_path):
    city = _backfill_story(
        "city00001", "Schenectady council approves downtown project in Schenectady",
        [{"country": "US", "admin1": "US-NY", "city": "Schenectady",
          "metro": "us-ny-capital-region"}])
    metros = [
        _backfill_story(
            f"metro0000{i}", f"Capital Region transit expansion milestone number {i} in Albany",
            [{"country": "US", "admin1": "US-NY", "metro": "us-ny-capital-region"}])
        for i in range(1, 6)
    ]
    states = [
        _backfill_story(
            f"state0000{i}", f"New York state budget update number {i} for upstate counties",
            [{"country": "US", "admin1": "US-NY"}])
        for i in range(1, 6)
    ]
    nationals = [
        _backfill_story(
            f"natl00000{i}", f"National infrastructure bill update number {i} across the states",
            [{"country": "US"}])
        for i in range(1, 6)
    ]
    stories = [city] + metros + states + nationals
    clusters = []
    clusters.append({"eventId": city["id"], "score": 1.0, "section": "local", "members": []})
    for i, s in enumerate(metros):
        clusters.append({"eventId": s["id"], "score": 0.9 - i * 0.01,
                         "section": "regional", "members": []})
    for i, s in enumerate(states):
        clusters.append({"eventId": s["id"], "score": 0.8 - i * 0.01,
                         "section": "state", "members": []})
    for i, s in enumerate(nationals):
        clusters.append({"eventId": s["id"], "score": 0.7 - i * 0.01,
                         "section": "national", "members": []})
    sp, cp = _write_inputs(tmp_path, stories, clusters)
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z", "pub-backfill")
    edition = json.loads((out / "feeds/us/ny/schenectady/latest.json").read_text(encoding="utf-8"))
    ids = [s["id"] for s in edition["stories"]]
    assert len(ids) == 16
    assert len(set(ids)) == 16  # deduped
    assert ids[0] == "city00001"
    assert ids[1:6] == [f"metro0000{i}" for i in range(1, 6)]
    assert ids[6:11] == [f"state0000{i}" for i in range(1, 6)]
    assert ids[11:16] == [f"natl00000{i}" for i in range(1, 6)]
    assert len(ids) <= 30  # edition cap
    # Stories keep their own locations so the app can tell local from wider.
    by_id = {s["id"]: s for s in edition["stories"]}
    assert by_id["city00001"]["locations"][0].get("city") == "Schenectady"
    assert "city" not in by_id["metro00001"]["locations"][0]
    assert by_id["metro00001"]["locations"][0].get("metro") == "us-ny-capital-region"
    assert by_id["state00001"]["locations"][0].get("admin1") == "US-NY"
    # Identical across editions: metro story in city == metro story in metro feed.
    metro_ed = json.loads((out / "feeds/us/ny/regions/us-ny-capital-region/latest.json")
                          .read_text(encoding="utf-8"))
    metro_by_id = {s["id"]: s for s in metro_ed["stories"]}
    assert metro_by_id["metro00001"] == by_id["metro00001"]
    assert metro_by_id["city00001"] == by_id["city00001"]
    # Metro backfills from state then national; state backfills from national.
    metro_ids = [s["id"] for s in metro_ed["stories"]]
    assert metro_ids[:6] == ["city00001"] + [f"metro0000{i}" for i in range(1, 6)]
    assert metro_ids[6:11] == [f"state0000{i}" for i in range(1, 6)]
    assert metro_ids[11:16] == [f"natl00000{i}" for i in range(1, 6)]
    state_ed = json.loads((out / "feeds/us/ny/state/latest.json").read_text(encoding="utf-8"))
    state_ids = [s["id"] for s in state_ed["stories"]]
    assert len(state_ids) == 16 and len(set(state_ids)) == 16
    assert state_ids[-5:] == [f"natl00000{i}" for i in range(1, 6)]
    national_ed = json.loads((out / "feeds/us/national/latest.json").read_text(encoding="utf-8"))
    assert len(national_ed["stories"]) == 16
    # Sections stay schema-compatible: single top section lists every story.
    assert edition["sections"] and edition["sections"][0]["id"] == "top"
    assert edition["sections"][0]["storyIds"] == ids
    # Same rule for the evening snapshot.
    out_eve = _run_publish(tmp_path, sp, cp, "2026-09-23T22:17:00Z", "pub-backfill-eve")
    eve = json.loads((out_eve / "feeds/us/ny/schenectady/evening.json").read_text(encoding="utf-8"))
    assert [s["id"] for s in eve["stories"]] == ids


def test_city_with_full_quota_is_unchanged(tmp_path):
    city_stories = []
    clusters = []
    sections = (["local"] * 10 + ["regional"] * 10 + ["state"] * 5 + ["national"] * 5)
    for i in range(30):
        sid = f"fullcity{i:04d}"
        s = _backfill_story(
            sid, f"Schenectady neighborhood project update number {i} in Schenectady",
            [{"country": "US", "admin1": "US-NY", "city": "Schenectady",
              "metro": "us-ny-capital-region"}])
        city_stories.append(s)
        clusters.append({"eventId": sid, "score": 1.0 - i * 0.001,
                         "section": sections[i], "members": []})
    extra_state = _backfill_story(
        "extrastt01", "New York state extra budget story for upstate counties",
        [{"country": "US", "admin1": "US-NY"}])
    extra_nat = _backfill_story(
        "extranat01", "National extra infrastructure story across the states",
        [{"country": "US"}])
    stories = city_stories + [extra_state, extra_nat]
    clusters += [
        {"eventId": "extrastt01", "score": 0.01, "section": "state", "members": []},
        {"eventId": "extranat01", "score": 0.01, "section": "national", "members": []},
    ]
    sp, cp = _write_inputs(tmp_path, stories, clusters)
    out = _run_publish(tmp_path, sp, cp, "2026-09-23T10:17:00Z", "pub-full")
    edition = json.loads((out / "feeds/us/ny/schenectady/latest.json").read_text(encoding="utf-8"))
    ids = [s["id"] for s in edition["stories"]]
    assert len(ids) == 30
    assert set(ids) == {f"fullcity{i:04d}" for i in range(30)}
    assert "extrastt01" not in ids and "extranat01" not in ids
