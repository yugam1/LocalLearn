# LocalLearn — architecture & block flow

How the pieces fit together: the RAG **pipeline** (data flowing left→right), the
`locallearn` **modules** that own each stage, and the two-tier **rig** the code
runs against. The whole point of Day 4/5 is being able to point at *one* block and
say "the bug is here" — so the diagrams below are drawn stage-by-stage on purpose.

## 1. The RAG pipeline (data flow)

Each stage is a distinct block with exactly one job. A wrong answer is always
traceable to one of them — that taxonomy IS the Day-5 debugging lesson.

```mermaid
flowchart LR
    subgraph INGEST["ingest"]
        D["docs/*.md<br/>(Beacon corpus)"] --> LD["load_documents()"]
    end
    LD --> CH["CHUNK<br/>Chunker(words, overlap)"]
    CH --> EM["EMBED<br/>OllamaClient.embed<br/>(nomic-embed-text)"]
    EM --> ST["STORE<br/>VectorStore.rebuild<br/>(Qdrant upsert)"]
    ST --> RE["RETRIEVE<br/>VectorStore.retrieve<br/>(top-k cosine)"]
    Q(["QUERY"]) --> RE
    RE --> GEN["GENERATE<br/>generate()<br/>(OllamaClient.chat)"]
    P["prompts<br/>GROUNDED / NAIVE"] --> GEN
    GEN --> A(["grounded answer<br/>+ citations"])

    classDef stage fill:#1f6feb,stroke:#0b3d91,color:#fff;
    classDef io fill:#2ea043,stroke:#116329,color:#fff;
    class CH,EM,ST,RE,GEN stage;
    class Q,A,D io;
```

**The debugging seam:** everything up to and including `RETRIEVE` is *retrieval*;
`GENERATE` is *generation*. The first question on any wrong answer is "which half
failed?" — answerable only because `display.show_retrieval` prints the retrieved
chunks **before** the generated prose.

### Where each Day-5 break lands

| Break | Stage it breaks | Symptom |
|-------|-----------------|---------|
| #1 Chunking | `CHUNK` | table severed from its header; number survives, label lost |
| #2 Retrieval | `RETRIEVE` (k too small) | answer spans 2 chunks, k=1 returns half |
| #3 Hallucination | `GENERATE` (prompt) | out-of-corpus; guardrail on = refuse, off = invent |
| #4 Stale index | `STORE` (freshness) | docs edited but not re-embedded; index is a snapshot |

## 2. Module map (`locallearn` package)

Which file owns which block. Import the **module**, call it qualified
(`chunking.load_documents`) — the module name IS the source file.

```mermaid
flowchart TD
    subgraph SCRIPTS["day scripts (thin: unique teaching content only)"]
        D2["day2_embeddings"]
        D3["day3_qdrant"]
        D4["day4_rag"]
        D5["day5_break_rag"]
    end

    subgraph PKG["locallearn package"]
        CFG["config<br/>Settings · load_dotenv"]
        OLL["ollama<br/>OllamaClient (embed + chat)"]
        SIM["similarity<br/>cosine"]
        CHK["chunking<br/>Document · Chunk · Chunker · load_documents"]
        VS["vectorstore<br/>VectorStore (Qdrant wrapper)"]
        GENM["generation<br/>format_context · generate"]
        PR["prompts<br/>GROUNDED_SYSTEM · NAIVE_SYSTEM"]
        DIS["display<br/>banner · show_retrieval · show_chunks · print_answer"]
        RL["runlog<br/>Tee · tee_stdout"]
    end

    D2 --> CFG & OLL & SIM & RL
    D3 --> CFG & OLL & VS & SIM & RL
    D4 --> CFG & OLL & VS & CHK & GENM & PR & DIS & RL
    D5 --> CFG & OLL & VS & CHK & GENM & PR & DIS & RL

    VS -. "embedder injected" .-> OLL
    GENM -. uses .-> PR
    GENM -. "chat via" .-> OLL

    classDef pkg fill:#8957e5,stroke:#4c2889,color:#fff;
    classDef scr fill:#bf8700,stroke:#7d4e00,color:#fff;
    class CFG,OLL,SIM,CHK,VS,GENM,PR,DIS,RL pkg;
    class D2,D3,D4,D5 scr;
```

**Key relationships**
- `VectorStore` takes the embedder as a **dependency** (`embedder=client`) — it
  never reaches for Ollama itself. That's the SOLID seam that lets Day 3 drive it
  at the raw-vector level and Day 4/5 at the text level.
- `generate()` is the only place `prompts` and `OllamaClient.chat` meet — swapping
  `GROUNDED` ↔ `NAIVE` there is the entire Day-5 hallucination experiment.
- `config.Settings` feeds URLs/models into everything; `runlog.tee_stdout` wraps
  every `main()` so each run self-logs to `result/dayN.txt`.

## 3. The rig (two-tier, deliberate client/server split)

```mermaid
flowchart LR
    subgraph MAC["Mac (Intel MBP) — dev / app tier"]
        CODE["code + git + Claude Code<br/>day scripts run here"]
    end
    subgraph ASUS["ASUS ROG (Ubuntu) — server tier · 192.168.1.19"]
        OLLS["Ollama (GPU/CUDA)<br/>:11434 · systemd"]
        QDR["Qdrant (Docker)<br/>:6333"]
    end
    CODE -- "OLLAMA_URL<br/>embed + chat" --> OLLS
    CODE -- "QDRANT_URL<br/>store + retrieve" --> QDR

    classDef mac fill:#1f6feb,stroke:#0b3d91,color:#fff;
    classDef srv fill:#cf222e,stroke:#82071e,color:#fff;
    class CODE mac;
    class OLLS,QDR srv;
```

Cross-machine state syncs **only** through `PROGRESS.md` + git — the client points
at the ASUS over the LAN via `OLLAMA_URL` / `QDRANT_URL` (in `.env`).
