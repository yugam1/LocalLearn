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

Run it, read the output, then edit SENTENCES / QUERY and run again.
Nothing here is a black box — open every function.
"""
import os
import sys
import requests
import numpy as np


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


# Load .env from the repo root regardless of where you invoke the script from.
load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))

# Config-driven endpoint (good FDE habit). Set OLLAMA_URL in .env (or export it);
# defaults to localhost — correct on the ASUS, override to the ASUS IP on the Mac.
OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
EMBED_MODEL = "nomic-embed-text"

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
    
    # TODO(you): add 3-5 sentences designed to fool the embedder —
    #   negations, antonyms in the same topic, same word / different meaning,
    #   different words / same meaning. Predict the score BEFORE you run.
]

# The query you're "searching" for. Change this and re-run to feel retrieval.
QUERY = "How can I recover access to my account?"


def embed(text: str) -> np.ndarray:
    """One sentence -> one vector. This is the only 'AI' call in the file."""
    resp = requests.post(
        f"{OLLAMA_URL}/api/embeddings",
        json={"model": EMBED_MODEL, "prompt": text},
        timeout=60,
    )
    resp.raise_for_status()
    return np.array(resp.json()["embedding"], dtype=np.float32)


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    """
    Cosine similarity = how aligned two vectors point, ignoring length.
      1.0 = identical direction, 0 = unrelated, -1 = opposite.
    This is literally what a vector DB computes for you at scale. No magic.
    """
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def main() -> None:
    # Sanity: is Ollama reachable and is the model pulled?
    try:
        tags = requests.get(f"{OLLAMA_URL}/api/tags", timeout=10).json()
    except Exception as e:
        sys.exit(f"Can't reach Ollama at {OLLAMA_URL} — is it running? ({e})")
    names = [m["name"] for m in tags.get("models", [])]
    if not any(n.startswith(EMBED_MODEL) for n in names):
        sys.exit(f"Model '{EMBED_MODEL}' not pulled. Run: ollama pull {EMBED_MODEL}")

    print(f"Embedding {len(SENTENCES)} sentences with {EMBED_MODEL} @ {OLLAMA_URL}\n")
    vecs = [embed(s) for s in SENTENCES]
    print(f"Vector dimensionality: {len(vecs[0])}  "
          f"(each sentence is now a point in {len(vecs[0])}-D space)\n")

    # ── Part A: every pair, most-similar first. Scan for surprises. ──
    print("=" * 70)
    print("PAIRWISE SIMILARITY (highest first) — hunt for the traps")
    print("=" * 70)
    pairs = []
    for i in range(len(SENTENCES)):
        for j in range(i + 1, len(SENTENCES)):
            pairs.append((cosine(vecs[i], vecs[j]), i, j))
    for score, i, j in sorted(pairs, reverse=True):
        print(f"{score:.3f}  | {SENTENCES[i][:38]:<40} <-> {SENTENCES[j][:38]}")

    # ── Part B: semantic search — rank the corpus against QUERY. ──
    print("\n" + "=" * 70)
    print(f"SEARCH RESULTS for query: {QUERY!r}")
    print("=" * 70)
    qv = embed(QUERY)
    ranked = sorted(
        ((cosine(qv, v), s) for v, s in zip(vecs, SENTENCES)), reverse=True
    )
    for rank, (score, s) in enumerate(ranked, 1):
        print(f"{rank:>2}. {score:.3f}  {s}")

    print("\n--- Now go fill in notes/day2.md from what you see above. ---")


if __name__ == "__main__":
    main()
