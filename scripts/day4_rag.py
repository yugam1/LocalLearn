#!/usr/bin/env python3
"""
Day 4 — Full RAG pipeline, end to end, in one file.

This is THE Forward-Deployed deliverable in miniature: a customer hands you a
pile of docs and a fuzzy goal ("make our docs answerable"), and you ship a thing
that answers questions *from their data* with citations. Everything before today
was a piece of this — embeddings (Day 2) and a vector DB (Day 3) — now assembled
into the actual pipeline:

    ingest docs -> chunk -> embed -> store in Qdrant -> retrieve top-k -> answer

Two design choices are deliberate and worth internalising:

1. The corpus (../docs) is a FICTIONAL product ("Beacon"). If it were about real
   public facts, the LLM could answer from its training memory and you'd never
   know whether retrieval actually did anything. Proprietary/made-up data makes
   retrieval the ONLY possible source of truth — so a right answer proves the
   pipeline works, and a wrong one has nowhere to hide.

2. We print the RETRIEVED CHUNKS before the generated answer, always. In Day 5
   you'll deliberately break this thing, and the first debugging question is
   always "was it retrieval or generation?" You can only answer that if you can
   see what came back from the DB *before* the model spun it into prose.

Swap in your own docs any time: drop .md/.txt files in ../docs (or point
DOCS_DIR elsewhere) and re-run. Nothing here is Beacon-specific.
"""
import os
import glob
import textwrap
import requests
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


HERE = os.path.dirname(__file__)
load_dotenv(os.path.join(HERE, "..", ".env"))

OLLAMA_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
QDRANT_URL = os.environ.get("QDRANT_URL", "http://localhost:6333")
EMBED_MODEL = "nomic-embed-text"                              # same embedder as Days 2–3
GEN_MODEL = os.environ.get("GEN_MODEL", "llama3.1:8b")        # the answer-writer
COLLECTION = "day4_beacon_docs"
DOCS_DIR = os.environ.get("DOCS_DIR", os.path.join(HERE, "..", "docs"))

# Chunking knobs. Word-based sliding window with overlap — deliberately simple
# and visible, because in Day 5 you'll turn these dials to BREAK retrieval.
# Overlap exists so a fact that straddles a chunk boundary still lands whole in
# at least one chunk. Too-big chunks dilute the embedding; too-small ones sever
# context. This tradeoff is a real FDE conversation, not a default to ignore.
CHUNK_WORDS = 120
CHUNK_OVERLAP = 25

# Change this to interrogate the corpus. Try, in separate runs:
#   - answerable-from-one-doc: "Why is my Beacon Tag showing as offline?"
#   - answerable-from-the-table: "How many Tags can I have on the Pro plan?"
#   - NOT in the docs at all:    "Does Beacon integrate with Salesforce?"
#     ^ that last one is your Day-5 hallucination preview — watch whether the
#       grounding prompt makes it say "I don't know" or whether it invents.
QUERY = "Why is my Beacon Tag showing as offline and how do I fix it?"
TOP_K = 4


# ── Embedding: identical to Days 2–3. One string -> one 768-D vector. ─────────
def embed(text: str) -> list[float]:
    resp = requests.post(
        f"{OLLAMA_URL}/api/embeddings",
        json={"model": EMBED_MODEL, "prompt": text},
        timeout=60,
    )
    resp.raise_for_status()
    return resp.json()["embedding"]


# ── Chunking: split one document into overlapping word-windows. ───────────────
def chunk_text(text: str, size: int, overlap: int) -> list[str]:
    words = text.split()
    if not words:
        return []
    step = max(1, size - overlap)
    chunks = []
    for start in range(0, len(words), step):
        window = words[start : start + size]
        chunks.append(" ".join(window))
        if start + size >= len(words):  # last window reached the end; stop
            break
    return chunks


# ── Ingest: read every .md/.txt in DOCS_DIR, chunk each, tag with its source. ─
def load_chunks() -> list[dict]:
    paths = sorted(glob.glob(os.path.join(DOCS_DIR, "*.md")) +
                   glob.glob(os.path.join(DOCS_DIR, "*.txt")))
    if not paths:
        raise SystemExit(f"No .md/.txt docs found in {DOCS_DIR} — nothing to ingest.")
    records = []
    for path in paths:
        source = os.path.basename(path)
        with open(path) as f:
            text = f.read()
        for i, chunk in enumerate(chunk_text(text, CHUNK_WORDS, CHUNK_OVERLAP)):
            records.append({"source": source, "chunk": i, "text": chunk})
    return records


def connect() -> QdrantClient:
    """Connect + fail loudly with the fix if the container isn't up. (Day 3 pattern.)"""
    try:
        client = QdrantClient(url=QDRANT_URL, timeout=10)
        client.get_collections()  # forces a real round-trip
        return client
    except Exception as e:
        raise SystemExit(
            f"Can't reach Qdrant at {QDRANT_URL} — is the container up? ({e})\n"
            f"On the ASUS run:\n"
            f"  docker run -d --name qdrant -p 6333:6333 -p 6334:6334 \\\n"
            f"    -v $(pwd)/qdrant_storage:/qdrant/storage qdrant/qdrant"
        )


# ── Generation: hand the retrieved context to the LLM under a GROUNDING prompt. ─
def generate(query: str, retrieved: list) -> str:
    # Number each retrieved chunk so the model can cite it as [n], and so YOU can
    # map a citation back to an exact source#chunk. This is the whole game: the
    # answer must be traceable to a document, not to the model's imagination.
    context_blocks = []
    for n, hit in enumerate(retrieved, start=1):
        p = hit.payload
        context_blocks.append(f"[{n}] (source: {p['source']}#chunk{p['chunk']})\n{p['text']}")
    context = "\n\n".join(context_blocks)

    system = (
        "You are a support assistant for a product called Beacon. Answer the "
        "user's question using ONLY the numbered context sources provided. "
        "Cite the sources you use inline like [1] or [2]. If the answer is not "
        "contained in the context, reply exactly: 'I don't know based on the "
        "provided documents.' Do not use any outside knowledge."
    )
    user = f"Context:\n{context}\n\nQuestion: {query}"

    resp = requests.post(
        f"{OLLAMA_URL}/api/chat",
        json={
            "model": GEN_MODEL,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "stream": False,
            "options": {"temperature": 0},  # deterministic-ish, so reruns are comparable
        },
        timeout=180,
    )
    resp.raise_for_status()
    return resp.json()["message"]["content"].strip()


def main() -> None:
    client = connect()

    # 1) INGEST + CHUNK
    records = load_chunks()
    sources = sorted({r["source"] for r in records})
    print(f"Ingested {len(sources)} docs -> {len(records)} chunks "
          f"({CHUNK_WORDS}-word windows, {CHUNK_OVERLAP} overlap): {', '.join(sources)}")

    # 2) EMBED every chunk (on the ASUS, over the network)
    print(f"Embedding {len(records)} chunks with {EMBED_MODEL} @ {OLLAMA_URL} ...")
    vecs = [embed(r["text"]) for r in records]
    dim = len(vecs[0])

    # 3) STORE — fresh collection each run so re-ingests are clean. Cosine = Day 2/3 metric.
    print(f"Loading into Qdrant collection '{COLLECTION}' @ {QDRANT_URL} (dim={dim})")
    if client.collection_exists(COLLECTION):
        client.delete_collection(COLLECTION)
    client.create_collection(
        collection_name=COLLECTION,
        vectors_config=VectorParams(size=dim, distance=Distance.COSINE),
    )
    client.upsert(
        collection_name=COLLECTION,
        wait=True,
        points=[PointStruct(id=i, vector=v, payload=records[i]) for i, v in enumerate(vecs)],
    )

    # 4) RETRIEVE — embed the query, pull top-k nearest chunks
    qv = embed(QUERY)
    hits = client.query_points(
        collection_name=COLLECTION, query=qv, limit=TOP_K, with_payload=True
    ).points

    # 4a) SHOW THE RETRIEVAL *before* the answer. This is the debugging seam.
    print("\n" + "=" * 76)
    print(f"QUERY: {QUERY!r}")
    print("=" * 76)
    print(f"RETRIEVED top-{TOP_K} chunks (this is what the model gets to read):\n")
    for n, h in enumerate(hits, start=1):
        p = h.payload
        preview = textwrap.shorten(p["text"], width=150, placeholder=" …")
        print(f"  [{n}] {h.score:.3f}  {p['source']}#chunk{p['chunk']}")
        print(f"        {preview}\n")

    # 5) GENERATE — grounded answer with citations
    print("-" * 76)
    print("ANSWER (grounded, cite-or-refuse):\n")
    answer = generate(QUERY, hits)
    print(textwrap.fill(answer, width=76, replace_whitespace=False))

    print("\n" + "=" * 76)
    print(
        "Debugging seam: the chunks above are RETRIEVAL; the prose is GENERATION.\n"
        "When an answer is wrong (Day 5), first ask which half failed — did the\n"
        "right chunk not come back (retrieval), or did it come back and the model\n"
        "ignored/mangled it (generation)? Different bug, different fix. Now go\n"
        "write notes/day4.md — and try the out-of-corpus query to test grounding."
    )


if __name__ == "__main__":
    main()
