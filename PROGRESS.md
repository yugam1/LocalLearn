# PROGRESS — AI Primer for FDE

> **Purpose:** the shared brain across machines/sessions. Any Claude Code session (Mac dev box or a future ASUS ops session) reads this on startup to get oriented. Keep it terse and current — update it at the end of each work session.

---

## Who / what

- **Goal:** learn the AI/GenAI track for Forward Deployed Engineer interviews — hands-on, local-first. Priority: production systems, tradeoffs, debugging failure modes > theory.
- **Learner profile:** full-stack eng (Node/Express, Spring Boot, TS). Learns by building + breaking, not reading.
- **The plan:** see [`START_HERE.md`](./START_HERE.md) for the full curriculum, priorities, and Week-1 day-by-day.

## Hardware / architecture

Two-tier hybrid (details + setup commands in `START_HERE.md` → "Your rig"):

- **ASUS ROG G15 (Ubuntu)** = server tier. GPU (GTX 1650 Ti 4GB, CUDA), 16GB RAM.
  - Runs Ollama (`OLLAMA_HOST=0.0.0.0:11434`) + Qdrant (Docker, :6333).
  - LAN IP: `__________` ← fill in (`hostname -I`)
- **Mac (Intel MBP 2019, i7-9750H, 16GB)** = dev/app tier. All code + git live here. Claude Code runs here (single dev brain).
  - Client points at ASUS via `OLLAMA_URL` / `QDRANT_URL`.

## Working-across-machines rule

Code + Claude Code = **Mac only**. ASUS is headless infra. If a second Claude session is ever run on the ASUS, it shares state ONLY through this file + git — conversation histories do NOT sync.

---

## Status

**Current day:** Day 0 (setup) — not started yet.
**Last updated:** 2026-08-02

### Week 1 checklist
- [ ] **Day 1** — Ollama up; talk to API; observe tokens/context; break context window ("lost in the middle"); write `notes/day1.md`
- [ ] **Day 2** — embeddings by hand (`nomic-embed-text`), cosine similarity script; find a false-positive match
- [ ] **Day 3** — Qdrant in Docker; real similarity search; compare vs numpy brute force → **first hybrid client/server day**
- [ ] **Day 4** — full RAG pipeline (ingest → chunk → embed → store → retrieve → answer w/ citations)
- [ ] **Day 5** — break RAG 4 ways: chunking / retrieval / hallucination / stale index
- [ ] **Day 6** — improve retrieval (hybrid BM25+vector, reranker); measure if quality actually improved
- [ ] **Day 7** — eval harness: 20-Q gold set, exact-match + LLM-as-judge, produce a %

---

## Log (newest first)

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
