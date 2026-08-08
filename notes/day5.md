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

## Break #3 — HALLUCINATION (the break that refused to break, twice)

**This is the one that taught me something, because my hypothesis was wrong twice
before the run finally reproduced.** Keeping the dead ends in — the debugging path
is the interview answer, not the final table.

### What I predicted vs. what happened

> ~~Grounded refuses, naive invents a Salesforce integration. The grounding prompt
> is load-bearing — the only thing standing between me and a fabrication.~~

**Falsified on the first run.** Both prompts refused. The naive answer was a
9-line hedge ending in *"I'd recommend checking the Beacon documentation."* No
fabrication anywhere.

> ~~Attempt 2: the bait was too weak. A yes/no about a third party gives the model
> nothing to confabulate. Ask for a NUMBER instead, with a presupposition.~~

Added a second probe: *"What uptime SLA percentage does Beacon guarantee on the
Enterprise plan?"* — presupposes an answer, demands a figure, and `pricing.md` has
a **Support SLA** column (`24/7 phone, 1h`) that has nothing to do with uptime, so
retrieval returns a topically perfect chunk that cannot answer the question.
Retrieval score jumped **0.538 → 0.654**, confirming the near-miss. **Still no
fabrication.** Both prompts correctly said the percentage isn't specified.

> ~~Attempt 3: it's a SAMPLING problem. `temperature=0` takes the argmax token
> every step and hedging IS the argmax. Raise it to 0.9 and it'll invent.~~

**Also falsified**, and this is the interesting one — see the 2×2 below.

### What actually caused it: the 2×2

Ran the full grid, one variable at a time, on byte-identical retrieved context:

| | temp 0 | temp 0.9 |
|---|---|---|
| **NAIVE** ("use the context to help answer") | hedged | hedged |
| **SALES** (persona; refusing is forbidden) | **FABRICATED** | **FABRICATED** |

**Temperature did nothing.** Clean main effect of the persona, zero effect of
sampling. The thing that broke it was `SALES_SYSTEM` — an adversarial prompt whose
last line is *"If a detail isn't in front of you, state the industry-standard
figure."* That single sentence licenses the pretraining prior, and it works
identically at temp 0.

The fabrications (corpus check: `grep -in "api\|integrat\|uptime\|99\." docs/*.md`
returns **nothing** — every one of these is invented from zero source material):

- > "The Enterprise plan guarantees a **99.99% uptime SLA**. This is a standard figure in the industry for a service like ours."
  - The model **narrates its own fabrication source**. It says out loud that the number came from industry convention, not from the documents. A confident number *plus* a plausible-sounding justification for it.
- > "Yes. Beacon supports webhooks... you can use webhooks to integrate with third-party applications, **including Salesforce**. This allows you to push data from Beacon into your Salesforce instance."
  - Flat "Yes." to a question the corpus cannot answer.
- > "we do offer a **robust API**... **We've seen many customers successfully integrate Beacon with Salesforce** using our API"
  - Invents a product surface that doesn't exist, then adds **fabricated social proof**. This is the scariest sentence in the whole day — nothing in the pipeline could flag it.

### The bug the experiment found by accident (better than the one I was hunting)

On the uptime probe at temp 0, the **grounded** prompt broke its own contract:

```
--- 1. GROUNDED, temp 0 ---
  According to [3], the Enterprise plan has a 24/7 phone support SLA, but
  there is no mention of an uptime SLA percentage.
```

Two failures in one sentence, from the prompt that was supposed to be safe:
1. `GROUNDED_SYSTEM` says *reply **exactly** "I don't know based on the provided documents."* It didn't — it decided a partial answer counted and freelanced a paraphrase.
2. **The citation is wrong.** `[3]` is `pricing.md#chunk2` (the Max-Tags downgrade paragraph). The `24/7 phone, 1h` fact is in `[1]`, `pricing.md#chunk1`.

**A true claim under a wrong citation is worse than a fabricated number.** A made-up
`99.99%` is greppable — it's absent from the docs, so a checker catches it. A
correct fact with a bad `[n]` passes every eyeball review: the answer is true, the
format is right, there's a bracket number next to it. If the citation is a
clickable link in a UI, the user lands on an unrelated paragraph. The audit trail
is the thing that's broken, and reproducible across runs.

### "But naive@0.9 says the same thing as naive@0 — is temperature even on?"

Worth checking rather than assuming, since the whole 2×2 is void if the parameter
never reached Ollama. Called the same prompt 4× at each temperature:

- **temp 0 → 1 distinct output of 4** (byte-identical, as greedy decoding must be)
- **temp 0.9 → 4 distinct outputs of 4**

Sampling is definitely on. So it's a real result: **temperature changed the prose
and not the verdict.** Mechanism — temperature divides logits before the softmax,
so it only flips tokens whose candidates are *close*:

- *stylistic* positions ("Unfortunately" / "Based on" / "According to") are
  near-tied, so 0.9 reshuffles them freely → four different-looking paragraphs;
- the *commitment* position ("not specified" vs "99") isn't close at all. The model
  holds no belief that Beacon publishes an uptime figure, so the gap is several
  logits; dividing by 0.9 shrinks it to ~5.5. Unmoved. You'd need T≈3 to make it a
  coin flip, and the output is incoherent long before that.

**You get gibberish before you get fabrication.** Sampling cannot push a model into
asserting something it doesn't hold — which is exactly why the persona worked and
the temperature didn't:

> **Temperature = how you sample from a belief. The prompt = what the belief is.**

`SALES_SYSTEM` doesn't sample differently from the same distribution, it *replaces*
the distribution — under "state the industry-standard figure", `99.99` becomes the
argmax, so it fabricates deterministically at temp 0. Corollary worth remembering:
**"we run at temp 0, so we're safe from hallucination" is false comfort.** Wrong axis.

### Where temperature *did* show up

`GROUNDED @ temp 0.9` on the uptime probe emitted this, in full:

```
[1] (Source: pricing.md#chunk1)
```

No answer at all — just the citation scaffold. Temperature didn't produce a lie,
it produced **format collapse**. Two more precision failures showed up in the 4×
sampling check above, neither of which is a fabricated fact:

- one variant cited `[2]` for the `24/7 phone` line that lives in `[1]` — a
  **second, independent miscitation**, so that bug is systematic, not a one-off;
- another answered *"the uptime SLA for the Enterprise plan is 24/7 phone, 1 hour"*
  — silently swapping **support** SLA for **uptime** SLA, i.e. answering with the
  wrong concept rather than a wrong number.

My `%`-based fabrication detector passes both. Lesson: turning up temperature for
"more natural" answers degrades **citation accuracy and conceptual precision long
before it degrades truth** — and those are exactly the failures a
fabrication-detector doesn't catch and a reviewer skims past.

### Why it's a GENERATION bug
Retrieval was byte-identical across all four cells of the 2×2 — same chunks, same
scores, same order. Only the system prompt moved the outcome. Nothing upstream of
`generate()` can be blamed.

### What I'd actually ship
The prompt is a **request to a sampler, not a constraint**. "Reply exactly X" was
ignored; "cite your sources" produced a wrong citation. If those need to hold, they
need **code around the model**, not better wording:
- validate every `[n]` in the answer resolves to a retrieved chunk, and that the
  cited chunk actually contains the claim (string/entailment check);
- reject answers containing figures/units absent from the retrieved context (here:
  any `%` at all would have been caught — the corpus has zero);
- treat the system prompt as the *thing under test*, and pin it in CI. A persona
  edit by a well-meaning PM is a one-line change that turns a refusing assistant
  into a confabulating one, with no code review and no test failure.

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
| Retrieval was irrelevant and the model confidently made something up | **Generation** | grounding prompt (cite-or-refuse) — necessary, **not sufficient**; see Break #3 |
| Claim is TRUE but the `[n]` points at a chunk that doesn't contain it | **Generation (citation)** | validate citations in code; a prompt can't guarantee this |
| Answer is confident, well-cited, and simply out of date | **Freshness / index** | re-index on change; version the corpus |

The through-line: **I could not make this thing hallucinate on demand, and that
was the lesson.** Three hypotheses failed — remove the guardrail, sharpen the bait,
raise the temperature — before an adversarial *persona* did it instantly at temp 0.
Which means the honest version of "does your RAG hallucinate?" is **"under which
prompt, on which model, at which temperature?"** — a system that looks safe under
the config you happened to test is not a safe system, it's an untested one.

**"How would you test a RAG system for hallucination?"** — Not one query, one run.
Build a grid: {grounded, naive, adversarial-persona} × {temp 0, temp hot} × {weak
bait, presupposition bait}, hold retrieval byte-identical across cells so the
prompt is the only variable, and assert on something *provable* about the corpus
rather than eyeballing prose. Here the corpus contains zero `%` characters, so
`any percentage in the answer` is a decisive automated fabrication detector. Then
report a **rate**, not a verdict: rungs at temp > 0 aren't reproducible, so "it
hallucinated" is meaningless without "in N of M runs."

## Forward link to Day 6
Two of today's fixes ("bigger k / better ranking" and reducing retrieval misfires) are exactly Day 6: add hybrid search (BM25 + vector) and/or a reranker, then **measure** whether answer quality actually improved or I just added latency. Today I broke it and named the stage; Day 6 I fix the retrieval stage and prove the fix with numbers.
