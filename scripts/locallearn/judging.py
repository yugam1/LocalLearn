"""Answer-level judging — did the GENERATED ANSWER get it right, not just the chunk.

Day 6 measured retrieval: did the right chunk reach the top of the pile? Grading
that needed no LLM, because a retrieved chunk is verbatim source text — a literal
substring check (`must_contain in chunk.text`) is airtight. A GENERATED answer is
NOT verbatim source text; the model paraphrases, reorders, drops words. "1h" from
the pricing table might come out of the model's mouth as "one hour" or "an hour".
That means the exact same substring trick that was airtight in evaluation.py
becomes BRITTLE here — not because the code got worse, but because the thing
being graded changed shape. That fragility isn't a bug to patch around; it's the
actual argument for an LLM judge at this layer: a judge reads for MEANING, for
the same reason vector search beats keyword search on a paraphrase (Day 6,
chapter 2, one layer up the stack).

Two graders per answer, on purpose, so they can be compared and their
disagreements inspected instead of trusting either one blindly:

  exact_match  — cheap, deterministic, zero LLM calls. A PASS is a strong
                 signal (the model's own words happened to contain the fact
                 verbatim). A FAIL is a WEAK signal — it might just mean the
                 model paraphrased a correct fact, not that it got it wrong.
  llm_judge    — reads the actual generated answer against a plain-English
                 rubric (`criteria`) and returns PASS/FAIL + one reason. Costs a
                 generation call per verdict. A judge is itself an unverified
                 component until you test it — see sanity_check_judge() and
                 judge_self_consistency(), the answer-level equivalent of Day
                 6's sanity_check_gold(): test the GRADER before trusting a
                 single number it produces.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field


JUDGE_SYSTEM = (
    "You are a strict grading assistant. You will be given a QUESTION, a RUBRIC "
    "describing the facts a correct answer must contain, and a CANDIDATE ANSWER. "
    "Decide whether the candidate answer satisfies the rubric. Minor wording "
    "differences, rephrasing, and extra (correct) detail are FINE. A missing "
    "required fact, a WRONG fact, or a fact the candidate invented that isn't in "
    "the rubric's spirit is NOT fine. Reply on the first line with exactly PASS "
    "or FAIL, then on a second line one short sentence explaining why."
)


@dataclass(frozen=True)
class AnswerGoldQuery:
    """One labelled question, graded at the ANSWER level (not the chunk level).

    `must_contain` is the cheap deterministic check — kept for comparison against
    the judge, not because it's trusted on its own here (see module docstring).

    `criteria` is the rubric handed to the LLM judge: what FACTS a correct answer
    must contain, in plain English. Write it about the facts, not the wording, or
    you've just re-invented a brittle substring check with extra steps.

    `needs_chunks` is a PREDICTION, written down before running: how many
    distinct source facts (usually = source chunks) a correct answer legitimately
    draws on. Same spirit as Day 6's `expects` — write it down so the run can
    prove it wrong. This is the field the baseline-vs-degraded (k=4 vs k=1)
    pipeline comparison is designed to stress: a `needs_chunks=2` query is the
    one k=1 is predicted to break.

    `expects_refusal` marks the one out-of-corpus canary — correct behavior is
    "I don't know", not an answer.
    """

    query: str
    must_contain: str
    criteria: str
    needs_chunks: int
    expects_refusal: bool = False
    note: str = ""


@dataclass(frozen=True)
class JudgeVerdict:
    passed: bool
    reason: str


def exact_match(gold: AnswerGoldQuery, answer: str) -> bool:
    """The cheap check. Case-insensitive substring, same mechanics as Day 6's
    GoldQuery.matches() — but checked against the model's own PROSE, not
    verbatim source text, which is exactly why it's expected to under-count
    correct answers here. A FAIL from this function is a lead to inspect, not a
    verdict to trust on its own."""
    return gold.must_contain.lower() in answer.lower()


def llm_judge(client, query: str, criteria: str, answer: str) -> JudgeVerdict:
    """Ask the model to grade another model's answer against a rubric.

    temperature=0.0 on purpose: a verdict that isn't reproducible is a verdict
    that's actually measuring dice, not the answer. Parsed defensively — Day 5
    already proved "reply exactly X" is a request to the sampler, not a rule the
    model obeys, and a reranker score is not the only place that bites you."""
    raw = client.chat(
        system=JUDGE_SYSTEM,
        user=f"QUESTION: {query}\n\nRUBRIC: {criteria}\n\nCANDIDATE ANSWER:\n"
             f"{answer}\n\nVerdict:",
        temperature=0.0,
    )
    lines = [ln.strip() for ln in raw.splitlines() if ln.strip()]
    first = lines[0].upper() if lines else ""
    passed = first.startswith("PASS")
    reason = lines[1] if len(lines) > 1 else raw.strip()
    return JudgeVerdict(passed=passed, reason=reason)


def sanity_check_judge(client, sample: AnswerGoldQuery,
                        good_answer: str, bad_answer: str) -> None:
    """Canary check: can the judge actually discriminate, or does it rubber-stamp
    everything PASS? Lenience bias (an LLM judge that's too agreeable) is a
    well-documented failure mode, and it's dangerous precisely because it makes
    every downstream % look great. Feed it one obviously-correct and one
    obviously-wrong answer to the SAME question; if it can't tell them apart,
    nothing measured below this point is trustworthy.

    Mirrors evaluation.sanity_check_gold() one layer up the stack: test the
    TEST before you believe a single number it produces — except here the thing
    being tested is the grader itself, not the gold set."""
    print("\nJudge sanity check (can it actually discriminate PASS from FAIL?)")
    good = llm_judge(client, sample.query, sample.criteria, good_answer)
    bad = llm_judge(client, sample.query, sample.criteria, bad_answer)
    print(f"  known-GOOD answer -> {'PASS' if good.passed else 'FAIL'}  "
          f"({good.reason})")
    print(f"  known-BAD  answer -> {'PASS' if bad.passed else 'FAIL'}  "
          f"({bad.reason})")
    if not good.passed or bad.passed:
        raise SystemExit(
            "\nJudge sanity check FAILED: it did not correctly separate a "
            "known-good answer from a known-bad one on the same question. Do "
            "NOT trust any verdict below until this passes — every % downstream "
            "inherits this bug silently, the same way an unchecked gold set "
            "silently corrupted Day 6's numbers."
        )


def judge_self_consistency(client, sample: AnswerGoldQuery, answer: str,
                            repeats: int = 3) -> bool:
    """Run the identical judge call several times at temperature=0 and check it
    gives the same verdict every time. If it doesn't, the judge ITSELF is a
    noise source, and every % in the tables below has that noise baked in
    without any way to see it from the aggregate number alone."""
    verdicts = [llm_judge(client, sample.query, sample.criteria, answer).passed
                for _ in range(repeats)]
    stable = len(set(verdicts)) == 1
    print(f"\nJudge self-consistency check ({repeats}x @ temperature=0): "
          f"{verdicts} -> {'STABLE' if stable else 'UNSTABLE — judge is noisy'}")
    return stable


@dataclass
class AnswerResult:
    """One (gold query, pipeline) cell. Keeps the answer text and the hits that
    fed it — same reasoning as Day 6's QueryResult.top_refs: a FAIL is only
    diagnosable if you can see what the model actually said and what it was
    given to say it with."""

    gold: AnswerGoldQuery
    answer: str
    hits: list
    exact: bool
    judge: JudgeVerdict
    latency_ms: float


@dataclass
class PipelineRun:
    """One pipeline's (retriever + prompt + k) full pass over the gold set."""

    name: str
    results: list[AnswerResult] = field(default_factory=list)

    @property
    def exact_pct(self) -> float:
        return 100 * sum(r.exact for r in self.results) / len(self.results)

    @property
    def judge_pct(self) -> float:
        return 100 * sum(r.judge.passed for r in self.results) / len(self.results)

    @property
    def disagree_pct(self) -> float:
        """Fraction where the cheap check and the judge disagreed — the number
        that tells you how much to trust exact_match on its own for THIS gold
        set. High disagreement doesn't mean the judge is wrong; per the module
        docstring, it usually means exact_match is under-counting paraphrases."""
        n = len(self.results)
        return 100 * sum(r.exact != r.judge.passed for r in self.results) / n

    @property
    def p50_ms(self) -> float:
        import statistics
        return statistics.median(r.latency_ms for r in self.results)


def evaluate_pipeline(name: str, pipeline_fn, gold: list[AnswerGoldQuery],
                       client) -> PipelineRun:
    """Run one full pipeline (retrieve + generate) over the whole gold set,
    grading every answer both ways. `pipeline_fn(query) -> (answer_text, hits)`
    is the ONLY thing that varies between pipelines, so any difference in the
    resulting table is attributable to that pipeline's config and nothing else
    — same discipline as Day 6's evaluate() taking a retriever as its one
    variable.

    Timing wraps retrieve+generate+judge together: that's the full latency a
    real request (plus an async grading job) would actually incur."""
    run = PipelineRun(name)
    for g in gold:
        t0 = time.perf_counter()
        answer, hits = pipeline_fn(g.query)
        exact = exact_match(g, answer)
        verdict = llm_judge(client, g.query, g.criteria, answer)
        elapsed = (time.perf_counter() - t0) * 1000
        run.results.append(AnswerResult(g, answer, hits, exact, verdict, elapsed))
    return run


def print_comparison(runs: list[PipelineRun]) -> None:
    """The money table — quality (two ways) and cost, side by side."""
    print(f"\n{'='*76}")
    print(f"RESULTS — {len(runs[0].results)} gold questions, answer-level")
    print("=" * 76)
    print(f"{'pipeline':<12} {'exact%':>8} {'judge%':>8} {'disagree%':>10} "
          f"{'p50 ms':>10}")
    print("-" * 76)
    for r in runs:
        print(f"{r.name:<12} {r.exact_pct:>7.1f}% {r.judge_pct:>7.1f}% "
              f"{r.disagree_pct:>9.1f}% {r.p50_ms:>10.1f}")


def print_per_query(runs: list[PipelineRun]) -> None:
    """Per-question exact/judge verdicts. The aggregate % hides WHICH questions
    moved — a pipeline can lift the average while regressing a question that
    used to pass, and only this view shows it (same lesson as Day 6's
    print_per_query)."""
    print(f"\n{'-'*76}")
    print("PER-QUESTION VERDICTS  (E=exact_match, J=judge; both shown per pipeline)")
    print("-" * 76)
    names = [r.name for r in runs]
    header = f"{'needs':<6} {'question':<38} " + " ".join(f"{n:>12}" for n in names)
    print(header)
    for i, ar in enumerate(runs[0].results):
        cells = []
        for r in runs:
            a = r.results[i]
            cells.append(f"E{'✓' if a.exact else '✗'} J{'✓' if a.judge.passed else '✗':>8}")
        q = ar.gold.query[:36] + ("…" if len(ar.gold.query) > 36 else "")
        print(f"{ar.gold.needs_chunks:<6} {q:<38} " + " ".join(f"{c:>12}" for c in cells))


def print_disagreements(run: PipelineRun, limit: int = 6) -> None:
    """Every question where exact_match and the judge disagreed, with the full
    answer text — the concrete evidence for deciding which grader to trust on
    THIS gold set, instead of taking either % on faith."""
    print(f"\n{'-'*76}")
    print(f"EXACT vs JUDGE DISAGREEMENTS — pipeline {run.name!r}")
    print("-" * 76)
    shown = 0
    for r in run.results:
        if r.exact == r.judge.passed:
            continue
        shown += 1
        print(f"\n  Q: {r.gold.query}")
        print(f"     must_contain: {r.gold.must_contain!r}  "
              f"(exact={'PASS' if r.exact else 'FAIL'})")
        print(f"     judge: {'PASS' if r.judge.passed else 'FAIL'} "
              f"({r.judge.reason})")
        print(f"     answer: {r.answer[:200]}{'…' if len(r.answer) > 200 else ''}")
        if shown >= limit:
            break
    if shown == 0:
        print("  (none — exact_match and the judge agreed on every question)")
