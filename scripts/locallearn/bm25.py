"""BM25 — lexical (keyword) retrieval, hand-rolled like Day 2's cosine.

Day 2-5 retrieved by MEANING: embed the query, find the nearest vectors. That has
a blind spot the whole of Day 6 is about — embeddings are lossy compression, and
what they compress away first is exactly what nobody can afford to lose: rare
literal tokens. A serial format `BT-XXXXXXXXX`, a hostname, an error code, a
navigation path — these carry almost no *semantic* signal, so a 768-D vector
smears them into "identifier-ish". Two different error codes end up neighbours.

BM25 has the opposite bias. It knows nothing about meaning; it only counts words.
So it nails the literal token and is helpless against a paraphrase. The two
methods fail on DIFFERENT queries, which is the entire argument for hybrid: you
fuse them not because fusion is fancy but because their errors are uncorrelated.

The formula (BM25-Okapi), for query Q against document D:

    score(D,Q) = Σ_q  IDF(q) · ( f(q,D)·(k1+1) ) / ( f(q,D) + k1·(1 - b + b·|D|/avgdl) )

Three ideas, each worth being able to explain out loud:

  IDF          — a word matching in FEWER documents is worth more. "the" matches
                 everywhere and tells you nothing; "OBD" matches one chunk and
                 tells you everything.
  SATURATION   — the k1 term makes the 10th occurrence of a word worth far less
                 than the 2nd. Without it, a chunk that spams a keyword wins
                 every time. Plain TF-IDF has this bug; BM25 is TF-IDF with the
                 diminishing-returns curve bolted on.
  LENGTH NORM  — the b term penalises long documents, which otherwise win just by
                 containing more words and thus more chances to match.

No dependency, ~40 lines. Nothing here is magic, which is the point.
"""
from __future__ import annotations

import math
import re
from collections import Counter

# Tokenizer choice IS a retrieval design decision, not a detail. This one splits
# on any non-alphanumeric run, so "OBD-II" -> ["obd", "ii"] and
# "console.beacon.aldritch.example" -> four tokens. That's deliberate: a user
# typing "OBD" should still hit "OBD-II". The cost is that you can no longer
# require the exact hyphenated form. Swap this regex and your Recall@k moves —
# try it, it's the cheapest experiment in the whole day.
TOKEN_RE = re.compile(r"[a-z0-9]+")


def tokenize(text: str) -> list[str]:
    return TOKEN_RE.findall(text.lower())


class BM25Index:
    """In-memory lexical index over Chunks. Mirrors VectorStore's role, minus the
    server: build once from chunks, then score a query against every document.

    Brute-force scoring over all N docs is fine here (N=10) and is the honest
    thing to show — a real deployment uses an inverted index (Elasticsearch,
    Tantivy, Qdrant's sparse vectors) for the same math at large N. Same
    'the index is pure overhead at tiny N' lesson Day 3 measured for Qdrant.
    """

    def __init__(self, chunks, k1: float = 1.5, b: float = 0.75):
        self.chunks = list(chunks)
        self.k1 = k1  # saturation: higher = term frequency keeps mattering longer
        self.b = b    # length normalisation: 0 = off, 1 = full
        self.docs = [tokenize(c.text) for c in self.chunks]
        self.lengths = [len(d) for d in self.docs]
        self.avgdl = (sum(self.lengths) / len(self.docs)) if self.docs else 0.0
        self.freqs = [Counter(d) for d in self.docs]

        # Document frequency -> IDF, precomputed once per term.
        df: Counter = Counter()
        for d in self.docs:
            df.update(set(d))
        n = len(self.docs)
        # The +0.5 smoothing is what keeps IDF finite for a term in every doc.
        self.idf = {
            term: math.log(1 + (n - cnt + 0.5) / (cnt + 0.5))
            for term, cnt in df.items()
        }

    def scores(self, query: str) -> list[float]:
        """BM25 score of the query against every chunk, positionally aligned."""
        q_terms = tokenize(query)
        out = []
        for i, freq in enumerate(self.freqs):
            # Length-normalised denominator floor, computed once per document.
            norm = self.k1 * (1 - self.b + self.b * self.lengths[i] / (self.avgdl or 1))
            total = 0.0
            for term in q_terms:
                f = freq.get(term, 0)
                if not f:
                    continue  # a term absent from this doc contributes exactly 0
                total += self.idf.get(term, 0.0) * (f * (self.k1 + 1)) / (f + norm)
            out.append(total)
        return out

    def top(self, query: str, k: int) -> list[tuple[int, float]]:
        """-> [(chunk_index_into_self.chunks, score)] best first, zeros dropped.

        Dropping zero-scoring docs matters and is a real difference from vector
        search: BM25 can legitimately return NOTHING when the query shares no
        vocabulary with the corpus. Cosine always hands back k neighbours no
        matter how irrelevant (Day 5 Break #3 leaned on exactly that). An empty
        BM25 result is a HONEST 'no lexical match', and it's information.
        """
        ranked = sorted(enumerate(self.scores(query)), key=lambda p: -p[1])
        return [(i, s) for i, s in ranked[:k] if s > 0]
