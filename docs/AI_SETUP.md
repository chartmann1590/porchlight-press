# AI setup — newsroom model hosting & caching (Phase 3)

See also: `ARCHITECTURE.md` (data flow), `ZERO_COST_ARCHITECTURE.md` ($0 rules),
`HOW_TO_ADD_A_SOURCE.md` (registry), `HOW_TO_ADD_A_CITY.md` (new markets).

All $0, no billing, no keys for the core path. Model binaries are never
committed (`*.gguf` is gitignored).

## Models

| Role | File | Size | SHA-256 (see `pipeline/ai/models.lock`) | License |
|---|---|---|---|---|
| Primary (always used) | `Qwen3-4B-Q4_K_M.gguf` | 2.50 GB | `7485fe6f…` | Apache-2.0 |
| Fallback (kept for manual `--model` override only) | `Qwen3-1.7B-Q8_0.gguf` | 1.83 GB | `061b54da…` | Apache-2.0 |

0.6B is rejected: it copies excerpts verbatim instead of rewriting
(benchmark 2026-09-23, MASTER_PLAN §11). Production always uses the 4B
primary for quality (live regression 5/8 vs 1/30 on 1.7B at run
35983811629); throughput is not the goal because the time-budgeted stage
publishes top-ranked stories first and carries the rest to the next run
(4 runs/day).

## Mirroring the models to our GitHub Release (automated)

`Qwen3-4B-Q4_K_M.gguf` is 2.50 GB, over GitHub's 2 GB per-asset limit, so it
is stored as two shards produced by llama.cpp's splitter
(`llama-gguf-split --split --split-max-size 1900M` →
`-00001-of-00002.gguf` / `-00002-of-00002.gguf`).
`llama-server -m <first shard>` loads split files natively; the pipeline
workflow passes the first shard path.

There are no manual steps. To mirror (or re-mirror), run the workflow:

1. GitHub > Actions > **mirror-models** > **Run workflow**.
2. It reads URLs, sizes, and SHA-256 hashes from `pipeline/ai/models.lock`
   (the single source of truth), downloads both GGUFs from Hugging Face,
   verifies each SHA-256, splits the 4B file with the pinned prebuilt
   llama.cpp, creates or updates the `model-qwen3` release, and uploads the
   two shards plus the 1.7B file plus `LICENSE`.
3. It is idempotent: verified downloads are reused, an existing split is
   kept, and release assets that already exist with the right size are
   skipped. Re-running is always safe.
4. The run summary lists every asset with its size and SHA-256; copy the two
   shard hashes into `pipeline/ai/models.lock` (`releaseAssetNote`) on the
   next docs pass so the lock stays the complete record.

The nightly pipeline's Hugging Face fallback (`ai-newsroom.yml`) is
unchanged: on cache miss it downloads from our Release when populated,
else from Hugging Face with SHA-256 verification, so CI works before and
after the first mirror run.

## How CI runs it (`.github/workflows/ai-newsroom.yml`)

1. `actions/cache` keyed on `model-${MODEL_FILE}-${SHA256}`, path
   `~/.cache/newspaper-models/`.
2. On cache miss: `gh release download model-qwen3` from this repo, else
   `curl` from Hugging Face, then `sha256sum -c` against `models.lock`.
3. Download the prebuilt llama.cpp CPU release (`b11138`,
   `llama-*-bin-ubuntu-x64.tar.gz` from `ggml-org/llama.cpp`) — no 5–10 min
   source build — and start `llama-server` with `--jinja`, `-c 4096`,
   `--host 127.0.0.1 --port 8080`, then poll `/health`.
4. Call the portable entry point (same command works on any machine with
   Python 3.12 and the llama.cpp binary):
   `python -m pipeline.newsroom --in <clusters> --out <stories>`.
5. Budget: `AI_MAX_ARTICLES_PER_RUN` (default 50) + 25-min wall-clock cap.
   Queue in rank order; overflow past the budget ships as source cards and
   is retried next run (4 runs/day carry over). Production always uses the
   4B primary for quality; the old >50 overflow switch to 1.7B is disabled
   (live regression 5/8 vs 1/30 at run 35983811629).

## Local run (any machine, no GitHub)

```bash
# Terminal 1: start the model (after downloading per models.lock):
llama-server -m ~/.cache/newspaper-models/Qwen3-4B-Q4_K_M.gguf \
  --jinja -c 4096 --host 127.0.0.1 --port 8080
# Terminal 2: fixture clusters -> stories (mock-free offline tests use
# fixtures; this command needs the live server):
python -m pipeline.process --in state/normalized.json --out state/clusters.json
python -m pipeline.newsroom --in state/clusters.json --out state/stories.json
# Killed server still yields a complete feed of source cards:
# (stop llama-server, rerun newsroom; exit 0, all stories aiGenerated=false)
```

## Fallback chain

local llama-server → Cloudflare Workers AI (only when `CF_ACCOUNT_ID` +
`CF_API_TOKEN` exist; free daily allocation; quota/HTTP error returns
`None`) → deterministic source card (original headline, publisher,
permitted excerpt, link; no fabricated summary).

Every published AI story carries `aiGenerated: true`, `aiModel`,
`generatedAt`, and full `sources[]` with original headlines/URLs/
timestamps. Anything that fails validation becomes a source card.

## Attribution

Qwen3 is Apache-2.0; the `LICENSE` file ships alongside the Release
assets (redistribution allowed with the license). No gazetteer/postal
downloads are needed for this phase beyond Phase 2's trimmed files.
