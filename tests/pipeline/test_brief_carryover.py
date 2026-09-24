"""AI brief carry-over across runs (offline, mocked LLM).

Accepted briefs persist in state/clusters.json keyed by cluster id +
source-content fingerprint; unchanged clusters reuse them with no model call.
"""
import json


def _member(i, sid, headline=None, excerpt=None):
    return {
        "id": f"m{i}",
        "sourceId": sid,
        "publisher": f"Publisher {sid}",
        "headline": headline or "Central Avenue fire in Albany",
        "excerpt": excerpt or (
            "Firefighters responded to a blaze on Central Avenue in Albany. "
            "Crews closed the street while they worked the scene with officials on site."
        ),
        "url": f"https://example.com/{sid}/{i}",
        "publishedAt": "2026-09-23T09:05:00Z",
        "rightsMode": "RSS_EXCERPT_ALLOWED",
    }


def _cluster(eid, score=0.5, tier="high", status="new", members=None):
    members = members if members is not None else [
        _member(1, f"{eid}-a"), _member(2, f"{eid}-b"),
    ]
    return {
        "eventId": eid,
        "members": members,
        "memberIds": [m["id"] for m in members],
        "aliases": [],
        "firstSeen": "2026-09-23T09:05:00Z",
        "lastSeen": "2026-09-23T09:40:00Z",
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "section": "local",
        "category": "local",
        "headline": members[0]["headline"],
        "score": score,
        "scoreComponents": {},
        "breaking": False,
        "confidence": tier.upper(),
        "confidenceTier": tier,
        "independentSources": 2,
        "sources": [
            {"sourceId": m["sourceId"], "publisher": m["publisher"],
             "headline": m["headline"], "url": m["url"],
             "publishedAt": m["publishedAt"], "rightsMode": m["rightsMode"]}
            for m in members
        ],
        "status": status,
        "version": 1,
    }


def _good_brief_for(cluster):
    body = (
        "Firefighters responded to a blaze on Central Avenue in Albany, according to the sources. "
        "Crews closed the street. They worked the scene with officials on site. "
        "The Central Avenue blaze in Albany closed the street as crews worked the scene. "
        "Firefighters responded in Albany as crews closed Central Avenue. "
        "The street was closed on Central Avenue in Albany with firefighters on scene. "
        "Crews worked the scene on Central Avenue in Albany. "
        "Firefighters and crews responded to the Central Avenue blaze in Albany."
    )
    return {
        "headline": "Albany Central Avenue fire update",
        "dek": "Crews closed the street.",
        "body": body,
        "category": "local",
        "locations": [{"country": "US", "admin1": "US-NY", "city": "Albany"}],
        "people": [],
        "organizations": [],
        "sourceIds": [m["id"] for m in cluster["members"]],
        "aiModel": "test",
        "confidence": 0.75,
    }


def _write_clusters(path, clusters):
    path.write_text(json.dumps({"generatedAt": "2026-09-23T12:00:00Z",
                                "clusters": clusters}), encoding="utf-8")


def _attach_stored_brief(cluster, brief=None, model="Qwen3-4B-Q4_K_M"):
    from pipeline.state import cluster_content_fingerprint

    brief = brief if brief is not None else _good_brief_for(cluster)
    cluster["lastBrief"] = dict(brief)
    cluster["lastBriefFingerprint"] = cluster_content_fingerprint(cluster)
    cluster["lastBriefModel"] = model
    cluster["lastBriefHash"] = "legacy-hash-kept"
    cluster["lastGeneratedAt"] = "2026-09-23T11:00:00Z"
    return brief


def test_unchanged_cluster_reuses_brief_without_model_call(tmp_path, monkeypatch, capsys):
    from pipeline import newsroom as nr

    c = _cluster("eid-reuse-0001", status="unchanged")
    _attach_stored_brief(c)
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [c])

    calls: list[str] = []

    def _fake_generate(self, cluster):
        calls.append(str(cluster.get("eventId")))
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--max-articles", "10"])
    assert rc == 0
    assert calls == []  # no model call for the unchanged, fingerprint-matched cluster
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1 and stories[0]["aiGenerated"] is True
    assert stories[0]["headline"] == "Albany Central Avenue fire update"
    out = capsys.readouterr().out
    assert "reused=1" in out and "ai=1" in out


def test_changed_cluster_regenerates(tmp_path, monkeypatch):
    from pipeline import newsroom as nr
    from pipeline.state import cluster_content_fingerprint

    c = _cluster("eid-change-0001", status="unchanged")
    _attach_stored_brief(c)
    # Simulate an updated source: same members, edited excerpt -> fingerprint drift.
    c["members"][0]["excerpt"] = (
        "Firefighters responded to a SECOND alarm blaze on Central Avenue in Albany. "
        "Crews closed the street while they worked the scene with officials on site. "
        "A second engine joined the response on Central Avenue."
    )
    assert c["lastBriefFingerprint"] != cluster_content_fingerprint(c)
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [c])

    calls: list[str] = []

    def _fake_generate(self, cluster):
        calls.append(str(cluster.get("eventId")))
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(in_path),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--max-articles", "10"])
    assert rc == 0
    assert calls == ["eid-change-0001"]  # fingerprint mismatch -> model called
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert stories[0]["aiGenerated"] is True
    # State now stores the fresh fingerprint matching the changed content.
    state = json.loads(in_path.read_text(encoding="utf-8"))
    rec = state["clusters"][0] if isinstance(state.get("clusters"), list) else state["clusters"]["eid-change-0001"]
    assert rec["lastBriefFingerprint"] == cluster_content_fingerprint(c)
    assert rec["lastBrief"]["headline"] == "Albany Central Avenue fire update"


def test_expired_cluster_drops_stored_brief():
    from datetime import datetime, timezone

    from pipeline.state import update_state

    now = datetime(2026, 9, 23, 12, 0, tzinfo=timezone.utc)
    old_members = [_member(1, "a")]
    prev = {"old": {
        "eventId": "old", "members": old_members, "memberIds": ["m1"],
        "aliases": [], "version": 3, "firstSeen": "2026-09-10T09:00:00Z",
        "lastSeen": "2026-09-10T10:00:00Z",
        "lastBriefHash": "abc", "lastGeneratedAt": "2026-09-10T11:00:00Z",
        "lastBrief": {"headline": "H", "body": "B words here"},
        "lastBriefFingerprint": "fp", "lastBriefModel": "M",
    }}
    out = update_state(prev, [], {}, now=now, prune_days=7)
    assert "old" not in out  # pruned cluster takes its stored brief with it


def test_reused_brief_failing_revalidation_still_downgraded(tmp_path, monkeypatch):
    from pipeline import newsroom as nr
    from pipeline.publish import revalidate_ai_stories

    # Stored "died" brief: passes newsroom reuse untouched, then must be
    # downgraded by publish-time publication_outcome_reasons.
    members = [{
        "id": "h1",
        "sourceId": "wten-news10",
        "publisher": "WTEN News10 ABC",
        "headline": "NY Army National Guard crew chief injured in helicopter crash takes 'final flight'",
        "excerpt": "The crew chief was injured when the helicopter went down during a training flight, according to Guard officials. He was taken to the hospital for treatment.",
        "url": "https://example.com/heli",
        "publishedAt": "2026-09-23T19:44:55Z",
        "rightsMode": "RSS_EXCERPT_ALLOWED",
    }]
    cluster = {
        "eventId": "f50f7807554b049d",
        "members": members,
        "memberIds": ["h1"],
        "aliases": [],
        "firstSeen": "2026-09-23T19:44:55Z",
        "lastSeen": "2026-09-23T20:00:00Z",
        "locations": [{"country": "US", "admin1": "US-NY", "metro": "us-ny-capital-region"}],
        "section": "regional",
        "category": "public-safety",
        "headline": members[0]["headline"],
        "score": 0.6,
        "scoreComponents": {},
        "breaking": False,
        "confidence": "HIGH",
        "confidenceTier": "high",
        "independentSources": 1,
        "sources": [],
        "status": "unchanged",
        "version": 2,
    }
    died_brief = {
        "headline": "Military veteran dies in helicopter crash",
        "dek": "WTEN News10 reports a military veteran died in a helicopter crash.",
        "body": ("According to Guard officials, a helicopter went down during a training "
                 "flight and the crew chief was injured. He was taken to the hospital for "
                 "treatment, WTEN News10 ABC reported. The crash occurred in the Capital "
                 "Region, according to the source. A military veteran died in the helicopter "
                 "crash on the training flight with the Guard crew chief."),
        "category": "public-safety",
        "locations": [{"country": "US", "admin1": "US-NY", "metro": "us-ny-capital-region"}],
        "people": [],
        "organizations": [],
        "sourceIds": ["h1"],
        "aiModel": "test",
        "confidence": 0.7,
    }
    _attach_stored_brief(cluster, brief=died_brief)
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [cluster])

    def _fail_on_call(self, cl):
        raise AssertionError("reused brief must not call the model")

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fail_on_call)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources")])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1 and stories[0]["aiGenerated"] is True  # reused pre-revalidation

    clusters_by_id = {"f50f7807554b049d": cluster}
    out, n_downgraded, _log = revalidate_ai_stories(stories, clusters_by_id)
    assert n_downgraded == 1
    assert out[0]["aiGenerated"] is False


def test_fingerprint_ignores_member_order():
    from pipeline.state import cluster_content_fingerprint

    members = [_member(1, "a"), _member(2, "b"), _member(3, "c")]
    c1 = _cluster("eid-order-0001", members=list(members))
    c2 = _cluster("eid-order-0001", members=[members[2], members[0], members[1]])
    assert cluster_content_fingerprint(c1) == cluster_content_fingerprint(c2)


def test_reordered_members_still_reuse_without_model_call(tmp_path, monkeypatch):
    from pipeline import newsroom as nr

    c = _cluster("eid-reorder-0001", status="unchanged")
    _attach_stored_brief(c)
    # Same members, shuffled order: fingerprint must still match.
    c["members"] = [c["members"][1], c["members"][0]]
    c["memberIds"] = [m["id"] for m in c["members"]]
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [c])

    def _fail_on_call(self, cl):
        raise AssertionError("reordered members must still reuse without a model call")

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fail_on_call)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources")])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1 and stories[0]["aiGenerated"] is True
