#!/usr/bin/env python3
"""
Day 7 — Does the ANSWER actually get it right? (the eval harness, one level up)

Day 6 measured retrieval: did the right CHUNK reach the top of the pile? That's
the ceiling metric — necessary, not sufficient. A chunk arriving is not the same
as the model USING it correctly: it can still misread it, drop half of it, or
answer confidently from the wrong one. Today closes that gap by grading the
ANSWER itself, over a 20-question gold set, two ways:

    exact_match — cheap, deterministic substring check on the model's OWN
                  WORDS. Brittle here in a way it wasn't in Day 6 (there it
                  checked verbatim SOURCE text) — see locallearn/judging.py's
                  docstring for why that's the actual argument for a judge.
    llm_judge   — a second model call that reads the answer against a
                  plain-English rubric and returns PASS/FAIL + a reason.
                  Verified BEFORE it's trusted: sanity_check_judge() proves it
                  can tell a known-good answer from a known-bad one, and
                  judge_self_consistency() proves it doesn't flip its own
                  verdict on a rerun — the answer-level equivalent of Day 6's
                  sanity_check_gold().

The pipeline comparison reruns Day 5 Break #2 — k=1 silently dropped half of a
two-chunk answer — but properly this time: not one anecdote, twenty questions,
a real percentage.

    baseline  — k=4, vector retriever (Day 4's known-good config)
    degraded  — k=1, vector retriever (Day 5 Break #2's config, at scale)

Both pipelines use GROUNDED_SYSTEM and the same retriever CLASS — only k moves.
One knob, same discipline as Day 6: change one variable, hold everything else
still, and the table can only be telling you about that one knob.

Self-logs to result/day7.txt. Run it, then fill notes/day7_post.md.
"""
import os

from locallearn import (
    config, ollama, vectorstore, chunking, retrievers, judging,
    generation, prompts, display, runlog,
)

settings = config.Settings.from_env()
client = ollama.OllamaClient(settings.ollama_url, settings.embed_model, settings.gen_model)
COLLECTION = "day7_answers"

# Day 4/5/6 baseline knobs, held fixed — same corpus, same chunker, every day.
CHUNK_WORDS = 120
CHUNK_OVERLAP = 25

# Which pipelines to run. Drop "degraded" for a faster loop while iterating on
# the gold set itself — "baseline" alone is 20 retrieve+generate+judge calls,
# "degraded" adds 20 more.
PIPELINES = [p.strip() for p in
             os.environ.get("PIPELINES", "baseline,degraded").split(",") if p.strip()]

# ── JUDGE CANARY ────────────────────────────────────────────────────────────
# Fixtures for sanity_check_judge(): one obviously-correct and one obviously-
# wrong answer to the SAME question. If the judge can't tell these apart,
# nothing measured below is trustworthy — see judging.sanity_check_judge().
JUDGE_CANARY = judging.AnswerGoldQuery(
    query="What is the default idle threshold?",
    must_contain="10 minutes",
    criteria="States the default idle threshold is 10 minutes.",
    needs_chunks=1,
)
JUDGE_CANARY_GOOD = "The default idle threshold is 10 minutes, configurable per fleet."
JUDGE_CANARY_BAD = "Beacon does not have an idle threshold feature."


# ── THE GOLD SET (20 answer-level questions) ────────────────────────────────
# `must_contain` is the cheap check, kept for comparison against the judge, not
# because it's trusted alone (see judging.py). `criteria` is the judge's rubric
# — write it about FACTS, not wording. `needs_chunks` is a PREDICTION, written
# down before running: how many distinct source facts does a correct answer
# legitimately need? That's the field the baseline (k=4) vs degraded (k=1)
# comparison is designed to stress — a needs_chunks=2 question is the one k=1
# is predicted to break.
GOLD = [
    judging.AnswerGoldQuery(
        query="Where do I change the idle threshold, and what is it by default?",
        must_contain="10 minutes",
        criteria="States the default idle threshold is 10 minutes AND says it's "
                 "changed at Console → Fleet → Settings → Idle threshold.",
        needs_chunks=1,
        note="Day 6's Part B demo query, reused so this gold set connects to "
             "last time's finding: two different retrievers returned different "
             "context here but produced a byte-identical answer.",
    ),
    judging.AnswerGoldQuery(
        query="What should I do if a Tag has been silent for more than 2 hours?",
        must_contain="OBD-II",
        criteria="Instructs the user to power-cycle the Tag by unplugging it "
                 "from the OBD-II port for about 30 seconds and reinserting it "
                 "— not just naming a possible cause (like a blown fuse) "
                 "without giving the actual fix.",
        needs_chunks=2,
        note="Day 5 Break #2's exact query, at scale instead of as one "
             "anecdote. must_contain='OBD-II' is intentionally coarse — that "
             "word also appears in the unrelated blown-fuse cause, so an "
             "exact_match PASS here does not by itself prove the fix was "
             "given. That's why criteria carries the real check.",
    ),
    judging.AnswerGoldQuery(
        query="What URL is the Beacon web console at?",
        must_contain="console.beacon.aldritch.example",
        criteria="States the console URL is console.beacon.aldritch.example.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="What is the serial number format printed on a Tag label?",
        must_contain="BT-XXXXXXXXX",
        criteria="States the serial format is a 12-character code shaped like "
                 "BT-XXXXXXXXX.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="How many fleets can a single Beacon account hold?",
        must_contain="50",
        criteria="States the max is 50 fleets per account.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="Can a read-only user draw zones (geofences) on the map?",
        must_contain="Viewer",
        criteria="Says no — a Viewer role is read-only and cannot draw "
                 "geofences; only a Dispatcher (or Owner) can.",
        needs_chunks=2,
        note="Requires combining the roles paragraph with the geofence "
             "definition, which live in different parts of overview.md — "
             "predicted 2 chunks, unlike most of this set.",
    ),
    judging.AnswerGoldQuery(
        query="Why do I get alerted every time my drivers pause for lunch, "
              "and how do I fix it?",
        must_contain="idle threshold",
        criteria="Explains idle alerts fire when a vehicle stops longer than "
                 "the fleet's idle threshold (default 10 min), and that "
                 "raising the threshold per fleet fixes it.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="My van's tracker stopped showing up on the live map — what's "
              "going on?",
        must_contain="15 minutes",
        criteria="Explains a Tag is marked offline after no ping for more "
                 "than 15 minutes and names at least one plausible cause.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="How long until old trip data gets thinned out, and what "
              "happens to it?",
        must_contain="downsampled",
        criteria="States that after 90 days, route history is downsampled to "
                 "one point per 5 minutes and kept for the plan's retention "
                 "window.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="What does the Starter plan cost per Tag per month?",
        must_contain="$4",
        criteria="States Starter costs $4 per Tag per month.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="What support SLA does the Enterprise plan include?",
        must_contain="1h",
        criteria="States Enterprise gets 24/7 phone support with a 1-hour "
                 "response SLA.",
        needs_chunks=1,
        note="PREDICTED FRAGILE for exact_match: the source table says '1h' "
             "but the model will likely write '1 hour' in prose. Watch this "
             "row for an exact/judge disagreement.",
    ),
    judging.AnswerGoldQuery(
        query="What happens to new Tags once I hit my plan's Max Tags cap?",
        must_contain="pending",
        criteria="States new Tags beyond the cap still report but sit in a "
                 "pending state and aren't shown on the map until you "
                 "upgrade or remove existing Tags.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="Who is allowed to change the billing plan or payment method, "
              "and where?",
        must_contain="Owner",
        criteria="States only an account Owner can change plan/payment "
                 "method, from Console → Settings → Billing.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="If I downgrade and go over the new plan's Tag cap, which Tags "
              "get deactivated first?",
        must_contain="newest",
        criteria="States the most recently activated Tags are moved to "
                 "pending first (newest deactivated first) until back under "
                 "the cap.",
        needs_chunks=1,
        note="PREDICTED FRAGILE for exact_match: 'newest deactivated first' "
             "is a parenthetical in the source; the model may rephrase as "
             "'the ones added most recently' without the word 'newest'.",
    ),
    judging.AnswerGoldQuery(
        query="Do webhook events work on the Starter plan?",
        must_contain="silently dropped",
        criteria="States webhooks are a Pro/Enterprise feature and events are "
                 "silently dropped (no error, no delivery) on Starter.",
        needs_chunks=1,
        note="PREDICTED FRAGILE for exact_match: 'silently dropped' is exact "
             "source wording the model may rephrase as 'not sent' or "
             "'ignored without notification'.",
    ),
    judging.AnswerGoldQuery(
        query="If my webhook endpoint is down, does Beacon keep retrying "
              "forever?",
        must_contain="3 times",
        criteria="States Beacon retries a failing webhook 3 times with "
                 "exponential backoff then gives up (does not queue "
                 "indefinitely); an endpoint down more than ~10 minutes "
                 "permanently misses events.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="How do I stop being charged for a Tag I am not using?",
        must_contain="not billed",
        criteria="States a Tag that never reports a ping in the billing "
                 "month is not billed at all — the fix is to let it go "
                 "inactive, not necessarily an explicit cancel step.",
        needs_chunks=1,
        note="The Day 2/3 polarity-trap query, reused at answer level.",
    ),
    judging.AnswerGoldQuery(
        query="My Tag lost power entirely and other OBD accessories in the "
              "same vehicle also stopped working — what's wrong?",
        must_contain="fuse",
        criteria="Identifies a blown Tag fuse (the Tag draws power from the "
                 "OBD-II port) as the cause when other OBD accessories also "
                 "lost power.",
        needs_chunks=1,
    ),
    judging.AnswerGoldQuery(
        query="Does Beacon integrate with Salesforce?",
        must_contain="I don't know based on the provided documents.",
        criteria="Refuses to state a definite yes/no about Salesforce "
                 "integration, or explicitly says the documentation doesn't "
                 "cover it. Any answer that asserts Beacon DOES or DOES NOT "
                 "integrate with Salesforce as a stated fact is WRONG — the "
                 "corpus never mentions Salesforce.",
        needs_chunks=0,
        expects_refusal=True,
        note="The Day 4/5 out-of-corpus canary, reused as gold query #19 so "
             "this set also tests grounding discipline, not just fact recall. "
             "must_contain is GROUNDED_SYSTEM's exact refusal string, so a "
             "correct-but-paraphrased refusal ('the docs don't mention "
             "Salesforce') would FAIL exact_match but should PASS the judge "
             "— predicted disagreement, and a meaningful one to watch for.",
    ),
    judging.AnswerGoldQuery(
        query="I'm on the Pro plan with 200 active Tags and want to add 60 "
              "more — will they all show up on the map?",
        must_contain="250",
        criteria="Correctly reasons that 200+60=260 exceeds Pro's 250 Tag "
                 "cap, so states 10 of the new Tags will NOT show on the map "
                 "(pending state) until upgrade or removal.",
        needs_chunks=2,
        note="The hardest question on purpose: needs the Pro cap NUMBER "
             "(pricing table) AND the cap-consequence DESCRIPTION (overages "
             "paragraph) from two different parts of pricing.md, plus actual "
             "arithmetic. Dropping either chunk breaks the answer a "
             "DIFFERENT way — drop the table and it won't know the cap is "
             "250; drop the overages paragraph and it may wrongly say 'yes, "
             "you'll just get billed more.' The single best test of whether "
             "k actually matters in this set.",
    ),
]


def build(store: vectorstore.VectorStore):
    """Ingest once — same corpus, same chunker knobs as every prior day, so
    today's numbers are comparable to Day 4-6's without a footnote."""
    docs = chunking.load_documents(settings.docs_dir)
    chunks = chunking.Chunker(CHUNK_WORDS, CHUNK_OVERLAP).chunk(docs)
    print(f"\nIngested {len(docs)} docs -> {len(chunks)} chunks "
          f"({CHUNK_WORDS}-word windows, {CHUNK_OVERLAP} overlap)")
    store.rebuild(chunks)
    print(f"  dense: Qdrant collection {COLLECTION!r} on {settings.qdrant_url}")
    return chunks


def make_pipeline(retriever, k: int, system: str):
    """One (retriever, k, prompt) combo, wrapped as the single function
    judging.evaluate_pipeline() needs: query -> (answer, hits). Keeping this as
    a closure rather than a class is deliberate — there's exactly one thing
    that varies here (which config), not a family of interchangeable strategies
    the way Day 6's retrievers were."""
    def run(query: str):
        hits = retriever.retrieve(query, k)
        answer = generation.generate(client, query, hits, system)
        return answer, hits
    return run


def main() -> None:
    client.require_model(settings.gen_model.split(":")[0])
    store = vectorstore.VectorStore.connect(
        settings.qdrant_url, COLLECTION, embedder=client)

    print(f"Ollama @ {settings.ollama_url} · Qdrant @ {settings.qdrant_url} · "
          f"gen={settings.gen_model} · embed={settings.embed_model}")
    build(store)
    vec_r = retrievers.VectorRetriever(store)

    display.banner("STEP 0 — is the JUDGE trustworthy before it grades anything?")
    judging.sanity_check_judge(client, JUDGE_CANARY, JUDGE_CANARY_GOOD, JUDGE_CANARY_BAD)
    judging.judge_self_consistency(client, JUDGE_CANARY, JUDGE_CANARY_GOOD)

    configs = {
        "baseline": (vec_r, 4, prompts.GROUNDED_SYSTEM),
        "degraded": (vec_r, 1, prompts.GROUNDED_SYSTEM),
    }
    unknown = [p for p in PIPELINES if p not in configs]
    if unknown:
        raise SystemExit(f"Unknown pipeline(s) {unknown}. Pick from: {', '.join(configs)}")

    display.banner(f"STEP 1 — {len(GOLD)} gold questions x {len(PIPELINES)} "
                    f"pipeline(s): {', '.join(PIPELINES)}")
    print("Each question costs 1 generate call + 1 judge call, per pipeline — "
          f"expect {len(GOLD) * 2 * len(PIPELINES)} sequential LLM calls total.")

    runs = []
    for name in PIPELINES:
        retriever, k, system = configs[name]
        print(f"  running {name!r} (k={k})…", flush=True)
        runs.append(judging.evaluate_pipeline(
            name, make_pipeline(retriever, k, system), GOLD, client))

    judging.print_comparison(runs)
    judging.print_per_query(runs)
    for r in runs:
        judging.print_disagreements(r)

    display.banner("STEP 2 — one question, side by side, in full")
    # The needs_chunks=2 question is the one k=1 is predicted to break. Show the
    # actual retrieved chunks and the actual answer for both pipelines, so the
    # % above has to justify itself in readable output — same move as Day 6's
    # Part B.
    demo = GOLD[1]  # the >2h silent Tag question
    for name in PIPELINES:
        retriever, k, system = configs[name]
        hits = retriever.retrieve(demo.query, k)
        print(f"\n--- pipeline: {name} (k={k}) ---")
        display.show_retrieval(demo.query, hits)
        display.print_answer(generation.generate(client, demo.query, hits, system))

    print("\n" + "=" * 76)
    print("THE QUESTION TO ANSWER IN notes/day7_post.md:")
    print("  Did the judge PASS the sanity check and the self-consistency check?")
    print("  What % did baseline get (exact vs judge)? What % did degraded get?")
    print("  Which needs_chunks=2 questions actually broke under k=1, and did")
    print("  every needs_chunks=1 question survive it?")
    print("  Where did exact_match and the judge disagree, and who was right?")
    print("A gold-set % you can't trace to a specific question's answer text is")
    print("not a measurement, it's a vibe with a number attached.")


if __name__ == "__main__":
    with runlog.tee_stdout(os.path.join(settings.result_dir, "day7.txt")):
        main()
    print(f"\nLogged to: {os.path.relpath(os.path.join(settings.result_dir, 'day7.txt'))}")
