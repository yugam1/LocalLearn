"""Generation — assemble retrieved chunks into a grounded prompt and ask the LLM.

The numbering is load-bearing: each chunk becomes [n] with its source, so the
model can cite [1]/[2] and YOU can map a citation back to an exact source#chunk.
An answer must be traceable to a document, not to the model's imagination.
"""
from __future__ import annotations


def format_context(hits) -> str:
    """Number the retrieved hits [1..n] and tag each with its source#chunk."""
    blocks = []
    for n, hit in enumerate(hits, start=1):
        p = hit.payload
        blocks.append(f"[{n}] (source: {p['source']}#chunk{p['chunk']})\n{p['text']}")
    return "\n\n".join(blocks)


def generate(client, query: str, hits, system: str, temperature: float = 0.0) -> str:
    """Hand the numbered context + question to the LLM under `system` and return
    the answer. `client` is any OllamaClient-like object exposing .chat().

    `temperature` defaults to 0 so every day's runs stay reproducible. Day 5's
    hallucination break is the one caller that raises it: at temp 0 the model
    takes the safest token every step, and hedging IS the safe token — which
    suppresses fabrication even under a bad prompt and can fool you into
    thinking your guardrail was never needed."""
    user = f"Context:\n{format_context(hits)}\n\nQuestion: {query}"
    return client.chat(system=system, user=user, temperature=temperature)
