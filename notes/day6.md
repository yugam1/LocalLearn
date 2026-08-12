# Day 6 notes — make retrieval better, and prove it

> Goal: Day 5 Break #2 showed retrieval silently handing over half an answer.
> Today I fix that stage — hybrid (BM25 + vector) and a reranker — and the fix is
> the easy half. The half that counts is the sentence at the end:
> **"Recall@4 went 1.00 → 1.00 and p50 latency went 83.5ms → 74.5ms."**
> Which is the most useful sentence I could have gotten, because it says the
> improvement I built **bought nothing measurable**. The baseline was already at
> the ceiling. Details below — the whole day is about what you do with that.

## Setup / how I ran it
- [x] `python scripts/day6_better_retrieval.py` (single run, self-logs to `result/day6.txt`).
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
10 gold queries, k=4, `llama3.1:8b` + `nomic-embed-text` on the ASUS over the LAN.

| retriever | Recall@4 | MRR | p50 ms | p95 ms | vs baseline |
|---|---|---|---|---|---|
| vector (baseline) | **1.00** | **0.817** | **83.5** | **190.2** | — |
| bm25 | **0.80** | **0.683** | **0.1** | **0.1** | MRR −0.133, ~0× latency |
| hybrid | **1.00** | **0.825** | **74.5** | **261.2** | MRR **+0.008**, 0.9× latency |
| rerank | **1.00** | **0.933** | **17098.9** | **19069.6** | MRR **+0.117**, **204.9×** latency |

**The headline is the first column: baseline recall was already 1.00.** Every
correct chunk was already inside the top 4 before I wrote a line of BM25. So
there was no recall to buy, and the entire experiment collapses into a *ranking*
question — which is exactly what MRR is for, and exactly why a one-metric table
would have told me nothing.

Consequence worth internalising: **I built the wrong fix for this corpus.** Day 5
Break #2 wasn't a retrieval-quality bug at all, it was a `k` bug — k=1 on a
two-chunk answer. Hybrid search doesn't fix that; `k=4` already did.

### Recall@k depends entirely on k — and the winner flips
Recomputed from the per-query rank table (arithmetic on the real output, not a
second run):

| retriever | R@1 | R@2 | R@3 | R@4 |
|---|---|---|---|---|
| vector | 0.70 | 0.80 | **1.00** | 1.00 |
| bm25 | 0.60 | 0.70 | 0.80 | 0.80 |
| hybrid | 0.70 | **0.90** | 0.90 | 1.00 |
| rerank | **0.90** | 0.90 | 1.00 | 1.00 |

At **k=2 hybrid beats vector** (0.90 vs 0.80). At **k=3 vector beats hybrid**
(1.00 vs 0.90). At k=4 they tie. Same data, three different conclusions — so
"Recall@k improved" is a meaningless claim without the k pinned, and quoting a
single k that happens to flatter your change is the easiest honest-looking lie in
retrieval work. This is the argument for MRR being in the table next to it: MRR
integrates over all the k values at once, and it says these two are a wash
(0.817 vs 0.825).

### Did my per-query predictions hold?
Scoreboard: **4 of 8 falsifiable predictions confirmed, 1 outright wrong, 3
untestable** because both arms tied at rank 1.

- **`lexical` (4 queries) — only 1 of 4 confirmed.** BM25 beat vector only on
  `"What URL is the Beacon web console at?"` (bm25 rank 1 vs vector rank 3 — the
  hostname case, exactly as argued). The other three (`BT-XXXXXXXXX`,
  `Fleet → Settings`, `50 fleets`) **both** arms nailed at rank 1, so the
  prediction couldn't be tested. Why vector did fine on identifiers here:
  **10 chunks.** There is nothing for a smeared hostname embedding to collide
  *with*. The lexical failure mode is a function of corpus size and near-duplicate
  density, and my corpus has neither. n=10 chunks / 10 queries can't detect the
  effect hybrid exists for — that's a limitation of the experiment, not a result.
- **`semantic` (4 queries) — 3 of 4 confirmed.** BM25 missed
  `"My van's tracker stopped showing up"` entirely (MISS vs vector rank 3) and
  came 3rd/2nd on the lunch-alert and "thinned out" queries. Dense retrieval's
  case holds.
- **The one I got flat wrong:** `"Can a read-only user draw zones on the map?"`
  — tagged `semantic`, BM25 won it at rank 1. The pre-check already flagged this
  and the run confirmed it. My guess was shared tokens; the query does share
  `map`/`user`/`zones` surface vocabulary with `overview.md#chunk2`, so the
  double-paraphrase (read-only→Viewer, zones→geofences) I designed the query
  around wasn't load-bearing — the query leaked enough literal tokens to be
  solvable lexically. **A "semantic" test query that shares content words with
  the target isn't testing semantics.** Gold-set design bug, not a retriever result.
- **Was hybrid ever worse than an arm? Yes, twice.**
  - `"My van's tracker stopped showing up"` — vector **3**, bm25 **MISS**,
    hybrid **4**. Fusing a wrong arm with a right one *demoted* the answer.
  - `"Why do I get alerted every time my drivers pause for lunch?"` — vector
    **1**, bm25 **3**, hybrid **2**.

  Mechanism: **RRF has no notion of arm confidence.** It rewards agreement, and
  when one arm is authoritative and the other is noise for that query, agreement
  is the wrong signal — BM25's confident-but-irrelevant top hits earn real RRF
  credit and push the correct chunk down. That is the price of the trick that
  makes RRF attractive (throwing away magnitudes to dodge calibration): you also
  throw away the information that would tell you which arm to trust here.
  Hybrid's +0.008 MRR is 2 wins (URL 3→2, billing 2→1) minus 2 losses, i.e. noise.

### What the reranker cost
- p50 latency multiple vs. baseline: **204.9×** (83.5 ms → **17.1 seconds per query**).
- MRR gain over hybrid: **+0.108** (0.825 → 0.933). Over vector: **+0.117**.
- What it actually did: took 8 of 10 queries to rank 1 → **9 of 10**. It fixed
  every query hybrid had left mis-ranked, including recovering the URL query to
  rank 1 and un-doing hybrid's lunch-alert regression. The one it couldn't fix is
  `"My van's tracker stopped showing up"` (still rank 3) — **and that's the
  structural lesson: a reranker can only reorder the candidate set it was
  handed.** Its first stage (hybrid, fetch_k=6) put that chunk low, so the LLM's
  ceiling on that query was set before it ran.
- **Verdict: correct result, unshippable implementation.** 17s p50 is not a
  latency cost, it's a different product. But the number indicts my *reranker*,
  not *reranking*: 6 sequential generate calls to an 8B model ≈ 2.8s each, on a
  4GB-VRAM GPU that has to split an 8B model with CPU (Day 1's wall). A real
  cross-encoder (bge-reranker-base, Cohere Rerank) scores a (query, passage) pair
  in one small forward pass — tens of ms for all 6, and they batch. **The honest
  claim is "pointwise LLM reranking on this rig costs 205×", not "reranking is
  expensive."** Reporting the second would be the wrong lesson learned from a
  right measurement.
- **Did the LLM give lots of tied scores? Can't tell from this run** — the tables
  print ranks, not the raw 0–10 scores, and `hit.parts["llm"]` never reaches
  stdout. Indirect evidence says it *was* discriminating (it moved ranks on 3 of
  10 queries and never shuffled a rank-1 away), but that's inference, not
  measurement. **Harness gap: log the per-candidate LLM scores.** If it rates
  everything 7, the stable sort silently degrades to first-stage order and I'd be
  paying 17s for the identity function — and I currently have no way to see that.

### Did better retrieval change the ANSWER? (Part B)
Same query (`"Where do I change the idle threshold, and what is it by default?"`),
same prompt, same model; only the retriever differs.

- **vector answer:** *"According to [1], you can change the idle threshold in
  Console → Fleet → Settings → Idle threshold. The default idle threshold is 10
  minutes."*
- **hybrid answer:** **byte-identical.**
- **Did the metric improvement show up in user-visible output? No — and the way
  it failed to is the finding.** The two retrievers returned *different* context:
  they share only 2 of 4 chunks (vector: `troubleshooting#2, troubleshooting#3,
  pricing#2, pricing#1`; hybrid: `troubleshooting#2, pricing#2, overview#0,
  overview#2`). **3 of 4 slots changed and the answer didn't move one character**,
  because both put the answer-bearing chunk at rank 1 and the grounded prompt
  ignored the rest. Ranks 2–4 were ballast on this query.

  So: MRR gains *below the top slot* are invisible to the user — right up until
  someone trims k to save tokens, or the answer needs two chunks (Day 5 Break #2
  again). MRR isn't measuring today's answer quality; it's measuring **how much
  margin you have before a config change breaks you.** That's a real thing to
  buy, but I should say that's what I bought rather than implying the answers got
  better.

### Two things the run taught me about my own harness
1. **"0.9× latency" is an artifact, and my table printed it with a straight
   face.** Hybrid p50 (74.5 ms) came out *below* vector p50 (83.5 ms) — but
   `HybridRetriever` calls `VectorRetriever` and *then* does BM25 and fusion, so
   it cannot physically be faster. The 9 ms gap is noise on an ~80 ms LAN
   round-trip whose cost is dominated by the embed call to Ollama (hybrid's p95 is
   *higher*: 261 vs 190). **A benchmark that reports a speedup the call graph
   forbids is telling you your n is too small**, and 10 samples with no repeats is
   too small. Fix: repeat each query several times and report a median of medians,
   or at minimum don't print a delta narrower than the run-to-run spread.
2. **The gold set is the experiment.** Both bugs that mattered today were in the
   gold set, not the retrievers — the `"30 seconds"` ambiguity caught pre-run by
   `sanity_check_gold()`, and the `semantic`-tagged query that was solvable
   lexically, caught only by the results disagreeing with my prediction. Writing
   the prediction down is what turned the second one from an invisible flaw into a
   finding.

---

## Answer bank

**"You added hybrid search. How do you know it helped?"**
**It didn't, and I can show you the number.** On a 10-query labelled gold set at
k=4: Recall went 1.00 → 1.00, MRR 0.817 → 0.825, p50 84 ms → 75 ms (a difference
inside my measurement noise). Hybrid won two queries and lost two — it pulled a
hostname lookup from rank 3 to 2 and a billing question from 2 to 1, and it
*demoted* two paraphrase queries because RRF gave the lexical arm's confident
wrong hits real credit. The reason there was nothing to win: **my first stage was
already at Recall@4 = 1.00**, so I'd built a recall fix for a corpus with no
recall problem. What I'd actually ship from this is not the retriever — it's the
harness, because it's what stopped me shipping a change that only added a code
path. And I'd say clearly that 10 queries over 10 chunks is too small a corpus to
detect the effect hybrid exists for; the next step is more docs and a bigger gold
set, not more fusion.

**"When would you NOT use a reranker?"**
**When the first stage is already at Recall@k ≈ 1.0** — a reranker can only
reorder chunks the model was going to read anyway. It buys precision, not recall,
and **it cannot fix a chunk the first stage never fetched**, so if recall is the
problem it's the wrong tool at any price. I have both halves of that from one run:
my reranker took MRR 0.825 → 0.933 (8/10 → 9/10 answers at rank 1) and cost
**205× p50 latency, 17 seconds a query** — and the single query it *couldn't* fix
was the one its first stage had already buried at rank 4. Also: don't use a
generative LLM pointwise as your reranker. My 17s is 6 sequential 8B calls on a
4GB GPU; a cross-encoder does the same job in one batched forward pass. The
measurement indicts my implementation, not the technique.

**"Vector search is semantic — why would you ever want keyword matching?"**
Because embeddings are **lossy compression, and rare literal tokens are the first
thing they throw away.** Concretely from my run: *"What URL is the Beacon web
console at?"* — vector put the chunk containing `console.beacon.aldritch.example`
at **rank 3**; BM25 put it at **rank 1**, in 0.1 ms. A hostname has almost no
distributional meaning, so cosine has nothing to grip. In enterprise corpora
(error codes, SKUs, config keys, file paths, ticket ids) that's most of what users
actually type. Caveat I'd volunteer: on my 10-chunk corpus vector still won three
of four identifier queries at rank 1, because there was nothing for a smeared
embedding to collide with. **The lexical advantage scales with corpus size and
near-duplicate density** — which is why "we need hybrid" is a claim about your
corpus, not about retrieval in general.

**"How big should k be?"**
Day 5 Break #2 answered the floor (too small drops half the answer). Today added
the ceiling — and something sharper: **the ranking of my retrievers flips with k.**
Same run, recomputed: at k=2 hybrid beats vector 0.90 vs 0.80; at k=3 vector beats
hybrid 1.00 vs 0.90; at k=4 they tie at 1.00. So "Recall@k improved" is not a
claim until k is pinned, and picking the flattering k is the easiest honest-looking
lie in this work. That's what MRR is doing next to it — it integrates over every k
at once and says the two are a wash. Practically: set k from the recall curve
(mine flattens at k=3–4), then watch MRR as the early warning, because a correct
chunk sitting at rank 4 survives k=4 and dies the day someone trims k to save
tokens. And k has a real cost on the other side — Part B showed 3 of 4 context
slots changing with **zero** change to the answer, i.e. I was paying tokens for
chunks the model ignored.

## Would I ship this?

- **hybrid — no, not on this evidence.** +0.008 MRR is inside the noise, it caused
  two regressions, and it adds a second index to keep in sync with the first (an
  operational cost Day 5's stale-index break already showed me how to get bitten
  by). I'd keep it behind a flag and re-run the harness once the corpus is big
  enough for the lexical failure mode to actually appear.
- **rerank — the result yes, this implementation no.** 17 s/query is not a
  latency budget. The +0.117 MRR says the *precision* stage is where the gain
  lives, so the next move is a real cross-encoder, then re-measure. If that lands
  the same MRR at <100 ms, ship it.
- **The thing that's genuinely worth keeping is `evaluation.py`** — gold set,
  sanity check, comparison/per-query/disagreement tables. It's the only artifact
  today that would have caught me shipping a no-op, and it's the skeleton Day 7
  builds the answer-level harness on.

## Forward link to Day 7
Today measured **retrieval** (did the right chunk arrive?). Day 7 measures **answers** (was the response right?) with a 20-question gold set and an LLM judge. Today's harness is the skeleton: `GoldQuery`, `evaluate()`, the comparison table. Day 7 swaps substring-grading for a judge and adds the answer-level number — *"we went from 60% to 85%"*. Note the two can disagree: perfect retrieval with a wrong answer is a generation bug (Day 5's territory), and that's exactly the split the two harnesses together let me prove.
