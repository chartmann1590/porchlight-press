"""AI feasibility benchmark: runs fixture clusters through a local llama-server and
reports speed, JSON validity, and unsupported names/numbers. Stdlib only."""
import json
import re
import sys
import time
import urllib.request

SERVER = "http://127.0.0.1:8080/v1/chat/completions"

SYSTEM = """You are an automated news editor.
Write a concise newspaper-style news brief using ONLY the supplied source information.
DO NOT: invent facts, invent quotes, invent names, invent dates, infer motives, make unsupported conclusions, add unsupported background, change numeric values, make political judgments, copy long passages verbatim.
Clearly distinguish uncertainty. If sources disagree, say they disagree and attribute each version. If information is developing, say details are developing.
Use neutral journalistic language. Do not endorse candidates or parties.
Return strict structured JSON."""

SCHEMA = {
    "type": "object",
    "properties": {
        "headline": {"type": "string"},
        "dek": {"type": "string"},
        "body": {"type": "string"},
        "category": {"type": "string"},
        "locations": {"type": "array", "items": {"type": "string"}},
        "people": {"type": "array", "items": {"type": "string"}},
        "organizations": {"type": "array", "items": {"type": "string"}},
        "sourceIds": {"type": "array", "items": {"type": "string"}},
        "confidence": {"type": "number"},
    },
    "required": ["headline", "dek", "body", "category", "locations", "people", "organizations", "sourceIds", "confidence"],
}

NUM = re.compile(r"\$?\d[\d,]*(?:\.\d+)?")
NAME = re.compile(r"\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)+\b")


def norm_num(s):
    return s.replace("$", "").replace(",", "")


def check(brief, cluster):
    src = " ".join(f"{s['publisher']} {s['headline']} {s['excerpt']}" for s in cluster["sources"])
    src_nums = {norm_num(n) for n in NUM.findall(src)}
    text = f"{brief['headline']} {brief['dek']} {brief['body']}"
    issues = []
    for n in NUM.findall(text):
        if norm_num(n) not in src_nums:
            issues.append(f"number not in sources: {n}")
    low = src.lower()
    for name in set(NAME.findall(text)) | set(brief.get("people", [])):
        if name.lower() not in low:
            issues.append(f"name not in sources: {name}")
    ids = {s["id"] for s in cluster["sources"]}
    bad = [i for i in brief.get("sourceIds", []) if i not in ids]
    if bad:
        issues.append(f"unknown sourceIds: {bad}")
    words = len(brief["body"].split())
    if not 40 <= words <= 250:
        issues.append(f"body length {words} words")
    return issues


def generate(cluster):
    user = "SOURCES:\n" + json.dumps(cluster["sources"], indent=1) + f"\n\nCategory hint: {cluster['category']}"
    payload = {
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
        "temperature": 0.2,
        "max_tokens": 600,
        "response_format": {"type": "json_schema", "json_schema": {"name": "brief", "schema": SCHEMA}},
        "chat_template_kwargs": {"enable_thinking": False},
    }
    req = urllib.request.Request(SERVER, json.dumps(payload).encode(), {"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=900) as r:
        resp = json.load(r)
    return resp, time.time() - t0


def main(model, out_md, out_json):
    clusters = json.load(open("scripts/ai_feasibility/fixtures.json"))["clusters"]
    results = []
    for c in clusters:
        row = {"cluster": c["id"]}
        try:
            resp, secs = generate(c)
            content = resp["choices"][0]["message"]["content"]
            row["seconds"] = round(secs, 1)
            row["completionTokens"] = resp.get("usage", {}).get("completion_tokens")
            t = resp.get("timings", {})
            row["genTokPerSec"] = round(t.get("predicted_per_second", 0), 1)
            row["promptTokPerSec"] = round(t.get("prompt_per_second", 0), 1)
            row["raw"] = content
            brief = json.loads(content)
            row["validJson"] = True
            row["issues"] = check(brief, c)
            row["brief"] = brief
        except Exception as e:  # report, don't abort the benchmark
            row.setdefault("validJson", False)
            row["error"] = repr(e)
        results.append(row)
        print(json.dumps(row, indent=1), flush=True)

    json.dump({"model": model, "results": results}, open(out_json, "w"), indent=1)
    ok = [r for r in results if r.get("validJson")]
    clean = [r for r in ok if not r["issues"]]
    avg = sum(r["seconds"] for r in ok) / len(ok) if ok else 0
    with open(out_md, "a") as f:
        f.write(f"## {model}\n\n")
        f.write(f"- Valid JSON: **{len(ok)}/{len(results)}** · Passed checks: **{len(clean)}/{len(results)}** · Avg seconds/brief: **{avg:.1f}**\n")
        f.write(f"- Projected briefs in a 12-min budget: **{int(720 / avg) if avg else 0}**\n\n")
        f.write("| Cluster | Sec | Gen tok/s | Issues |\n|---|---|---|---|\n")
        for r in results:
            issues = "; ".join(r.get("issues", [])) or r.get("error", "") or "none"
            f.write(f"| {r['cluster']} | {r.get('seconds', '-')} | {r.get('genTokPerSec', '-')} | {issues} |\n")
        f.write("\n<details><summary>Generated briefs</summary>\n\n")
        for r in ok:
            b = r["brief"]
            f.write(f"**{r['cluster']}: {b['headline']}**\n\n_{b['dek']}_\n\n{b['body']}\n\n---\n\n")
        f.write("</details>\n\n")


if __name__ == "__main__":
    main(*sys.argv[1:4])
