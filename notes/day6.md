# Day 6 notes — make retrieval better, and prove it

> Goal: Day 5 Break #2 showed retrieval silently handing over half an answer.
> Today I fix that stage — hybrid (BM25 + vector) and a reranker — and the fix is
> the easy half. The half that counts is the sentence at the end:
> **"Recall@4 went 0.__ → 0.__ and p50 latency went __ms → __ms."**
> A retrieval change I can't describe in those terms isn't an improvement, it's a diff.
> Fill this in from `result/day6.txt`.

## Setup / how I ran it
- [ ] `python scripts/day6_better_retrieval.py` (single run, self-logs to `result/day6.txt`).
- Held FIXED so the retriever is the only variable: 120-word / 25-overlap chunks, `k=4`, `nomic-embed-text` 768-D, `llama3.1:8b`, Qdrant collection `day6_hybrid`. Both indexes get the **same chunk objects**.
- Four retrievers: `vector` (Day 4/5 baseline) · `bm25` (hand-rolled) · `hybrid` (RRF fusion) · `rerank` (hybrid over-fetches 6, LLM re-scores each).
- Metric is **retrieval-level**, not answer-level: *did the chunk containing the answer reach top-k?* Answer grading needs an LLM judge — that's Day 7. Retrieval recall is the **ceiling** on everything downstream.

## Why hybrid should work at all (the argument, before the numbers)
The two arms fail on **different** queries — their errors are uncorrelated:
- **Vector** compresses meaning into 768 floats. What it destroys first is rare literal tokens: a hostname or a serial format embeds as "identifier-ish", so two unrelated identifiers land next to each other.
- **BM25** counts words and knows nothing about meaning. It nails the literal token and is helpless against a paraphrase that shares no vocabulary.

Fusing them is worth it *only* if that's actually true on my corpus — which is what the `expects` column tests. **I wrote the prediction down per query before running, so the run is allowed to prove me wrong.**

## The gold set (10 queries, substring-graded)
`must_contain` is a literal phrase the correct chunk provably has, so grading needs no LLM and no judgement call. Substrings rather than chunk ids **on purpose** — chunk indices shift whenever the chunker knobs move, which would silently invalidate the gold set.

### The eval harness had a bug before it had a result
Found while building it, and it's the most transferable thing in the day: my first draft graded the "silent >2h" query on the substring `"30 seconds"`. That phrase appears in **two** places — the power-cycle fix *and* `overview.md`'s "reports a GPS ping every 30 seconds". A retriever fetching an entirely unrelated chunk would have been scored **correct**.

So `sanity_check_gold()` now runs before any measurement and hard-fails on two things:
- **UNWINNABLE** — phrase in no chunk. Drags every arm down equally, so the *comparison* still looks sane while the absolute numbers are garbage. That's what makes it dangerous.
- **AMBIGUOUS** — phrase spans multiple source docs, so a wrong chunk can score right. Must be acknowledged with `ambiguous_ok=True` (only `"idle threshold"` legitimately is — both docs answer it).

> **An eval harness is code, and code has bugs. An unchecked gold set reports three digits of precision about nothing.** First question to ask of any eval you're handed: *does it test itself?*

---

## Results

### The money table
| retriever | Recall@4 | MRR | p50 ms | p95 ms | vs baseline |
|---|---|---|---|---|---|
| vector (baseline) | **[fill]** | **[fill]** | **[fill]** | **[fill]** | — |
| bm25 | **[fill]** | **[fill]** | **[fill]** | **[fill]** | **[fill]** |
| hybrid | **[fill]** | **[fill]** | **[fill]** | **[fill]** | **[fill]** |
| rerank | **[fill]** | **[fill]** | **[fill]** | **[fill]** | **[fill]** |

*(Offline pre-check, BM25 arm alone: Recall@4 **0.80**, MRR **0.683**, p50 **<0.1 ms**. Fill the rest from the real run — the vector and rerank rows need the ASUS.)*

### Did my per-query predictions hold?
- Queries I marked `lexical` — did BM25 actually beat vector on all four? **[fill]**
- Queries I marked `semantic` — did vector actually beat BM25? **[fill]** (BM25 pre-check: missed `"van's tracker stopped showing up"` entirely, but won `"read-only user draw zones"` at rank 1 — so **at least one `semantic` prediction is already wrong**. Why? **[fill — probably shared tokens like "map"/"draw"]**)
- Any query where **hybrid was worse than both arms**? **[fill]** — RRF can do this: a doc ranked 3rd by both arms can be beaten by a doc ranked 1st by one and missing from the other.

### What the reranker cost
- p50 latency multiple vs. baseline: **[fill]×**
- MRR gain over hybrid: **[fill]**
- Verdict — worth it, or did hybrid already capture the gain? **[fill]**
- Did the LLM give lots of tied scores? **[fill]** — if it rated everything 7, it isn't discriminating and I'm paying latency for a stable sort.

### Did better retrieval change the ANSWER? (Part B)
Same query, prompt, and model; only the retriever differs.
- vector answer: **[fill]**
- hybrid answer: **[fill]**
- Did the metric improvement actually show up in the user-visible output? **[fill]** — *if it didn't, the metric was measuring something the user doesn't experience, and that's worth knowing.*

---

## Answer bank

**"You added hybrid search. How do you know it helped?"**
**[fill from the table — must include a Recall@k delta, an MRR delta, AND a latency cost. An answer with only the quality number is the answer of someone who didn't measure.]**

**"When would you NOT use a reranker?"**
**[fill]** — starting point: when the first stage already has Recall@k near 1.0, the reranker can only reorder chunks the model was going to see anyway. It buys precision, not recall. **It cannot fix a chunk the first stage never fetched** — so if Recall@k is the problem, a reranker is the wrong tool at any price.

**"Vector search is semantic — why would you ever want keyword matching?"**
**[fill with the concrete losing query from the run]** — the shape of the answer: embeddings are lossy compression, and identifiers/codes/hostnames/paths are exactly what the compression throws away. In enterprise corpora those are most of what users actually search for.

**"How big should k be?"**
Day 5 Break #2 answered the floor (too small drops half the answer). Today adds the ceiling: **[fill — did anything improve going to k=4 that wouldn't at k=2? what does the MRR column say about how fragile the ranking is?]**. A correct chunk sitting at rank 4 survives k=4 and dies the day someone trims k to save tokens — MRR is what warns you you're one config change away.

## Forward link to Day 7
Today measured **retrieval** (did the right chunk arrive?). Day 7 measures **answers** (was the response right?) with a 20-question gold set and an LLM judge. Today's harness is the skeleton: `GoldQuery`, `evaluate()`, the comparison table. Day 7 swaps substring-grading for a judge and adds the answer-level number — *"we went from 60% to 85%"*. Note the two can disagree: perfect retrieval with a wrong answer is a generation bug (Day 5's territory), and that's exactly the split the two harnesses together let me prove.
