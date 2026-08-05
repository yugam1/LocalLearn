"""Ollama client — the ONE place that knows the shape of Ollama's HTTP API.

Every day hits the same two endpoints: /api/embeddings to turn text into a vector,
and /api/chat to turn context into an answer. Wrapping them in a class means the
request shape, timeouts, and the 'temperature 0 so reruns are comparable' policy
are defined once (DRY); a script just calls .embed() / .chat().
"""
from __future__ import annotations

import requests


class OllamaClient:
    def __init__(self, base_url: str, embed_model: str, gen_model: str = "llama3.1:8b"):
        self.base_url = base_url.rstrip("/")
        self.embed_model = embed_model
        self.gen_model = gen_model

    def embed(self, text: str) -> list[float]:
        """One string -> one embedding vector. The only 'AI' call in retrieval."""
        resp = requests.post(
            f"{self.base_url}/api/embeddings",
            json={"model": self.embed_model, "prompt": text},
            timeout=60,
        )
        resp.raise_for_status()
        return resp.json()["embedding"]

    def chat(self, system: str, user: str, temperature: float = 0.0) -> str:
        """Grounded generation. temperature=0 by default so reruns are comparable."""
        resp = requests.post(
            f"{self.base_url}/api/chat",
            json={
                "model": self.gen_model,
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
                "stream": False,
                "options": {"temperature": temperature},
            },
            timeout=180,
        )
        resp.raise_for_status()
        return resp.json()["message"]["content"].strip()

    def list_models(self) -> list[str]:
        """Names of pulled models — used to fail loudly if a model is missing."""
        resp = requests.get(f"{self.base_url}/api/tags", timeout=10)
        resp.raise_for_status()
        return [m["name"] for m in resp.json().get("models", [])]

    def require_model(self, prefix: str) -> None:
        """Fail loudly with the fix if Ollama is down or no model matches `prefix`."""
        try:
            names = self.list_models()
        except Exception as e:  # unreachable / not running
            raise SystemExit(f"Can't reach Ollama at {self.base_url} — is it running? ({e})")
        if not any(n.startswith(prefix) for n in names):
            raise SystemExit(f"Model '{prefix}' not pulled. Run: ollama pull {prefix}")
