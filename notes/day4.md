# Day 4 notes — full RAG pipeline (ingest → chunk → embed → store → retrieve → answer)

> Goal: assemble Days 2–3 into the real FDE deliverable — answer questions from a
> customer's OWN docs, with citations, and be able to point at exactly which
> chunk each answer came from. Corpus is a FICTIONAL product (`../docs`, "Beacon")
> on purpose: if the model can answer without retrieval, the docs weren't needed.
> Fill this in from running `scripts/day4_rag.py` against the ASUS.

## Setup done
- [x] Qdrant + Ollama up on the ASUS, reachable from the Mac (`$QDRANT_URL`, `$OLLAMA_URL`) — hit `192.168.1.19:11434` (Ollama) + `192.168.1.19:6333` (Qdrant)
- [x] Generation model present on the ASUS (`GEN_MODEL`, default `llama3.1:8b` — `ollama list`)
- [x] Ran `day4_rag.py` client→server (embeddings + generation on ASUS, DB on ASUS)

## Pipeline shape (what actually ran)
- Docs ingested: **3** (`overview.md`, `pricing.md`, `troubleshooting.md`) → chunks: **10** (120-word windows, 25 overlap)
- Embedding model: `nomic-embed-text` (768-D, dim confirmed in output) · Generation model: **`llama3.1:8b`** (default; `GEN_MODEL` not overridden)
- Collection: `day4_beacon_docs`, Cosine distance (same metric as Days 2–3)

## Part A — the happy path (answer is in the docs)
- Query: `"Why is my Beacon Tag showing as offline and how do I fix it?"`
- Retrieved top-4 chunks (source#chunk + score):
  1. **0.743 · troubleshooting.md#chunk0** — the "A Tag shows as offline" section itself
  2. **0.643 · troubleshooting.md#chunk3** — webhook retries (loosely related, wrong topic)
  3. **0.626 · troubleshooting.md#chunk2** — power-cycle fix / silent >2h
  4. **0.625 · troubleshooting.md#chunk1** — cellular buffering + blown fuse
- Notable: **all 4 came from `troubleshooting.md`** — retrieval correctly ignored
  `overview.md` and `pricing.md` entirely. The right doc dominated.
- Did the top chunk actually contain the answer? **Yes** — chunk0 is the exact
  "offline" section, and its score (0.743) sits clearly above the pack (next is
  0.643). Clean separation, no ambiguity about the winner.
- Answer the model gave (summarised): listed the four causes (parked indoors /
  no cellular signal / blown OBD-II fuse / deactivated in billing) and the fixes
  (power-cycle via OBD-II 30s, check signal, replace fuse, check plan cap), then
  a "contact support" fallback. All content is faithful — every fact traces to a
  retrieved chunk, **nothing invented**.
- Did it cite `[n]` correctly? **Content yes, indices loose.** It only cited `[1]`
  and `[4]`, but the power-cycle instruction it attributed to `[1]`/`[4]` actually
  lives in `[3]` (chunk2). So the *facts* are grounded but the *pointer* is
  imprecise — the model collapsed several chunks under two citation numbers.
  → flag this: "faithful content, sloppy attribution" is its own failure mode.
- Takeaway: a *right* answer here isn't the model being smart — it's retrieval
  putting chunk0 in front of it at 0.743. Generation just reworded chunks
  **[1]+[3]+[4]** into prose; the "intelligence" was the cosine ranking.

## Part B — retrieval vs. generation (the seam I'll live in on Day 5)
- The script prints chunks BEFORE the answer on purpose. What each half did:
  - Retrieval quality: **strong.** Right chunk (chunk0) came back at rank 1 with a
    clear score gap (0.743 vs 0.643), and all top-4 stayed inside the correct doc.
    Retrieval did its job — the answer was physically in front of the model.
  - Generation quality: **faithful but sloppy on citations.** No hallucination —
    every claim maps to a retrieved chunk — but the `[n]` attribution was wrong in
    places (cited `[1]`/`[4]` for a fact that's in `[3]`). Content grounded,
    provenance smeared.
- One-line rule I want to keep: **"When the answer is wrong, don't argue with the
  prose — read the retrieved chunks first. If the right chunk isn't there, it's a
  retrieval bug; if it is there and the prose is off, it's a generation bug."**
  Corollary from this run: even a *right* answer can have a *wrong citation* —
  grounded ≠ correctly attributed, and those fail independently.

## Part C — grounding test (out-of-corpus query — Day 5 hallucination preview)
- Re-ran with a query whose answer is NOT in the docs: `"Does Beacon integrate
  with Salesforce?"` (captured in `result/day4New.txt`).
- What got retrieved (there's always *something* — cosine always returns top-k):
  1. **0.538 · troubleshooting.md#chunk2** — power-cycle / silent >2h
  2. **0.528 · troubleshooting.md#chunk3** — webhook retries (Pro/Enterprise)
  3. **0.514 · pricing.md#chunk0** — per-active-Tag billing
  4. **0.513 · overview.md#chunk0** — product overview
  Note the tell-tale signature of an out-of-corpus query: **scores are low and
  flat** (0.538 → 0.513, a 0.025 spread) and the top-4 are scattered across
  *all three* docs — the exact opposite of Part A's single-doc dominance and
  clean 0.743-vs-0.643 gap. No chunk is actually about Salesforce; cosine just
  handed over the 4 least-bad.
- Did the model say **"I don't know based on the provided documents"** or did it
  **invent** an answer from the loosely-related chunks? It said, verbatim:
  **"I don't know based on the provided documents."** The guardrail held — no
  invented integration, no hedged half-answer stitched from the noise chunks.
- Why this matters: top-k retrieval *never returns nothing* — it hands over the
  4 least-bad chunks no matter how irrelevant. The only thing standing between
  that and a confident hallucination is the grounding instruction. **It held
  here** — but note it's the *only* thing that did; retrieval gave the model
  four irrelevant chunks and a weaker prompt would've confabulated from them.

## The three answers (in my own words)
**1. Walk me through the RAG system I built (the interview opener).**
I ingested 3 Beacon docs and chunked them into 10 overlapping 120-word windows
(25-word overlap so a fact straddling a boundary still lands whole somewhere).
Each chunk went through `nomic-embed-text` on the ASUS → a 768-D vector, stored
in Qdrant under Cosine distance with a `{source, chunk, text}` payload so every
vector remembers where it came from. At query time I embed the question the same
way, pull the top-4 nearest chunks, number them, and stuff them into a grounded
prompt ("answer ONLY from these sources, cite [n], else say I don't know"). The
LLM (`llama3.1:8b`) writes the answer. Tradeoffs to name: chunk size (too big
dilutes the embedding, too small severs context), k (too small misses multi-chunk
answers, too big adds noise), and the grounding prompt as the only thing between
retrieval and a hallucination.

**2. The answer is wrong — how do I debug it?**
Read the RETRIEVED chunks *before* the prose — that's why the script prints them
first. If the right chunk isn't in the top-k, it's a **retrieval** bug: chunking,
embedding, or k too small. If the right chunk IS there but the answer's off, it's
a **generation** bug: prompt or the model ignoring/mangling context. Today's run
added a third, subtler mode: the answer was faithful but the *citations* were
wrong (attributed a chunk-2 fact to [1]/[4]) — so "grounded" and "correctly
attributed" fail independently, and I check both.

**3. Forward link to Day 5 (break RAG four ways):**
- **Chunking** — split a step-list/table mid-way so the answer lands in no single
  chunk → wrong answer even with perfect retrieval.
- **Retrieval** — ask a question that needs 2 chunks while k returns 1 → half an
  answer.
- **Hallucination** — the out-of-corpus query (Part C, now run): the guardrail
  *held* on the Salesforce query ("I don't know…"). Day 5 stress-tests it —
  weaken the grounding prompt and watch it invent from the 4 least-bad chunks.
- **Stale index** — edit a doc but don't re-embed → confidently returns the old
  answer. Each maps to exactly one stage of the pipeline I built today.
