# Day 1 notes

> Fill this in from what you *observe*, not what you read. Three questions at the bottom are the deliverable.

## Setup done
- [x] Ollama running on ASUS as a systemd service, bound to `*:11434` (all interfaces)
- [x] Models pulled: `llama3.2:3b`, `llama3.1:8b` (plus `nomic-embed-text` early, for Day 2)

## Observations

### Model size contrast (3b vs 8b)
- GPU (GTX 1650 Ti, 4GB VRAM): 3b fits fully on GPU. 8b (5.9GB loaded) does **not** fit — `ollama ps` showed `45%/55% CPU/GPU` split.
- tokens/sec 3b: 77 tokens / 1.90s eval_duration ≈ **40.5 tok/s**
- tokens/sec 8b: 76 tokens / 17.88s eval_duration ≈ **4.25 tok/s** (~9.5x slower than 3b)
- quality difference noticed: both gave correct, coherent answers to a simple factual/definition question at this size — quality gap wasn't obvious on an easy prompt; the *speed* gap was the dramatic difference here.

### Raw API (the JSON tells the story)
- `prompt_eval_count` (input tokens): 36 (3b run) / 21 (8b run, shorter because no system prompt)
- `eval_count` (output tokens): 77 (3b) / 76 (8b)
- `eval_duration` → tokens/sec: 40.5 (3b) vs 4.25 (8b)
- What surprised me: the slowdown isn't really about the model being "bigger" in a compute sense — it's that 8b (5.9GB) doesn't fit in 4GB of VRAM, so Ollama silently offloads ~45% of layers to CPU. That CPU/GPU split is what tanks throughput, not raw parameter count. **VRAM capacity is the real sizing constraint**, confirmed directly instead of assumed.

### System prompt reliability
- Gave contradictory instructions ("answer in exactly one word" + "always answer in three full paragraphs") in the same system message.
- Neither cleanly won. The model answered `Paris` (satisfying "one word") on its own line, then continued into three full paragraphs anyway. It tried to satisfy both instructions rather than picking one — a messy blend, not a clean override.
- Takeaway: contradictory instructions in a system prompt don't fail safely or predictably — they produce inconsistent, hard-to-test output shape. In production this means: don't let instructions accumulate/conflict (e.g. app default + tenant override + user override) without resolving them before they hit the model.

### Context-window failure
- Model's context window (from `ollama ps`): **4096 tokens**.
- Filler pushed prompt to `prompt_eval_count: 4096` (the request asked for way more, ~4096 is the hard cap) → secret word **lost**, model answered "There was no secret word."
- Recalibrated to stay just under the cap (~3646-3647 tokens): secret word placed at **START** → recalled correctly (`BANANA`). Secret word placed at **MIDDLE** (same total size) → also recalled correctly (`BANANA`).
- Roughly how much filler before it broke: this model's failure mode wasn't a gradual "lost in the middle" degradation — it was a **hard truncation cliff** right at 4096 tokens. Below the cap, recall was reliable at both start and middle placement; the instant the prompt exceeded `num_ctx`, recall failed outright (context got silently truncated).

---

## The three answers (write these in your own words)

**1. What is a token, and why does its count matter for cost and latency?**

A token is roughly a word-piece — the unit the model actually reads/writes, not a character or a whole word (my ~9-word filler sentence chunked to ~9-11 tokens). Every request pays for tokens twice: `prompt_eval_count` (tokens you send in) and `eval_count` (tokens generated back), and both cost time — I measured 40.5 tok/s on 3b vs 4.25 tok/s on 8b for the *same* output length. So token count directly sets latency (more tokens = more sequential decode steps) and cost (hosted APIs bill per token on both sides of that split).

**2. System vs user message — what's the difference, and how reliable is the system prompt?**

The system message sets standing behavior/persona/constraints for the whole conversation; the user message is the actual per-turn ask. In practice the system prompt is a strong *bias*, not a hard rule — when I gave it contradictory system instructions, it didn't obey one and ignore the other; it tried to satisfy both and produced an inconsistent shape (one-word answer, then three paragraphs anyway). So you can't treat a system prompt as a guarantee — it needs to be tested, and conflicting instructions from different layers of an app (defaults, tenant config, user prefs) will produce unpredictable output unless resolved before the model sees them.

**3. What broke when the context got too long, and *where* in the context did failures happen?**

This model's context window is 4096 tokens (confirmed via `ollama ps`). Below that cap, it reliably recalled information whether it was placed at the very start or in the middle of the filler. The moment the prompt exceeded 4096 tokens, the whole thing broke — not gradually, but as a hard cutoff: `prompt_eval_count` capped at exactly 4096 and the model reported no secret word at all, meaning content got silently truncated rather than the model just "struggling" with it. Lesson: for small local models, check `num_ctx` / `ollama ps` context size explicitly — going even slightly over it doesn't degrade gracefully, it drops information outright, which is a much sharper production failure mode than I expected going in.

