"""Chunking + document loading — turn a directory of docs into embeddable chunks.

A Chunk carries its provenance (source file + index) so a retrieved hit is always
traceable back to a document — the whole point of citations in RAG. The Chunker is
a small strategy object: change its knobs (or swap it) to change HOW documents are
split without touching ingestion or storage. Day 5 does exactly that to break
retrieval on purpose.
"""
from __future__ import annotations

import os
import glob
from dataclasses import dataclass


@dataclass(frozen=True)
class Document:
    source: str  # filename, e.g. "pricing.md"
    text: str


@dataclass(frozen=True)
class Chunk:
    source: str
    index: int
    text: str

    @property
    def ref(self) -> str:
        return f"{self.source}#chunk{self.index}"

    @property
    def payload(self) -> dict:
        # Qdrant stores this dict verbatim; keys match the Day 4/5 schema.
        return {"source": self.source, "chunk": self.index, "text": self.text}


@dataclass(frozen=True)
class Chunker:
    """Word-based sliding window with overlap. Deliberately simple and visible so
    its failure modes (severing a table from its header) are easy to induce.
    Overlap exists so a fact straddling a boundary still lands whole in one chunk."""

    size: int
    overlap: int

    def split(self, text: str) -> list[str]:
        words = text.split()
        if not words:
            return []
        step = max(1, self.size - self.overlap)
        chunks = []
        for start in range(0, len(words), step):
            window = words[start : start + self.size]
            chunks.append(" ".join(window))
            if start + self.size >= len(words):  # last window reached the end
                break
        return chunks

    def chunk(self, docs: list[Document]) -> list[Chunk]:
        out: list[Chunk] = []
        for doc in docs:
            for i, piece in enumerate(self.split(doc.text)):
                out.append(Chunk(source=doc.source, index=i, text=piece))
        return out


def load_documents(docs_dir: str) -> list[Document]:
    """Read every .md/.txt in docs_dir as a Document. Chunking is applied later so
    each caller can chunk the SAME docs differently (Day 5 relies on this)."""
    paths = sorted(
        glob.glob(os.path.join(docs_dir, "*.md"))
        + glob.glob(os.path.join(docs_dir, "*.txt"))
    )
    if not paths:
        raise SystemExit(f"No .md/.txt docs found in {docs_dir} — nothing to ingest.")
    docs = []
    for path in paths:
        with open(path) as f:
            docs.append(Document(source=os.path.basename(path), text=f.read()))
    return docs
