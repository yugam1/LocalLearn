#!/usr/bin/env python3
"""
Day 2 — Embeddings by hand.

Goal: build the intuition that a vector DB later automates. You will:
  1. Turn sentences into vectors (embeddings) via nomic-embed-text.
  2. Compute cosine similarity yourself (it's ~3 lines of numpy — no magic).
  3. Rank sentences against a query = this IS "semantic search", by hand.
  4. HUNT FOR FAILURES: pairs that SCORE high but MEAN opposite things,
     and pairs that MEAN the same but score low. That gap is why real
     retrieval misfires — the whole point of the day.

Run it, read the output, then edit SENTENCES / QUERY and run again. The plumbing
(embed, cosine, config) lives in the `locallearn` package so this file shows only
the day's actual lesson — but nothing there is a black box; open it and read.
Every run also tees its output to result/day2.txt so you can fill notes/ later.
"""
import os

# Import feature MODULES (not loose names) so every call names its source file:
# config.Settings lives in config.py, similarity.cosine in similarity.py, etc.
from locallearn import config, ollama, similarity, runlog

settings = config.Settings.from_env()
client = ollama.OllamaClient(settings.ollama_url, settings.embed_model)

# ─────────────────────────────────────────────────────────────────────────────
# The corpus. Start with these, then ADD YOUR OWN adversarial pairs (see mission).
# Some of these are traps on purpose — don't assume the obvious pairs win.
# ─────────────────────────────────────────────────────────────────────────────
SENTENCES = [
    "The stock price rose sharply this morning.",
    "The stock price fell sharply this morning.",
    "I love this restaurant, the food is amazing.",
    "I do not love this restaurant, the food is terrible.",
    "How do I reset my password?",
    "I forgot my login credentials and can't get in.",
    "How do I permanently delete my account?",
    "She sat on the river bank watching the water.",
    "He deposited his paycheck at the bank downtown.",
    "The cat slept on the warm windowsill all afternoon.",
    "I visited the Taj Mahal in India last summer.",
    "The Taj Mahal is a famous mausoleum located in Agra, India.",
    "The food in Hotel Taj in Mumbai was delicious and authentic.",
    # TODO(you): add 3-5 sentences designed to fool the embedder —
    #   negations, antonyms in the same topic, same word / different meaning,
    #   different words / same meaning. Predict the score BEFORE you run.
]

# The query you're "searching" for. Change this and re-run to feel retrieval.
QUERY = "Suggest me some good restaurants in Mumbai."


def main() -> None:
    # Sanity: is Ollama reachable and is the embedder pulled? (fails loud w/ fix)
    client.require_model(settings.embed_model)

    print(f"Embedding {len(SENTENCES)} sentences with {settings.embed_model} "
          f"@ {settings.ollama_url}\n")
    vecs = [client.embed(s) for s in SENTENCES]
    print(f"Vector dimensionality: {len(vecs[0])}  "
          f"(each sentence is now a point in {len(vecs[0])}-D space)\n")

    # ── Part A: every pair, most-similar first. Scan for surprises. ──
    print("=" * 70)
    print("PAIRWISE SIMILARITY (highest first) — hunt for the traps")
    print("=" * 70)
    pairs = []
    for i in range(len(SENTENCES)):
        for j in range(i + 1, len(SENTENCES)):
            pairs.append((similarity.cosine(vecs[i], vecs[j]), i, j))
    for score, i, j in sorted(pairs, reverse=True):
        print(f"{score:.3f}  | {SENTENCES[i][:38]:<40} <-> {SENTENCES[j][:38]}")

    # ── Part B: semantic search — rank the corpus against QUERY. ──
    print("\n" + "=" * 70)
    print(f"SEARCH RESULTS for query: {QUERY!r}")
    print("=" * 70)
    qv = client.embed(QUERY)
    ranked = sorted(
        ((similarity.cosine(qv, v), s) for v, s in zip(vecs, SENTENCES)), reverse=True
    )
    for rank, (score, s) in enumerate(ranked, 1):
        print(f"{rank:>2}. {score:.3f}  {s}")

    print("\n--- Now go fill in notes/day2.md from what you see above. ---")


if __name__ == "__main__":
    with runlog.tee_stdout(os.path.join(settings.result_dir, "day2.txt")):
        main()
