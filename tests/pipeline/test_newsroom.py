"""Newsroom orchestration tests (offline, mocked LLM -- never a real model)."""
import json


def _member(i, sid, rights="RSS_EXCERPT_ALLOWED", headline=None):
    # NOTE: headline/excerpt text is identical for every member on purpose.
    # Differing numbers (e.g. "update 1" vs "update 2") would look like a
    # cross-source number disagreement to the validator; real members differ
    # by publisher/URL, not by embedded counters. `i` only disambiguates ids.
    return {
        "id": f"m{i}",
        "sourceId": sid,
        "publisher": f"Publisher {sid}",
        "headline": headline or "Central Avenue fire in Albany",
        "excerpt": (
            "Firefighters responded to a blaze on Central Avenue in Albany. "
            "Crews closed the street while they worked the scene with officials on site."
        ),
        "url": f"https://example.com/{sid}/{i}",
        "publishedAt": "2026-09-23T09:05:00Z",
        "rightsMode": rights,
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


def _write_clusters(path, clusters):
    path.write_text(json.dumps({"generatedAt": "2026-09-23T12:00:00Z",
                                "clusters": clusters}), encoding="utf-8")


def _good_brief_for(cluster):
    # Grounded paraphrase: every content word comes from the member
    # headlines/excerpts (plus stopwords/allowlisted boilerplate). No numbers,
    # no quotes, no 12-word verbatim runs, 60+ words for the length gate.
    # Note: no extra fields -- the schema rejects additionalProperties.
    body = (
        "Firefighters responded to a blaze on Central Avenue in Albany, according to the sources. "
        "Crews closed the street. They worked the scene with officials on site. "
        "The Central Avenue blaze in Albany closed the street as crews worked the scene. "
        "Firefighters responded in Albany as crews closed Central Avenue. "
        "The street was closed on Central Avenue in Albany with firefighters on scene. "
        "Crews worked the scene on Central Avenue in Albany. "
        "Firefighters and crews responded to the Central Avenue blaze in Albany."
    )
    assert 60 <= len(body.split()) <= 220, (cluster["eventId"], len(body.split()))
    return {
        "headline": "Albany Central Avenue fire update",
        # Dek ends with "street" (not "Albany") so the dek->body word
        # boundary cannot form a 12-gram matching the source
        # headline->excerpt boundary ("... in Albany. Firefighters ...").
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


def test_killed_model_still_yields_complete_source_cards(tmp_path):
    from pipeline.newsroom import main

    clusters = [_cluster("eid-aaaa-0001", score=0.9), _cluster("eid-bbbb-0002", score=0.8)]
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, clusters)
    # Nothing listening on this port -> transport failure -> source cards, exit 0.
    rc = main(["--in", str(in_path), "--out", str(out_path),
               "--state", str(tmp_path / "state.json"),
               "--sources-dir", str(tmp_path / "no-sources"),
               "--llama-url", "http://127.0.0.1:9",
               "--max-articles", "10", "--wall-clock-minutes", "5"])
    assert rc == 0
    payload = json.loads(out_path.read_text(encoding="utf-8"))
    assert len(payload["stories"]) == 2
    assert all(s["aiGenerated"] is False for s in payload["stories"])
    assert all(s["sources"] and s["sources"][0]["url"].startswith("http")
               for s in payload["stories"])


def test_low_tier_publishes_card_even_with_working_llm(tmp_path, monkeypatch):
    from pipeline import newsroom as nr

    low = _cluster("eid-low-0001", tier="low", status="new")
    high = _cluster("eid-high-0002", tier="high", status="new")
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [low, high])

    def _fake_generate(self, cluster):
        return _good_brief_for(cluster), json.dumps(_good_brief_for(cluster)), None

    async_ok = _fake_generate
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", async_ok)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--max-articles", "10"])
    assert rc == 0
    stories = {s["id"]: s for s in json.loads(out_path.read_text(encoding="utf-8"))["stories"]}
    assert stories["eid-low-0001"]["aiGenerated"] is False  # publish gate
    assert stories["eid-high-0002"]["aiGenerated"] is True


def test_validation_failure_falls_back_to_card(tmp_path, monkeypatch):
    from pipeline import newsroom as nr

    cluster = _cluster("eid-bad-0001", tier="high")
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [cluster])

    bad_brief = _good_brief_for(cluster)
    bad_brief["people"] = ["Invented Person XYZ"]

    def _fake_generate(self, cluster):
        return dict(bad_brief), json.dumps(bad_brief), None

    def _fake_retry(self, messages):
        return dict(bad_brief), json.dumps(bad_brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate_with_messages", _fake_retry)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources")])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1 and stories[0]["aiGenerated"] is False


def test_budget_cap_and_retry_next_run(tmp_path, monkeypatch):
    from pipeline import newsroom as nr

    c1 = _cluster("eid-1111-0001", score=0.9)
    c2 = _cluster("eid-2222-0002", score=0.8)
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    state_path = tmp_path / "state.json"
    _write_clusters(in_path, [c1, c2])

    calls: list[str] = []

    def _fake_generate(self, cluster):
        calls.append(str(cluster.get("eventId")))
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)

    # Production usage updates the clusters file in place (--state == --in).
    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(in_path),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--max-articles", "1"])
    assert rc == 0
    # Only the top-ranked cluster attempted; the other ships as a card.
    assert calls == ["eid-1111-0001"]
    stories = {s["id"]: s for s in json.loads(out_path.read_text(encoding="utf-8"))["stories"]}
    assert stories["eid-1111-0001"]["aiGenerated"] is True
    assert stories["eid-2222-0002"]["aiGenerated"] is False
    # Successful brief hash recorded so the next run does not regenerate it.
    state = json.loads(in_path.read_text(encoding="utf-8"))
    if isinstance(state.get("clusters"), list):
        rec = {r["eventId"]: r for r in state["clusters"]}["eid-1111-0001"]
    else:
        rec = state["clusters"]["eid-1111-0001"]
    assert rec.get("lastBriefHash")


def test_wall_clock_expiry_stops_new_briefs_but_keeps_cards(tmp_path, monkeypatch):
    # Time-budgeted AI stage (news-refresh 45-min cap): when the wall clock
    # is exhausted, no new briefs are STARTED; every cluster still ships as
    # a deterministic source card, and queued clusters keep no brief hash so
    # they stay queued for the next run.
    from pipeline import newsroom as nr

    c1 = _cluster("eid-1111-0001", score=0.9)
    c2 = _cluster("eid-2222-0002", score=0.8)
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [c1, c2])

    calls: list[str] = []

    def _fake_generate(self, cluster):
        calls.append(str(cluster.get("eventId")))
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    # t0 reads 0.0; every budget check after that sees the clock exhausted.
    ticks = iter([0.0, 9999.0, 9999.0, 9999.0, 9999.0])
    monkeypatch.setattr(nr.time, "monotonic", lambda: next(ticks, 9999.0))

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--max-articles", "10", "--wall-clock-minutes", "25"])
    assert rc == 0
    assert calls == []  # budget exhausted before the first attempt
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 2
    assert all(s["aiGenerated"] is False for s in stories)
    assert all(s["sources"] and s["sources"][0]["url"].startswith("http")
               for s in stories)


def test_overflow_switches_to_fallback_model(tmp_path, monkeypatch):
    # Always primary for quality (overflow disabled); large queue stays on 4B.
    from pipeline import newsroom as nr

    clusters = [_cluster(f"eid-{i:04d}", score=1.0 - i * 0.001) for i in range(55)]
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, clusters)

    seen: dict[str, str] = {}

    def _fake_init(self, base_url="http://127.0.0.1:8080", model_name="x", **kw):
        seen["model"] = model_name

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "__init__", _fake_init)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--max-articles", "55"])
    assert rc == 0
    assert seen.get("model") == "Qwen3-4B-Q4_K_M"
    payload = json.loads(out_path.read_text(encoding="utf-8"))
    assert payload["model"] == "Qwen3-4B-Q4_K_M"


def test_stories_validate_against_schema(tmp_path, monkeypatch):
    import jsonschema

    from pipeline import newsroom as nr

    clusters = [_cluster("eid-schema-01", tier="high"), _cluster("eid-schema-02", tier="low")]
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, clusters)

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    assert nr.main(["--in", str(in_path), "--out", str(out_path),
                    "--state", str(tmp_path / "state.json"),
                    "--sources-dir", str(tmp_path / "no-sources")]) == 0
    from pathlib import Path as _Path
    schema = json.loads((_Path("schemas/story.schema.json")).read_text(encoding="utf-8"))
    validator = jsonschema.validators.validator_for(schema)(schema)
    for story in json.loads(out_path.read_text(encoding="utf-8"))["stories"]:
        assert not list(validator.iter_errors(story)), story["id"]


def test_factcheck_rejects_unsupported_claims(tmp_path, monkeypatch):
    from pipeline import newsroom as nr

    cluster = _cluster("eid-fc-reject-01", tier="high")
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [cluster])

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    def _fake_factcheck(self, messages):
        return ["The fire started at midnight (unsupported by sources)."], "raw", None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate_factcheck", _fake_factcheck)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--enable-factcheck"])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1
    # Factcheck flagged support -> brief rejected -> source card fallback.
    assert stories[0]["aiGenerated"] is False


def test_factcheck_keeps_brief_when_fully_supported(tmp_path, monkeypatch):
    from pipeline import newsroom as nr

    cluster = _cluster("eid-fc-keep-02", tier="high")
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [cluster])

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    def _fake_factcheck(self, messages):
        return [], "raw", None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate_factcheck", _fake_factcheck)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--enable-factcheck"])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1
    # Empty unsupported list -> brief kept as an AI story.
    assert stories[0]["aiGenerated"] is True


def test_factcheck_failure_is_fail_open(tmp_path, monkeypatch):
    from pipeline import newsroom as nr

    cluster = _cluster("eid-fc-open-03", tier="high")
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [cluster])

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    def _fake_factcheck(self, messages):
        # Provider error (transport / parse) -> _factcheck returns None ->
        # fail-open keeps the already-validated brief.
        return None, "", "factcheck transport: ConnectionError: down"

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate_factcheck", _fake_factcheck)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--enable-factcheck"])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1
    assert stories[0]["aiGenerated"] is True


def test_fifty_queued_stays_on_primary_model(tmp_path, monkeypatch):
    # Boundary pin: exactly 50 queued (== overflowThreshold) keeps 4B.
    # The model choice is made on the full queue size before capping.
    from pipeline import newsroom as nr

    clusters = [_cluster(f"eid-{i:04d}", score=1.0 - i * 0.001) for i in range(50)]
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, clusters)

    seen: dict[str, str] = {}

    def _fake_init(self, base_url="http://127.0.0.1:8080", model_name="x", **kw):
        seen["model"] = model_name

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "__init__", _fake_init)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--max-articles", "50"])
    assert rc == 0
    assert seen.get("model") == "Qwen3-4B-Q4_K_M"
    assert json.loads(out_path.read_text(encoding="utf-8"))["model"] == "Qwen3-4B-Q4_K_M"


def test_factcheck_rejection_falls_back_to_workers_ai(tmp_path, monkeypatch):
    # Local brief passes validation but factcheck flags it; Workers AI is
    # configured, so it gets one shot, and its clean brief is published.
    from pipeline import newsroom as nr

    cluster = _cluster("eid-fc-workers-04", tier="high")
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [cluster])
    monkeypatch.setenv("CF_ACCOUNT_ID", "test-acct")
    monkeypatch.setenv("CF_API_TOKEN", "test-token")

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    fc_calls: list[bool] = []

    def _fake_factcheck(self, messages):
        fc_calls.append(True)
        if len(fc_calls) == 1:
            return ["Local brief has an unsupported midnight claim."], "raw", None
        return [], "raw", None

    worker_calls: list[bool] = []

    def _fake_workers_generate(self, cluster):
        worker_calls.append(True)
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate_factcheck", _fake_factcheck)
    monkeypatch.setattr(nr.CloudflareWorkersAIProvider, "generate", _fake_workers_generate)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--enable-factcheck"])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1
    assert worker_calls  # Workers AI was tried after the factcheck rejection
    assert stories[0]["aiGenerated"] is True


def test_factcheck_rejection_by_both_providers_falls_back_to_card(tmp_path, monkeypatch):
    # Both providers' briefs are factcheck-flagged: the headline + link card
    # ships. Never publish unvalidated text.
    from pipeline import newsroom as nr

    cluster = _cluster("eid-fc-both-05", tier="high")
    in_path = tmp_path / "clusters.json"
    out_path = tmp_path / "stories.json"
    _write_clusters(in_path, [cluster])
    monkeypatch.setenv("CF_ACCOUNT_ID", "test-acct")
    monkeypatch.setenv("CF_API_TOKEN", "test-token")

    def _fake_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    def _fake_factcheck(self, messages):
        return ["Unsupported claim in every brief."], "raw", None

    def _fake_workers_generate(self, cluster):
        brief = _good_brief_for(cluster)
        return brief, json.dumps(brief), None

    monkeypatch.setattr(nr.LocalLlamaProvider, "generate", _fake_generate)
    monkeypatch.setattr(nr.LocalLlamaProvider, "generate_factcheck", _fake_factcheck)
    monkeypatch.setattr(nr.CloudflareWorkersAIProvider, "generate", _fake_workers_generate)

    rc = nr.main(["--in", str(in_path), "--out", str(out_path),
                  "--state", str(tmp_path / "state.json"),
                  "--sources-dir", str(tmp_path / "no-sources"),
                  "--enable-factcheck"])
    assert rc == 0
    stories = json.loads(out_path.read_text(encoding="utf-8"))["stories"]
    assert len(stories) == 1
    assert stories[0]["aiGenerated"] is False
