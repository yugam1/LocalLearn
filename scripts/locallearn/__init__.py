"""locallearn — shared building blocks for the LocalLearn day scripts.

Each day imports the plumbing it needs from here and keeps only its unique
teaching content (the corpus, the queries, the breaks, the diagnoses). Modules
are split by FEATURE so you can review one concern at a time:

    config      Settings + .env loading
    ollama      OllamaClient (embed + chat)
    similarity  cosine (the by-hand metric a vector DB automates)
    chunking    Document / Chunk / Chunker / load_documents
    vectorstore VectorStore (Qdrant wrapper; embedder injected)
    generation  format_context + generate (grounded answer with citations)
    prompts     GROUNDED_SYSTEM / NAIVE_SYSTEM
    display     banner / show_retrieval / show_chunks / print_answer
    runlog      Tee / tee_stdout (self-logging to result/)
"""
from .config import Settings, load_dotenv
from .ollama import OllamaClient
from .similarity import cosine
from .chunking import Document, Chunk, Chunker, load_documents
from .vectorstore import VectorStore
from .generation import format_context, generate
from .prompts import GROUNDED_SYSTEM, NAIVE_SYSTEM
from .display import banner, show_retrieval, show_chunks, print_answer
from .runlog import Tee, tee_stdout

__all__ = [
    "Settings",
    "load_dotenv",
    "OllamaClient",
    "cosine",
    "Document",
    "Chunk",
    "Chunker",
    "load_documents",
    "VectorStore",
    "format_context",
    "generate",
    "GROUNDED_SYSTEM",
    "NAIVE_SYSTEM",
    "banner",
    "show_retrieval",
    "show_chunks",
    "print_answer",
    "Tee",
    "tee_stdout",
]
