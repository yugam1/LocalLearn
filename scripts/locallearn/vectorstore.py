"""Vector store — a thin, teachable wrapper over Qdrant.

Responsibility: persist vectors + payloads and return nearest neighbours. It does
NOT know how to embed — an embedder (OllamaClient) is *injected* (dependency
inversion), so the store depends on the abstraction 'something that turns text
into a vector', not on Ollama specifically. That split is why Day 3 can drive it
at the raw-vector level (keeping its own vectors for a brute-force check) while
Day 4/5 drive it at the text level, both with the same class.
"""
from __future__ import annotations

from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct


class VectorStore:
    def __init__(self, client: QdrantClient, collection: str, embedder=None):
        self.client = client
        self.collection = collection
        self.embedder = embedder

    @classmethod
    def connect(cls, url: str, collection: str, embedder=None) -> "VectorStore":
        """Connect + fail loudly with the fix if the container isn't up."""
        try:
            client = QdrantClient(url=url, timeout=10)
            client.get_collections()  # forces a real round-trip
        except Exception as e:
            raise SystemExit(
                f"Can't reach Qdrant at {url} — is the container up? ({e})\n"
                f"On the ASUS run:\n"
                f"  docker run -d --name qdrant -p 6333:6333 -p 6334:6334 \\\n"
                f"    -v $(pwd)/qdrant_storage:/qdrant/storage qdrant/qdrant"
            )
        return cls(client, collection, embedder)

    # ── vector level: caller supplies vectors (Day 3 keeps its own for numpy) ──
    def load(self, vectors: list[list[float]], payloads: list[dict]) -> int:
        """(Re)create the collection from scratch and upsert. Returns vector dim.
        Fresh each time so re-ingests are clean. Cosine = the Day 2/3 metric."""
        dim = len(vectors[0])
        if self.client.collection_exists(self.collection):
            self.client.delete_collection(self.collection)
        self.client.create_collection(
            collection_name=self.collection,
            vectors_config=VectorParams(size=dim, distance=Distance.COSINE),
        )
        self.client.upsert(
            collection_name=self.collection,
            wait=True,
            points=[
                PointStruct(id=i, vector=list(v), payload=payloads[i])
                for i, v in enumerate(vectors)
            ],
        )
        return dim

    def search(self, vector: list[float], k: int) -> list:
        return self.client.query_points(
            collection_name=self.collection,
            query=list(vector),
            limit=k,
            with_payload=True,
        ).points

    # ── text level: needs the injected embedder (Day 4/5) ──
    def _embed(self, text: str) -> list[float]:
        if self.embedder is None:
            raise ValueError(
                "VectorStore has no embedder — pass embedder= to use rebuild()/retrieve(), "
                "or call load()/search() at the vector level."
            )
        return self.embedder.embed(text)

    def rebuild(self, chunks) -> int:
        """Embed every Chunk and reload the collection. Returns vector dim."""
        vectors = [self._embed(c.text) for c in chunks]
        return self.load(vectors, [c.payload for c in chunks])

    def retrieve(self, query: str, k: int) -> list:
        """Embed the query and return the top-k nearest chunks (as Qdrant hits)."""
        return self.search(self._embed(query), k)
