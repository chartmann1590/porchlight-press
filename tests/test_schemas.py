"""Phase 0 exit criteria: schemas validate sample fixtures."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def test_validate_schemas_script_passes():
    proc = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "validate_schemas.py")],
        capture_output=True, text=True, cwd=ROOT,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr


def test_taxonomy_has_single_reconciled_list():
    import json
    taxonomy = json.loads((ROOT / "schemas" / "taxonomy.json").read_text())
    ids = [c["id"] for c in taxonomy["categories"]]
    assert ids == ["local", "public-safety", "business", "technology", "science",
                   "sports", "entertainment", "politics", "health",
                   "environment", "travel", "weather"]
