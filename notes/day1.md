# Day 1 notes

> Fill this in from what you *observe*, not what you read. Three questions at the bottom are the deliverable.

## Setup done
- [ ] Ollama running on ASUS (`nvidia-smi` shows the GPU in use during generation?)
- [ ] Models pulled: `llama3.2:3b`, `llama3.1:8b`

## Observations

### Model size contrast (3b vs 8b)
- RAM resident 3b: ___   8b: ___
- tokens/sec 3b: ___   8b: ___
- quality difference noticed: ___

### Raw API (the JSON tells the story)
- `prompt_eval_count` (input tokens): ___
- `eval_count` (output tokens): ___
- `eval_duration` → tokens/sec = ___
- What surprised me: ___

### System prompt reliability
- Gave contradictory instructions ("one word" vs "three paragraphs"). Which won? ___
- Takeaway: ___

### Context-window failure ("lost in the middle")
- Secret word at START of long context — remembered? ___
- Secret word in MIDDLE — remembered? ___
- Roughly how much filler before it broke: ___

---

## The three answers (write these in your own words)

**1. What is a token, and why does its count matter for cost and latency?**


**2. System vs user message — what's the difference, and how reliable is the system prompt?**


**3. What broke when the context got too long, and *where* in the context did failures happen?**

