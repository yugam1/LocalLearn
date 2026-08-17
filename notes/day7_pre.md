# Day 7, part 1 — the class transcript, BEFORE you run anything

> Format note: this is the concepts half, written the same way Day 6's notes
> were — a teacher explaining it out loud to someone who's never heard the
> words before, with toy code for the ideas that need it. **No results appear
> in this file, because none exist yet.** Run `python scripts/day7_eval_answers.py`
> when you're done reading, then open `notes/day7_post.md` — that's where the
> real numbers get filled in. Splitting it this way is deliberate: if the
> results were sitting three scrolls below the explanation, you'd skim the
> concepts to get to the number. Reading this file with zero numbers in sight
> forces you to actually predict something first, which is the whole point.

---

## Chapter 1 — Why Day 6 wasn't the end of the story

Quick recap of where we left off, because today's whole design leans on it.

Day 6 asked one question: **did the correct page of the binder reach the top
of the pile the model gets to read?** That's it. It never once looked at what
the model actually *said*. And the punchline was that the plain-old search was
already finding the right page **100% of the time** — so all of Day 6's fancy
machinery (hybrid search, an AI judge re-ordering candidates) had nothing to
fix on that test.

Here's the gap that leaves open, though: **finding the right page and reading
it correctly are two different skills.** You can hand a student the exact
right page of the textbook and they can still misread it, skip half of it, or
answer confidently using the wrong page from memory instead. Day 6 measured
"did they get handed the right page." Today measures **"did they get the
question right, period"** — which is the number that actually matters, and the
number an interviewer actually wants to hear.

There's a very concrete unfinished thread from Day 5 sitting here too: **Break
#2**, where I set `k=1` (only fetch one page instead of four) on a question
that genuinely needed two pages' worth of information to answer fully, and the
system quietly returned half an answer. I only tested that on **one question**,
by hand, as an anecdote. Today reruns that exact idea — properly. Twenty
questions instead of one, a real measured percentage instead of a single
"yeah, that looked wrong."

---

## Chapter 2 — Why grading an ANSWER is a genuinely different, harder problem than grading a CHUNK

This is the single most important idea in today's lesson, so let's slow way
down on it.

In Day 6, "did the right page get retrieved" was graded like this: take a
literal phrase that's guaranteed to be in the correct page — say,
`"console.beacon.aldritch.example"` — and check `if phrase in page_text`. That
check is **airtight**, because the "page text" is the original document,
word-for-word, unchanged. A textbook page doesn't rewrite itself.

But today we're not grading a page anymore. We're grading **what the AI said
out loud** after reading that page. And an AI *never* just copy-pastes the
source text back at you — it paraphrases, it reorders, it summarizes. So if
the correct fact in the source document is literally the text `"1h"` (from a
table cell), the model might say:

> "...and Enterprise customers get a one-hour response SLA."

Read that sentence. It's **completely correct**. But if your grading code is
still doing `if "1h" in answer`, it just marked a **correct answer as wrong**,
because the model wrote "one-hour" instead of "1h".

```python
# The SAME grading trick that was bulletproof in Day 6, now applied one layer
# up the pipeline — to see exactly where it breaks.

source_chunk = "Enterprise: 24/7 phone support, 1h SLA"
model_answer = "Enterprise customers get a one-hour phone-support response SLA."

def grade(text, must_contain):
    return must_contain.lower() in text.lower()

print(grade(source_chunk, "1h"))    # True  -- was checking verbatim doc text
print(grade(model_answer, "1h"))    # False -- checking the model's OWN WORDS
# Same fact. Same correctness. Different verdict, because "1h" became "one-hour"
# somewhere between the document and the model's mouth, and a plain substring
# check has no idea those two strings mean the same thing.
```

**This is not a bug you can code your way around by listing more spellings.**
There are infinite correct ways to phrase "one hour" — "an hour," "1 hr,"
"sixty minutes," "within the hour." You cannot enumerate them all. The moment
you're grading *generated prose* instead of *verbatim source text*, a rigid
string check stops being trustworthy on its own. You need something that reads
for **meaning**, not for exact characters.

Which is exactly the same idea from Day 6, chapter 2, one level higher up the
stack: keyword search is great at exact tokens and blind to paraphrase; the
same is now true of your grading code. **The fix in Day 6 was a search system
that understands meaning (embeddings). The fix here is a grader that
understands meaning — and the cheapest one available is another AI model.**

---

## Chapter 3 — Hiring a second AI to grade the first one's homework

The idea is simple to say and slightly unsettling the first time you actually
do it: **ask a language model to read the question, a description of what a
correct answer needs, and the candidate's answer — and just... judge it.**
PASS or FAIL, like a teacher grading an essay against a rubric instead of an
exact answer key.

```python
# TOY version of the real prompt used in locallearn/judging.py.

JUDGE_SYSTEM = (
    "You are a strict grading assistant. You'll get a QUESTION, a RUBRIC of "
    "facts a correct answer must contain, and a CANDIDATE ANSWER. Minor "
    "wording differences are fine. Missing or wrong FACTS are not. Reply "
    "PASS or FAIL on the first line, then one sentence why."
)

def judge(model, question, rubric, answer):
    prompt = f"QUESTION: {question}\nRUBRIC: {rubric}\nCANDIDATE ANSWER:\n{answer}"
    reply = model.chat(JUDGE_SYSTEM, prompt, temperature=0)
    first_line = reply.strip().splitlines()[0].upper()
    return first_line.startswith("PASS")

# judge(model,
#   "What SLA does Enterprise get?",
#   "States Enterprise gets 24/7 phone support with a 1-hour response SLA.",
#   "Enterprise customers get a one-hour phone-support response SLA.")
# -> True. The judge reads "one-hour" and "1h" as the same fact, because it's
#    reasoning about MEANING, the exact thing a substring check can't do.
```

Notice the rubric (`RUBRIC`) is written about **facts**, not about exact
wording — "states the SLA is 1 hour," not "contains the string 1h." Write a
rubric about wording and you've just reinvented the brittle substring check
with extra steps and a slower, more expensive process on top.

**Why is `temperature=0` non-negotiable here, more than almost anywhere else in
this whole project?** A grade that changes depending on random chance isn't a
grade — it's a coin flip wearing a lab coat. If the judge is going to disagree
with itself from one run to the next, you need to know that *before* you quote
any percentage it produces, which brings us to the chapter that actually
matters most today.

---

## Chapter 4 — Don't trust a grader you haven't tested

Here's a thought experiment. Imagine a teacher who gives every single student
an A, no matter what they write. That teacher is *useless* as a grader — not
because they're lying, but because their grade carries **zero information**.
An A from them tells you nothing about whether the work was actually good.

This is a real, well-known failure mode for AI judges, and it even has a name:
**lenience bias** — a judge model that's too agreeable, too eager to find a
way to say PASS. It's dangerous precisely *because* it's invisible from the
outside: every percentage the judge produces will look great, right up until
you realize the judge was never actually checking anything.

So before Day 6 trusted a single retrieval number, it ran a self-check
(`sanity_check_gold`) that made sure the test questions themselves weren't
secretly broken. Today does the equivalent thing **one level up** — testing
not the questions, but **the grader itself**, with two checks:

### Check 1 — can it actually tell right from wrong?

Feed the judge one answer you *know* is correct and one you *know* is dead
wrong, for the exact same question. If it can't tell them apart — if it PASSes
the wrong one, or FAILs the right one — you stop immediately. Nothing measured
afterward means anything until this passes.

```python
good = "The default idle threshold is 10 minutes, configurable per fleet."
bad  = "Beacon does not have an idle threshold feature."

judge(model, "What is the default idle threshold?",
      "States the default idle threshold is 10 minutes.", good)  # must be True
judge(model, "What is the default idle threshold?",
      "States the default idle threshold is 10 minutes.", bad)   # must be False
```

### Check 2 — does it agree with itself?

Ask the judge to grade the *exact same* answer three separate times, at
`temperature=0`, where it should have zero excuse to waver. If it says PASS,
FAIL, PASS across three runs on identical input, the judge itself is a source
of random noise — and every percentage downstream silently inherits that
noise without any way for you to see it just by looking at the final number.

```python
verdicts = [judge(model, q, rubric, answer) for _ in range(3)]
print(verdicts)          # e.g. [True, True, True]  -> stable, trust it
# [True, False, True]    -> UNSTABLE. The judge is not a ruler, it's a dice roll.
```

**The lesson underneath both checks, and it's the same lesson Day 6 taught
about the gold set:** any tool that hands you a number — a test suite, a
grading rubric, a dashboard, an AI judge — is itself something that can be
wrong, and it will not announce that it's wrong. It will just hand you a
confident, clean-looking percentage. **The first question to ask of any
measurement tool is "how do I know THIS is telling the truth?"** — and you
answer that with a check that runs *before* you look at the real numbers, not
a vibe about how smart the model is.

---

## Chapter 5 — Today's actual experiment: rerunning Day 5's break, properly

Day 5, Break #2, in one sentence: I asked a question whose full answer lived
in **two** different pages of the binder, but told the search step to only
hand over **one** page (`k=1`). The system answered — confidently — with half
the truth, and didn't say a word about the missing half. That was one
question, tested by hand, once.

Today reruns that exact idea at real scale, across **twenty** questions,
comparing two configurations that are identical **except for one number**:

- **`baseline`** — fetch 4 pages per question (`k=4`), same as Day 4/5/6's
  known-good setup.
- **`degraded`** — fetch only 1 page per question (`k=1`), Day 5 Break #2's
  broken setup, reintroduced on purpose.

Everything else — which model generates the answer, which prompt it's given,
which documents exist — is held **completely identical** between the two.
That's the same discipline as Day 6: change exactly one knob, and whatever
difference shows up in the table can only be about that one knob.

**Why do this instead of comparing search strategies again, like Day 6did?**
Because Day 6 already told us the search-strategy knob (vector vs. hybrid vs.
reranked) barely mattered on this corpus — recall was already at the ceiling.
The `k` knob is the one Day 5 already showed us breaks something real. Today
puts a real number on exactly how much, and — the more interesting question —
on **which kinds of questions specifically**.

Which is why every question in today's 20-question gold set carries a field
called `needs_chunks` — my own **prediction**, written down before running
anything, of whether a correct answer genuinely requires **one** fact/page or
**two**. If my predictions are right, only the `needs_chunks=2` questions
should break under `k=1`, and every `needs_chunks=1` question should survive
it untouched. If a `needs_chunks=1` question breaks anyway, or a
`needs_chunks=2` question survives fine, that's the interesting finding — same
as Day 6's `expects` field getting falsified was more valuable than it being
confirmed.

---

## Chapter 6 — Two graders, side by side, on purpose

Every one of the 20 questions gets graded **twice** — once by the cheap
substring check, once by the LLM judge — and the script reports **both
percentages plus how often they disagreed.**

Why bother running the cheap check at all, if Chapter 2 just spent a whole
section explaining it's unreliable? Two reasons:

1. **A PASS from exact_match is still a strong, free, zero-cost signal.** If
   the model's own words happen to contain the exact fact, that's genuinely
   good evidence, and it cost nothing — no extra AI call, no latency, no risk
   of the judge itself being wrong.
2. **The gap between the two numbers is itself the finding.** If exact_match
   says 70% and the judge says 95%, that 25-point gap isn't noise — it's a
   measurement of *how much the cheap check was undercounting correct-but-
   paraphrased answers.* That's a genuinely useful thing to know before you
   decide, in a real system, whether you can afford to skip the expensive
   judge call and just ship the free check.

I've already flagged a few questions in the gold set (Enterprise's SLA,
the downgrade-order question) where I **predict** the two graders will
disagree, purely because the correct fact in the source document is phrased
in a way I don't expect the model to repeat verbatim. Watch those rows
specifically once you have real output.

There's also one deliberately special question in the set: **"Does Beacon
integrate with Salesforce?"** — the exact out-of-corpus canary from Day 4/5.
The documentation never mentions Salesforce at all, so the only correct
behavior is to refuse — "I don't know based on the provided documents." The
cheap check looks for that *exact* refusal sentence, so a correct-but-
differently-worded refusal ("the docs don't cover this") would **FAIL**
exact_match while still deserving a **PASS** from the judge. That predicted
mismatch is, itself, a small live demonstration of Chapter 2's whole argument.

---

## Before you run it — write these down

Same rule as Day 6: a prediction only counts if it's on the page **before**
you see the answer. Fill these in now, in your own words, then run
`python scripts/day7_eval_answers.py` and compare.

- [ ] **Will the judge pass the sanity check?** (known-good answer PASS, known-
      bad answer FAIL) — yes/no, and why you think so.
- [ ] **Will the judge be self-consistent** across 3 repeated calls at
      temperature 0? — yes/no.
- [ ] **Baseline (k=4) predicted score** — rough %, exact_match and judge,
      separately.
- [ ] **Degraded (k=1) predicted score** — rough %, exact_match and judge,
      separately. How big a gap do you expect vs. baseline?
- [ ] **Which specific questions do you expect to fail under k=1?** (Hint:
      look at which ones are marked `needs_chunks=2` in the script.)
- [ ] **Where do you expect exact_match and the judge to disagree**, and in
      which direction (exact_match too strict, or too lenient)?

---

## What comes next

Once you've run it: open `notes/day7_post.md` and we fill in what actually
happened — the real percentages, which predictions survived contact with the
data, and whether the two harnesses (Day 6's retrieval-level table and today's
answer-level table) tell the same story or contradict each other. A
contradiction between them isn't a bug in either harness — it's exactly the
signal that separates a **retrieval** problem from a **generation** problem,
which is the single most useful sentence you can say in a RAG debugging
conversation: *"the right chunk got there, and it still got the answer wrong —
so this isn't a search problem, look at the prompt."*
