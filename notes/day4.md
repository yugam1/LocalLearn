# Day 4 notes — full RAG pipeline (ingest → chunk → embed → store → retrieve → answer)

> Goal: assemble Days 2–3 into the real FDE deliverable — answer questions from a
> customer's OWN docs, with citations, and be able to point at exactly which
> chunk each answer came from. Corpus is a FICTIONAL product (`../docs`, "Beacon")
> on purpose: if the model can answer without retrieval, the docs weren't needed.
> Fill this in from running `scripts/day4_rag.py` against the ASUS.

## Setup done
- [ ] Qdrant + Ollama up on the ASUS, reachable from the Mac (`$QDRANT_URL`, `$OLLAMA_URL`)
- [ ] Generation model present on the ASUS (`GEN_MODEL`, default `llama3.1:8b` — `ollama list`)
- [ ] Ran `day4_rag.py` client→server (embeddings + generation on ASUS, DB on ASUS)

## Pipeline shape (what actually ran)
- Docs ingested: **[fill — count + names]** → chunks: **[fill count]** (120-word windows, 25 overlap)
- Embedding model: `nomic-embed-text` (768-D) · Generation model: **[fill — GEN_MODEL used]**
- Collection: `day4_beacon_docs`, Cosine distance (same metric as Days 2–3)

## Part A — the happy path (answer is in the docs)
- Query: `"Why is my Beacon Tag showing as offline and how do I fix it?"`
- Retrieved top-4 chunks (source#chunk + score):
  1. **[fill]**
  2. **[fill]**
  3. **[fill]**
  4. **[fill]**
- Did the top chunk actually contain the answer? **[yes / no]**
- Answer the model gave (paste or summarise): **[fill]**
- Did it cite `[n]` correctly, and did the citation point at the right source? **[fill]**
- Takeaway: a *right* answer here isn't the model being smart — it's retrieval
  putting the right chunk in front of it. Generation just reworded chunk **[n]**.

## Part B — retrieval vs. generation (the seam I'll live in on Day 5)
- The script prints chunks BEFORE the answer on purpose. Note what each half did:
  - Retrieval quality (did the right chunk come back at all, and near the top?): **[fill]**
  - Generation quality (given those chunks, was the prose faithful to them?): **[fill]**
- One-line rule I want to keep: "When the answer is wrong, first ask *which half*
  failed." **[reword in my own words after seeing it run]**

## Part C — grounding test (out-of-corpus query — Day 5 hallucination preview)
- Re-ran with a query whose answer is NOT in the docs (e.g. `"Does Beacon
  integrate with Salesforce?"`).
- What got retrieved (there's always *something* — cosine always returns top-k): **[fill]**
- Did the model say **"I don't know based on the provided documents"** or did it
  **invent** an answer from the loosely-related chunks? **[fill]**
- Why this matters: top-k retrieval *never returns nothing* — it hands over the
  4 least-bad chunks no matter how irrelevant. The only thing standing between
  that and a confident hallucination is the grounding instruction. **[did it hold?]**

## The three answers (in my own words)
**1. Walk me through the RAG system I built (the interview opener).**
[fill — ingest N docs → chunk into overlapping windows → embed each with
nomic-embed-text → store 768-D vectors in Qdrant with source/chunk payload →
embed the query → top-k cosine retrieve → stuff numbered chunks into a grounded
prompt → LLM answers citing [n]. Name the tradeoff at each stage.]

**2. The answer is wrong — how do I debug it?**
[fill — check the RETRIEVED chunks first. Right chunk absent = retrieval bug
(chunking / embedding / k too small). Right chunk present but answer wrong =
generation bug (prompt / model ignored context). This split is the whole skill.]

**3. Forward link to Day 5 (break RAG four ways):**
[fill — chunking (split a table/step-list mid-way → wrong answer), retrieval
(question needing 2 chunks, k returns 1), hallucination (out-of-corpus query +
does the guardrail hold?), stale index (edit a doc, don't re-embed, get the old
answer). Each maps to a stage in the pipeline I just built.]
