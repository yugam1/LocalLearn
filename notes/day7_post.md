# Day 7, part 2 — the class transcript, AFTER the run

> Format note: this is the second half of Day 7, picking up right where
> `notes/day7_pre.md` left off — "run it, then come back here." Filled in
> below from the **real** output in `result/day7.txt`. Numbers are not
> rounded to make a point cleaner, and nothing below is a finding that
> "should" be true but wasn't actually observed in the log.

---

## Chapter 7 — Was the judge even trustworthy? (check this FIRST)

**Sanity check** (`sanity_check_judge`):
- known-GOOD answer verdict: `PASS` — reason given: "The candidate answer
  includes the required fact that the default idle threshold is 10 minutes,
  and also mentions that it is configurable per fleet, which is a minor
  additional detail that is consistent with the rubric."
- known-BAD answer verdict: `FAIL` — reason given: "The candidate answer is
  incorrect because it denies the existence of an idle threshold feature,
  whereas the rubric states that the default idle threshold is 10 minutes."
- Passed the check? **Yes.**

**Self-consistency check** (3 identical calls at temperature 0):
- verdicts: `[True, True, True]`
- stable? **Yes.**
- If unstable: n/a — it wasn't.

> Did this match your Chapter-6 prediction from `day7_pre.md`? `day7_pre.md`
> has the prediction checkboxes but no prediction text was actually written in
> before this run — so there's nothing recorded to compare against here. If
> you want this comparison to mean something on the next iteration, fill the
> `[FILL]`s in the pre file *before* running the script next time.

---

## Chapter 8 — The money table

```
pipeline       exact%   judge%  disagree%     p50 ms
----------------------------------------------------------------------------
baseline        80.0%    90.0%      20.0%    39031.1
degraded        65.0%    60.0%      15.0%    30699.0
```

| pipeline | exact% | judge% | disagree% | p50 ms |
|---|---|---|---|---|
| baseline (k=4) | 80.0% | 90.0% | 20.0% | 39031.1 |
| degraded (k=1) | 65.0% | 60.0% | 15.0% | 30699.0 |

**The headline sentence:** exact_match went 80.0% → 65.0% and judge% went
90.0% → 60.0% when k dropped from 4 to 1 — a 15-point drop on the cheap check
and a steeper 30-point drop on the judge. Latency also dropped, 39.0s → 30.7s
p50, which is the mechanical explanation for *why* someone would be tempted to
run k=1 in the first place: fewer retrieved tokens means a shorter generation
prompt and a faster reply, and that speedup is exactly what you're trading
against a real, measured 30-point accuracy hit.

Was the gap between baseline and degraded as big as predicted? Unrecorded —
same issue as Chapter 7: no prediction was written into `day7_pre.md` before
the run, so there's no baseline to compare "predicted" against "actual." What
the log does say: the judge-% gap (30 points) is noticeably bigger than the
exact-% gap (15 points) — meaning k=1 didn't just make the model's wording
drift further from the literal source strings, it made a chunk of the
*answers themselves* actually get worse (see Chapter 10 — some judge FAILs
under degraded are for real errors, not just paraphrase noise).

---

## Chapter 9 — Which questions actually broke under k=1?

| question (needs_chunks=2) | baseline verdict | degraded (k=1) verdict |
|---|---|---|
| "...silent for more than 2 hours?" | PASS (E✓ J✓) | PASS (E✓ J✓) |
| "Can a read-only user draw zones...?" | PASS (E✓ J✓) | FAIL (E✗ J✗) |
| "...200 active Tags and want to add 60 more...?" | PASS (E✗ exact-fail, J✓ judge-pass) | FAIL (E✗ J✗) |

Only **2 of the 3** `needs_chunks=2` questions actually broke under k=1 as
predicted (the read-only-zones question and the 260-Tags overage question).
The silent-Tag/OBD-II question — Day 5 Break #2's original anecdote —
**survived** at k=1.

Did **every** `needs_chunks=1` question survive `k=1` untouched? **No.**
Three single-fact questions flipped from PASS to FAIL going baseline →
degraded, despite being predicted as needing only one chunk:

- "What URL is the Beacon web console at?" — E✓J✓ (baseline) → E✗J✗ (degraded)
- "My van's tracker stopped showing up on the live map…" — E✓J✓ → E✗J✗
- "What support SLA does the Enterprise plan include?" — E✗J✓ (baseline;
  exact failed on "1h" vs "1 hour" but judge correctly passed the paraphrase)
  → **E✗J✗** (degraded; this time the judge failed it too — not just a
  wording miss, the log shows no disagreement recorded for this question
  under degraded, meaning exact_match and the judge *agreed* it was wrong)

This is the interesting finding the template asked to watch for: for these
three questions, `needs_chunks=1` was correct about *how much information the
answer needs*, but wrong about an unstated assumption underneath it — that
the single needed chunk would be **rank 1** under plain vector search. `k=1`
only keeps rank 1; if the right chunk was actually sitting at rank 2, 3, or 4
under baseline's `k=4`, cutting to `k=1` silently drops it even though the
question never needed more than one chunk's worth of *information*. That's a
retrieval-ranking finding, not a chunk-count finding — it belongs back next
to Day 6's recall table, not filed under "needs more context."

(Caveat: the log doesn't print the retrieved-hit list for these three specific
questions — only the pass/fail verdicts — so "the right chunk wasn't rank 1"
is the best-supported explanation, not a directly confirmed one from this
run's output. Confirming it would mean rerunning with `display.show_retrieval`
added for these three queries specifically.)

Did any `needs_chunks=2` question **survive** `k=1` anyway? **Yes** — the
silent-Tag/OBD-II question. Chapter 5's Step 2 side-by-side shows why: the
single top-ranked chunk (`troubleshooting.md#chunk2`, score 0.651) already
contained **both** halves of the answer — "...the Tag has been silent over 2
hours, power-cycle it by unplugging it from the OBD-II port..." — in the same
120-word window. The prediction that this question needed two *separate*
chunks was wrong; the chunker happened to keep the trigger condition and the
fix instructions together in one window, so `k=1` never actually had to
choose between them.

---

## Chapter 10 — Where the two graders disagreed, and who was actually right

**Baseline disagreements (4):**

- **"Why do I get alerted every time my drivers pause for lunch...?"**
  exact=PASS, judge=FAIL. Judge's reason: the answer says "a moving vehicle
  stops longer than the threshold" while the rubric just says "vehicle stops
  longer than the threshold" — the judge called this a contradiction. It
  isn't one; a vehicle has to have been moving to *idle* in the first place,
  so "moving vehicle" is a reasonable, correct elaboration, not an error. This
  is the judge being **wrong** — overly strict on a detail that doesn't
  actually conflict with the rubric.
- **"What support SLA does the Enterprise plan include?"** exact=FAIL
  (looked for `1h`), judge=PASS. Model wrote "24/7 phone support and 1 hour
  response time" — judge correctly read "1 hour" as the same fact as "1h."
  Judge is **right**; this is exactly the Chapter 2 paraphrase problem the
  judge exists to fix.
- **"If my webhook endpoint is down, does Beacon keep retrying forever?"**
  exact=FAIL (looked for `3 times`), judge=PASS. Judge is very likely
  **right** here too, but the answer text in the log is truncated with `…`
  before it would show whether "3 times" or an equivalent phrase appears — so
  this one isn't fully verifiable from `result/day7.txt` as printed.
- **"...200 active Tags and want to add 60 more...?"** exact=FAIL (looked for
  `250`), judge=PASS, reason cites "the 250 Tag cap" explicitly. Also
  truncated before that point in the visible answer text, so can't confirm by
  eye whether the model literally wrote "250" (in which case exact_match's
  FAIL would be odd) or conveyed the cap without the literal digits (in which
  case the judge is doing exactly its job). Judge's stated reasoning is
  correct regardless.

**Degraded disagreements (3):**

- **"Why do I get alerted every time my drivers pause for lunch...?"** again,
  exact=PASS, judge=FAIL — but this time for a real reason: the answer
  hedges ("it seems that..."), trails into "which might be too short for…"
  and, per the judge, contradicts itself and half-says "I don't know." This
  looks like genuine k=1 degradation, and here the judge is **right** to
  fail it — unlike the same question under baseline, where the judge was
  wrong. Same question, same judge, opposite verdict on being correct —
  worth remembering that "is the judge trustworthy" isn't a single yes/no,
  it's per-answer.
- **"What happens to new Tags once I hit my plan's Max Tags cap?"**
  exact=PASS, judge=FAIL. Judge's reason: the answer "introduces a new
  detail about the order... not mentioned in the rubric." But the GOLD
  criteria for this question literally says: *"the most recently activated
  Tags are moved to pending first (newest deactivated first)."* The model's
  answer said almost exactly that. The judge flagged a detail that **is** in
  its own rubric as unsupported — this is the judge being **wrong**, and in
  the opposite direction from the classic lenience-bias worry: here it's
  being too strict, not too agreeable.
- **"If my webhook endpoint is down, does Beacon keep retrying forever?"**
  same pattern as baseline — exact=FAIL, judge=PASS, judge likely right.

**Who was actually right, overall:** of the 7 total disagreements, the judge
was demonstrably **wrong twice** — both times by being too *strict*, not too
lenient (the "moving vehicle" nitpick and the Max-Tags-ordering nitpick).
That's worth flagging on its own: the sanity check in Chapter 7 only tested
one lenience-direction failure mode (PASS a bad answer). It never tested
whether the judge could be *unfairly harsh* on a correct answer, and this run
found two real instances of exactly that failure mode slipping through a
judge that had passed its sanity check.

Going through the specifically predicted-fragile questions:
- **Enterprise SLA question** — disagreed as predicted (baseline only); judge
  was right, exact_match was too strict. Model literally wrote "1 hour
  response time" instead of "1h."
- **Downgrade-order question** ("If I downgrade and go over the new plan's
  Tag cap...") — did **not** disagree in either pipeline (E✓J✓ both times).
  The predicted fragility ("the model may rephrase 'newest' away") never
  materialized — the model kept the word "newest" (or close enough) both
  times.
- **Salesforce refusal question** — also did **not** disagree (E✓J✓ both
  pipelines). The model used the system prompt's exact refusal string
  verbatim both times, so exact_match passed on its own — the predicted
  paraphrase-refusal scenario, where the judge would need to rescue a
  differently-worded "I don't know," never came up in this particular run.

---

## Chapter 11 — Did this connect back to Day 6?

- What did `baseline` (k=4) answer for the idle-threshold question? **Not
  captured in this run's log.** `result/day7.txt` records PASS/PASS for this
  question (row 1 of the per-query table) but the actual generated answer
  text was only printed for the Step 2 demo question (the silent-Tag/OBD-II
  one, `GOLD[1]`), not for `GOLD[0]` (the idle-threshold question). So it
  isn't possible to quote it here or diff it word-for-word against Day 6's
  recorded answer without rerunning with that answer explicitly printed.
- Did it match Day 6's recorded answer? Unknown, for the same reason — the
  verdict says the answer contained "10 minutes" and passed both graders, so
  it's *consistent* with Day 6's finding, but not verified byte-for-byte here.

Was there a question where the right chunk clearly reached the model but the
answer was still wrong (a generation bug, not a retrieval bug)? **Not clearly
found in this run.** The one place we can actually see the retrieved context
and the answer side by side (Step 2) — the silent-Tag question — got the
correct chunk at rank 1 in both pipelines and produced a correct answer in
both. The three `needs_chunks=1` questions that broke under degraded
(Chapter 9) look like a **retrieval** problem (right chunk not at rank 1,
lost when `k` dropped to 1), not a generation problem — the model wasn't
shown to misread context it actually received; it more likely lost the
context entirely. Confirming that distinction for certain would need the
retrieved-hit list logged for those three specific questions, which this run
didn't do.

---

## Answer bank — fill this from what actually happened

**"How do you know your RAG answers are actually correct, not just that
retrieval worked?"**
Because I measured them separately. Day 6 showed recall was already at the
retrieval ceiling on this corpus, so today's harness graded the *answer*
against a fact-based rubric instead — twenty questions, two configs that
differ only in `k`. Baseline (k=4) scored 80% exact / 90% judge; degraded
(k=1) dropped to 65% exact / 60% judge. The interesting part isn't the
headline drop, it's *which* questions broke: two of three questions I'd
predicted needed two chunks did break, but so did three questions I'd
predicted needed only one — meaning the real failure wasn't "not enough
information," it was "the one chunk it needed wasn't ranked first," which is
a retrieval-ranking problem hiding behind what looked like a chunk-count
problem.

**"Why do you need an LLM judge instead of just checking keywords in the
answer?"**
Because the model paraphrases, and a substring check can't tell paraphrase
from wrong. Exact and judge disagreed on 20% of baseline questions. Concrete
example: the source table says the Enterprise SLA is "1h"; the model wrote
"1 hour response time." `"1h" in answer` returns False on a completely
correct answer. The judge correctly read the two as the same fact.

**"How do you know your LLM judge itself isn't lying to you?"**
Two checks ran before trusting any of the 20 questions: a sanity check
(known-good answer → PASS, known-bad answer → FAIL — both correct here) and a
self-consistency check (three identical calls at temperature 0 → `[True,
True, True]`, stable). Both passed on the first try in this run. But the
disagreement review afterward found something the sanity check *didn't*
catch: the judge was wrong twice, and both times by being too strict, not too
lenient — flagging a reasonable elaboration ("moving vehicle") and a detail
that was actually in its own rubric (Tag deactivation order) as errors. A
sanity check with one good/bad pair only tests the lenience-bias direction;
it doesn't test for an overly harsh judge, which is a real, separate failure
mode this run surfaced.

**"Tell me about a time retrieval worked but the answer was still wrong."**
Didn't find one on this run — the one question with a full retrieval-vs-answer
transcript (silent-Tag/OBD-II) had the right chunk at rank 1 and a correct
answer in both pipelines. A bigger gold set, or this same set rerun with the
retrieved-hit list printed for every question (not just the Step 2 demo),
would be needed to actually catch that case if it exists — right now three
questions are suspicious (Chapter 9's needs_chunks=1 breaks) but unconfirmed
without seeing what was retrieved for them specifically.

---

## Would you ship the degraded (k=1) config? Would you trust this judge in production?

**k=1: no.** A 30-point judge-score drop and a 15-point exact-match drop for
a ~9-second latency win is not a trade worth making, especially since the
questions that broke weren't the ones predicted to break — meaning the
failure mode (right chunk not ranked first) is not something you can scope
around in advance by eyeballing which questions "need more context."

**The judge: trust it as a strong signal, not a blind release gate.** It
caught a real degradation (the lunch-idle hedge/self-contradiction under
k=1) that exact_match also happened to miss in the other direction. But it
also produced two confirmed wrong verdicts in twenty questions, both from
being too strict. One good/bad sanity pair is not enough evidence to run this
judge as the sole gate on a release — it would need a larger, more varied
sanity set (including borderline-but-correct answers, not just clearly-good
and clearly-bad ones) before I'd let it block a deploy unattended.

---

## What Week 1 built, end to end

Six days ago this started as one `curl` call to a local model. By the end of
this file: a working RAG pipeline (Day 4), a taxonomy of the four ways it
breaks (Day 5), a measured, honest verdict on whether "improving" retrieval
actually helped (Day 6 — mostly no, recall was already at ceiling), and now
an answer-level harness that puts a real percentage on whether the whole
system gets it right, validated at both the retrieval layer and the judge
layer before either number gets trusted. If an interviewer asked "how would
you know a RAG system in production is working," the honest answer built over
this week is: you don't trust one number — you keep a gold set with a
predicted-difficulty label per question, you grade the answer two ways
(cheap and expensive) so the *gap* between them tells you something, and you
sanity-check the expensive grader itself before you let it near a real
percentage, because this run proved even a judge that passes its sanity check
can still be wrong — just not in the direction the sanity check was built to
catch.
