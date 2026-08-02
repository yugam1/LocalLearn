# Day 3 notes — real vector DB (Qdrant) vs. brute-force numpy

> Goal: prove Qdrant returns the SAME ranking as yesterday's hand-rolled cosine
> loop, then articulate what the DB *actually* buys you (hint: not correctness).
> Fill this in from running `scripts/day3_qdrant.py` against the ASUS.

## Setup done
- [ ] Qdrant container up on the ASUS (`docker ps` shows `qdrant/qdrant`, port 6333)
- [ ] `QDRANT_URL` reachable from the Mac (`curl $QDRANT_URL/collections`)
- [ ] Ran `day3_qdrant.py` client→server (embeddings on ASUS, DB on ASUS)

## Part A — does the DB agree with brute force?
- Same top-5 ordering as numpy? **[yes / no]**
- Max score gap (Qdrant Cosine vs numpy cosine): **[fill]** (expect ~0.000 — same metric)
- Takeaway: at this scale Qdrant is just cosine-with-an-index. Same math, same
  answers. The DB did NOT improve retrieval quality — the Day 2 antonym/negation
  blind spot is still there, now stored in a database.

## Part B — what did the DB actually buy me?
- Latency at N=13 — Qdrant: **[fill] ms** | numpy loop: **[fill] ms**
- Which was faster here, and why? **[fill]** (expect numpy — index is overhead at tiny N)
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
[fill — trick question: the *same* thing. It indexes it for scale, doesn't change it.]

**2. When is the DB worth it vs. a numpy loop?**
[fill — N large enough that O(N)/query hurts, OR you need persistence/filtering/concurrency.]

**3. Forward link to Day 4 (RAG):**
[fill — this collection becomes the retrieval backend; top-k from here feeds the prompt.]
