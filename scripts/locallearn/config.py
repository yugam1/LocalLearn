"""Configuration — .env loading + one Settings object every script shares.

Why a dataclass instead of the same os.environ lookups copy-pasted into every
day: one place defines what a "run" needs (endpoints, models, paths), built once
from the environment. Scripts read `settings.<field>` instead of re-deriving
defaults — Single Responsibility, and the defaults live in exactly one file.
"""
from __future__ import annotations

import os
from dataclasses import dataclass

# repo root = two levels up from this file (scripts/locallearn/config.py).
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def load_dotenv(path: str | None = None) -> None:
    """Tiny stdlib .env loader — no external dep. Real env vars win over the file.
    Defaults to the repo-root .env regardless of where a script is invoked from."""
    if path is None:
        path = os.path.join(PROJECT_ROOT, ".env")
    if not os.path.exists(path):
        return
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            os.environ.setdefault(key.strip(), val.strip())


@dataclass(frozen=True)
class Settings:
    """Immutable snapshot of a run's configuration. Build with Settings.from_env()."""

    ollama_url: str
    qdrant_url: str
    embed_model: str
    gen_model: str
    docs_dir: str
    result_dir: str

    @classmethod
    def from_env(cls) -> "Settings":
        load_dotenv()
        return cls(
            ollama_url=os.environ.get("OLLAMA_URL", "http://localhost:11434"),
            qdrant_url=os.environ.get("QDRANT_URL", "http://localhost:6333"),
            embed_model=os.environ.get("EMBED_MODEL", "nomic-embed-text"),
            gen_model=os.environ.get("GEN_MODEL", "llama3.1:8b"),
            docs_dir=os.environ.get("DOCS_DIR", os.path.join(PROJECT_ROOT, "docs")),
            result_dir=os.environ.get("RESULT_DIR", os.path.join(PROJECT_ROOT, "result")),
        )
