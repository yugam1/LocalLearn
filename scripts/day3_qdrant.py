#!/usr/bin/env python3
"""
Day 3 — Stand up a real vector DB (Qdrant) and prove it's not magic.

Yesterday you WERE the vector DB: you held every vector in a Python list and ran
a cosine loop by hand. Today you hand that job to Qdrant and ask one blunt
question: does the database return the SAME ranking my numpy loop does?

If yes (it will, at this scale), then the DB didn't buy you *better answers* —
it bought you speed, persistence, and metadata filtering at scale. Knowing
exactly what a tool does and does NOT buy you is the whole FDE game.

We use the official `qdrant-client` (what real projects use). It's a thin wrapper
over Qdrant's REST/gRPC API — the calls below map 1:1 to HTTP endpoints:
    client.create_collection(...)   -> PUT  /collections/{name}
    client.upsert(...)              -> PUT  /collections/{name}/points
    client.query_points(...)        -> POST /collections/{name}/points/query
Nothing hidden — the library just saves you hand-building JSON.
"""
import os
import sys
import time
import requests
import numpy as np
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct


def load_dotenv(path=".env"):
    """Tiny stdlib .env loader — no external dep. Real env vars win over the file."""
    if not os.path.exists(path):
        return
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            os.environ.setdefault(key.strip(), val.strip())


load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
QDRANT_URL = os.environ.get("QDRANT_URL", "http://localhost:6333")
EMBED_MODEL = "nomic-embed-text"
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


# ── Embedding: identical to Day 2. One sentence -> one 768-D vector. ──────────
def embed(text: str) -> np.ndarray:
    resp = requests.post(
        f"{OLLAMA_URL}/api/embeddings",
        json={"model": EMBED_MODEL, "prompt": text},
        timeout=60,
    )
    resp.raise_for_status()
    return np.array(resp.json()["embedding"], dtype=np.float32)


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    """The exact same 'by hand' metric from Day 2 — our ground truth today."""
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def connect() -> QdrantClient:
    """Connect + fail loudly with the fix if the container isn't up."""
    try:
        client = QdrantClient(url=QDRANT_URL, timeout=10)
        client.get_collections()  # forces a real round-trip
        return client
    except Exception as e:
        sys.exit(
            f"Can't reach Qdrant at {QDRANT_URL} — is the container up? ({e})\n"
            f"On the ASUS run:\n"
            f"  docker run -d --name qdrant -p 6333:6333 -p 6334:6334 \\\n"
            f"    -v $(pwd)/qdrant_storage:/qdrant/storage qdrant/qdrant"
        )


def main() -> None:
    client = connect()

    print(f"Embedding {len(SENTENCES)} sentences with {EMBED_MODEL} @ {OLLAMA_URL}")
    vecs = [embed(s) for s in SENTENCES]
    dim = len(vecs[0])
    print(f"Vector dim: {dim}. Loading into Qdrant @ {QDRANT_URL}\n")

    # Drop + create so re-runs are clean. Cosine distance = same metric as Day 2.
    if client.collection_exists(COLLECTION):
        client.delete_collection(COLLECTION)
    client.create_collection(
        collection_name=COLLECTION,
        vectors_config=VectorParams(size=dim, distance=Distance.COSINE),
    )

    # Load every sentence as a point: id + vector + payload (the text itself).
    client.upsert(
        collection_name=COLLECTION,
        wait=True,
        points=[
            PointStruct(id=i, vector=v.tolist(), payload={"text": SENTENCES[i]})
            for i, v in enumerate(vecs)
        ],
    )

    qv = embed(QUERY)

    # ── Qdrant search (indexed / ANN) ──
    t0 = time.perf_counter()
    hits = client.query_points(
        collection_name=COLLECTION, query=qv.tolist(), limit=TOP_K, with_payload=True
    ).points
    db_ms = (time.perf_counter() - t0) * 1000
    db_hits = [(h.score, h.payload["text"]) for h in hits]

    # ── Brute-force numpy (yesterday's exact method) = ground truth ──
    t0 = time.perf_counter()
    bf = sorted(
        ((cosine(qv, v), s) for v, s in zip(vecs, SENTENCES)), reverse=True
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
    main()
