#!/usr/bin/env python3
"""
Day 3 — Stand up a real vector DB (Qdrant) and prove it's not magic.

Yesterday you WERE the vector DB: you held every vector in a Python list and ran
a cosine loop by hand. Today you hand that job to Qdrant and ask one blunt
question: does the database return the SAME ranking my numpy loop does?

If yes (it will, at this scale), then the DB didn't buy you *better answers* — it
bought you speed, persistence, and metadata filtering at scale. Knowing exactly
what a tool does and does NOT buy you is the whole FDE game.

Note the split this file demonstrates: VectorStore.load()/search() work at the
raw-vector level, so we keep our OWN `vecs` list to run the brute-force numpy
check against Qdrant's answer. Day 4/5 will drive the same class at the text
level instead. Every run tees to result/day3.txt.
"""
import os
import time

# Import feature MODULES (not loose names) so every call names its source file:
# config.Settings lives in config.py, vectorstore.VectorStore in vectorstore.py, etc.
from locallearn import config, ollama, vectorstore, similarity, runlog

settings = config.Settings.from_env()
client = ollama.OllamaClient(settings.ollama_url, settings.embed_model)
COLLECTION = "day3_sentences"

# Same corpus as Day 2 so you can compare behaviour directly. The traps carry
# over: antonyms/negation that fooled cosine yesterday will fool Qdrant too —
# because Qdrant IS cosine, just indexed. The DB doesn't fix retrieval quality.
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
]

QUERY = "How can I recover access to my account?"
TOP_K = 5


def main() -> None:
    store = vectorstore.VectorStore.connect(settings.qdrant_url, COLLECTION)  # no embedder: vector-level

    print(f"Embedding {len(SENTENCES)} sentences with {settings.embed_model} "
          f"@ {settings.ollama_url}")
    vecs = [client.embed(s) for s in SENTENCES]
    dim = store.load(vecs, [{"text": s} for s in SENTENCES])
    print(f"Vector dim: {dim}. Loaded into Qdrant @ {settings.qdrant_url}\n")

    qv = client.embed(QUERY)

    # ── Qdrant search (indexed / ANN) ──
    t0 = time.perf_counter()
    hits = store.search(qv, TOP_K)
    db_ms = (time.perf_counter() - t0) * 1000
    db_hits = [(h.score, h.payload["text"]) for h in hits]

    # ── Brute-force numpy (yesterday's exact method) = ground truth ──
    t0 = time.perf_counter()
    bf = sorted(
        ((similarity.cosine(qv, v), s) for v, s in zip(vecs, SENTENCES)), reverse=True
    )[:TOP_K]
    bf_ms = (time.perf_counter() - t0) * 1000

    print("=" * 72)
    print(f"QUERY: {QUERY!r}   (top {TOP_K})")
    print("=" * 72)
    print(f"{'#':>2}  {'QDRANT (indexed)':<40}  {'NUMPY (brute force)':<40}")
    print("-" * 72)
    for rank in range(TOP_K):
        ds, dt = db_hits[rank]
        bs, bt = bf[rank]
        flag = "" if dt == bt else "   <-- ORDER DIFFERS"
        print(f"{rank+1:>2}  {ds:.3f} {dt[:33]:<34}  {bs:.3f} {bt[:33]:<34}{flag}")

    # ── The verdict: same ranking? same scores? ──
    same_order = [t for _, t in db_hits] == [t for _, t in bf]
    max_score_gap = max(abs(db_hits[r][0] - bf[r][0]) for r in range(TOP_K))
    print("\n" + "=" * 72)
    print(f"Same top-{TOP_K} ordering as brute force? {same_order}")
    print(f"Max score difference (Qdrant vs numpy cosine): {max_score_gap:.5f}")
    print(f"Latency — Qdrant: {db_ms:.2f} ms   |   numpy loop: {bf_ms:.2f} ms")
    print("=" * 72)
    print(
        "\nRead that carefully: at 13 vectors the numpy loop is likely FASTER —\n"
        "the DB's index is pure overhead here. The DB wins only when N is huge\n"
        "(then brute force is O(N) per query and Qdrant's HNSW is ~O(log N)).\n"
        "That crossover — plus persistence + payload filtering — is what you\n"
        "actually bought. Now go write notes/day3.md.\n"
    )


if __name__ == "__main__":
    with runlog.tee_stdout(os.path.join(settings.result_dir, "day3.txt")):
        main()
