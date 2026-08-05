# LocalLearn — project guide for Claude

Hands-on, local-first AI/GenAI track for a full-stack eng prepping **Forward
Deployed Engineer** interviews. Philosophy: **build the thing, break the thing,
explain the tradeoff.** Systems, tradeoffs, and failure modes over theory.

## Read these first (in order) before scaffolding any new task

1. **`PROGRESS.md`** — the shared brain across machines/sessions. Tells you the
   **current day**, what's done, and the newest-first log. Always read this first
   and update it at the end of a work session.
2. **`START_HERE.md`** — the curriculum + priority tiers + the Week-1 day-by-day
   roadmap. The spec for what each day (Day N) is supposed to teach and break.
3. **The previous day's `notes/day{N-1}.md` and `scripts/day{N-1}_*.py`** — match
   their structure, voice, and depth. Each day builds directly on the last.

## Layout

- `scripts/dayN_<topic>.py` — the runnable exercise for day N (the deliverable).
- `notes/dayN.md` — the write-up: observations + "the answers in my own words"
  (interview-bank style). Scaffolded with **blanks** the user fills after running.
- `docs/` — the corpus for RAG days: a **fictional** product "Beacon" (Aldritch
  Logistics fleet-tracking). Fictional on purpose so retrieval is the only
  possible source of truth — if the model can answer without the docs, the docs
  weren't needed. `overview.md`, `pricing.md` (has a table), `troubleshooting.md`.
- `result/dayN*.txt` — captured real output from a run, used to fill the notes.
- `.env` (git-ignored; see `.env.example`) — `OLLAMA_URL`, `QDRANT_URL`,
  `GEN_MODEL`. `requirements.txt` + `.venv` for deps.

## How new tasks (each "day") are built

- **The user runs the exercises himself.** My job: **scaffold + interpret, never
  auto-run and hand back results.** Write the script + a notes template with
  blanks, then let the user run it against the ASUS and report output; then we
  fill the notes from what actually happened. Don't fabricate results.
- **Script conventions** (see `day4_rag.py` / `day5_break_rag.py` as the template):
  - A tunable constant at the top (`QUERY`, `MODE`, chunk knobs) the user edits
    and re-runs — env vars override (`os.environ.get`). Include a tiny stdlib
    `load_dotenv` (no external dep).
  - Heavy teaching comments in FDE framing (why, not just what). Print the
    **retrieved chunks BEFORE the generated answer** — that retrieval-vs-generation
    seam is the core debugging lesson. Fail loudly with the fix if infra is down.
  - `temperature: 0` so reruns are comparable.
- **Notes conventions**: observations grounded in the real run + a short
  "interview answer bank" section ("walk me through what you built", "the answer
  is wrong, how do you debug it"). Keep placeholders until a run fills them.

## Rig — hybrid two-tier (client/server split is deliberate, mirrors real deploys)

- **ASUS ROG (Ubuntu), LAN IP `192.168.1.19`** = server tier. Ollama (GPU/CUDA)
  on `:11434` as a systemd service; Qdrant (Docker) on `:6333`. Headless infra.
- **Mac (Intel MBP)** = dev/app tier. **All code + git live here.** Claude Code
  runs here — single dev brain. Client points at the ASUS via `OLLAMA_URL` /
  `QDRANT_URL`. Models present: `llama3.2:3b`, `llama3.1:8b`, `nomic-embed-text`,
  `qwen2.5-coder:7b`.
- Cross-machine state syncs **only** through `PROGRESS.md` + git — conversation
  history does not. Keep `PROGRESS.md` current so a fresh session can pick up.

## Commit / workflow

- Only commit or push when the user asks. Work happens on a feature branch
  (currently `fdelearn`); `master` is the main branch.
