"""Retrieval evaluation — turn "it feels better" into a number.

Day 6's whole thesis: adding hybrid search or a reranker is not an improvement
until you have measured it. Anyone can bolt on BM25 and declare victory; the FDE
move is to show a table where the new thing sometimes LOSES, and to know the
latency you paid.

Deliberately scoped to RETRIEVAL, not answers. A gold answer needs an LLM judge
and that's Day 7. Here the question is narrower and much cheaper to check:
**did the chunk containing the answer make it into the top k?** If it didn't, no
prompt on earth saves you — that's the Day 5 Break #2 lesson turned into a metric.

Two metrics, because they answer different questions:

  Recall@k — did the right chunk appear at all in the top k? This is the one that
             gates everything downstream. Recall@k = 0.6 means 40% of questions
             are UNANSWERABLE no matter how good your model is.
  MRR      — 1/rank of the first correct chunk, averaged. Recall says "was it in
             there"; MRR says "how close to the top". Both matter: a chunk at
             rank 4 survives k=4 but dies the moment someone trims k to save
             tokens, and MRR is what warns you that you're one config change away.
"""
from __future__ import annotations

import statistics
import time
from dataclasses import dataclass


@dataclass(frozen=True)
class GoldQuery:
    """One labelled question.

    `must_contain` is a literal substring that the RIGHT chunk provably has.
    Substrings rather than chunk ids on purpose: chunk indices shift the moment
    anyone touches the chunker knobs, which would silently invalidate the gold
    set. A substring stays correct across re-chunking, so this gold set survives
    Day 5's experiments.

    `expects` names which retriever we PREDICT will win, so the results table can
    show where the prediction failed. Writing the prediction down before the run
    is the difference between an experiment and a demo.
    """

    query: str
    must_contain: str
    expects: str  # "lexical" | "semantic" | "either"
    note: str = ""
    # Set when the phrase legitimately appears in more than one source doc and
    # any of them counts as a correct answer. Forces the gold-set author to
    # ACKNOWLEDGE each ambiguity instead of discovering it as a silent false
    # positive later — see sanity_check_gold().
    ambiguous_ok: bool = False

    def matches(self, payload: dict) -> bool:
        """The entire grader: is the answer-bearing substring in this chunk?

        Case-insensitive substring, nothing cleverer. That cheapness is the
        feature — grading costs no LLM call and cannot itself hallucinate, so a
        disagreement in the results table is always about retrieval and never
        about the judge. The price is that the phrase must be unique enough to
        be unambiguous, which is exactly what sanity_check_gold() enforces.
        """
        return self.must_contain.lower() in payload["text"].lower()


@dataclass
class QueryResult:
    """One (retriever, gold query) cell: how the retriever did on this question.

    Keeps `top_refs` even when the query succeeded, because the debugging value
    is in what it retrieved INSTEAD — a miss is only diagnosable if you can see
    the four wrong chunks it preferred.
    """

    gold: GoldQuery
    rank: int | None  # 1-based rank of the first correct chunk; None = missed
    latency_ms: float
    top_refs: list[str]

    @property
    def rr(self) -> float:
        """Reciprocal rank: 1.0 at rank 1, 0.5 at rank 2, 0.0 on a miss.

        The 1/rank curve is deliberately harsh at the top — moving a chunk from
        rank 2 to rank 1 is worth more than moving it from 8 to 4, which matches
        what actually matters when k gets trimmed to save prompt tokens.
        """
        return 1.0 / self.rank if self.rank else 0.0


@dataclass
class RunResult:
    """One retriever's full pass over the gold set — one row of the table."""

    name: str
    per_query: list[QueryResult]

    @property
    def recall(self) -> float:
        """Fraction of queries where the right chunk landed anywhere in top-k.

        The ceiling metric: whatever this number is, no prompt, model, or
        temperature can push end-to-end accuracy above it.
        """
        return sum(1 for r in self.per_query if r.rank) / len(self.per_query)

    @property
    def mrr(self) -> float:
        """Mean reciprocal rank — recall weighted by HOW HIGH the chunk landed.

        Two retrievers can tie on Recall@4 while one puts every answer at rank 1
        and the other at rank 4. MRR is what separates them, and it's the early
        warning that a k reduction would wreck you.
        """
        return sum(r.rr for r in self.per_query) / len(self.per_query)

    @property
    def p50_ms(self) -> float:
        """Typical per-query latency. The cost half of the quality/cost table —
        an MRR gain is only an improvement once you've named what it cost."""
        return statistics.median(r.latency_ms for r in self.per_query)

    @property
    def p95_ms(self) -> float:
        """Tail latency — the number an SLO is written against, not the average.
        A reranker with a fine p50 and a brutal p95 is still a bad pager night."""
        # With ~10 queries this is really "the worst one" — labelled p95 because
        # that's the number you'd actually be held to in production. Small-N tail
        # statistics are shaky; the ORDER of magnitude is the honest takeaway.
        lat = sorted(r.latency_ms for r in self.per_query)
        return lat[min(len(lat) - 1, int(0.95 * len(lat)))]


def sanity_check_gold(gold: list[GoldQuery], chunks) -> None:
    """Test the TEST before trusting any number it produces.

    Two failure modes, both of which silently corrupt every metric downstream and
    neither of which announces itself:

    UNWINNABLE — `must_contain` matches no chunk at all. The query can never be
        scored correct, so it drags every retriever's Recall down by the same
        amount. The COMPARISON still looks sane (all arms equally penalised),
        which is what makes it dangerous: the relative table reads fine while the
        absolute numbers are simply wrong. Hard failure.

    AMBIGUOUS — the phrase appears in chunks from DIFFERENT source docs. Now a
        retriever can fetch a chunk that happens to contain the string in an
        unrelated context and be scored CORRECT. This is a false positive in the
        grader itself, and it inflates whichever retriever is luckiest. Found
        exactly this while building the Day 6 set: "30 seconds" matched both the
        power-cycle fix AND overview.md's "reports a GPS ping every 30 seconds".
        Warning + must be acknowledged with ambiguous_ok=True.

    Matches within the SAME doc are benign — that's just the chunker's overlap
    window duplicating a phrase across adjacent chunks, and both copies are
    equally correct.

    The generalisable point: an eval harness is code, and code has bugs. An
    unchecked gold set will happily report three digits of precision about
    nothing.
    """
    print("\nGold-set sanity check (is the TEST itself sound?)")
    unwinnable, unacknowledged = [], []
    for g in gold:
        where = [c.ref for c in chunks if g.must_contain.lower() in c.text.lower()]
        sources = {ref.split("#")[0] for ref in where}
        if not where:
            unwinnable.append(g)
            print(f"  UNWINNABLE  {g.must_contain!r} — present in NO chunk")
        elif len(sources) > 1 and not g.ambiguous_ok:
            unacknowledged.append(g)
            print(f"  AMBIGUOUS   {g.must_contain!r} -> {', '.join(where)}"
                  f"  (spans {len(sources)} docs; a wrong chunk could score correct)")
        else:
            flag = "ok(amb)" if len(sources) > 1 else "ok"
            print(f"  {flag:<11} {g.must_contain[:32]!r:<36} -> {', '.join(where)}")

    if unwinnable or unacknowledged:
        raise SystemExit(
            f"\nGold set is not trustworthy: {len(unwinnable)} unwinnable, "
            f"{len(unacknowledged)} unacknowledged ambiguity. Fix the phrases (or "
            f"set ambiguous_ok=True where several docs genuinely answer it) before "
            f"believing any metric below."
        )


def evaluate(retriever, gold: list[GoldQuery], k: int) -> RunResult:
    """Run one retriever over the whole gold set, timing every query.

    The only thing that varies between arms is the `retriever` argument — same
    chunks, same queries, same k — so any difference in the resulting table is
    attributable to the retrieval strategy and nothing else. That's the whole
    reason this takes a retriever rather than building one.

    Timing wraps ONLY retrieve(), deliberately: it includes the embed call and
    (for the rerank arm) its LLM scoring calls, because that is what a request
    would actually wait on, but excludes grading, which is test-harness cost the
    user never pays.
    """
    results = []
    for g in gold:
        t0 = time.perf_counter()
        hits = retriever.retrieve(g.query, k)
        elapsed = (time.perf_counter() - t0) * 1000
        rank = next(
            (i for i, h in enumerate(hits, start=1) if g.matches(h.payload)), None
        )
        results.append(QueryResult(
            gold=g,
            rank=rank,
            latency_ms=elapsed,
            top_refs=[f"{h.payload['source']}#chunk{h.payload['chunk']}" for h in hits],
        ))
    return RunResult(retriever.name, results)


def print_comparison(runs: list[RunResult], k: int) -> None:
    """The money table. Quality AND cost side by side — reporting either one
    alone is how teams talk themselves into a reranker they didn't need."""
    print(f"\n{'='*76}")
    print(f"RESULTS — {len(runs[0].per_query)} gold queries, k={k}")
    print("=" * 76)
    print(f"{'retriever':<12} {'Recall@k':>9} {'MRR':>7} {'p50 ms':>9} {'p95 ms':>9}  vs baseline")
    print("-" * 76)
    base = runs[0]
    for r in runs:
        delta = ""
        if r is not base:
            d_mrr = r.mrr - base.mrr
            slower = r.p50_ms / base.p50_ms if base.p50_ms else float("inf")
            delta = f"MRR {d_mrr:+.3f}, {slower:.1f}x latency"
        print(f"{r.name:<12} {r.recall:>9.2f} {r.mrr:>7.3f} "
              f"{r.p50_ms:>9.1f} {r.p95_ms:>9.1f}  {delta}")


def print_per_query(runs: list[RunResult]) -> None:
    """Per-query ranks. The aggregate hides the interesting part — a retriever can
    lift the average while REGRESSING queries that used to work, and only this
    view shows it. 'Our MRR went up' is not the same claim as 'nothing got worse'."""
    print(f"\n{'-'*76}")
    print("PER-QUERY RANK OF THE CORRECT CHUNK  ('-' = not retrieved at all)")
    print("-" * 76)
    names = [r.name for r in runs]
    print(f"{'expects':<9} {'query':<40} " + " ".join(f"{n:>8}" for n in names))
    for i, qr in enumerate(runs[0].per_query):
        cells = []
        for r in runs:
            rank = r.per_query[i].rank
            cells.append(f"{rank if rank else '-':>8}")
        q = qr.gold.query[:38] + ("…" if len(qr.gold.query) > 38 else "")
        print(f"{qr.gold.expects:<9} {q:<40} " + " ".join(cells))


def print_disagreements(runs: list[RunResult], limit: int = 3) -> None:
    """Where the retrievers disagree most — the queries worth reading in full.

    An averaged metric tells you THAT something changed; only a concrete
    disagreement tells you WHY, and the why is what you say in an interview."""
    print(f"\n{'-'*76}")
    print("BIGGEST DISAGREEMENTS (where the choice of retriever actually mattered)")
    print("-" * 76)

    def spread(i: int) -> int:
        ranks = [r.per_query[i].rank or 99 for r in runs]
        return max(ranks) - min(ranks)

    idxs = sorted(range(len(runs[0].per_query)), key=lambda i: -spread(i))
    for i in idxs[:limit]:
        if spread(i) == 0:
            continue
        g = runs[0].per_query[i].gold
        print(f"\n  Q: {g.query}")
        print(f"     needs a chunk containing: {g.must_contain!r}  (expects {g.expects})")
        if g.note:
            print(f"     why: {g.note}")
        for r in runs:
            qr = r.per_query[i]
            print(f"       {r.name:<10} rank={qr.rank if qr.rank else 'MISS':<5} "
                  f"top: {', '.join(qr.top_refs[:3])}")
