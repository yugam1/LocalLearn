"""Presentation helpers — keep print formatting out of the pipeline logic so the
day scripts read as a narrative and every day renders retrieval the same way.

The cardinal rule lives here: always show the RETRIEVED chunks before the ANSWER,
because 'was it retrieval or generation?' is the first RAG debugging question and
you can only answer it if you saw what came back from the DB before the prose.
"""
from __future__ import annotations

import textwrap


def banner(title: str) -> None:
    print("\n" + "=" * 76)
    print(title)
    print("=" * 76)


def show_retrieval(query: str, hits: list) -> None:
    print(f"\nQUERY: {query!r}")
    print(f"RETRIEVED top-{len(hits)} (what the model gets to read):")
    for n, h in enumerate(hits, start=1):
        p = h.payload
        preview = textwrap.shorten(p["text"], width=140, placeholder=" …")
        print(f"  [{n}] {h.score:.3f}  {p['source']}#chunk{p['chunk']}")
        print(f"        {preview}")


def show_chunks(chunks) -> None:
    print(f"  -> {len(chunks)} chunks: " + ", ".join(c.ref for c in chunks))


def print_answer(text: str, indent: str = "    ", width: int = 72) -> None:
    print("  ANSWER:")
    print(textwrap.indent(textwrap.fill(text, width=width), indent))
