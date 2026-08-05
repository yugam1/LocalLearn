# Day 5 notes — break RAG four ways (the money day)

> Goal: Day 4 built a RAG pipeline that WORKS. Today I break it on purpose, one
> stage at a time, and practice the single most valuable FDE sentence: *"the
> answer is wrong — is it retrieval or generation?"* Each failure below maps to
> exactly ONE stage of `ingest → CHUNK → embed → store → RETRIEVE → GENERATE`.
> Fill this in from running `scripts/day5_break_rag.py` (one MODE per capture).

## Setup / how I ran it
- [ ] Same rig as Day 4: Ollama + Qdrant on the ASUS (`$OLLAMA_URL` `192.168.1.19:11434`, `$QDRANT_URL` `192.168.1.19:6333`), `GEN_MODEL=llama3.1:8b`, `nomic-embed-text` (768-D). Collection `day5_break_rag` (separate from Day 4's).
- [ ] Each mode prints HEALTHY then BROKEN back-to-back + a DIAGNOSIS line. Ran one MODE per capture:
  - `MODE=chunking` → `result/day5_chunking.txt`
  - `MODE=retrieval` → `result/day5_retrieval.txt`
  - `MODE=hallucination` → `result/day5_hallucination.txt`
  - `MODE=stale` → `result/day5_stale.txt`
- The A/B is the whole point: I can only *call* a bug "chunking" (vs. retrieval vs. generation) because I watched the SAME question succeed once and fail once with exactly one variable changed.

---

## Break #1 — CHUNKING (sever the table from its header)
- The dial: chunk size only. **120-word windows** (whole pricing table in one chunk) → **10-word / 0-overlap** (table shredded). Same query, model, grounding prompt.
- Query: `"How much does the Pro plan cost per Tag per month?"` (true answer: **$9**, but only knowable if the `Price / Tag / mo` header sits next to the `Pro` row).
- HEALTHY run — chunks / retrieval / answer: **[fill after run]**
- BROKEN run — what the pricing chunks looked like shredded (header vs. Pro row in different chunks?): **[fill after run]**
  - Did retrieval still return *a* pricing chunk? **[fill]** · Did it have the header, the number, or neither? **[fill]**
  - Broken answer: right $9 / wrong number / "I don't know"? **[fill]**
- Why it's a CHUNKING bug (not retrieval/generation): retrieval faithfully returned a chunk and the model read it fine — but the chunk lost the header that told you `$9` was a **price**. The failure was baked in at the split, upstream of everything else.
- Fix lives at the chunk stage: structure-aware splitting (keep the table/its header together), not a bigger k or a better prompt.

## Break #2 — RETRIEVAL (answer needs two chunks; k=1 returns one)
- The dial: **k only**. `k=4` → `k=1`. Same index, same query, same model.
- Query: `"What does it mean when a Beacon Tag is offline, and what should I do if it's still silent after more than 2 hours?"` — needs TWO chunks of `troubleshooting.md`: the **definition/causes** (chunk0) AND the **>2h power-cycle fix** (chunk2).
- HEALTHY (k=4) — did both chunks come back, and at what ranks? **[fill after run]**
  - Was the power-cycle fix at rank 2+ (i.e. exactly what k=1 would drop)? **[fill]**
- BROKEN (k=1) — which single chunk returned, and what half of the answer went missing? **[fill after run]**
- Why it's a RETRIEVAL bug: the right chunk EXISTS in the DB (k=4 proves it) but never reached the model. Top-k never returns nothing, so k=1 *looked* like it worked while silently handing over half the answer.
- Fix: bigger k / better ranking (Day 6 hybrid + reranker), not chunking or the prompt.

## Break #3 — HALLUCINATION (out-of-corpus; the guardrail is the only wall)
- The dial: **system prompt only**. Grounded (cite-or-refuse) vs. naive ("use the context to help answer"). **Identical** retrieved chunks in both runs.
- Query: `"Does Beacon integrate with Salesforce?"` (not in the corpus — this is the Day 4 Part C query; there cosine returned 4 flat ~0.51 chunks scattered across all docs).
- What got retrieved this run (should be the same low/flat scatter): **[fill after run]**
- WITH guardrail — did it refuse (`"I don't know based on the provided documents."`)? **[fill]** (Day 4: it held.)
- WITHOUT guardrail — did the naive prompt **invent** a Salesforce integration from the 4 least-bad chunks? **[fill after run]** — quote the fabrication if it did.
- Why it's a GENERATION bug: retrieval was identical and irrelevant in both runs; the *only* thing that changed the outcome was the prompt. That proves the grounding instruction is **load-bearing** — the cheapest, highest-leverage guardrail, and (per Day 4) the only thing that held.

## Break #4 — STALE INDEX (the vector store is a photo of the old truth)
- The dial: **index freshness only**. Note: the edit is SIMULATED in-memory (`$4→$6` string replace) — it does NOT touch the real `docs/pricing.md`.
- Query: `"How much does the Starter plan cost per active Tag per month?"` (real doc: **$4**).
- v1 (indexed as-is) answer: **[fill after run]** (expect $4)
- After the simulated edit ($4→$6) WITHOUT re-embedding — did re-query still answer the **stale $4**? **[fill after run]**
- After re-embedding the edited docs — did it flip to the **fresh $6**? **[fill after run]**
- Why it's a PIPELINE / FRESHNESS bug (not chunking/retrieval/generation quality): the vectors + payloads in Qdrant are a snapshot taken at embed time. Editing the source doesn't touch the index. Scariest failure of the four: the answer is confident, well-cited, and wrong *only because it's out of date*.
- Fix: re-index on change (ingest job / webhook / TTL), version the corpus.

---

## The failure taxonomy (the interview answer, in my own words)

**"The RAG answer is wrong — walk me through how you'd debug it."**
First I read the RETRIEVED chunks *before* the prose — that one move splits people who've built RAG from people who've read about it. Then I place the failure on exactly one stage of the pipeline:

| Symptom I see | Guilty stage | Fix |
|---|---|---|
| Right chunk came back but it's missing the label/context that makes it an answer | **Chunking** | structure-aware splitting; keep tables/lists/code whole |
| The needed fact exists in the DB but never reached the model | **Retrieval** | bigger k, hybrid search, reranker (Day 6) |
| Retrieval was irrelevant and the model confidently made something up | **Generation** | grounding prompt (cite-or-refuse); it's the only wall |
| Answer is confident, well-cited, and simply out of date | **Freshness / index** | re-index on change; version the corpus |

The through-line: **[fill in one sentence after running — what surprised me most about which stage owned which failure]**.

## Forward link to Day 6
Two of today's fixes ("bigger k / better ranking" and reducing retrieval misfires) are exactly Day 6: add hybrid search (BM25 + vector) and/or a reranker, then **measure** whether answer quality actually improved or I just added latency. Today I broke it and named the stage; Day 6 I fix the retrieval stage and prove the fix with numbers.
