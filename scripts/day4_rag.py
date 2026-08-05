#!/usr/bin/env python3
"""
Day 4 — Full RAG pipeline, end to end.

THE Forward-Deployed deliverable in miniature: a customer hands you a pile of docs
and a fuzzy goal ("make our docs answerable"), and you ship a thing that answers
questions *from their data* with citations. Everything before today was a piece of
this — embeddings (Day 2) and a vector DB (Day 3) — now assembled into:

    ingest docs -> chunk -> embed -> store in Qdrant -> retrieve top-k -> answer

Two design choices are deliberate and worth internalising:

1. The corpus (../docs) is a FICTIONAL product ("Beacon"). If it were about real
   public facts, the LLM could answer from training memory and you'd never know
   whether retrieval did anything. Made-up data makes retrieval the ONLY possible
   source of truth — a right answer proves the pipeline; a wrong one can't hide.

2. We print the RETRIEVED CHUNKS before the generated answer, always. In Day 5
   you deliberately break this thing, and the first debugging question is always
   "was it retrieval or generation?" — answerable only if you can see what came
   back from the DB *before* the model spun it into prose.

The pipeline stages now live in the `locallearn` package (Chunker, VectorStore,
generate, the grounding prompt); this file is just the wiring + the narrative.
Every run tees its output to result/day4.txt.
"""
import os
import textwrap

from locallearn import (
    Settings,
    OllamaClient,
    VectorStore,
    Chunker,
    load_documents,
    generate,
    GROUNDED_SYSTEM,
    show_retrieval,
    tee_stdout,
)

settings = Settings.from_env()
ollama = OllamaClient(settings.ollama_url, settings.embed_model, settings.gen_model)
COLLECTION = "day4_beacon_docs"

# Chunking knobs. Word-based sliding window with overlap — deliberately simple and
# visible, because in Day 5 you'll turn these dials to BREAK retrieval. Too-big
# chunks dilute the embedding; too-small ones sever context. Real FDE tradeoff.
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


def main() -> None:
    store = VectorStore.connect(settings.qdrant_url, COLLECTION, embedder=ollama)

    # 1) INGEST + CHUNK
    docs = load_documents(settings.docs_dir)
    chunks = Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(docs)
    sources = sorted({c.source for c in chunks})
    print(f"Ingested {len(sources)} docs -> {len(chunks)} chunks "
          f"({CHUNK_WORDS}-word windows, {CHUNK_OVERLAP} overlap): {', '.join(sources)}")

    # 2) EMBED + 3) STORE (fresh collection each run; cosine = Day 2/3 metric)
    print(f"Embedding {len(chunks)} chunks with {settings.embed_model} "
          f"@ {settings.ollama_url} and loading into '{COLLECTION}' ...")
    dim = store.rebuild(chunks)
    print(f"Loaded into Qdrant @ {settings.qdrant_url} (dim={dim})")

    # 4) RETRIEVE — and SHOW the retrieval before the answer. The debugging seam.
    hits = store.retrieve(QUERY, TOP_K)
    print("\n" + "=" * 76)
    show_retrieval(QUERY, hits)

    # 5) GENERATE — grounded answer with citations
    print("\n" + "-" * 76)
    print("ANSWER (grounded, cite-or-refuse):\n")
    print(textwrap.fill(generate(ollama, QUERY, hits, GROUNDED_SYSTEM), width=76))

    print("\n" + "=" * 76)
    print(
        "Debugging seam: the chunks above are RETRIEVAL; the prose is GENERATION.\n"
        "When an answer is wrong (Day 5), first ask which half failed — did the\n"
        "right chunk not come back (retrieval), or did it come back and the model\n"
        "ignore/mangle it (generation)? Different bug, different fix. Now go write\n"
        "notes/day4.md — and try the out-of-corpus query to test grounding."
    )


if __name__ == "__main__":
    with tee_stdout(os.path.join(settings.result_dir, "day4.txt")):
        main()
