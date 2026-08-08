#!/usr/bin/env python3
"""
Day 6 — Make retrieval better, and PROVE it (or prove it didn't).

Day 5 diagnosed four ways RAG breaks. Break #2 was retrieval: the answer lived in
two chunks, k=1 returned one, and the pipeline lied confidently. Today fixes that
stage — and the fix is the easy half. The hard half, and the only half an
interviewer cares about, is the sentence at the end:

    "Recall@4 went 0.__ -> 0.__ and p50 latency went __ms -> __ms."

Anyone can bolt BM25 onto a vector search. Almost nobody measures whether it
helped, and a surprising number of "improvements" are pure latency. So this day
is built as an EXPERIMENT, not a demo: four retrievers, one labelled gold set,
one table, and a written-down prediction per query that the run is allowed to
falsify. (Day 5's whole lesson was that my predictions kept being wrong. Good.)

    1. vector  — Day 4/5 baseline. Cosine over nomic-embed-text. Meaning.
    2. bm25    — hand-rolled lexical scoring. Exact tokens, zero semantics.
    3. hybrid  — Reciprocal Rank Fusion of the two. Fuses RANKS, not scores.
    4. rerank  — hybrid over-fetches, then llama3.1:8b re-scores each candidate.

Why those two arms fuse well: their errors are UNCORRELATED. Vector search
smears rare literal tokens (a hostname embeds as "identifier-ish", so two
different hostnames are neighbours). BM25 is blind to any paraphrase that shares
no vocabulary. Each one's blind spot is the other's strength — that, not novelty,
is the argument for hybrid.

The metric is RETRIEVAL-level (did the chunk containing the answer reach top-k?),
not answer-level — answer grading needs an LLM judge, which is Day 7. Retrieval
recall is the ceiling on everything downstream: if the chunk never arrives, no
prompt can save you.

Self-logs to result/day6.txt. Run it, then fill notes/day6.md from what happened.
"""
import os

from locallearn import (
    config, ollama, vectorstore, chunking, bm25, retrievers, evaluation,
    generation, prompts, display, runlog,
)

settings = config.Settings.from_env()
client = ollama.OllamaClient(settings.ollama_url, settings.embed_model, settings.gen_model)
COLLECTION = "day6_hybrid"

# Day 4/5 baseline knobs, held FIXED so the only variable today is the retriever.
CHUNK_WORDS = 120
CHUNK_OVERLAP = 25

# Tunables: edit here, or override from the environment (launch.json drives these).
# k is the headline knob — Day 5 Break #2 found its floor, today probes the ceiling.
TOP_K = int(os.environ.get("TOP_K", "4"))

# Reranker over-fetch: how many candidates the LLM re-scores per query. This is
# the latency dial — cost is linear in it (one LLM call per candidate per query).
# 6 x 10 queries = 60 sequential calls, so expect the rerank row to be SLOW.
RERANK_FETCH_K = int(os.environ.get("RERANK_FETCH_K", "6"))

# Which arms to score. Drop "rerank" for a fast iteration loop — the other three
# are sub-second and the reranker is ~60 sequential LLM calls. Useful when you're
# debugging the gold set or the fusion and don't want to wait on the LLM each time.
ARMS = [a.strip() for a in
        os.environ.get("ARMS", "vector,bm25,hybrid,rerank").split(",") if a.strip()]

# One end-to-end answer at the end, baseline vs. best retriever.
DEMO_QUERY = "Where do I change the idle threshold, and what is it by default?"


# ── THE GOLD SET ───────────────────────────────────────────────────────────────
# Ten questions with a provable right answer. `must_contain` is a literal string
# that the correct chunk demonstrably has, so grading needs no LLM and no
# judgement call — the check is a substring test. Substrings, not chunk ids, so
# the gold set stays valid if anyone re-tunes the chunker.
#
# `expects` is a PREDICTION, written before running: which family should win.
# The point of writing it down is to be able to be wrong in public. Any row where
# the run contradicts `expects` is the most interesting line in the output.
GOLD = [
    # ---- lexical: rare literal tokens that embeddings compress into mush ----
    evaluation.GoldQuery(
        query="What URL is the Beacon web console at?",
        must_contain="console.beacon.aldritch.example",
        expects="lexical",
        note="A hostname carries almost no semantic signal. Cosine has nothing to "
             "grip; BM25 matches the literal token.",
    ),
    evaluation.GoldQuery(
        query="What is the serial number format printed on a Tag label?",
        must_contain="BT-XXXXXXXXX",
        expects="lexical",
        note="Same shape of problem as the hostname: an identifier pattern.",
    ),
    evaluation.GoldQuery(
        query="Where exactly in the console do I change the idle threshold?",
        must_contain="Fleet → Settings",
        expects="lexical",
        note="A navigation path is a literal string. Note BOTH docs discuss idle "
             "alerts, so the semantic arm has a genuine ambiguity to resolve.",
    ),
    evaluation.GoldQuery(
        query="How many fleets can a single Beacon account hold?",
        must_contain="50 fleets",
        expects="lexical",
        note="A specific number. Watch whether vector returns the RIGHT doc with "
             "the WRONG number — the Day 5 chunking failure in a new costume.",
    ),

    # ---- semantic: paraphrases sharing almost no vocabulary with the doc ----
    evaluation.GoldQuery(
        query="My van's tracker stopped showing up on the live map",
        must_contain="no ping from it for more",
        expects="semantic",
        note="Not one content word overlaps the doc's 'Tag / offline / ping'. "
             "BM25 should be helpless here; this is dense retrieval's whole case.",
    ),
    evaluation.GoldQuery(
        query="Why do I get alerted every time my drivers pause for lunch?",
        must_contain="idle threshold",
        expects="semantic",
        # Genuinely ambiguous and that's fine: overview.md defines the idle alert
        # and troubleshooting.md explains raising the threshold. Either chunk
        # answers the user. Marked explicitly so the grader doesn't flag it.
        ambiguous_ok=True,
        note="'pause for lunch' -> 'stops longer than the idle threshold'. Pure "
             "meaning, no shared tokens.",
    ),
    evaluation.GoldQuery(
        query="How long until old trip data gets thinned out?",
        must_contain="downsampled",
        expects="semantic",
        note="'thinned out' is a paraphrase of 'downsampled' that shares no stem.",
    ),
    evaluation.GoldQuery(
        query="Can a read-only user draw zones on the map?",
        must_contain="Viewer is",
        expects="semantic",
        note="Two paraphrases at once: read-only user->Viewer, zones->geofences.",
    ),

    # ---- hard: the Day 5 Break #2 question, and a billing-state trap ----
    evaluation.GoldQuery(
        query="What should I do if a Tag has been silent for more than 2 hours?",
        # NOT "30 seconds" — that was the first draft and it was a grader bug.
        # overview.md also says "reports a GPS ping every 30 seconds", so a
        # retriever fetching the wrong doc entirely would have scored CORRECT.
        # The phrase below is unique to the power-cycle fix chunk.
        must_contain="unplugging it from the OBD-II port",
        expects="either",
        note="Day 5 Break #2's second half — the chunk k=1 dropped. Does hybrid "
             "pull the FIX chunk up, or still only the definition chunk?",
    ),
    evaluation.GoldQuery(
        query="How do I stop being charged for a Tag I am not using?",
        must_contain="not billed",
        expects="either",
        note="Day 2/3 lineage: the polarity trap. 'stop being charged' is close in "
             "embedding space to BOTH the billing rules and the deactivation flow.",
    ),
]


def build(store: vectorstore.VectorStore):
    """Ingest once, then hand the SAME chunks to both indexes.

    Both arms must see identical chunks or the comparison is meaningless — any
    difference in the table has to come from the retrieval strategy, not from one
    arm having been given better source material."""
    docs = chunking.load_documents(settings.docs_dir)
    chunks = chunking.Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(docs)
    print(f"\nIngested {len(docs)} docs -> {len(chunks)} chunks "
          f"({CHUNK_WORDS}-word windows, {CHUNK_OVERLAP} overlap)")

    store.rebuild(chunks)                      # dense index (Qdrant, on the ASUS)
    lex = bm25.BM25Index(chunks)               # lexical index (in-memory, here)
    print(f"  dense : Qdrant collection {COLLECTION!r} on {settings.qdrant_url}")
    print(f"  sparse: BM25 over {len(lex.chunks)} chunks, "
          f"avg length {lex.avgdl:.1f} tokens, vocab {len(lex.idf)}")
    return chunks, lex


def main() -> None:
    client.require_model(settings.gen_model.split(":")[0])
    store = vectorstore.VectorStore.connect(
        settings.qdrant_url, COLLECTION, embedder=client)

    print(f"Ollama @ {settings.ollama_url} · Qdrant @ {settings.qdrant_url} · "
          f"gen={settings.gen_model} · embed={settings.embed_model}")
    chunks, lex = build(store)
    # Validate the gold set BEFORE measuring anything with it.
    evaluation.sanity_check_gold(GOLD, chunks)

    vec_r = retrievers.VectorRetriever(store)
    bm_r = retrievers.BM25Retriever(lex)
    hyb_r = retrievers.HybridRetriever(vec_r, bm_r)
    rr_r = retrievers.RerankRetriever(hyb_r, client, fetch_k=RERANK_FETCH_K)
    available = {r.name: r for r in (vec_r, bm_r, hyb_r, rr_r)}

    unknown = [a for a in ARMS if a not in available]
    if unknown:
        raise SystemExit(f"Unknown arm(s) {unknown}. Pick from: {', '.join(available)}")
    # Order matters: the table reports every row as a delta vs. the FIRST one, so
    # the baseline has to lead. Comparing hybrid against bm25 instead of against
    # the Day 4/5 vector baseline would be a different (and misleading) claim.
    chosen = [available[a] for a in ARMS]

    display.banner(f"PART A — {len(chosen)} retrievers, one gold set, one table")
    print(f"Scoring {len(GOLD)} queries x {len(chosen)} retrievers at k={TOP_K}: "
          f"{', '.join(ARMS)}")
    if "rerank" in ARMS:
        print(f"The rerank row makes {RERANK_FETCH_K} LLM calls PER QUERY "
              f"(~{RERANK_FETCH_K * len(GOLD)} sequential calls), so it will be "
              f"visibly slow — that's the lesson, not a bug.\n"
              f"Set ARMS=vector,bm25,hybrid for a fast iteration loop.")

    runs = []
    for r in chosen:
        print(f"  running {r.name}…", flush=True)
        runs.append(evaluation.evaluate(r, GOLD, TOP_K))

    evaluation.print_comparison(runs, TOP_K)
    evaluation.print_per_query(runs)
    evaluation.print_disagreements(runs)

    display.banner("PART B — does better retrieval change the ANSWER?")
    # A metric only matters if it moves the user-visible output. Same query, same
    # prompt, same model — only the retriever differs.
    for r in (vec_r, hyb_r):
        print(f"\n--- retriever: {r.name} ---")
        hits = r.retrieve(DEMO_QUERY, TOP_K)
        display.show_retrieval(DEMO_QUERY, hits)
        display.print_answer(
            generation.generate(client, DEMO_QUERY, hits, prompts.GROUNDED_SYSTEM))

    print("\n" + "=" * 76)
    print("THE QUESTION TO ANSWER IN notes/day6.md:")
    print("  Did Recall@k improve? Did MRR? What did it cost in p50/p95 latency?")
    print("  Was the RERANKER worth its latency, or did hybrid capture the gain?")
    print("  Which queries got WORSE — and would you ship this?")
    print("A retrieval change you cannot describe in those terms is not an")
    print("improvement, it is a diff.")


if __name__ == "__main__":
    with runlog.tee_stdout(os.path.join(settings.result_dir, "day6.txt")):
        main()
    print(f"\nLogged to: {os.path.relpath(os.path.join(settings.result_dir, 'day6.txt'))}")
