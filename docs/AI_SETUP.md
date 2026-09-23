# AI setup — newsroom model hosting & caching (Phase 3)

All $0, no billing, no keys for the core path. Model binaries are never
committed (`*.gguf` is gitignored).

## Models

| Role | File | Size | SHA-256 (see `pipeline/ai/models.lock`) | License |
|---|---|---|---|---|
| Primary | `Qwen3-4B-Q4_K_M.gguf` | 2.50 GB | `7485fe6f…` | Apache-2.0 |
| Fallback (whole run switches when >50 queued) | `Qwen3-1.7B-Q8_0.gguf` | 1.83 GB | `061b54da…` | Apache-2.0 |

0.6B is rejected: it copies excerpts verbatim instead of rewriting
(benchmark 2026-09-23, MASTER_PLAN §11).

## One-time upload to a GitHub Release (manual, done once)

`Qwen3-4B-Q4_K_M.gguf` is 2.50 GB, over GitHub's 2 GB per-asset limit.
Split it with llama.cpp's splitter, then upload both shards plus the 1.7B
file plus the Apache-2.0 license text to tag `model-qwen3` on this repo:

```bash
# 1. Download the originals (Hugging Face fallback URLs are in models.lock).
# 2. Split the 4B file (llama.cpp b11138+ provides llama-gguf-split):
llama-gguf-split --split --split-max-size 1900M Qwen3-4B-Q4_K_M.gguf
# -> Qwen3-4B-Q4_K_M-00001-of-00002.gguf + Qwen3-4B-Q4_K_M-00002-of-00002.gguf
# 3. Create the release and upload (needs `gh auth login` once):
gh release create model-qwen3 --title "Qwen3 models (Apache-2.0)" --notes "Qwen3 GGUFs for the AI newsroom pipeline. See pipeline/ai/models.lock for SHAs."
gh release upload model-qwen3 \
  Qwen3-4B-Q4_K_M-00001-of-00002.gguf \
  Qwen3-4B-Q4_K_M-00002-of-00002.gguf \
  Qwen3-1.7B-Q8_0.gguf \
  LICENSE
# 4. Record the two shard SHA-256 values into pipeline/ai/models.lock
#    (releaseAssetNote there) in a follow-up commit.
```

`llama-server -m <first shard>` loads split files natively; the workflow
passes the first shard path.

Status 2026-09-23: release upload is deferred (no large-asset upload from
this dev session). The workflow downloads from our Release on cache miss
and falls back to Hugging Face with SHA-256 verification, so CI works
before and after the upload.

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
   is retried next run. More than ~50 queued switches the run to 1.7B.

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
