"""Cosine similarity — Day 2's core lesson, kept as its own tiny module because
it's a *concept*, not plumbing: 'a vector DB is just this, indexed.' Day 3 reuses
it as the brute-force ground truth to check Qdrant against.
"""
from __future__ import annotations

import numpy as np


def cosine(a, b) -> float:
    """How aligned two vectors point, ignoring length. 1=identical, 0=unrelated,
    -1=opposite. Accepts lists or arrays. This is literally what a vector DB
    computes for you at scale — no magic."""
    a = np.asarray(a, dtype=np.float32)
    b = np.asarray(b, dtype=np.float32)
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))
