"""Retrievers — one interface, four strategies, so they can be swapped and MEASURED.

Every retriever here answers the same question ("give me the k best chunks for
this query") and returns the same shape, which is the only reason Day 6 can put
them side by side in a table. That's Liskov substitution doing real work: the
generation stage downstream cannot tell which retriever it was handed.

The returned Hit deliberately mimics a Qdrant hit (`.score` + `.payload`), so
display.show_retrieval and generation.format_context — written in Day 4, before
any of this existed — keep working untouched.

    VectorRetriever  — Day 4/5's behaviour. Meaning, via cosine over embeddings.
    BM25Retriever    — lexical. Exact tokens, no semantics.
    HybridRetriever  — fuses the two by RANK (RRF), not by score.
    RerankRetriever  — over-fetches with a cheap retriever, then re-orders with
                       an expensive LLM pass. The accuracy/latency trade in one class.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from . import bm25 as bm25_mod


@dataclass(frozen=True)
class Hit:
    """Qdrant-hit-shaped so downstream code is retriever-agnostic.
    `parts` records where the score came from — the debugging affordance that
    makes a fused ranking explainable instead of a magic number."""
    score: float
    payload: dict
    parts: dict = field(default_factory=dict)


class VectorRetriever:
    """Dense retrieval — the Day 4/5 baseline. Good at paraphrase, bad at rare
    literal tokens (an embedding of a hostname is mostly noise)."""

    name = "vector"

    def __init__(self, store):
        self.store = store

    def retrieve(self, query: str, k: int) -> list[Hit]:
        return [
            Hit(score=h.score, payload=h.payload, parts={"cosine": h.score})
            for h in self.store.retrieve(query, k)
        ]


class BM25Retriever:
    """Lexical retrieval. Mirror image of the above: nails the exact token,
    completely blind to a paraphrase that shares no vocabulary."""

    name = "bm25"

    def __init__(self, index: bm25_mod.BM25Index):
        self.index = index

    def retrieve(self, query: str, k: int) -> list[Hit]:
        return [
            Hit(score=s, payload=self.index.chunks[i].payload, parts={"bm25": s})
            for i, s in self.index.top(query, k)
        ]


class HybridRetriever:
    """Reciprocal Rank Fusion of vector + BM25.

    Why fuse RANKS instead of scores: a cosine score (~0.5-0.75, bounded) and a
    BM25 score (unbounded, 0-15+ here) are not on the same scale, and normalising
    them is fragile — min/max shifts with every query, so the weighting silently
    changes underneath you. RRF sidesteps the calibration problem entirely by
    throwing the magnitudes away and keeping only the ordering:

        RRF(d) = Σ_retrievers  1 / (k_rrf + rank_d)

    k_rrf=60 is the value from the original TREC paper. Its job is to flatten the
    difference between rank 1 and rank 2 so that ONE retriever's confident #1
    can't automatically win — a doc both retrievers rank 2nd beats a doc only one
    of them ranks 1st. Agreement is the signal being rewarded.

    over_fetch pulls more candidates from each arm than the final k, because a
    document ranked 6th by vector and 5th by BM25 should be able to surface — but
    only if both arms were asked for more than 4.
    """

    name = "hybrid"

    def __init__(self, vector: VectorRetriever, lexical: BM25Retriever,
                 k_rrf: int = 60, over_fetch: int = 8):
        self.vector = vector
        self.lexical = lexical
        self.k_rrf = k_rrf
        self.over_fetch = over_fetch

    def retrieve(self, query: str, k: int) -> list[Hit]:
        fused: dict[str, dict] = {}
        for arm in (self.vector, self.lexical):
            for rank, hit in enumerate(arm.retrieve(query, self.over_fetch), start=1):
                ref = f"{hit.payload['source']}#chunk{hit.payload['chunk']}"
                slot = fused.setdefault(
                    ref, {"payload": hit.payload, "score": 0.0, "parts": {}}
                )
                slot["score"] += 1.0 / (self.k_rrf + rank)
                # Keep each arm's rank so the fused order is explainable: you can
                # see a chunk won because BOTH arms liked it, vs. one arm shouting.
                slot["parts"][f"{arm.name}_rank"] = rank
        ordered = sorted(fused.values(), key=lambda s: -s["score"])
        return [Hit(s["score"], s["payload"], s["parts"]) for s in ordered[:k]]


class RerankRetriever:
    """Two-stage: cheap over-fetch, then an LLM re-scores each candidate.

    This is the standard production shape — a fast recall-oriented first stage
    hands ~20 candidates to a slow precision-oriented second stage. The reason it
    works: the first stage only has to get the right chunk into the candidate SET
    (recall), and the reranker only has to order a short list (precision). Neither
    has to be good at the other's job.

    Real systems use a cross-encoder (bge-reranker, Cohere Rerank) — a small model
    trained to score (query, passage) pairs in one forward pass. We don't have one
    on the rig, so we use the generation LLM pointwise. That is MUCH slower and
    strictly worse, and saying so is the point: this class exists to make the
    latency cost visible, not to be the fastest possible reranker. Watch the p50
    column in the results table before you reach for one of these.
    """

    name = "rerank"

    SYSTEM = (
        "You rate how well a passage answers a question. Reply with ONLY an "
        "integer from 0 to 10. 0 = irrelevant, 10 = directly and completely "
        "answers it. No words, no punctuation, just the integer."
    )

    def __init__(self, base, client, fetch_k: int = 6):
        self.base = base
        self.client = client
        self.fetch_k = fetch_k

    def _score(self, query: str, text: str) -> float:
        raw = self.client.chat(
            system=self.SYSTEM,
            user=f"Question: {query}\n\nPassage:\n{text}\n\nScore (0-10):",
            temperature=0.0,  # a rating must be reproducible or the metric is noise
        )
        # Never trust an LLM to honour "ONLY an integer" — Day 5 proved that
        # "reply exactly X" is a request, not a constraint. Parse defensively and
        # fall back to the first-stage order rather than crashing the run.
        digits = "".join(ch if ch.isdigit() else " " for ch in raw).split()
        return float(digits[0]) if digits else 0.0

    def retrieve(self, query: str, k: int) -> list[Hit]:
        candidates = self.base.retrieve(query, self.fetch_k)
        scored = []
        for pos, hit in enumerate(candidates):
            llm = self._score(query, hit.payload["text"])
            scored.append(Hit(
                score=llm,
                payload=hit.payload,
                # Keep the first-stage rank so you can SEE what the rerank moved.
                parts={**hit.parts, "llm": llm, "stage1_rank": pos + 1},
            ))
        # Stable sort on the LLM score: ties keep first-stage order, so a reranker
        # that rates everything 7 degrades gracefully to the baseline instead of
        # shuffling randomly. (If you see lots of ties, that's a finding — it means
        # the reranker isn't discriminating and you're paying latency for nothing.)
        scored.sort(key=lambda h: -h.score)
        return scored[:k]
