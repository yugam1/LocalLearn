# Day 3 notes — real vector DB (Qdrant) vs. brute-force numpy

> Goal: prove Qdrant returns the SAME ranking as yesterday's hand-rolled cosine
> loop, then articulate what the DB *actually* buys you (hint: not correctness).
> Filled in from running `scripts/day3_qdrant.py` against the ASUS.
> Run env: Ollama + Qdrant both @ `192.168.1.19` (ASUS), Mac as client.
> Query used this run: `"How can I recover access to my account?"`, top 5.

## Setup done
- [x] Qdrant container up on the ASUS (`docker ps` shows `qdrant/qdrant`, port 6333)
- [x] `QDRANT_URL` reachable from the Mac (`curl $QDRANT_URL/collections`)
- [x] Ran `day3_qdrant.py` client→server (embeddings on ASUS, DB on ASUS) — 13 sentences, 768-D

## Part A — does the DB agree with brute force?
- Same top-5 ordering as numpy? **yes** (`Same top-5 ordering? True`)
- Max score gap (Qdrant Cosine vs numpy cosine): **0.00000** (identical — same metric)
- Top-5 both returned, in the same order:
  1. `0.730` How do I permanently delete my account?
  2. `0.714` How do I reset my password?
  3. `0.655` I forgot my login credentials and can't get in.
  4. `0.539` I visited the Taj Mahal in India last summer.
  5. `0.504` The stock price fell sharply this morning.
- Takeaway: at this scale Qdrant is just cosine-with-an-index. Same math, same
  answers. The DB did NOT improve retrieval quality — it **faithfully reproduced
  the exact Day 2 danger**: for a user trying to *recover* access, the #1 hit is
  "permanently delete my account" (0.730), *above* "reset my password" (0.714)
  and "forgot my login credentials" (0.655). The most destructive possible doc,
  ranked first, purely on topic/keyword overlap — opposite intent and all. The
  bug didn't get fixed by moving into a real database; it got **persisted** into
  one. That's the whole point of today.

## Part B — what did the DB actually buy me?
- Latency at N=13 — Qdrant: **11.21 ms** | numpy loop: **0.20 ms**
- Which was faster here, and why? **numpy, by ~56×** — at 13 vectors the HNSW
  index + network round-trip is pure overhead. Brute force is a single tight
  in-memory dot-product loop; there's nothing for an index to save yet.
- So the value of the DB is:
  1. **Speed at scale** — brute force is O(N)/query; HNSW is ~O(log N). Crossover is at big N, not 13.
  2. **Persistence** — vectors survive a restart; no re-embedding every run.
  3. **Payload + filtering** — store metadata alongside vectors, filter by it at query time.
  4. **Concurrency / network** — many clients hit one indexed store over HTTP.

## Part C — the catch nobody mentions (ANN ≠ exact)
- Qdrant's HNSW index is **approximate** nearest neighbour. At 13 vectors it's
  effectively exact (matched numpy). At millions of vectors it trades a little
  **recall** for a lot of **speed** — it can miss the true top-k.
- Why this matters for RAG: an approximate index is a *second*, silent source of
  retrieval miss, stacked on top of the Day 2 embedding blind spot. "The right
  chunk exists in the DB but the index didn't return it" is a real failure mode.
- [ ] (Optional stretch) Skim Qdrant's HNSW params (`ef`, `m`) — the recall/speed knobs.

## The three answers (in my own words)
**1. What does a vector DB compute that my numpy loop didn't?**
Nothing — trick question. It computes the *exact same* cosine similarity (max
score gap 0.00000, identical top-5). What it adds is an HNSW *index* over those
vectors so the search scales, plus storage and payloads. It changes the *cost*
of the lookup, not the *answer*.

**2. When is the DB worth it vs. a numpy loop?**
Not at N=13 — here numpy won by ~56× (0.20 ms vs 11.21 ms). The DB pays off once
N is large enough that O(N)/query brute force hurts (HNSW is ~O(log N), so
there's a crossover at big N), OR when I need what a loop can't give me:
persistence across restarts (no re-embedding every run), metadata/payload
filtering at query time, and concurrent network clients hitting one store.

**3. Forward link to Day 4 (RAG):**
This `day3_sentences` collection becomes the retrieval backend. Day 4 takes a
user question, embeds it, pulls top-k from Qdrant, and stuffs those chunks into
the LLM prompt as context. Which means both blind spots I proved today —
opposite-intent false positives (the "delete beats recover" hit) *and* (at
scale) approximate-index recall misses — land directly in what the model gets to
read. If the top chunk is the *delete-account* doc, the LLM will confidently
answer from it. Retrieval quality caps RAG quality: the model can only be as
right as the chunks retrieval hands it.
