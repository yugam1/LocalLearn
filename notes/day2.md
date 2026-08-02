# Day 2 notes — embeddings by hand

> Filled from running `scripts/day2_embeddings.py` against the ASUS (`nomic-embed-text`, 13 sentences). The failure hunt (Part C) is the deliverable.

## Setup done
- [x] `nomic-embed-text` pulled on the ASUS
- [x] venv + `requests` + `numpy` (on the Mac; ran client→server against `192.168.1.19`)
- [x] First real client/server run — dev on Mac, embeddings computed on the ASUS over LAN (a day early)

## Part 0 — the raw vector
- Vector dimensionality: **768** — every sentence, short or long, becomes exactly 768 floats.
- Takeaway: "meaning" is compressed to a fixed-size point in 768-D space regardless of input length. That fixed size is what lets you compare any two sentences with one dot product.

## Part A — pairwise similarity (what actually scored high)
- **Highest-scoring pair in the whole matrix: `stock price ROSE` ↔ `stock price FELL` = 0.856.** Opposite meaning, *highest* similarity of all 78 pairs.
- 2nd highest: `I love this restaurant, amazing` ↔ `I do NOT love this restaurant, terrible` = **0.773**. Again opposite sentiment, near the top.
- 3rd highest: `reset my password?` ↔ `permanently delete my account?` = **0.742**. Opposite *intent* (recover vs destroy), scored higher than genuine synonyms.
- Same-word/different-meaning ("bank"): `river bank` ↔ `bank downtown` = **0.497** (mid-low — the model actually distinguished them, see prediction surprise below).
- Taj trap: `visited the Taj Mahal` (monument) ↔ `Taj Mahal is a mausoleum` (monument) = **0.648** (correct, same topic). But `visited the Taj Mahal` (monument) ↔ `food in Hotel Taj Mumbai` (a *hotel*) = **0.631** — almost as high, despite being a different entity. The shared proper noun "Taj" nearly fooled it.

## Part B — semantic search (the dangerous one)
- Query: `"How can I recover access to my account?"`
- **Top result: `How do I permanently delete my account?` (0.730)** — ranked #1.
- `How do I reset my password?` (0.714) came **#2**, and `I forgot my login credentials` (0.655) came **#3**.
- **This is a real, dangerous RAG bug in miniature:** a user trying to *recover* access gets the *delete-your-account* doc as the top hit — the most harmful possible answer, ranked first, purely because "account" + question-shape dominate the vector over the actual intent.

## Part C — THE FAILURE HUNT (the deliverable)

**False positive** — means the opposite / wrong intent but scores high:
- Pair: `reset my password?` ↔ `permanently delete my account?`
- Score: **0.742** (and in search, "delete account" beat "reset password" for a *recovery* query, 0.730 > 0.714)
- Why dangerous in RAG: opposite-intent, potentially destructive content outranks the correct doc. Cosine similarity measures topical/lexical overlap, not intent or polarity — so "do X" and "undo X" look almost identical. The antonym pair `rose`↔`fell` (0.856) is the same blind spot in its purest form.

**False negative** — means the same but scores lower than the false positive above:
- Pair: `reset my password?` ↔ `I forgot my login credentials and can't get in` (same real user intent: locked out, need access)
- Score: **0.677** — *lower* than the opposite-intent `reset ↔ delete` pair (0.742)
- Why it causes a retrieval MISS: the semantically-correct match (no shared keywords) scores **below** a semantically-wrong match (shared keywords). Top-k retrieval would surface the wrong doc and could push the right one out of the window entirely.

**Prediction vs reality** — where I guessed wrong:
- Predicted: the "bank" polysemy pair (`river bank` ↔ `bank downtown`) would be a strong false positive — same word, different meaning.
- Actual: only **0.497** — the model handled word-sense disambiguation fine from context.
- What it taught me: modern embedders are *good* at same-word/different-sense (context disambiguates), but *bad* at negation and antonyms, because those sentences are lexically and topically near-identical and only differ by a polarity word the embedding barely weights. The real trap isn't ambiguous words — it's opposite meanings dressed in identical vocabulary.

---

## The three answers (in my own words)

**1. What is an embedding, concretely?**
The model turned each sentence into a fixed 768-dimensional vector — a single point in 768-D space. "Similar" means the two points *point in nearly the same direction* (cosine near 1.0), independent of the sentence's length or magnitude. Semantic search is literally just: embed the query, find the corpus points whose direction is closest.

**2. Why does high cosine ≠ same meaning?**
Because cosine rewards shared topic and vocabulary, not intent or polarity. `stock rose` vs `stock fell` scored 0.856 — the single highest pair — even though they're opposites, because every word except one is shared and the topic is identical. The embedder under-weights the one word that flips the meaning. So a high score means "about the same thing," not "says the same thing."

**3. Forward link to RAG (Day 4):**
When RAG retrieves a wrong-but-similar chunk, it'll be the **false-positive** pattern from today: a chunk that shares the query's topic/keywords but has the opposite intent (like "delete account" surfacing for a "recover account" query). And when RAG *misses* the chunk that actually answers the question, it'll be the **false-negative** pattern: the right chunk phrased with different words scored below a keyword-matching decoy. Both are the exact behaviors I just reproduced by hand with 13 sentences — I'll recognize them instantly at 10,000 chunks.
