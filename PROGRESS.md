# PROGRESS — AI Primer for FDE

> **Purpose:** the shared brain across machines/sessions. Any Claude Code session (Mac dev box or a future ASUS ops session) reads this on startup to get oriented. Keep it terse and current — update it at the end of each work session.

---

## Who / what

- **Goal:** learn the AI/GenAI track for Forward Deployed Engineer interviews — hands-on, local-first. Priority: production systems, tradeoffs, debugging failure modes > theory.
- **Learner profile:** full-stack eng (Node/Express, Spring Boot, TS). Learns by building + breaking, not reading.
- **The plan:** see [`START_HERE.md`](./START_HERE.md) for the full curriculum, priorities, and Week-1 day-by-day.

## Hardware / architecture

Two-tier hybrid (details + setup commands in `START_HERE.md` → "Your rig"):

- **ASUS ROG G15 (Ubuntu)** = server tier. Hostname `yugam-ROG-Strix-G512LI-G512LI`. GPU (GTX 1650 Ti, 4GB VRAM), CUDA confirmed working.
  - **LAN IP: `192.168.1.19`**
  - Ollama runs as a **systemd service** (`ollama.service`, drop-in at `/etc/systemd/system/ollama.service.d/override.conf` sets `OLLAMA_HOST=0.0.0.0:11434`) — already bound to all interfaces, confirmed via `ss -tlnp` (`*:11434`). Survives reboot, no manual `ollama serve` needed.
  - Qdrant **is up** in Docker (container `stoic_tesla`, `qdrant/qdrant:1.18.3`), bound to `0.0.0.0:6333-6334`, confirmed reachable on the LAN IP (`curl http://192.168.1.19:6333/` responds). No collections loaded yet (`/collections` empty) — data loading is still Day 3 work, the server itself just happens to already be running.
  - Firewall (`ufw`) state not confirmed from this session (needs sudo password this session didn't have). Both 11434 and 6333 are reachable on the LAN as tested, so if that changes later, check `sudo ufw status` on the ASUS.
  - Repo remote: `git@github.com:yugam1/LocalLearn.git`.
- **Mac (Intel MBP 2019, i7-9750H, 16GB)** = dev/app tier. All code + git live here. Claude Code runs here (single dev brain).
  - Client points at ASUS via `OLLAMA_URL` / `QDRANT_URL`. Quick connect, once this repo is cloned on the Mac:
    ```bash
    git clone git@github.com:yugam1/LocalLearn.git
    export OLLAMA_URL="http://192.168.1.19:11434"
    curl $OLLAMA_URL/api/tags   # should list llama3.2:3b, llama3.1:8b, nomic-embed-text, qwen2.5-coder:7b
    ```

## Working-across-machines rule

Code + Claude Code = **Mac only** (the plan). ASUS is headless infra.

> **Deviation note (2026-08-02):** Day 1 was actually driven from a Claude Code session running **directly on the ASUS** (this repo is checked out at `/media/yugam/D1/Workspace/Projects/LocalLearn` there too), not the Mac — convenient for Day 1 specifically since `START_HERE.md` already recommends doing Day 1 locally on the ASUS. This ASUS-side session ran the curl/API exercises live via Bash and wrote the real measured results into `notes/day1.md` directly, rather than the user running them by hand. From Day 3 onward (client/server split), switch back to the Mac as the single dev brain per the rule below — if a Claude session is ever run on the ASUS again, it shares state with the Mac session ONLY through this file + git; conversation histories do NOT sync.

---

## Status

**Current day:** Day 4 done (full RAG run + notes filled, incl. Part C grounding test). Day 5 (break RAG 4 ways) scaffolded — script + notes template written, not yet run. All day scripts refactored onto a shared `scripts/locallearn/` package + now self-log to `result/`.
**Last updated:** 2026-08-05

### Week 1 checklist
- [x] **Day 1** — Ollama up; talk to API; observe tokens/context; break context window; write `notes/day1.md`
- [x] **Day 2** — embeddings by hand (`nomic-embed-text`), cosine similarity script; found false pos/neg + polarity blind spot
- [x] **Day 3** — Qdrant in Docker; real similarity search; matched numpy brute force exactly (0.00000 gap), numpy ~74× faster at N=13
- [x] **Day 4** — full RAG pipeline (ingest → chunk → embed → store → retrieve → answer w/ citations); happy-path + out-of-corpus grounding test both run
- [~] **Day 5** — break RAG 4 ways: chunking / retrieval / hallucination / stale index — **scaffolded, not yet run**
- [ ] **Day 6** — improve retrieval (hybrid BM25+vector, reranker); measure if quality actually improved
- [ ] **Day 7** — eval harness: 20-Q gold set, exact-match + LLM-as-judge, produce a %

---

## Log (newest first)

### 2026-08-05 — Refactor: shared `locallearn` package + self-logging (no new day)
- **Why:** every day script had copy-pasted the same plumbing (`load_dotenv`, `embed`, `cosine`, `connect`, chunking, `generate`, display helpers). Pulled the common code out so each `dayN` file shows only its unique teaching content (corpus, queries, breaks, diagnoses), and applied OOP/SOLID.
- **New package `scripts/locallearn/`** (feature-wise modules): `config` (`Settings` dataclass + `load_dotenv`), `ollama` (`OllamaClient`: `.embed`/`.chat`/`.require_model`), `similarity` (`cosine`), `chunking` (`Document`/`Chunk`/`Chunker`/`load_documents`), `vectorstore` (`VectorStore` — Qdrant wrapper with the **embedder injected**, so Day 3 drives it vector-level via `load()`/`search()` and Day 4/5 text-level via `rebuild()`/`retrieve()`), `generation` (`format_context`+`generate`), `prompts` (`GROUNDED_SYSTEM`/`NAIVE_SYSTEM`), `display` (`banner`/`show_retrieval`/`show_chunks`/`print_answer`), `runlog` (`Tee`+`tee_stdout`). `__init__.py` re-exports everything as `from locallearn import ...` (works because a script's own dir is on `sys.path`).
- **Self-logging for ALL days** (replaces shell `>` redirects): `day2`→`result/day2.txt`, `day3`→`result/day3.txt`, `day4`→`result/day4.txt`, `day5`→`result/day5_<mode>.txt`. `tee_stdout` writes the file AND still prints live; nests, so `day5 MODE=all` produces 5 files = 4 per-mode + a combined `day5_all.txt`.
- **Behavior preserved** (verified offline: byte-compile + import all + `Chunker` matches old `chunk_text`, payload schema unchanged `source`/`chunk`/`text`, tee nesting → 5 files). Not run against the ASUS yet. One cosmetic change: Day 4 retrieval now uses the shared `show_retrieval` (140-char previews) instead of its old boxed 150-char block.
- **Next:** unchanged — user still needs to run `day5_break_rag.py` per MODE, then fill `notes/day5.md`.

### 2026-08-04 — Day 4 done, Day 5 scaffolded
- **Day 4 run** (`scripts/day4_rag.py`, client→server): full pipeline ran — 3 Beacon docs → 10 chunks (120-word/25-overlap) → `nomic-embed-text` 768-D → Qdrant `day4_beacon_docs` (Cosine) → top-4 retrieve → grounded generate w/ `[n]` citations on `llama3.1:8b`. Happy-path query ("why is my Tag offline?") retrieved chunk0 at 0.743 with a clean gap over the pack (next 0.643), all top-4 inside `troubleshooting.md`. Answer was faithful (nothing invented) but **citations were sloppy** — attributed a chunk2 fact to `[1]`/`[4]` — a distinct "grounded ≠ correctly attributed" failure mode. Part C **out-of-corpus grounding test** (`result/day4New.txt`, "Does Beacon integrate with Salesforce?") now run: scores low+flat (0.538→0.513) scattered across all 3 docs, and the guardrail **held** — model replied verbatim "I don't know based on the provided documents." Findings in `notes/day4.md`.
- **Day 5 scaffold** (not yet run): built `scripts/day5_break_rag.py` — induces all four RAG failure modes, each as a HEALTHY-vs-BROKEN A/B in one run with a DIAGNOSIS line naming the guilty stage. (1) **chunking**: 120-word → 10-word/0-overlap shreds the pricing table from its header; (2) **retrieval**: k=4 → k=1 on a two-chunk question (offline def + >2h power-cycle fix) returns half; (3) **hallucination**: identical retrieval, grounding prompt on vs. off, on the Salesforce out-of-corpus query; (4) **stale index**: simulated in-memory `$4→$6` edit to pricing WITHOUT re-embedding → old answer until re-index (does NOT touch real `docs/`). `MODE` const (or env) picks one, or `all`. Separate collection `day5_break_rag`. `notes/day5.md` template written with per-break blanks + a symptom→stage→fix taxonomy table (the "is it retrieval or generation?" interview answer). Also added a project **`CLAUDE.md`** (read-order + conventions for building new days).
- **Next:** user runs `day5_break_rag.py` one MODE at a time → `result/day5_<mode>.txt`, then we fill `notes/day5.md` from real output. **[fill the four observed outcomes + the one-line taxonomy through-line after running.]**

### 2026-08-02 — Day 3 done, Day 4 scaffolded
- **Day 3 run** (`scripts/day3_qdrant.py`, ASUS): Qdrant matched the numpy brute-force cosine loop exactly — same top-5 order, max score gap `0.00000`. Latency at N=13: Qdrant `11.88 ms` vs numpy `0.16 ms` (~74× slower — the index is pure overhead at tiny N; crossover is at large N). Findings written to `notes/day3.md`. Recorded run (`result/day3.txt`, script default `QUERY = "How can I recover access to my account?"`, latency 11.21 ms vs 0.20 ms) **reproduced the exact Day-2 danger in a real vector DB**: "How do I permanently delete my account?" ranked #1 (0.730) for a *recovery* query, above "reset my password" (0.714) and "forgot my login credentials" (0.655). Opposite-intent, most-destructive doc wins on topic/keyword overlap — the bug got persisted into the DB, not fixed by it.
- **Day 4 scaffold** (not yet run): built the full RAG pipeline `scripts/day4_rag.py` (ingest `../docs` → 120-word overlapping chunks → embed → Qdrant `day4_beacon_docs` → top-k retrieve → grounded generate w/ `[n]` citations). Corpus is a **fictional product "Beacon"** (`docs/overview.md`, `docs/pricing.md` w/ a table, `docs/troubleshooting.md`) — deliberately proprietary so retrieval is the only source of truth. `GEN_MODEL` env defaults to `llama3.1:8b`. Script prints retrieved chunks BEFORE the answer (the retrieval-vs-generation debugging seam for Day 5). `notes/day4.md` template has blanks for happy-path + grounding/out-of-corpus test.
- **Next:** user runs `day4_rag.py` against the ASUS, then we fill `notes/day4.md` from the real output (happy-path query + the out-of-corpus "Salesforce" grounding test).

### 2026-08-02 — Day 2 done
- Built `scripts/day2_embeddings.py` (nomic-embed-text, 768-D, hand-rolled cosine, pairwise matrix + query search). Added `.env` / `.env.example` / `.gitignore` and a zero-dep dotenv loader; created `.venv` (requests, numpy).
- Ran client→server: dev on Mac, embeddings on ASUS (`192.168.1.19`) — first real hybrid run, a day early.
- Key findings (in `notes/day2.md`): antonyms/negation are the blind spot — `rose`↔`fell`=0.856 (highest of all pairs), `love`↔`do-not-love`=0.773. Polysemy ("bank") was NOT fooled (0.497). User's Taj trap partially worked (monument↔hotel 0.631 ≈ genuine 0.648).
- **Dangerous search result:** query "recover access to my account" ranked "permanently delete my account" #1 (0.730) over "reset password" (0.714) — opposite-intent doc wins. Plus false negative: correct `reset↔forgot-credentials` (0.677) scored below wrong `reset↔delete` (0.742).
- **Next:** Day 3 — Qdrant in Docker on the ASUS; move the numpy loop into a real vector DB over HTTP; compare DB results vs brute-force.

### 2026-08-02 — Day 1 done
- Confirmed: running directly on the ASUS (ROG Strix, GTX 1650 Ti), Ollama as a systemd service bound to `*:11434`.
- Ran Steps 2-4 live: 3b hit ~40.5 tok/s fully on GPU; 8b (5.9GB) didn't fit in 4GB VRAM, split 45%/55% CPU/GPU via `ollama ps`, dropped to ~4.25 tok/s (~9.5x slower) — first concrete "memory is the wall" data point.
- Contradictory system prompt ("one word" + "three paragraphs") didn't cleanly resolve either way — model blended both, an unpredictable-output lesson.
- Context-window test: model's `num_ctx` is 4096. Below the cap, recall was solid at both start and middle placement. Over the cap, it wasn't gradual "lost in the middle" degradation — it was a hard truncation cliff (prompt_eval_count capped at exactly 4096, secret word dropped outright).
- Filled in real observations + the three answers in `notes/day1.md` (was previously just the blank template).
- **Next:** Day 2 — embeddings by hand with `nomic-embed-text` (already pulled), cosine similarity script, find a false-positive semantic match.

### 2026-08-02 — setup / planning
- Wrote `START_HERE.md` (curriculum + priorities + Day-1 steps).
- Decided: ASUS Ubuntu = inference server (CUDA beats Intel-Mac CPU-only); Mac = dev client.
- Decided: hybrid two-tier, code-on-Mac / infra-on-ASUS, single Claude brain on Mac.
- Created `notes/` + this file.
- **Next:** install Ollama on ASUS, pull models, run Day 1 Steps 3–4 locally on ASUS.

---

## Open questions / parking lot

- (add cloud eval-baseline key — Groq or OpenRouter — when Day 7 lands)
- (systemd unit to make `ollama serve` with `OLLAMA_HOST=0.0.0.0` permanent on ASUS)
