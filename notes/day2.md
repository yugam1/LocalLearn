# Day 2 notes — embeddings by hand

> Fill from what you *observe* running `scripts/day2_embeddings.py`. The failure hunt (Part C) is the deliverable.

## Setup done
- [ ] `nomic-embed-text` pulled (`ollama pull nomic-embed-text`)
- [ ] venv + `pip install requests numpy`
- [ ] Poked the raw endpoint with curl first (saw the raw vector) — see below

## Part 0 — poke it raw (before the script)
- Vector dimensionality (length of the array): ___
- First few numbers looked like: ___
- Reaction to "a sentence is now 768 floats": ___

## Part A — pairwise similarity
- Highest-scoring pair overall: ___  (score ___)
- Did the "obvious" synonym pair actually win, or did something else? ___
- "river bank" vs "bank downtown" (same word, different meaning) score: ___

## Part B — semantic search (query ranking)
- Query used: ___
- Top result + score: ___
- Did the *right* answer rank #1? If not, what beat it and why do you think? ___

## Part C — THE FAILURE HUNT (the deliverable)

**False positive** — a pair that MEANS the opposite but SCORES high:
- Pair: ___
- Score: ___
- Why this is dangerous in RAG: ___

**False negative** — a pair that MEANS the same but SCORES low:
- Pair: ___
- Score: ___
- Why this causes a retrieval MISS in RAG: ___

**Your prediction vs reality** — one pair where you guessed the score wrong:
- Predicted: ___  Actual: ___  What that taught you: ___

---

## The three answers (in your own words)

**1. What is an embedding, concretely — what did the model turn a sentence into, and what does "similar" mean geometrically?**


**2. Why does "high cosine similarity" NOT mean "same meaning"? Give your own example from the run.**


**3. Connect it forward: when you build RAG (Day 4) and it retrieves a wrong-but-similar chunk, which failure from today explains it?**

