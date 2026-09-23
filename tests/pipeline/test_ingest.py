"""Ingest CLI tests (offline; BLOCKED-only registry is a clean no-op)."""
import json


def test_all_blocked_registry_exits_zero(tmp_path, capsys):
    from pipeline.ingest import main

    srcdir = tmp_path / "sources"
    srcdir.mkdir()
    (srcdir / "blocked.json").write_text(json.dumps({
        "apiVersion": 1,
        "id": "blocked-outlet",
        "name": "Blocked Outlet",
        "homepage": "https://example.com/",
        "feedUrl": "https://example.com/feed",
        "type": "rss",
        "coverage": {"country": "US"},
        "rightsMode": "BLOCKED",
        "language": "en",
        "enabled": True,
        "priority": 1,
    }), encoding="utf-8")
    out = tmp_path / "normalized.json"

    rc = main(["--out", str(out), "--sources-dir", str(srcdir),
               "--state", str(tmp_path / "http-state.json")])

    assert rc == 0  # config state, not a failure
    assert "no-op" in capsys.readouterr().out
    assert json.loads(out.read_text(encoding="utf-8"))["items"] == []
