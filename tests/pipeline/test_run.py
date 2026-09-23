"""Full-pipeline smoke (offline): empty registry runs end-to-end with no network."""
import json


def test_run_empty_registry_is_noop_success(tmp_path, monkeypatch):
    from pipeline import run as run_mod

    # Never allow real Commons HTTP even if a story appears.
    from pipeline import images as images_mod

    monkeypatch.setattr(images_mod.CommonsProvider, "search", lambda self, q, limit=3: [])
    empty_sources = tmp_path / "sources"
    empty_sources.mkdir()
    out = tmp_path / "public"
    state = tmp_path / "state"
    rc = run_mod.main(["--out", str(out), "--state-dir", str(state),
                       "--sources-dir", str(empty_sources),
                       "--generated-at", "2026-09-23T10:17:00Z"])
    assert rc == 0
    index = json.loads((out / "index.json").read_text(encoding="utf-8"))
    assert index["apiVersion"] == 1 and index["editions"] == []
    assert (out / "viewer.html").exists() and (out / "404.html").exists()
    assert (out / "locations/us.json").exists()
    stories = json.loads((state / "stories.json").read_text(encoding="utf-8"))
    assert stories["stories"] == []
