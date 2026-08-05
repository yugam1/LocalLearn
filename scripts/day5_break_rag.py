#!/usr/bin/env python3
"""
Day 5 — Break RAG four ways (the money day).

Day 4 built a RAG pipeline that WORKS. That's table stakes. The interview
differentiator — the thing the START_HERE plan calls "the money day" — is being
able to make it FAIL on purpose, one stage at a time, and then say out loud
*which* stage failed and *why*. A clean demo proves nothing; a diagnosed failure
proves you've actually built these things.

Every failure below maps to exactly ONE stage of the pipeline you built:

    ingest -> CHUNK -> embed -> store -> RETRIEVE -> GENERATE

  1. CHUNKING   — chunk so small the pricing TABLE is severed from its header;
                  the number survives but loses the label that gives it meaning.
  2. RETRIEVAL  — ask a question whose answer lives in TWO chunks, then set k=1
                  so only half of it comes back. Retrieval "worked" — and lied.
  3. HALLUCINATE— ask something NOT in the docs. Run it once WITH the grounding
                  prompt (refuses) and once WITHOUT (invents). Same retrieval,
                  opposite outcome — proving the guardrail is the only thing
                  standing between you and a confident fabrication.
  4. STALE INDEX— edit a doc but DON'T re-embed. The vector store is a photograph
                  of the old truth; it keeps answering from the snapshot.

Each mode runs the HEALTHY variant and the BROKEN variant back-to-back in one
invocation, then prints a DIAGNOSIS line naming the guilty stage. That A/B is
the whole point: you can only call a bug "retrieval" if you've SEEN generation
do fine on the same question with better chunks in front of it.

Workflow (same as Day 4): set MODE below (or `MODE=... python day5_break_rag.py`),
run it against the ASUS, and capture the output to result/day5_<mode>.txt. Then
fill notes/day5.md from what you actually observed — the notes have blanks on
purpose, because the lesson is what the model DID, not what I predicted.
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
EMBED_MODEL = "nomic-embed-text"                              # same embedder as Days 2–4
GEN_MODEL = os.environ.get("GEN_MODEL", "llama3.1:8b")        # the answer-writer
COLLECTION = "day5_break_rag"                                 # separate from day4's
DOCS_DIR = os.environ.get("DOCS_DIR", os.path.join(HERE, "..", "docs"))

# Healthy chunking knobs — the Day-4 baseline that WORKS. Each break below dials
# ONE of these (or k, or the prompt, or the index freshness) and leaves the rest
# alone, so the failure has exactly one cause.
CHUNK_WORDS = 120
CHUNK_OVERLAP = 25
TOP_K = 4

# Which failure to induce this run: chunking | retrieval | hallucination | stale
# (or "all" to run the whole gauntlet). Edit and re-run, one capture per mode.
MODE = os.environ.get("MODE", "chunking")


# ── Two system prompts. The ONLY difference is the cite-or-refuse guardrail. ──
# The hallucination mode swaps between them on identical retrieved context, which
# is what isolates "the guardrail" as a variable you can turn on and off.
GROUNDED_SYSTEM = (
    "You are a support assistant for a product called Beacon. Answer the "
    "user's question using ONLY the numbered context sources provided. "
    "Cite the sources you use inline like [1] or [2]. If the answer is not "
    "contained in the context, reply exactly: 'I don't know based on the "
    "provided documents.' Do not use any outside knowledge."
)
NAIVE_SYSTEM = (
    "You are a helpful support assistant for a product called Beacon. "
    "Use the context below to help answer the user's question."
)  # note what's MISSING: no 'only', no 'cite', no 'else say I don't know'.


# ── Shared pipeline pieces (same as Day 4, refactored so every mode reuses them) ─
def embed(text: str) -> list[float]:
    resp = requests.post(
        f"{OLLAMA_URL}/api/embeddings",
        json={"model": EMBED_MODEL, "prompt": text},
        timeout=60,
    )
    resp.raise_for_status()
    return resp.json()["embedding"]


def chunk_text(text: str, size: int, overlap: int) -> list[str]:
    words = text.split()
    if not words:
        return []
    step = max(1, size - overlap)
    chunks = []
    for start in range(0, len(words), step):
        window = words[start : start + size]
        chunks.append(" ".join(window))
        if start + size >= len(words):
            break
    return chunks


def load_raw_docs() -> list[tuple[str, str]]:
    """Return [(source_filename, full_text), ...] — chunking is applied later so
    each mode can chunk the SAME docs differently."""
    paths = sorted(glob.glob(os.path.join(DOCS_DIR, "*.md")) +
                   glob.glob(os.path.join(DOCS_DIR, "*.txt")))
    if not paths:
        raise SystemExit(f"No .md/.txt docs found in {DOCS_DIR} — nothing to ingest.")
    docs = []
    for path in paths:
        with open(path) as f:
            docs.append((os.path.basename(path), f.read()))
    return docs


def records_from(docs: list[tuple[str, str]], size: int, overlap: int) -> list[dict]:
    records = []
    for source, text in docs:
        for i, chunk in enumerate(chunk_text(text, size, overlap)):
            records.append({"source": source, "chunk": i, "text": chunk})
    return records


def connect() -> QdrantClient:
    try:
        client = QdrantClient(url=QDRANT_URL, timeout=10)
        client.get_collections()
        return client
    except Exception as e:
        raise SystemExit(
            f"Can't reach Qdrant at {QDRANT_URL} — is the container up? ({e})\n"
            f"On the ASUS run:\n"
            f"  docker run -d --name qdrant -p 6333:6333 -p 6334:6334 \\\n"
            f"    -v $(pwd)/qdrant_storage:/qdrant/storage qdrant/qdrant"
        )


def build_collection(client: QdrantClient, records: list[dict]) -> int:
    """Embed every record and (re)load COLLECTION from scratch. Returns the dim."""
    vecs = [embed(r["text"]) for r in records]
    dim = len(vecs[0])
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
    return dim


def retrieve(client: QdrantClient, query: str, k: int) -> list:
    qv = embed(query)
    return client.query_points(
        collection_name=COLLECTION, query=qv, limit=k, with_payload=True
    ).points


def show_retrieval(query: str, hits: list) -> None:
    print(f"\nQUERY: {query!r}")
    print(f"RETRIEVED top-{len(hits)} (what the model gets to read):")
    for n, h in enumerate(hits, start=1):
        p = h.payload
        preview = textwrap.shorten(p["text"], width=140, placeholder=" …")
        print(f"  [{n}] {h.score:.3f}  {p['source']}#chunk{p['chunk']}")
        print(f"        {preview}")


def generate(query: str, retrieved: list, system: str) -> str:
    context_blocks = []
    for n, hit in enumerate(retrieved, start=1):
        p = hit.payload
        context_blocks.append(f"[{n}] (source: {p['source']}#chunk{p['chunk']})\n{p['text']}")
    context = "\n\n".join(context_blocks)
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
            "options": {"temperature": 0},  # deterministic-ish so reruns compare
        },
        timeout=180,
    )
    resp.raise_for_status()
    return resp.json()["message"]["content"].strip()


def answer(query: str, hits: list, system: str = GROUNDED_SYSTEM) -> None:
    print("  ANSWER:")
    print(textwrap.indent(textwrap.fill(generate(query, hits, system), width=72), "    "))


def banner(title: str) -> None:
    print("\n" + "=" * 76)
    print(title)
    print("=" * 76)


def show_chunking(records: list[dict]) -> None:
    print(f"  -> {len(records)} chunks: " +
          ", ".join(f"{r['source']}#chunk{r['chunk']}" for r in records))


# ── BREAK #1: CHUNKING — sever the pricing table from its header row. ──────────
def run_chunking(client: QdrantClient) -> None:
    banner("BREAK #1 — CHUNKING  (sever the table; the number outlives its label)")
    docs = load_raw_docs()
    # The pricing table's meaning lives in the HEADER row ("| Price / Tag / mo |
    # Max Tags | ...") that labels every column. A cell like "$9" or "250" is
    # only meaningful next to that header. Chunk small enough and the header ends
    # up in a different chunk from the Pro data row — the value survives, the
    # label that makes it an ANSWER does not. Classic real-world RAG failure:
    # naive word-chunking is structurally blind to tables, code, and step-lists.
    query = "How much does the Pro plan cost per Tag per month?"

    print("\n--- HEALTHY (120-word windows: the whole table lands in one chunk) ---")
    healthy = records_from(docs, CHUNK_WORDS, CHUNK_OVERLAP)
    show_chunking([r for r in healthy if r["source"] == "pricing.md"])
    build_collection(client, healthy)
    hits = retrieve(client, query, TOP_K)
    show_retrieval(query, hits)
    answer(query, hits)

    print("\n--- BROKEN (10-word windows, 0 overlap: the table is shredded) ---")
    broken = records_from(docs, 10, 0)
    show_chunking([r for r in broken if r["source"] == "pricing.md"])
    build_collection(client, broken)
    hits = retrieve(client, query, TOP_K)
    show_retrieval(query, hits)
    answer(query, hits)

    print("\nDIAGNOSIS: same query, same model, same grounding prompt — only the")
    print("CHUNK SIZE changed. If the broken run gets it wrong or says 'I don't")
    print("know', the bug is CHUNKING: retrieval faithfully returned a chunk, but")
    print("the chunk lost the header that told you '$9' was a PRICE. Fix lives at")
    print("the chunk stage (structure-aware splitting), not retrieval or the LLM.")


# ── BREAK #2: RETRIEVAL — the answer needs two chunks; k=1 returns one. ────────
def run_retrieval(client: QdrantClient) -> None:
    banner("BREAK #2 — RETRIEVAL  (answer spans two chunks; k=1 brings back half)")
    docs = load_raw_docs()
    # This question has TWO parts living in two different chunks of
    # troubleshooting.md: the DEFINITION of 'offline' (no ping >15 min, the
    # causes) sits near the top (chunk0); the FIX for a >2h silence (power-cycle
    # via the OBD-II port for 30s) sits lower (chunk2). Top-k never returns
    # nothing, so k=1 returns *a* chunk and looks like it worked — but it dropped
    # the half you needed. This is why "how big is k?" is a real FDE question.
    query = ("What does it mean when a Beacon Tag is offline, and what should I "
             "do if it's still silent after more than 2 hours?")
    records = records_from(docs, CHUNK_WORDS, CHUNK_OVERLAP)
    build_collection(client, records)

    print("\n--- HEALTHY (k=4: both the definition chunk and the fix chunk return) ---")
    hits = retrieve(client, query, 4)
    show_retrieval(query, hits)
    answer(query, hits)

    print("\n--- BROKEN (k=1: only the single nearest chunk returns) ---")
    hits = retrieve(client, query, 1)
    show_retrieval(query, hits)
    answer(query, hits)

    print("\nDIAGNOSIS: same query, same chunks in the DB, same model — only k")
    print("changed. If the k=1 answer is missing the power-cycle fix (or the")
    print("definition), the bug is RETRIEVAL: the right chunk exists but never")
    print("reached the model. Fix is k / better ranking (Day 6), NOT chunking or")
    print("the prompt. Check the k=4 retrieval list: was the second half at rank 2+?")


# ── BREAK #3: HALLUCINATION — same retrieval, guardrail on vs. off. ───────────
def run_hallucination(client: QdrantClient) -> None:
    banner("BREAK #3 — HALLUCINATION  (out-of-corpus; the guardrail is the only wall)")
    docs = load_raw_docs()
    # Nothing in the Beacon docs mentions Salesforce. But cosine ALWAYS returns
    # top-k — it will hand over the 4 least-bad chunks no matter how irrelevant
    # (you saw this in Day 4 Part C: scores flat ~0.51, scattered across docs).
    # The retrieved context is IDENTICAL in both runs below. The only difference
    # is the system prompt. That isolates the grounding instruction as the single
    # thing deciding between an honest refusal and a confident fabrication.
    query = "Does Beacon integrate with Salesforce?"
    records = records_from(docs, CHUNK_WORDS, CHUNK_OVERLAP)
    build_collection(client, records)
    hits = retrieve(client, query, TOP_K)
    show_retrieval(query, hits)

    print("\n--- WITH GUARDRAIL (grounded: 'answer ONLY from context, else I don't know') ---")
    answer(query, hits, system=GROUNDED_SYSTEM)

    print("\n--- WITHOUT GUARDRAIL (naive: 'use the context to help answer') ---")
    answer(query, hits, system=NAIVE_SYSTEM)

    print("\nDIAGNOSIS: identical retrieved chunks, identical model — only the")
    print("PROMPT changed. If the naive run invents a Salesforce integration while")
    print("the grounded run refuses, you've proven the failure is GENERATION and")
    print("that the grounding prompt is load-bearing. It's the cheapest, highest-")
    print("leverage guardrail you have — and note it's the ONLY thing that held.")


# ── BREAK #4: STALE INDEX — edit the doc, skip re-embedding, get the old answer. ─
def run_stale(client: QdrantClient) -> None:
    banner("BREAK #4 — STALE INDEX  (the vector store is a photo of the old truth)")
    docs = load_raw_docs()
    query = "How much does the Starter plan cost per active Tag per month?"

    # v1: index the docs exactly as they are on disk today. Starter is $4.
    print("\n--- v1: index the docs as-is, then query ---")
    v1 = records_from(docs, CHUNK_WORDS, CHUNK_OVERLAP)
    build_collection(client, v1)
    hits = retrieve(client, query, TOP_K)
    show_retrieval(query, hits)
    answer(query, hits)

    # SIMULATED EDIT: pretend an FDE updated pricing.md — Starter goes $4 -> $6 —
    # but nobody re-ran the ingest job. We mutate the in-memory doc text ONLY; we
    # deliberately do NOT call build_collection again. The vectors + payloads in
    # Qdrant still describe the $4 world.
    print("\n--- EDIT pricing.md ($4 -> $6) but DO NOT re-embed, then re-query ---")
    edited_docs = [
        (src, text.replace("| Starter    | $4", "| Starter    | $6"))
        for src, text in docs
    ]
    changed = any(e[1] != o[1] for e, o in zip(edited_docs, docs))
    print(f"  (edit applied to source text: {changed}; index NOT rebuilt)")
    hits = retrieve(client, query, TOP_K)
    show_retrieval(query, hits)
    answer(query, hits)  # expect the STALE $4 — the index never saw the edit

    # Now do the thing the ops job forgot: re-embed the edited docs.
    print("\n--- RE-EMBED the edited docs (re-run ingest), then re-query ---")
    v2 = records_from(edited_docs, CHUNK_WORDS, CHUNK_OVERLAP)
    build_collection(client, v2)
    hits = retrieve(client, query, TOP_K)
    show_retrieval(query, hits)
    answer(query, hits)  # now the fresh $6

    print("\nDIAGNOSIS: the docs changed but the INDEX didn't. Retrieval returned a")
    print("chunk that is a faithful copy of yesterday's file — the vector store is")
    print("a snapshot taken at embed time, not a live view of your docs. The bug")
    print("isn't chunking/retrieval/generation quality at all; it's a PIPELINE")
    print("FRESHNESS/ops problem (re-index on change). The scariest kind: the")
    print("answer is confident, well-cited, and wrong only because it's out of date.")


MODES = {
    "chunking": run_chunking,
    "retrieval": run_retrieval,
    "hallucination": run_hallucination,
    "stale": run_stale,
}


def main() -> None:
    client = connect()
    print(f"Ollama @ {OLLAMA_URL} · Qdrant @ {QDRANT_URL} · gen={GEN_MODEL} · MODE={MODE}")
    if MODE == "all":
        for fn in MODES.values():
            fn(client)
    elif MODE in MODES:
        MODES[MODE](client)
    else:
        raise SystemExit(
            f"Unknown MODE={MODE!r}. Pick one of: {', '.join(MODES)} (or 'all')."
        )

    print("\n" + "=" * 76)
    print("Each break maps to ONE pipeline stage: chunking / retrieval / prompt /")
    print("freshness. That taxonomy IS the interview answer to 'the RAG answer is")
    print("wrong — how do you debug it?'. Now write notes/day5.md from what you saw.")


if __name__ == "__main__":
    main()
