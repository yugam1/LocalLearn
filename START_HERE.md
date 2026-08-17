# AI Primer for FDE — Hands-On, Local-First

> For a full-stack engineer (Node/Express, Spring Boot, TS) prepping for Forward Deployed Engineer interviews.
> Philosophy: **build the thing, break the thing, explain the tradeoff.** Reading is a lookup, not the plan.

---

## How to think about this track (read once)

An FDE is not an ML researcher. In interviews and on the job you are the person who:

- Takes a customer's messy data + a fuzzy goal ("make our docs searchable / summarize tickets / answer support Qs") and ships a **working system** on their infra and constraints.
- Reasons out loud about **tradeoffs**: latency vs. cost vs. quality, hosted API vs. self-hosted, bigger model vs. better retrieval.
- **Debugs** when the demo hallucinates in front of the customer. This is the highest-value skill and the one most people can't fake.

So we optimize for: *systems, tradeoffs, failure modes.* Not transformer math. You will never be asked to derive attention. You **will** be asked "the RAG answer is wrong, walk me through how you'd debug it."

### Priority order (what to actually spend time on)

| Tier | Topic | Why it matters for FDE |
|---|---|---|
| **P0** | Local inference basics (Ollama), prompt/context/tokens | The substrate for everything. Can't discuss cost/latency without it. |
| **P0** | RAG pipeline end-to-end (chunk → embed → store → retrieve → generate) | The single most common FDE deliverable. ~60% of the value is here. |
| **P0** | **Where RAG breaks** (bad chunks, wrong retrieval, hallucination, stale index) | This is the interview differentiator. Clean demos are worthless. |
| **P1** | Vector DBs & retrieval quality (embeddings, similarity, hybrid search, reranking) | The lever you pull when RAG is wrong. |
| **P1** | Eval & guardrails (how do you *know* it works? measuring correctness) | "How would you prove this to the customer?" |
| **P2** | LLM serving economics (tokens, throughput, batching, quantization, GPU vs CPU) | Sizing and cost conversations. Know the shape, not the derivations. |
| **P2** | Structured output / tool use / agents | Increasingly asked; build one small one. |
| **P3** | Fine-tuning vs. RAG vs. prompting (when to reach for which) | A *decision framework*, not a hands-on skill. Mostly talking points. |

Rule of thumb: if you can't demo it or draw its failure mode on a whiteboard, you don't know it yet.

---

## Your rig — hybrid setup (two-tier)

You have two machines. Use them as a **client/server split**, which is how real LLM systems are always deployed — app tier and model tier separated by a network boundary. That boundary (API contract, timeouts, latency budget, batching) is precisely what "LLM serving economics" interview questions probe, so making it physical is a feature, not overhead.

```
┌─────────────────────────┐         HTTP over LAN         ┌──────────────────────────┐
│   Mac (Intel)           │  ───────────────────────────► │   ASUS ROG (Ubuntu)      │
│   "app / dev tier"      │                                │   "model + data tier"    │
│   • your Python RAG code│   :11434  Ollama (inference)   │   • Ollama  (GPU / CUDA) │
│   • all editing + git   │   :6333   Qdrant (vectors)     │   • Qdrant  (Docker)     │
└─────────────────────────┘                                └──────────────────────────┘
```

**All code lives on the Mac. The ASUS is a headless server.** (This also keeps your dev context in one place — see "Working across two machines" below.)

### On the ASUS (the server)

```bash
# Ollama must bind to all interfaces, not just localhost:
OLLAMA_HOST=0.0.0.0:11434 ollama serve        # make permanent later via systemd override

# Qdrant in Docker, ports exposed:
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant

# Get the ASUS LAN IP — you'll need it on the Mac:
hostname -I                                    # e.g. 192.168.1.42

# If the firewall is on (Ubuntu desktop usually off by default):
sudo ufw allow 11434 && sudo ufw allow 6333
```

### On the Mac (the client / dev box) — verify, then config-drive it

```bash
curl http://192.168.1.42:11434/api/tags        # should list the models on the ASUS
```

**Never hardcode `localhost`.** Drive every endpoint from env vars — a one-line switch between local and remote, and a good FDE habit:

```bash
export OLLAMA_URL="http://192.168.1.42:11434"   # ASUS
export QDRANT_URL="http://192.168.1.42:6333"    # ASUS
# to run fully local instead, just: export OLLAMA_URL="http://localhost:11434"
```

Everywhere below that says `localhost:11434`, use `$OLLAMA_URL`.

**Caveats:** `0.0.0.0` exposes the model to your whole LAN — fine at home, not on untrusted Wi-Fi. Both boxes must be awake and on the same network.

**Sequencing:** do Day 1 (token / context-window pokes) *directly on the ASUS, locally* — fewer variables while you learn the primitives. Flip to the client/server split at **Day 3**, when Qdrant comes in and the network boundary starts teaching you something.

### Working across two machines (keeping Claude in the loop without losing context)

Claude Code context is **per-session and stored locally** — it does **not** sync across machines. Two consequences, and the setup that sidesteps them:

- **Recommended: one dev brain.** Because all code lives on the Mac and the ASUS is pure infra, you only ever run Claude Code here on the Mac. Full context stays intact; the ASUS is just an HTTP endpoint. This is why the "code on Mac, servers on ASUS" split matters — it makes the context question disappear.
- **If you ever want Claude active on the ASUS too** (e.g. to manage Docker / systemd / GPU there): that's a *separate* session with its own history — it won't know about this conversation. Bridge them with (a) a **git repo** synced between the machines and (b) a checked-in `PROGRESS.md` that both sessions read on startup. That file — plus notes I save to memory — is the shared brain; the conversation histories stay independent.
- **Parallelism within one session:** I can fan out background sub-agents from the Mac session to work on independent pieces concurrently (e.g. draft the eval harness while iterating on chunking). Long-running GPU jobs (embedding a big corpus) run async on the ASUS while you keep developing on the Mac — that overlap *is* the parallelism you want.

---

## START TODAY — Day 1 (~2–3 hrs)

Goal by end of today: **a local model answering questions in your terminal, and you understanding what a token, a context window, and a system prompt actually are — by poking them, not reading about them.**

### Step 1 — Install Ollama (10 min)

```bash
# macOS
brew install ollama        # or download the app from ollama.com
ollama serve &             # starts the local server on :11434
```

Pull two models of different sizes — the size contrast is the first lesson:

```bash
ollama pull llama3.2:3b      # small, fast, runs on CPU/laptop comfortably
ollama pull llama3.1:8b      # bigger, slower, noticeably "smarter"
```

### Step 2 — Talk to it, and watch the resource cost (20 min)

```bash
ollama run llama3.2:3b
```

Ask it a few things. Then, **while a generation is running**, open a second terminal:

```bash
# watch CPU/RAM. On Apple Silicon also try `sudo powermetrics` for GPU.
top -o cpu
```

> **Observe & note:** How much RAM does 3b hold resident vs 8b? How does tokens/sec differ? This is your first intuition for "why can't we just run GPT-4-sized models locally" — memory is the wall.

### Step 3 — Hit the API directly (30 min) — this is the real mental model

The chat UI hides everything important. Talk to the HTTP endpoint so you see the actual request shape:

```bash
curl http://localhost:11434/api/generate -d '{
  "model": "llama3.2:3b",
  "prompt": "Explain what a vector embedding is in two sentences.",
  "stream": false
}' | jq
```

Look at the response JSON. Note the fields: `eval_count` (tokens generated), `eval_duration`, `prompt_eval_count` (tokens in your prompt). **That's your tokens/sec and your cost model right there.**

Now the chat endpoint with a **system prompt** — the FDE's main control surface:

```bash
curl http://localhost:11434/api/chat -d '{
  "model": "llama3.2:3b",
  "messages": [
    {"role": "system", "content": "You are a terse assistant. Answer in exactly one word."},
    {"role": "user", "content": "What is the capital of France?"}
  ],
  "stream": false
}' | jq '.message.content'
```

> **Experiment (do this, don't skip):** Change the system prompt to something contradictory ("always answer in three paragraphs") while keeping the "one word" instruction. Watch which one wins. This is your first taste of *prompt reliability* — the thing that breaks in production.

### Step 4 — Break the context window on purpose (30 min)

Paste a huge blob of text (copy a long README, repeat it) into a prompt until the model starts ignoring the beginning or degrading. Small models have small context windows (a few K tokens).

```bash
# generate a big prompt and see what happens
curl http://localhost:11434/api/generate -d "{
  \"model\": \"llama3.2:3b\",
  \"prompt\": \"Here is a document. Remember the SECRET WORD is BANANA. $(python3 -c 'print("filler text. " * 3000)') What was the secret word?\",
  \"stream\": false
}" | jq '.response'
```

> **Observe:** Does it still remember BANANA? Move the secret to the *middle* of the filler and retry. This is the **"lost in the middle"** failure — models attend poorly to the center of long contexts. It's a real, cite-able production problem and a great interview answer.

### Step 5 — Write down what you saw (15 min)

Create `notes/day1.md` and answer, in your own words:
1. What is a token, and why does the count matter for cost and latency?
2. What's the difference between the system and user message, and how reliable is the system prompt?
3. What broke when the context got too long? Where in the context did failures happen?

If you can answer these three from observation, you're ahead of most people who "studied RAG."

---

## The rest of Week 1 (roadmap, so today has a destination)

Each day is build-first. Don't move on until the thing runs AND you've triggered its failure mode.

- **Day 2 — Embeddings by hand.** Pull `nomic-embed-text` via Ollama. Embed 10 sentences, compute cosine similarity in a ~40-line Python script, sort by similarity to a query. *Break it:* find two sentences that are semantically opposite but score as similar. Now you understand why retrieval misfires.
- **Day 3 — Stand up a vector DB.** `docker run` Qdrant (or Chroma, in-process, for speed). Load your embeddings, do a real similarity search over the HTTP API. Compare Qdrant vs. a brute-force numpy loop — same results, and you learn what the DB actually buys you (it's speed at scale, not magic).
- **Day 4 — Full RAG pipeline.** Ingest a real PDF/docs set → chunk → embed → store → retrieve top-k → stuff into prompt → answer with citations. ~150 lines. This is the FDE deliverable in miniature.
- **Day 5 — Break RAG four ways (the money day).** Deliberately induce and diagnose:
  1. **Chunking failure** — split a table/code block mid-way, watch the answer go wrong.
  2. **Retrieval failure** — ask something whose answer needs 2 chunks; top-k returns only 1.
  3. **Hallucination** — ask about something *not* in the docs; watch it confidently make it up. Then add a "say I don't know" guardrail and measure if it holds.
  4. **Stale index** — update a doc, don't re-index, get the old answer.
- **Day 6 — Make retrieval better.** Add hybrid search (keyword BM25 + vector) and/or a reranker. Measure: did answer quality actually improve, or did you just add latency? *Measuring* is the point.
- **Day 7 — Eval harness.** Build a 20-question gold set with expected answers. Write a script that runs your RAG over all 20 and scores them (exact match + an LLM-as-judge). Now you can say "we went from 60% to 85%" — the sentence that wins FDE interviews.

Everything after Week 1 (agents/tool use, serving economics & quantization, fine-tuning decision framework) builds on this spine.

---

## Week 2 (scoped 2026-08-17, after Week 1 closed out)

Same rule as Week 1: don't move on until the thing runs (or, for Day 8, until the
framework has been tested against real data) AND you've found its failure mode.
Order below is deliberate, not the order the three P2/P3 topics were first listed
in — cheapest-to-ship first, most-novel-infra last:

- **Day 8 — Fine-tuning vs. RAG vs. prompting decision framework.** Tagged **P3**
  in the priority table above — "a decision framework, not a hands-on skill" —
  and the rig's 4GB VRAM (GTX 1650 Ti) makes a real fine-tune impractical for
  anything worth demoing, so this is **not** a build-first day like Days 1-7.
  Build a decision matrix (data volume, latency budget, $ cost, drift/retraining
  cadence, engineering effort) and run it against **real data this project
  already has**: every failure mode actually found in Days 5-7 (the chunking
  break, the retrieval break, the hallucination break, the stale-index break,
  and the two judge-too-strict misses from Day 7). *Break it:* for each one, ask
  honestly — would fine-tuning have fixed this, or was it a RAG/prompt problem
  fine-tuning can't touch? Find the one case (if any) across the whole week
  where fine-tuning would've actually been the right call, and say so plainly
  if there isn't one — a decision framework that always says "don't fine-tune"
  isn't a framework, it's a bias.
- **Day 9 — Agents / tool use.** P2, and the most FDE-relevant hands-on skill of
  the three. Build a small tool-calling loop against a local Ollama model that
  supports function calling (`llama3.1:8b`) — give it 2-3 tools, e.g. a
  calculator and the Day 4-7 `retrieve()` wrapped as a `search_docs` tool — and
  let the model decide when to call a tool vs. answer directly. *Break it:* get
  it to call a tool with hallucinated/malformed arguments, or skip a tool call
  it actually needed. Framing this makes RAG retrieval "just another tool" one
  level up the stack, so it connects straight back to Day 5's retrieval-failure
  taxonomy instead of being a fresh topic.
- **Day 10 — Serving economics & quantization.** P2. Measure, don't derive:
  tokens/sec at the quant levels already pulled locally (`llama3.2:3b` vs
  `llama3.1:8b`), the GPU/CPU split Day 1 already found (8B doesn't fit in 4GB
  VRAM, splits 45/55 and drops ~9.5x), and batching effects — turn that into a
  real cost/latency table usable in a sizing conversation, the way Day 6/7
  already turned retrieval quality into a table instead of a vibe.

---

## Interview-answer bank (fill these in as you build)

Keep a running doc. For each, you want a *from-experience* answer, not a textbook one:

- "Walk me through a RAG system you've built." → your Day 4 pipeline.
- "The answer is wrong. How do you debug it?" → your Day 5 four-failure taxonomy: *is it retrieval or generation?* (Check what chunks came back first — this single question separates people who've built RAG from people who've read about it.)
- "How do you know it's working?" → your Day 7 eval harness + the numbers.
- "Hosted API vs. self-hosted — how do you decide?" → data sensitivity, cost at volume, latency, ops burden. You'll have felt all four.
- "How would you cut cost / latency?" → smaller model + better retrieval, quantization, caching, shorter context. You'll have measured tokens/sec.

---

## Environment checklist

**ASUS ROG (Ubuntu) — the server:**
- [ ] Ollama installed + NVIDIA CUDA driver working (`nvidia-smi` shows the 1650 Ti)
- [ ] `OLLAMA_HOST=0.0.0.0:11434 ollama serve`
- [ ] `ollama pull llama3.2:3b llama3.1:8b nomic-embed-text`
- [ ] Docker + Qdrant (`docker run -p 6333:6333 qdrant/qdrant`)
- [ ] `ufw` allows 11434 + 6333 (if firewall on)

**Mac (Intel) — the client / dev box:**
- [x] Docker (already installed)
- [ ] Python 3 + a venv (`python3 -m venv .venv && source .venv/bin/activate`)
- [ ] `pip install qdrant-client numpy requests` (as days need them)
- [ ] `OLLAMA_URL` / `QDRANT_URL` env vars pointed at the ASUS IP
- [ ] A `notes/` folder — your observations are the actual deliverable
- [ ] (optional) a git repo, so a future ASUS-side Claude session can share state via a checked-in `PROGRESS.md`

---

### Do this now
1. `brew install ollama && ollama serve &`
2. `ollama pull llama3.2:3b`
3. Run the Step 3 curl commands and read the JSON.
4. Trigger the Step 4 context-window failure.
5. Write `notes/day1.md`.

Come back and tell me what broke — that's where the real learning (and the next exercise) starts.
