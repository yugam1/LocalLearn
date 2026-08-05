"""Self-logging — tee stdout to result/ files so every run leaves a durable
artifact without a shell redirect.

Console output is ephemeral; the moment you scroll, the retrieval-vs-generation
evidence is gone. The whole job of these exercises is to STUDY what the model did
and transcribe it into notes/, so each run must persist. A tee (not a bare `>`
redirect) keeps the live terminal view AND writes the file, and you can't forget
to capture a run.
"""
from __future__ import annotations

import os
import sys
from contextlib import contextmanager


class Tee:
    """A stdout stand-in that forwards every write to several streams at once —
    e.g. the real terminal plus one or more open log files."""

    def __init__(self, *streams):
        self.streams = streams

    def write(self, data):
        for s in self.streams:
            s.write(data)

    def flush(self):
        for s in self.streams:
            s.flush()


@contextmanager
def tee_stdout(*paths):
    """Within this block, everything print()ed ALSO lands in each file in `paths`
    (opened fresh — one clean capture per run, not appended). Nests correctly: an
    inner tee_stdout adds its file on top of the outer one, so Day 5's `all` mode
    can write a combined log and per-mode logs at once. Always restores the real
    stdout on exit, even if the body raises mid-run."""
    for p in paths:
        os.makedirs(os.path.dirname(os.path.abspath(p)), exist_ok=True)
    files = [open(p, "w") for p in paths]
    real = sys.stdout
    sys.stdout = Tee(real, *files)
    try:
        yield
    finally:
        sys.stdout = real
        for f in files:
            f.close()
