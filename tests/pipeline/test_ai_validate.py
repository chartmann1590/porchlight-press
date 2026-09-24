"""Validator + parser tests: every rejection case the plan lists must reject."""
import json

from pipeline.ai.validate import parse_brief_json, validate_brief


def _cluster():
    return {
        "eventId": "abc123abc123abc1",
        "members": [
            {
                "id": "a1",
                "sourceId": "src-a",
                "publisher": "WNYT NewsChannel 13",
                "headline": "Fire breaks out on Central Avenue in Albany, crews on scene",
                "excerpt": "Firefighters responded to a blaze on Central Avenue in Albany on Tuesday. About 300 homes lost water service after a main break on River Street. The city budget is $98.4 million with a 1.9 percent tax levy increase.",
                "url": "https://example.com/a1",
                "publishedAt": "2026-09-23T09:05:00Z",
                "rightsMode": "RSS_EXCERPT_ALLOWED",
            },
            {
                "id": "b2",
                "sourceId": "src-b",
                "publisher": "CBS6 Albany",
                "headline": "Albany firefighters battle Central Avenue blaze, street closed",
                "excerpt": "A water main break on River Street in Troy affected roughly 500 customers, according to a utility spokesperson. Council member Jane Ortiz voted no on the budget. Repairs are expected by Thursday.",
                "url": "https://example.com/b2",
                "publishedAt": "2026-09-23T09:40:00Z",
                "rightsMode": "RSS_EXCERPT_ALLOWED",
            },
        ],
        "memberIds": ["a1", "b2"],
        "locations": [{"country": "US", "admin1": "US-NY", "admin2": "Albany County", "city": "Albany"}],
        "section": "local",
        "category": "local",
        "confidence": "HIGH",
        "confidenceTier": "high",
        "score": 0.8,
        "breaking": False,
        "status": "new",
        "version": 1,
        "firstSeen": "2026-09-23T09:05:00Z",
        "lastSeen": "2026-09-23T09:40:00Z",
    }


def _valid_brief():
    # ~100 words, all names/numbers/dates grounded, both 300 and 500 present
    # (disagreement attribution), no quotes, no 12-word verbatim runs.
    # Every sentence reuses source vocabulary (plus stopwords/allowlisted
    # boilerplate) so the 60%-coverage guard passes.
    body = (
        "On Tuesday, firefighters responded to a blaze on Central Avenue in Albany, "
        "according to WNYT NewsChannel and CBS6 Albany. "
        "City officials said about 300 homes lost water service. "
        "The main break was on River Street. "
        "A utility spokesperson said roughly 500 customers were affected in Troy, "
        "so sources disagree on the number affected. "
        "Council member Jane Ortiz voted no on the budget. "
        "The city budget is $98.4 million. "
        "The budget has a 1.9 percent tax levy increase. "
        "Repairs are expected by Thursday with firefighters in Albany and Troy. "
        "Central Avenue and River Street are closed."
    )
    assert 60 <= len(body.split()) <= 220, len(body.split())
    return {
        "headline": "Central Avenue fire disrupts service in Albany",
        "dek": "Officials report closures as crews respond in Albany.",
        "body": body,
        "category": "local",
        "locations": [{"country": "US", "admin1": "US-NY", "admin2": "Albany County", "city": "Albany"}],
        "people": ["Jane Ortiz"],
        "organizations": [],
        "sourceIds": ["a1", "b2"],
        "aiModel": "test",
        "confidence": 0.7,
    }


def test_valid_brief_passes():
    result = validate_brief(_valid_brief(), _cluster())
    assert result.ok, result.reasons


def test_parser_valid():
    brief, err = parse_brief_json(json.dumps(_valid_brief()))
    assert err is None and isinstance(brief, dict)


def test_parser_truncated_rejected():
    brief, err = parse_brief_json('{"headline": "abc", "dek": ')
    assert brief is None and err is not None


def test_parser_wrong_types_rejected_at_parse_or_schema():
    for raw in ('[]', '"just a string"', '123', '', 'null'):
        brief, err = parse_brief_json(raw)
        if brief is None:
            assert err is not None
        else:
            # 'null' parses to None? json.loads('null') -> None, not dict.
            result = validate_brief(brief, _cluster()) if isinstance(brief, dict) else None
            assert brief is None or (result is not None and not result.ok)


def test_parser_extra_fields_rejected_by_schema():
    bad = _valid_brief()
    bad["inventedField"] = "oops"
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("schema" in r.lower() or "additional" in r.lower() for r in result.reasons)


def test_invented_name_rejected():
    bad = _valid_brief()
    bad["people"] = ["John Smith"]
    bad["body"] += " John Smith witnessed the event."
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("john smith" in r.lower() for r in result.reasons)


def test_invented_multiword_span_rejected():
    bad = _valid_brief()
    # Inject an unsupported multi-word capitalized span into the body.
    bad["body"] = bad["body"].replace(
        "Council member Jane Ortiz", "Red Cross Volunteers"
    )
    # Ensure word count still in range.
    assert 60 <= len(bad["body"].split()) <= 220
    result = validate_brief(bad, _cluster())
    assert not result.ok


def test_changed_number_rejected():
    bad = _valid_brief()
    bad["body"] = bad["body"].replace("300 homes", "301 homes")
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("301" in r for r in result.reasons)


def test_invented_dollar_figure_rejected():
    bad = _valid_brief()
    bad["body"] += " An extra $123.4 million was announced."
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("123.4" in r for r in result.reasons)


def test_wrong_date_rejected():
    bad = _valid_brief()
    bad["body"] += " The next hearing is on 2026-09-24."
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("2026-09-24" in r or "date" in r.lower() for r in result.reasons)


def test_fake_source_id_rejected():
    bad = _valid_brief()
    bad["sourceIds"] = ["no-such-source"]
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("unknown sourceid" in r.lower() for r in result.reasons)


def test_fake_quote_rejected():
    bad = _valid_brief()
    bad["body"] += ' The mayor said "we will rebuild tomorrow".'
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("quote" in r.lower() for r in result.reasons)


def test_long_verbatim_copy_rejected():
    bad = _valid_brief()
    # 12+ consecutive words copied verbatim from the first excerpt.
    copied = (
        "Firefighters responded to a blaze on Central Avenue in Albany on Tuesday"
    )
    assert len(copied.split()) >= 12
    # Build a body that is otherwise valid but contains the verbatim run.
    # Keep word count in range by trimming elsewhere is unnecessary; the
    # verbatim guard fires regardless of length, but keep length valid so
    # the failure is attributable to verbatim copying.
    words = bad["body"].split()
    # Replace the first 20 words with the copied run + filler to stay in range.
    new_words = copied.split() + words[20:]
    bad["body"] = " ".join(new_words)
    assert 60 <= len(bad["body"].split()) <= 220
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("verbatim" in r.lower() for r in result.reasons)


def test_disagreement_single_value_rejected():
    bad = _valid_brief()
    # Drop the 500 value: sources disagree 300 vs 500, body must have both.
    bad["body"] = bad["body"].replace("500 customers", "customers")
    bad["body"] = bad["body"].replace("500", "")
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("disagreement" in r.lower() for r in result.reasons)


def test_title_case_headline_skipped():
    # A Title Case headline with novel words must NOT trigger the entity check
    # (check runs on body+dek only).
    good = _valid_brief()
    good["headline"] = "Extraordinary Council Breakfast In Albany"
    result = validate_brief(good, _cluster())
    # May still fail on other grounds? It should pass: headline words are
    # skipped, length is fine (<=110).
    assert result.ok, result.reasons


def test_length_limits():
    bad = _valid_brief()
    bad["headline"] = "x" * 111
    assert not validate_brief(bad, _cluster()).ok
    bad = _valid_brief()
    bad["dek"] = "y" * 201
    assert not validate_brief(bad, _cluster()).ok
    bad = _valid_brief()
    bad["body"] = "too short body here"
    assert not validate_brief(bad, _cluster()).ok


def test_unsupported_background_sentence_rejected():
    bad = _valid_brief()
    bad["body"] += (
        " Investigators said there were no casualties and the earthquake depth "
        "remains under investigation with seismic crews analyzing waveforms rapidly worldwide today."
    )
    result = validate_brief(bad, _cluster())
    assert not result.ok


def test_sentence_splitter_ignores_abbreviations():
    from pipeline.ai.validate import _split_sentences

    # (Like the guard itself, the splitter drops the .!? delimiters.)
    assert _split_sentences("Dr. Smith went to Washington. He returned Tuesday.") == [
        "Dr. Smith went to Washington", "He returned Tuesday",
    ]
    assert _split_sentences("The U.S. flag flies over Albany.") == [
        "The U.S. flag flies over Albany"
    ]
    assert _split_sentences("Meet after lunch, e.g. at noon in Troy.") == [
        "Meet after lunch, e.g. at noon in Troy"
    ]
    assert _split_sentences("St. Louis voted Tuesday. Troy voted Thursday.") == [
        "St. Louis voted Tuesday", "Troy voted Thursday"
    ]
    assert _split_sentences("Mr. Smith met Mrs. Jones and Ms. Ortiz.") == [
        "Mr. Smith met Mrs. Jones and Ms. Ortiz"
    ]
    assert _split_sentences("Born in Jan. 2020 in Troy.") == ["Born in Jan. 2020 in Troy"]
    assert _split_sentences("Michael J. Fox visited Troy.") == ["Michael J. Fox visited Troy"]
    assert _split_sentences("The curfew starts at 9 p.m. daily.") == [
        "The curfew starts at 9 p.m. daily"
    ]
    # Ordinary sentence ends still split.
    assert _split_sentences("Hello world. Goodbye.") == ["Hello world", "Goodbye"]


def test_abbreviation_does_not_hide_unsupported_sentence():
    # "U.S." near the end would fragment this sentence into <5-word pieces
    # under a naive splitter, dodging the coverage guard entirely.
    bad = _valid_brief()
    bad["body"] += " Shifts nationwide today per U.S. data."
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("background" in r.lower() or "covered" in r.lower() for r in result.reasons)


# --- fix/ai-brief-acceptance: false-positive regression tests ---

def test_resolved_state_name_accepted():
    # "New York" is the cluster's resolved admin1 (US-NY); sources say only
    # "Albany" but the brief must not fail the entity check for the state.
    good = _valid_brief()
    good["body"] += (
        " Firefighters responded in Albany, New York on Central Avenue"
        " with crews on scene in Albany."
    )
    assert 60 <= len(good["body"].split()) <= 220
    result = validate_brief(good, _cluster())
    assert not any("New York" in r for r in result.reasons), result.reasons
    assert result.ok, result.reasons


def test_resolved_county_name_accepted():
    # "Albany County" is the cluster's resolved admin2; same allowance.
    good = _valid_brief()
    good["body"] += (
        " Albany County crews responded on Central Avenue in Albany"
        " with firefighters on scene."
    )
    assert 60 <= len(good["body"].split()) <= 220
    result = validate_brief(good, _cluster())
    assert not any("Albany County" in r for r in result.reasons), result.reasons
    assert result.ok, result.reasons


def test_state_alias_accepted():
    # Common forms (NY) of the resolved state count as supported too.
    good = _valid_brief()
    good["body"] += (
        " Firefighters responded in Albany, NY on Central Avenue"
        " with crews on scene in Albany."
    )
    assert 60 <= len(good["body"].split()) <= 220
    result = validate_brief(good, _cluster())
    assert result.ok, result.reasons


def test_outside_cluster_place_still_rejected():
    # Strict everywhere else: a county/state NOT in this cluster still fails.
    bad = _valid_brief()
    bad["body"] += (
        " Rensselaer County crews responded on Central Avenue in Albany"
        " with firefighters on scene in Troy."
    )
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("Rensselaer County" in r for r in result.reasons)


def test_invented_city_still_rejected():
    bad = _valid_brief()
    bad["body"] += (
        " Los Angeles crews responded on Central Avenue in Albany"
        " with firefighters on scene."
    )
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("Los Angeles" in r for r in result.reasons)


def test_iso_datetime_with_time_parses():
    # Root cause of "date not in sources: 2026-09-23": publishedAt carries a
    # time (2026-09-23T09:05:00Z) and the old \b regex never matched it, so
    # every source date was invisible.
    from pipeline.ai.validate import _extract_dates

    found, _ = _extract_dates("2026-09-23T09:05:00Z")
    assert (2026, 9, 23) in found
    found2, _ = _extract_dates("2026-09-23")
    assert (2026, 9, 23) in found2


def test_source_date_other_format_accepted():
    # Same day in another format (September 23, 2026) must match the ISO
    # publishedAt timestamps once dates are normalized before comparing.
    good = _valid_brief()
    good["body"] += (
        " Firefighters responded on September 23, 2026 on Central Avenue"
        " in Albany with crews on scene."
    )
    assert 60 <= len(good["body"].split()) <= 220
    result = validate_brief(good, _cluster())
    assert not any("date not in sources" in r for r in result.reasons), result.reasons
    assert result.ok, result.reasons


def test_run_date_from_cluster_timestamps_accepted():
    # The run date (cluster firstSeen/lastSeen day) counts as supported even
    # when no member excerpt spells it out.
    cluster = _cluster()
    cluster["firstSeen"] = "2026-09-24T01:00:00Z"
    cluster["lastSeen"] = "2026-09-24T02:00:00Z"
    good = _valid_brief()
    good["body"] += (
        " Firefighters responded on September 24, 2026 on Central Avenue"
        " in Albany with crews on scene."
    )
    assert 60 <= len(good["body"].split()) <= 220
    result = validate_brief(good, cluster)
    assert not any("date not in sources" in r for r in result.reasons), result.reasons


def test_wrong_date_still_rejected():
    bad = _valid_brief()
    bad["body"] += (
        " Firefighters responded on September 25, 2026 on Central Avenue"
        " in Albany with crews on scene."
    )
    result = validate_brief(bad, _cluster())
    assert not result.ok
    assert any("date not in sources" in r for r in result.reasons)


def test_invented_background_padding_still_rejected():
    # Production padding from run 35939969517 must keep failing (coverage).
    for filler in (
        " Details about the investigation and the suspect are not yet available"
        " with firefighters on Central Avenue in Albany.",
        " The project is part of a broader initiative to improve infrastructure"
        " with firefighters on Central Avenue in Albany.",
        " The case is being handled by the Vermont State Police"
        " with firefighters on Central Avenue in Albany.",
    ):
        bad = _valid_brief()
        bad["body"] += filler
        assert 60 <= len(bad["body"].split()) <= 220, filler
        result = validate_brief(bad, _cluster())
        assert not result.ok, filler
        assert any("background" in r.lower() for r in result.reasons), (filler, result.reasons)
