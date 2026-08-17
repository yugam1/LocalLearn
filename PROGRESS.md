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

**Current day:** Week 1 complete. Week 2 roadmap scoped in `START_HERE.md` (Day 8 fine-tuning decision framework → Day 9 agents/tool use → Day 10 serving economics & quantization). **Day 8 not yet scaffolded.**
**Last updated:** 2026-08-17

### Week 1 checklist
- [x] **Day 1** — Ollama up; talk to API; observe tokens/context; break context window; write `notes/day1.md`
- [x] **Day 2** — embeddings by hand (`nomic-embed-text`), cosine similarity script; found false pos/neg + polarity blind spot
- [x] **Day 3** — Qdrant in Docker; real similarity search; matched numpy brute force exactly (0.00000 gap), numpy ~74× faster at N=13
- [x] **Day 4** — full RAG pipeline (ingest → chunk → embed → store → retrieve → answer w/ citations); happy-path + out-of-corpus grounding test both run
- [x] **Day 5** — break RAG 4 ways: chunking / retrieval / hallucination / stale index — run + notes filled
- [x] **Day 6** — improve retrieval (hybrid BM25+vector, reranker); measured — and the measurement said the "improvement" was a no-op on this corpus
- [x] **Day 7** — eval harness: 20-Q gold set, exact-match + LLM-as-judge, produce a % — run + notes filled; baseline 80%/90%, degraded 65%/60%

### Week 2 checklist
- [ ] **Day 8** — fine-tuning vs. RAG vs. prompting decision framework (P3, not build-first — test the framework against Days 5-7's real failure modes)
- [ ] **Day 9** — agents / tool use: small tool-calling loop on `llama3.1:8b`, `retrieve()` wrapped as a tool
- [ ] **Day 10** — serving economics & quantization: tokens/sec, GPU/CPU split, batching → a real cost/latency table

---

## Log (newest first)

### 2026-08-17 — Week 2 roadmap scoped in START_HERE.md (no run yet)

- `START_HERE.md` only named Week 2's three topics (agents/tool use, serving
  economics & quantization, fine-tuning decision framework) with no day-by-day
  breakdown — unlike Week 1, which was fully scoped before Day 1 ran. Added a
  **"Week 2"** section mirroring that structure: **Day 8** fine-tuning vs.
  RAG vs. prompting decision framework, **Day 9** agents/tool use, **Day 10**
  serving economics & quantization.
- **Order is deliberate, not topic-list order:** fine-tuning first because it's
  the cheapest to ship (the priority table already tags it **P3** — "a decision
  framework, not a hands-on skill" — and the rig's 4GB VRAM GTX 1650 Ti makes a
  real fine-tune impractical to demo), agents/tool use second because it's the
  most FDE-relevant hands-on P2 skill and reframes Day 4-7's `retrieve()` as
  "just another tool," serving economics last.
- **Day 8 will deliberately break the Week 1 build-first pattern** — instead of
  a new script, it tests the decision framework against **real data this
  project already has**: every failure mode found across Days 5-7 (chunking,
  retrieval, hallucination, stale index, the two judge-too-strict misses), one
  by one — would fine-tuning actually have fixed it? Point is to find out
  honestly if the answer to that is ever "yes" across the whole week's data, not
  to assume it never is.
- **Next:** scaffold Day 8 (framework doc + the failure-mode-by-failure-mode
  pass over Days 5-7) — not yet started.

### 2026-08-17 — Day 7 run: k=1 drops judge-score 30 points, and not on the questions predicted to break

- **Ran `day7_eval_answers.py`** against the ASUS → `result/day7.txt`; `notes/day7_post.md` filled. Judge sanity check passed (known-good→PASS, known-bad→FAIL) and self-consistency held (3/3 identical verdicts at temp 0) — trusted before reading any of the 20 real verdicts, per `sanity_check_judge`/`judge_self_consistency`.
- **The money table:** `baseline` (k=4) **80.0% exact / 90.0% judge**, p50 39.0s. `degraded` (k=1) **65.0% exact / 60.0% judge**, p50 30.7s. A 15-point exact-match drop, a steeper 30-point judge drop, for a ~9s latency win.
- **The predicted breaks were mostly wrong.** Only 2 of 3 `needs_chunks=2` questions broke under k=1 — the original Day 5 Break #2 anecdote (silent-Tag/OBD-II) actually **survived**, because the chunker happened to keep the trigger condition and the fix instructions in one 120-word window, so k=1 never had to choose. Meanwhile **3 questions predicted `needs_chunks=1` broke anyway** — not an information-quantity problem, a **ranking** problem: the one needed chunk wasn't reliably at rank 1 under plain vector search, so cutting to k=1 silently dropped it. That's a Day 6 recall-table finding wearing a Day 5 chunk-count costume.
- **Exact vs. judge disagreed on 7/20 total** (4 baseline, 3 degraded). Judge was right in 5 of 7 (mostly rescuing correct paraphrases exact_match couldn't see, e.g. "1h" vs "1 hour response time") but **wrong in 2, and both times by being too strict** — flagging a reasonable elaboration ("moving vehicle stops" vs "vehicle stops") and a detail that was actually *in its own rubric* (Tag deactivation order) as unsupported. The Chapter 7 sanity check only tests the lenience-bias direction (does it PASS a bad answer); it never tested for an overly harsh judge, and this run found that failure mode live, undetected by the sanity check that had already passed.
- **Verdict:** don't ship k=1 — the failure mode (right chunk not ranked first) isn't something you can scope around by eyeballing which questions "need more context," since the questions that broke weren't the ones predicted to. Trust the judge as a strong signal, not a blind release gate — one good/bad sanity pair caught lenience-direction failure but missed a harshness-direction one.
- **Process note:** `day7_pre.md`'s prediction checkboxes were never actually filled in before the run, so several "did reality match the prediction?" questions in `day7_post.md` have no baseline to compare against and say so explicitly rather than fabricating a predicted number after the fact.
- **Week 1 complete** (Days 1–7, all run + written up). Next: scope Week 2 — `START_HERE.md` names agents/tool use, serving economics & quantization, and a fine-tuning decision framework, but has no day-by-day breakdown yet, so Day 8 needs scaffolding from scratch before it can be run.

### 2026-08-16 — Day 6 notes rewritten as a teacher-transcript; Day 7 scaffolded (answer-level eval)

- **`notes/day6.md` rewritten**, at the user's request, as a narrated "explain it to a
  teenager" class transcript — same real numbers/findings as the original write-up
  (nothing fabricated), reframed with analogies + toy code (naive cosine, hand-rolled
  BM25 idea, RRF fusion, Recall/MRR) grounded in the real formulas from `bm25.py` /
  `retrievers.py` / `evaluation.py`. Established as the house style going forward for
  day notes; original structured/interview-bank content preserved inside it (answer
  bank, ship verdict, forward link), just narrated.
- **Day 7 scaffolded**, same transcript pattern, but split into two files per the
  user's request — a **pre-run** file (concepts only, zero results, ends with a
  predictions checklist) and a **post-run** file (blanks-only template, filled after
  the real run) — so results can't be skimmed to before the concepts land.
  - New package module **`locallearn/judging.py`**: `AnswerGoldQuery` / `JudgeVerdict`
    / `AnswerResult` / `PipelineRun`, `exact_match` (cheap substring check, now
    against GENERATED answer text instead of verbatim chunk text — the whole reason
    it's expected to be brittle here in a way Day 6's version wasn't), `llm_judge`
    (PASS/FAIL + reason, temp 0, defensively parsed), and — the new idea for this
    day — `sanity_check_judge()` + `judge_self_consistency()`: the judge itself gets
    tested (known-good vs known-bad answer discrimination, and repeat-call stability)
    BEFORE any of its verdicts are trusted, mirroring Day 6's `sanity_check_gold()`
    one layer up the stack.
  - New script **`scripts/day7_eval_answers.py`**: 20-question answer-level gold set
    over the same Beacon corpus/chunker knobs as Days 4-6. Compares two pipelines that
    differ in exactly one knob (`k`): `baseline` (k=4, Day 4's known-good config) vs
    `degraded` (k=1, Day 5 Break #2's config) — reruns that break as a real 20-question
    measurement instead of the original one-off anecdote. Each gold query carries a
    written-down `needs_chunks` prediction (1 or 2) for whether k=1 should break it,
    plus a few deliberately "fragile" exact-match cases (e.g. source says "1h", model
    likely says "one hour") to demonstrate exact-match vs judge disagreement live, and
    the Day 4/5 out-of-corpus Salesforce canary as `expects_refusal=True`.
  - Not run yet — `notes/day7_pre.md` has a predictions checklist to fill in first
    (per project convention: predict before running), then run the script against the
    ASUS, then fill `notes/day7_post.md` from the real `result/day7.txt` output.
- **Next:** user runs `python scripts/day7_eval_answers.py` against the ASUS, fills
  `notes/day7_post.md` from the real output, and we check whether the predictions
  (judge sanity/consistency, which `needs_chunks=2` questions actually broke under
  k=1, where exact-match and the judge disagreed) held.

### 2026-08-09 — Day 6 run: the improvement didn't improve anything (and that's the result)

- **Ran `day6_better_retrieval.py`** against the ASUS → `result/day6.txt`; `notes/day6.md` filled. 10 gold queries, k=4, all 4 arms.
- **The table** (Recall@4 / MRR / p50 / p95 ms): `vector` **1.00 / 0.817 / 83.5 / 190.2** · `bm25` **0.80 / 0.683 / 0.1 / 0.1** · `hybrid` **1.00 / 0.825 / 74.5 / 261.2** · `rerank` **1.00 / 0.933 / 17098.9 / 19069.6** (**204.9×** baseline p50).
- **Headline = the baseline was already at the ceiling.** Recall@4 was **1.00 before any change**, so there was no recall to buy and the whole experiment reduces to a ranking question. Day 5 Break #2 was never a retrieval-quality bug — it was a **`k` bug**, and `k=4` had already fixed it. Built the right harness for the wrong hypothesis.
- **Hybrid is a wash: +0.008 MRR = 2 wins − 2 losses.** Wins: URL query 3→2, "stop being charged" 2→1. **Losses: hybrid was WORSE than the vector arm on two queries** — "van's tracker stopped showing up" (vector 3, bm25 MISS, **hybrid 4**) and the lunch-alert query (vector 1, bm25 3, **hybrid 2**). Mechanism: **RRF has no notion of arm confidence** — it rewards agreement, so a confidently-wrong BM25 top-3 earns real credit and demotes the right chunk. That's the price of throwing away magnitudes to dodge calibration.
- **Recall@k flips the winner depending on k** (recomputed from the per-query rank table): k=2 → hybrid **0.90** vs vector 0.80; k=3 → vector **1.00** vs hybrid 0.90; k=4 → tie 1.00. So "Recall@k improved" is not a claim until k is pinned; MRR is the metric that integrates over all k and correctly calls it a wash.
- **Reranker: right result, unshippable implementation.** +0.117 MRR (8/10 → **9/10 queries at rank 1**), fixed every hybrid mis-rank — but **17.1 s/query**. The one query it couldn't fix was the one its own first stage buried at rank 4: *a reranker can only reorder the set it was handed.* The 205× indicts **pointwise 8B generative reranking on a 4GB GPU** (6 sequential calls ≈ 2.8 s each), not reranking — a cross-encoder does it batched in tens of ms. Next move: real cross-encoder, then re-measure.
- **Predictions scoreboard: 4/8 confirmed, 1 wrong, 3 untestable** (both arms tied at rank 1). The wrong one — `"Can a read-only user draw zones on the map?"`, tagged `semantic`, **BM25 won at rank 1** because the query leaks literal tokens (`map`/`user`/`zones`) to the target chunk. **A "semantic" test query sharing content words with its target isn't testing semantics** — gold-set design bug, caught only because the prediction was written down. Also: vector won 3 of 4 *lexical* queries at rank 1 — with 10 chunks there's nothing for a smeared identifier embedding to collide with, so **the lexical advantage is a function of corpus size/near-duplicate density**, and n=10 is too small to detect the effect hybrid exists for.
- **Part B — better retrieval did NOT change the answer.** vector and hybrid returned *different* context (only 2 of 4 chunks shared) and produced a **byte-identical** answer, because both put the answer chunk at rank 1 and the grounded prompt ignored ranks 2–4. MRR gains below the top slot are invisible to the user — what they actually buy is **margin before a k reduction breaks you**, and I should claim that rather than implying answers got better.
- **Two harness bugs the run exposed:** (1) the table printed hybrid as **0.9× latency (faster)**, which the call graph forbids — `HybridRetriever` calls the vector arm *then* does more work; 9 ms is noise on an ~80 ms LAN embed round-trip (hybrid p95 is higher: 261 vs 190). *A benchmark reporting a physically impossible speedup is telling you n is too small* — need repeats, not 1 sample/query. (2) **the reranker's raw 0–10 LLM scores are never logged**, so "did it emit lots of ties?" is unanswerable — it moved ranks on 3/10 queries so it was discriminating, but that's inference. Log `hit.parts["llm"]`.
- **Verdict:** don't ship hybrid on this evidence (noise-level gain, 2 regressions, a second index to keep in sync — cf. Day 5's stale-index break); keep the reranker's *result*, replace its implementation. **The keeper is `evaluation.py`** — it's the only thing today that stopped a no-op from shipping, and it's Day 7's skeleton.
- **Next:** Day 7 — 20-question gold set + LLM-as-judge on top of `GoldQuery`/`evaluate()`, producing an answer-level %. Watch for the two harnesses disagreeing: perfect retrieval + wrong answer = a generation bug (Day 5's territory).

### 2026-08-08 — Day 5 done (hallucination break took 3 attempts), Day 6 scaffolded

- **Day 5 run.** Chunking / retrieval / stale breaks behaved as designed. The **hallucination break refused to break**, and chasing it was the real lesson — two of my hypotheses were falsified by measurement:
  - *H1: "drop the grounding prompt → it fabricates."* **Wrong.** `NAIVE_SYSTEM` hedged just like `GROUNDED_SYSTEM`. Reason: `NAIVE` still says "use the context", and `generation.format_context` still wraps everything in a `Context: [1]… Question:` scaffold — prompt **structure** is a guardrail independent of prompt text.
  - *H2: "temp 0 is suppressing it; raise to 0.9."* **Wrong, and measured.** Ran the 2×2 {naive, sales} × {temp 0, 0.9}: naive hedges at BOTH temps, the sales persona fabricates at BOTH. Clean main effect of **persona**, zero effect of sampling. Verified temperature was actually reaching Ollama (4 calls at temp 0 → 1 distinct output; at 0.9 → 4 distinct), so this is a real result, not a plumbing bug. Mechanism: temperature only flips tokens whose candidates are close; the model holds no belief that Beacon publishes an uptime figure, so that gap is several logits and 0.9 doesn't dent it. **Temperature = how you sample from a belief; the prompt = what the belief is.**
  - **What finally broke it:** a third prompt rung, `SALES_SYSTEM` — a sales-engineer persona forbidding "I don't know" and licensing *"state the industry-standard figure"*. Fabricated a **99.99% uptime SLA** (narrating its own source: *"a standard figure in the industry"*), a flat **"Yes"** on Salesforce integration, and a nonexistent **API plus fake social proof** (*"we've seen many customers…"*). Corpus check: `grep -in "api\|integrat\|uptime\|99\." docs/*.md` → **nothing**.
  - **Better bug found by accident:** the **grounded** prompt broke its own contract — asked for an uptime %, it skipped the mandated exact refusal string and cited `[3]` for a fact that lives in `[1]`. Reproduced again in the sampling check with a different wrong `[n]`. **A true claim under a wrong citation beats every eyeball review**; a fabricated number is greppable. Takeaway: "reply exactly X" / "cite sources" are requests to a sampler, not constraints — enforce in code (validate every `[n]` resolves and the cited chunk contains the claim).
  - Temperature's real effect was **format collapse**, not lying: grounded@0.9 once emitted a bare `[1] (Source: pricing.md#chunk1)` with no answer, and other hot variants miscited or swapped *support* SLA for *uptime* SLA. **Precision degrades before truth** — and a `%`-based fabrication detector passes all of those.
  - Package changes: `prompts.SALES_SYSTEM` added; `generation.generate` now takes `temperature` (default 0.0, so other days are unchanged); `day5_break_rag.py` hallucination mode is now a 5-rung ladder over two probes (weak bait + a presupposition bait that demands a number).
- **Day 6 scaffold (not yet run).** `scripts/day6_better_retrieval.py` — four retrievers over one labelled gold set: `vector` (baseline) / `bm25` / `hybrid` (RRF, fuses **ranks** not scores, since cosine ~0.5-0.75 and BM25 0-15+ aren't comparable) / `rerank` (hybrid over-fetches 6, `llama3.1:8b` pointwise-rescores → deliberately slow, so the latency cost is visible). New modules: `bm25` (hand-rolled Okapi, no dep), `retrievers` (one `retrieve(query,k)` interface; `Hit` is Qdrant-shaped so `display`/`generation` are untouched), `evaluation` (`GoldQuery`, Recall@k, MRR, p50/p95, comparison + per-query + disagreement tables). Collection `day6_hybrid`.
  - Gold set = 10 queries graded by **literal substring** (no LLM needed), each tagged with a written-down prediction (`lexical` / `semantic` / `either`) so the run can falsify it.
  - **The harness had a bug before it had a result:** grading the ">2h silent" query on `"30 seconds"` also matched `overview.md`'s "GPS ping every 30 seconds", so a wrong chunk would have scored **correct**. Added `evaluation.sanity_check_gold()`, which hard-fails on *unwinnable* (phrase in no chunk — drags all arms down equally, so the comparison still looks sane while the absolute numbers are garbage) and *ambiguous* (phrase spans multiple docs; must be acknowledged with `ambiguous_ok=True`).
  - Verified offline (no ASUS): all 10 gold phrases present + unambiguous, BM25 arm alone scores **Recall@4 0.80 / MRR 0.683 / p50 <0.1 ms**, perfect on all 4 lexical queries, misses 2 semantic ones. One prediction is **already** wrong — BM25 wins `"Can a read-only user draw zones on the map?"` at rank 1 despite being tagged `semantic`.
- **Next:** user runs `python scripts/day6_better_retrieval.py` against the ASUS → `result/day6.txt`, then fill `notes/day6.md` (the four-row table + which predictions failed + whether the reranker earned its latency).

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
