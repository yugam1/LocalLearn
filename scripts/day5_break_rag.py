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
invocation, then prints a DIAGNOSIS line naming the guilty stage. That A/B is the
whole point: you can only call a bug "retrieval" if you've SEEN generation do fine
on the same question with better chunks in front of it.

The pipeline pieces (Chunker, VectorStore, generate, the prompts, display) live in
the `locallearn` package — this file is just the four breaks + the diagnoses.
Set MODE below (or `MODE=... python day5_break_rag.py`) and run against the ASUS.
The script LOGS ITSELF: every run tees to result/day5_<mode>.txt while still
printing live. MODE=all writes each mode's file PLUS a combined result/day5_all.txt
(5 files total). Then fill notes/day5.md from what you actually observed.
"""
import os

# Import the feature MODULES as namespaces (not their loose names) so every call
# site names its source file: config.Settings lives in config.py, chunking.Chunker
# in chunking.py, display.banner in display.py, runlog.tee_stdout in runlog.py.
# The module IS the file — that's the at-a-glance link between call and definition.
from locallearn import (
    config, ollama, vectorstore, chunking, generation, prompts, display, runlog,
)

settings = config.Settings.from_env()
client = ollama.OllamaClient(settings.ollama_url, settings.embed_model, settings.gen_model)
COLLECTION = "day5_break_rag"

# Healthy chunking knobs — the Day-4 baseline that WORKS. Each break below dials
# ONE of these (or k, or the prompt, or the index freshness) and leaves the rest
# alone, so the failure has exactly one cause.
CHUNK_WORDS = 120
CHUNK_OVERLAP = 25
TOP_K = 4

# Which failure to induce this run: chunking | retrieval | hallucination | stale
# (or "all" to run the whole gauntlet). Edit and re-run, one capture per mode.
MODE = os.environ.get("MODE", "chunking")


def answer(query: str, hits: list, system: str = prompts.GROUNDED_SYSTEM) -> None:
    display.print_answer(generation.generate(client, query, hits, system))


# Hallucination is as much a SAMPLING phenomenon as a prompting one. temperature=0
# takes the argmax token every step, and the safest next token is almost always a
# hedge ("not specified", "does not mention") — so temp 0 suppresses fabrication
# even with a bad prompt. Every other day wants 0 for reproducibility; the
# hallucination break needs a hot rung to show what sampling unlocks.
HOT_TEMP = float(os.environ.get("HOT_TEMP", "0.9"))


# ── BREAK #1: CHUNKING — sever the pricing table from its header row. ──────────
def run_chunking(store: vectorstore.VectorStore) -> None:
    display.banner("BREAK #1 — CHUNKING  (sever the table; the number outlives its label)")
    docs = chunking.load_documents(settings.docs_dir)
    # The pricing table's meaning lives in the HEADER row ("| Price / Tag / mo |
    # Max Tags | ...") that labels every column. A cell like "$9" or "250" is only
    # meaningful next to that header. Chunk small enough and the header ends up in
    # a different chunk from the Pro data row — the value survives, the label that
    # makes it an ANSWER does not. Classic real-world RAG failure: naive word-
    # chunking is structurally blind to tables, code, and step-lists.
    query = "How much does the Pro plan cost per Tag per month?"

    print("\n--- HEALTHY (120-word windows: the whole table lands in one chunk) ---")
    healthy = chunking.Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(docs)
    display.show_chunks([c for c in healthy if c.source == "pricing.md"])
    store.rebuild(healthy)
    hits = store.retrieve(query, TOP_K)
    display.show_retrieval(query, hits)
    answer(query, hits)

    print("\n--- BROKEN (10-word windows, 0 overlap: the table is shredded) ---")
    broken = chunking.Chunker(10, 0).chunk(docs)
    display.show_chunks([c for c in broken if c.source == "pricing.md"])
    store.rebuild(broken)
    hits = store.retrieve(query, TOP_K)
    display.show_retrieval(query, hits)
    answer(query, hits)

    print("\nDIAGNOSIS: same query, same model, same grounding prompt — only the")
    print("CHUNK SIZE changed. If the broken run gets it wrong or says 'I don't")
    print("know', the bug is CHUNKING: retrieval faithfully returned a chunk, but")
    print("the chunk lost the header that told you '$9' was a PRICE. Fix lives at")
    print("the chunk stage (structure-aware splitting), not retrieval or the LLM.")


# ── BREAK #2: RETRIEVAL — the answer needs two chunks; k=1 returns one. ────────
def run_retrieval(store: vectorstore.VectorStore) -> None:
    display.banner("BREAK #2 — RETRIEVAL  (answer spans two chunks; k=1 brings back half)")
    docs = chunking.load_documents(settings.docs_dir)
    # This question has TWO parts living in two different chunks of
    # troubleshooting.md: the DEFINITION of 'offline' (no ping >15 min, the causes)
    # sits near the top (chunk0); the FIX for a >2h silence (power-cycle via the
    # OBD-II port for 30s) sits lower (chunk2). Top-k never returns nothing, so k=1
    # returns *a* chunk and looks like it worked — but it dropped the half you
    # needed. This is why "how big is k?" is a real FDE question.
    query = ("What does it mean when a Beacon Tag is offline, and what should I "
             "do if it's still silent after more than 2 hours?")
    store.rebuild(chunking.Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(docs))

    print("\n--- HEALTHY (k=4: both the definition chunk and the fix chunk return) ---")
    hits = store.retrieve(query, 4)
    display.show_retrieval(query, hits)
    answer(query, hits)

    print("\n--- BROKEN (k=1: only the single nearest chunk returns) ---")
    hits = store.retrieve(query, 1)
    display.show_retrieval(query, hits)
    answer(query, hits)

    print("\nDIAGNOSIS: same query, same chunks in the DB, same model — only k")
    print("changed. If the k=1 answer is missing the power-cycle fix (or the")
    print("definition), the bug is RETRIEVAL: the right chunk exists but never")
    print("reached the model. Fix is k / better ranking (Day 6), NOT chunking or")
    print("the prompt. Check the k=4 retrieval list: was the second half at rank 2+?")


# ── BREAK #3: HALLUCINATION — same retrieval, guardrail on vs. off. ───────────
# The escalation ladder. Each rung removes ONE more thing that was holding the
# model back, so when a fabrication finally appears you can name exactly which
# safety mechanism was load-bearing. Ordered weakest-bait to strongest.
RUNGS = [
    ("1. GROUNDED, temp 0   (guardrail on: 'ONLY from context, else I don't know')",
     prompts.GROUNDED_SYSTEM, 0.0),
    ("2. NAIVE, temp 0      (guardrail off — but the 'Context:' scaffold remains)",
     prompts.NAIVE_SYSTEM, 0.0),
    (f"3. NAIVE, temp {HOT_TEMP}    (same prompt, sampling unlocked)",
     prompts.NAIVE_SYSTEM, HOT_TEMP),
    (f"4. SALES, temp {HOT_TEMP}    (adversarial persona: refusing is FORBIDDEN)",
     prompts.SALES_SYSTEM, HOT_TEMP),
    # Rung 4 moves TWO variables at once (persona AND temperature), so on its own
    # it cannot say which one caused the fabrication. Rung 5 is the missing cell of
    # the 2x2 — persona hot vs. persona cold. Measured answer: it fabricates at
    # temp 0 too, which is what proves temperature was never the cause.
    ("5. SALES, temp 0     (the 2x2's missing cell: persona WITHOUT the hot dice)",
     prompts.SALES_SYSTEM, 0.0),
]


def probe_hallucination(store: vectorstore.VectorStore, query: str, why: str) -> None:
    """Run ONE out-of-corpus question up the whole ladder on the EXACT same
    retrieved context, so the only variables are the prompt and the temperature.

    Retrieval happens once, deliberately. If rung 4 fabricates and rung 1 refuses
    on byte-identical context, no one can argue the bug lives in retrieval."""
    print(f"\n>>> PROBE: {why}")
    hits = store.retrieve(query, TOP_K)
    display.show_retrieval(query, hits)

    for label, system, temp in RUNGS:
        print(f"\n--- {label} ---")
        display.print_answer(
            generation.generate(client, query, hits, system, temperature=temp)
        )


def run_hallucination(store: vectorstore.VectorStore) -> None:
    display.banner("BREAK #3 — HALLUCINATION  (out-of-corpus; the guardrail is the only wall)")
    docs = chunking.load_documents(settings.docs_dir)
    # Nothing in the Beacon docs answers EITHER question below. But cosine ALWAYS
    # returns top-k — it hands over the 4 least-bad chunks no matter how irrelevant
    # (Day 4 Part C: scores flat ~0.51, scattered across docs). The retrieved
    # context is IDENTICAL across the grounded/naive pair, so the system prompt is
    # the only variable: honest refusal vs. confident fabrication.
    #
    # TWO probes, because the FIRST run of this taught a lesson we didn't plan:
    # not every out-of-corpus question is hallucination bait. The shape of the
    # question decides whether the model invents anything at all.
    store.rebuild(chunking.Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(docs))

    # PROBE A — the WEAK bait (kept deliberately: it's the control).
    # A yes/no about a third-party product. Beacon is fictional, so the model has
    # no priors to draw on and "not mentioned" IS the highest-probability answer.
    # Both prompts land in roughly the same place. Watch the naive one anyway: it
    # hedges INTO ungrounded territory ("webhooks could be used to integrate with
    # Salesforce…") — inference the docs never licensed. A customer reads that as
    # a yes. Soft hallucination, not flagrant, and easy to miss in review.
    probe_hallucination(
        store,
        "Does Beacon integrate with Salesforce?",
        "WEAK bait — yes/no about a third party; nothing to confabulate.",
    )

    # PROBE B — the STRONG bait, and the one that should actually break.
    # Three properties the weak bait lacks:
    #   1. PRESUPPOSITION — it doesn't ask *whether* an SLA exists, it asks what
    #      the number IS. Refusing now means contradicting the question's premise.
    #   2. NEAR-MISS RETRIEVAL — pricing.md HAS a "Support SLA" column (24/7 phone,
    #      1h) but says nothing about UPTIME. So a topically perfect, high-scoring
    #      chunk comes back that does not contain the answer. That's the dangerous
    #      case: retrieval looks healthy on the score line and is still useless.
    #   3. A STRONG WORLD PRIOR — "99.9%" is the default SaaS uptime number. The
    #      model doesn't need the docs to produce a plausible-looking figure.
    # If the naive run emits a percentage, that number came from pretraining, not
    # from Aldritch Logistics — and nothing in the pipeline would have flagged it.
    probe_hallucination(
        store,
        "What uptime SLA percentage does Beacon guarantee on the Enterprise plan?",
        "STRONG bait — presupposes an answer, asks for a NUMBER, and retrieval "
        "returns a chunk that mentions 'SLA' without ever stating an uptime.",
    )

    print("\nDIAGNOSIS: identical retrieved chunks per probe — only the PROMPT and")
    print("TEMPERATURE moved. Read the ladder bottom-up and find the rung where it")
    print("first invents something; THAT names the safety mechanism that was")
    print("actually load-bearing. Three things this run is really teaching:")
    print()
    print("  1. TEMPERATURE WAS A RED HERRING (measured, not assumed). Rungs 2 and")
    print("     3 are the same prompt at temp 0 vs 0.9 and BOTH hedge; rung 4")
    print("     fabricates at 0.9 AND at temp 0. The 2x2 has a clean main effect of")
    print("     PERSONA and no effect of sampling. What temperature actually broke")
    print("     was output FORMAT: grounded@0.9 once emitted a bare '[1] (Source:")
    print("     pricing.md#chunk1)' and no answer. Structure degrades before truth.")
    print()
    print("  2. 'NAIVE' WAS NEVER UNGROUNDED. format_context still wraps every")
    print("     rung in 'Context: [1]… Question:'. Prompt STRUCTURE is a guardrail")
    print("     independent of prompt TEXT — which is why rung 4 has to attack the")
    print("     persona, not just delete an instruction.")
    print()
    print("  3. WATCH THE CITATIONS, NOT JUST THE CLAIMS. The grounded rung has")
    print("     already been caught stating a TRUE fact under a WRONG [n]. A")
    print("     fabricated number is greppable; a real claim with a bad citation")
    print("     passes review and quietly poisons your audit trail. 'Reply exactly")
    print("     X' is a request to a sampler, not a constraint — if you need the")
    print("     refusal string or valid citations guaranteed, that is CODE around")
    print("     the model (validate every [n] resolves), not a nicer prompt.")


# ── BREAK #4: STALE INDEX — edit the doc, skip re-embedding, get the old answer. ─
def run_stale(store: vectorstore.VectorStore) -> None:
    display.banner("BREAK #4 — STALE INDEX  (the vector store is a photo of the old truth)")
    docs = chunking.load_documents(settings.docs_dir)
    query = "How much does the Starter plan cost per active Tag per month?"

    # v1: index the docs exactly as they are on disk today. Starter is $4.
    print("\n--- v1: index the docs as-is, then query ---")
    store.rebuild(chunking.Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(docs))
    hits = store.retrieve(query, TOP_K)
    display.show_retrieval(query, hits)
    answer(query, hits)

    # SIMULATED EDIT: pretend an FDE updated pricing.md — Starter goes $4 -> $6 —
    # but nobody re-ran the ingest job. We build NEW Document objects with the
    # edited text and deliberately do NOT call store.rebuild again. The vectors +
    # payloads in Qdrant still describe the $4 world.
    print("\n--- EDIT pricing.md ($4 -> $6) but DO NOT re-embed, then re-query ---")
    edited_docs = [
        chunking.Document(d.source, d.text.replace("| Starter    | $4", "| Starter    | $6"))
        for d in docs
    ]
    changed = any(e.text != o.text for e, o in zip(edited_docs, docs))
    print(f"  (edit applied to source text: {changed}; index NOT rebuilt)")
    hits = store.retrieve(query, TOP_K)
    display.show_retrieval(query, hits)
    answer(query, hits)  # expect the STALE $4 — the index never saw the edit

    # Now do the thing the ops job forgot: re-embed the edited docs.
    print("\n--- RE-EMBED the edited docs (re-run ingest), then re-query ---")
    store.rebuild(chunking.Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(edited_docs))
    hits = store.retrieve(query, TOP_K)
    display.show_retrieval(query, hits)
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


def log_path(mode: str) -> str:
    return os.path.join(settings.result_dir, f"day5_{mode}.txt")


def header() -> None:
    print(f"Ollama @ {settings.ollama_url} · Qdrant @ {settings.qdrant_url} · "
          f"gen={settings.gen_model} · MODE={MODE}")


def footer() -> None:
    print("\n" + "=" * 76)
    print("Each break maps to ONE pipeline stage: chunking / retrieval / prompt /")
    print("freshness. That taxonomy IS the interview answer to 'the RAG answer is")
    print("wrong — how do you debug it?'. Now write notes/day5.md from what you saw.")


def main() -> None:
    store = vectorstore.VectorStore.connect(settings.qdrant_url, COLLECTION, embedder=client)

    if MODE in MODES:
        # One mode -> one log file (result/day5_<mode>.txt).
        written = [log_path(MODE)]
        with runlog.tee_stdout(*written):
            header()
            MODES[MODE](store)
            footer()

    elif MODE == "all":
        # Run the whole gauntlet. The outer tee captures a combined log; each mode
        # nests an inner tee for its OWN file. Result: 5 files (one per mode +
        # day5_all.txt). header/footer land only in the combined log.
        with runlog.tee_stdout(log_path("all")):
            header()
            for name, fn in MODES.items():
                with runlog.tee_stdout(log_path(name)):  # nests: also -> per-mode file
                    fn(store)
            footer()
        written = [log_path(m) for m in MODES] + [log_path("all")]

    else:
        raise SystemExit(
            f"Unknown MODE={MODE!r}. Pick one of: {', '.join(MODES)} (or 'all')."
        )

    # Console-only summary (stdout is restored here) so you see where it landed.
    print("\nLogged to:")
    for p in written:
        print(f"  {os.path.relpath(p)}")


if __name__ == "__main__":
    main()
