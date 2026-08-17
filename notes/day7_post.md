# Day 7, part 2 — the class transcript, AFTER the run

> Format note: this is the second half of Day 7, picking up right where
> `notes/day7_pre.md` left off — "run it, then come back here." Fill in every
> `[FILL: ...]` below from the **real** output in `result/day7.txt`. Don't
> round a number to make a point cleaner, and don't fabricate a finding that
> "should" be true — Day 6's whole lesson was that the number you expect and
> the number you measure are allowed to disagree, and the disagreement is
> usually the more interesting sentence.

---

## Chapter 7 — Was the judge even trustworthy? (check this FIRST)

Before a single one of the twenty questions gets discussed, the run had to
clear two gates. If either failed, **everything below is provisional** — the
script would have hard-stopped on the first one, so if you're reading real
numbers past this point, it means both passed. Record what actually happened
anyway, because "it passed" and "here's exactly what it said" are different
levels of evidence.

**Sanity check** (`sanity_check_judge` — can it tell a known-good answer from
a known-bad one?):
- known-GOOD answer verdict: `[FILL: PASS/FAIL]` — reason given:
  `[FILL: the judge's one-sentence reason]`
- known-BAD answer verdict: `[FILL: PASS/FAIL]` — reason given:
  `[FILL: the judge's one-sentence reason]`
- Passed the check? `[FILL: yes/no]`

**Self-consistency check** (3 identical calls at temperature 0):
- verdicts: `[FILL: e.g. [True, True, True]]`
- stable? `[FILL: yes/no]`
- If unstable: `[FILL: what does this mean for every % below — how much do you
  discount them?]`

> Did this match your Chapter-6 prediction from `day7_pre.md`? `[FILL]`

---

## Chapter 8 — The money table

`[FILL: paste the real comparison table printed by print_comparison() —
pipeline / exact% / judge% / disagree% / p50 ms, for every pipeline you ran]`

| pipeline | exact% | judge% | disagree% | p50 ms |
|---|---|---|---|---|
| baseline (k=4) | `[FILL]` | `[FILL]` | `[FILL]` | `[FILL]` |
| degraded (k=1) | `[FILL]` | `[FILL]` | `[FILL]` | `[FILL]` |

**The headline sentence, in your own words** — the exact shape Day 6 asked
for: *"[Metric] went ___% -> ___% and [other metric] went ___% -> ___%."*
`[FILL]`

Was the gap between baseline and degraded as big as you predicted in
`day7_pre.md`, bigger, or smaller? `[FILL, and say why you think the size
landed where it did]`

---

## Chapter 9 — Which questions actually broke under k=1?

Pull this straight from the per-query table (`print_per_query`) — every
question tagged `needs_chunks=2` in the script, and whether it survived `k=1`
or not:

| question (needs_chunks=2) | baseline verdict | degraded (k=1) verdict |
|---|---|---|
| "...silent for more than 2 hours?" | `[FILL]` | `[FILL]` |
| "Can a read-only user draw zones...?" | `[FILL]` | `[FILL]` |
| "...200 active Tags and want to add 60 more...?" | `[FILL]` | `[FILL]` |

Did **every** `needs_chunks=1` question survive `k=1` untouched, exactly as
predicted? `[FILL — if any single-fact question broke anyway, that's the most
interesting line in this whole file: it means the "right" chunk for that
question wasn't actually rank-1 under plain vector search, a finding that
belongs back in Day 6's retrieval table, not here.]`

Did any `needs_chunks=2` question **survive** `k=1` anyway? `[FILL — if so,
why: was the single chunk that vector search ranked #1 unexpectedly enough on
its own, e.g. did the model already know or reasonably infer the missing
half?]`

---

## Chapter 10 — Where the two graders disagreed, and who was actually right

`[FILL: paste the disagreements from print_disagreements() for at least the
baseline pipeline — the question, the must_contain string, the judge's
verdict + reason, and the actual answer text]`

Go through your **predicted-fragile** questions from the script (Enterprise
SLA, the downgrade-order phrasing, the Salesforce refusal) one at a time:

- **Enterprise SLA question** — did exact_match and the judge actually
  disagree? `[FILL]` If so, what did the model literally write instead of
  "1h"? `[FILL — quote it]`
- **Downgrade-order question** — same. `[FILL]`
- **Salesforce refusal question** — did the model refuse in different words
  than the exact GROUNDED_SYSTEM string, and did the judge correctly still
  PASS it? `[FILL — this one matters most: a judge that FAILS a correct
  paraphrased refusal is arguably worse than exact_match here, because it's
  supposed to be the tool that FIXES the paraphrase problem]`

**For every disagreement you found, who was actually right — exact_match or
the judge?** Was there a case where the judge was WRONG (passed something that
was actually incorrect, or failed something that was actually fine)? `[FILL —
if you find one, that's worth a sentence on its own: it means lenience bias
(or its opposite, an overly strict judge) survived the sanity check, which
only tested ONE question, not all twenty]`

---

## Chapter 11 — Did this connect back to Day 6?

Day 6's Part B found something specific: two different retrievers returned
*different* chunks for the idle-threshold question, but produced a
**byte-identical answer**, because the answer-bearing chunk was rank 1 for
both. That's question #1 in today's gold set too.

- What did `baseline` (k=4) answer for the idle-threshold question? `[FILL]`
- Did it match Day 6's recorded answer
  (*"...Console → Fleet → Settings → Idle threshold. The default idle
  threshold is 10 minutes."*)? `[FILL]`

More broadly: **did today's retrieval-level and answer-level results agree
with each other, or contradict?** Specifically — was there any question where
the right chunk clearly reached the model (you can see it in the retrieved
context) but the ANSWER was still wrong? `[FILL — if yes, that's a generation
bug, not a retrieval bug, and it's worth naming which part of the prompt or
model behavior caused it, the same way Day 5 diagnosed its four breaks]`

---

## Answer bank — fill this from what actually happened

*(Same idea as Day 6's answer bank: write these the way you'd actually say
them out loud in an interview, once you have real numbers to hang them on.)*

**"How do you know your RAG answers are actually correct, not just that
retrieval worked?"**
`[FILL — lead with the real baseline vs. degraded numbers, name which specific
kind of question broke and why (needs_chunks), and be honest about what the
gap size does or doesn't prove]`

**"Why do you need an LLM judge instead of just checking keywords in the
answer?"**
`[FILL — use your own real disagree% number and at least one concrete
example where exact_match failed a correct paraphrased answer]`

**"How do you know your LLM judge itself isn't lying to you?"**
`[FILL — walk through the sanity check and self-consistency check results;
if either ever failed during iteration before the final run, say so — that's
a more convincing answer than a check that happened to pass on the first try]`

**"Tell me about a time retrieval worked but the answer was still wrong."**
`[FILL — if you found one in Chapter 11, this is it; if you didn't find one on
this run, say that honestly and explain what a bigger/harder gold set would
need to have in it to surface one]`

---

## Would you ship the degraded (k=1) config? Would you trust this judge in production?

`[FILL — two separate verdicts. The k=1 question should have an obvious
answer by now; the judge-trust question is more interesting: would you ship a
system that uses this exact judge to gate a release, or would you want a
bigger sanity-check set than one good/bad pair before trusting it at scale?]`

---

## What Week 1 built, end to end

Six days ago this started as one `curl` call to a local model. By the end of
this file you have: a working RAG pipeline (Day 4), a taxonomy of the four
ways it breaks (Day 5), a measured, honest verdict on whether "improving"
retrieval actually helped (Day 6, and the answer was mostly no), and now a
harness that can put a real, defensible percentage on whether the whole system
gets answers right — validated at both the retrieval layer AND the judge
layer before trusting either number. `[FILL: one or two sentences, in your own
words, on what you'd actually say if an interviewer asked "walk me through
how you'd know a RAG system in production is working." That sentence is the
actual deliverable of Week 1 — everything above was how you earned the right
to say it with a straight face.]`
