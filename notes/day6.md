# Day 6 — the class transcript (explain it to a 15-year-old, keep the numbers real)

> Format note: this is Day 6's actual findings, rewritten as if I were teaching
> them out loud to someone who's never heard the word "embedding." Every number
> below is the real result from `python scripts/day6_better_retrieval.py`
> (self-logged to `result/day6.txt`) — nothing here is made up to make the story
> nicer. The toy code blocks are simplified stand-ins for teaching, not the real
> script; the real implementation is `scripts/locallearn/bm25.py`,
> `retrievers.py`, and `evaluation.py`.

---

## Chapter 1 — Why "search" is secretly the whole ballgame

Okay. Picture an open-book exam. You get to bring one giant binder of notes in
with you. Here's the catch: you don't get to read the whole binder during the
test. You get 10 seconds to flip to **one page**, and whatever's on that page
is all you're allowed to use to answer the question.

If you flip to the wrong page, it doesn't matter how smart you are or how good
your handwriting is — you're going to get the question wrong, because the right
information was never in front of you.

That's a RAG system (Retrieval-Augmented Generation, but forget the acronym).
The AI model is you, taking the exam. The "flip to one page" step is called
**retrieval**. Everything people obsess over — prompt engineering, which model
you use, temperature settings — all of that happens *after* the page-flip. If
the page-flip is wrong, none of it can save you.

So Day 6's entire job was: **make the page-flip better, and prove — with a
number, not a feeling — whether it actually got better.**

Spoiler, because it's the most important sentence in this whole lesson: I built
a fancier page-flipping system, ran the numbers, and it turned out **the
original page-flipping system was already flipping to the right page 100% of
the time.** I built a fix for a problem that didn't exist on this test. That's
not a failure — that's the entire point of measuring things instead of guessing.
Hold that thought; we'll come back to it with the actual numbers.

---

## Chapter 2 — Two totally different ways to "search"

There are two fundamentally different tricks for finding the right page in the
binder. They're bad at *opposite* things, which turns out to matter a lot.

### Trick 1: search by meaning ("vector" / "embedding" search)

Imagine you could take any sentence and turn it into a **point on a map**, where
sentences that *mean* similar things land near each other, even if they don't
share a single word. "My car won't start" and "the vehicle refuses to turn
over" would land right next to each other on this map, despite having zero
words in common.

That map has hundreds of dimensions instead of 2 (x, y), but the idea is
identical to a 2D map: **distance = how different the meaning is.** We call a
point on this map an "embedding," and "how close are two points" is measured
with something called cosine similarity — don't worry about the name, just
know it's a distance score between 0 and 1.

```python
# TOY version — not the real code, just the idea.
# Pretend an embedding is just 3 numbers instead of 768.
import math

def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    mag_a = math.sqrt(sum(x * x for x in a))
    mag_b = math.sqrt(sum(y * y for y in b))
    return dot / (mag_a * mag_b)

car_wont_start   = [0.9, 0.1, 0.4]   # made-up coordinates
vehicle_no_crank = [0.85, 0.15, 0.35]  # a paraphrase — lands NEARBY
pizza_recipe     = [0.1, 0.9, 0.2]   # unrelated — lands FAR

print(cosine(car_wont_start, vehicle_no_crank))  # close to 1.0 — similar!
print(cosine(car_wont_start, pizza_recipe))      # close to 0.0 — unrelated
```

This is *incredible* at paraphrases. Ask "why does my van keep beeping at me"
and it'll find a chunk that says "the alert triggers when idle time exceeds
the threshold" even though not one word matches.

**But here's its blind spot, and it's the whole reason Day 6 exists:**
squeezing a sentence down into a handful of "meaning coordinates" throws
information away, and the first thing it throws away is anything that doesn't
*have* meaning — like a serial number, a hostname, or an error code. To this
kind of search, `BT-4471829` and `BT-9982104` both just smell like
"identifier-ish." It genuinely cannot tell them apart very well, because
neither of them *means* anything — they're just codes.

Real number from my run: I asked *"What URL is the Beacon web console at?"*
The correct chunk (containing `console.beacon.aldritch.example`) got buried at
**rank 3**. The embedding search *knew* the question was about a URL and a
console, but it couldn't grab onto the actual hostname text, because a
hostname doesn't "mean" anything to a meaning-based map.

### Trick 2: search by exact words (BM25 / keyword search)

This is the boring, old-school approach, and it's the opposite of trick 1 in
every way. It doesn't understand meaning *at all*. It just counts words. Think
of the index at the back of a textbook: "photosynthesis .......... page 142."
It doesn't know what photosynthesis *means* — it just knows that word appears
on that page, and it'll take you straight there if you type that exact word.

The real formula (called BM25) looks scarier than it is. It's three ideas
stacked on top of each other:

1. **Rare words matter more.** If the word "the" is in every single page, it
   tells you nothing — everyone has it. If the word "OBD" is in exactly one
   page, and your question has the word "OBD" in it, that's basically a
   smoking gun. This is called **IDF** (inverse document frequency) — rarity
   is a signal.
2. **Diminishing returns.** A page that says "van van van van van" 50 times
   isn't necessarily 50x more about vans than a page that says it twice. BM25
   has a curve built in so the 10th repeat of a word barely counts for
   anything more than the 9th did — otherwise a spammy page could win just by
   repetition.
3. **Long pages get a penalty.** A page that's 10x longer has 10x more chances
   to accidentally contain your search word. BM25 discounts for that so a
   giant page doesn't win purely by being giant.

```python
# TOY version of the idea — score = how "IDF-heavy" the shared words are.
# Pretend a "page" is just a string, and a query is just a string.
def toy_score(query_words, page_words, rare_words):
    score = 0
    for w in query_words:
        if w in page_words:
            score += rare_words.get(w, 1)   # rare words score higher
    return score

pages = {
    "page_A": "the fleet console lives at console.beacon.aldritch.example",
    "page_B": "your van reports a GPS ping every 30 seconds",
}
rarity = {"console.beacon.aldritch.example": 9, "the": 0.1, "fleet": 2}

query = ["what", "url", "is", "the", "console", "at"]
# ^ notice "console.beacon.aldritch.example" isn't even a literal match to
# "console" alone in real BM25 — this toy is simplified on purpose.
```

Real number from my run: for that exact URL question, BM25 found the right
chunk at **rank 1**, and it took **0.1 milliseconds** — no AI model, no GPU, no
network call. It's just counting.

BM25's weakness is the mirror image of the embedding search's weakness: it is
**completely helpless against a paraphrase**. If your question shares zero
words with the answer, BM25 has literally nothing to grab onto. It's not
"less good" at that — it returns nothing at all, honestly, like a shrug.

### The key insight that makes Chapter 3 make sense

**These two tricks fail on *different* questions.** Vector search fails on
literal identifiers. Keyword search fails on paraphrases. If their mistakes
were the same mistakes, combining them would be pointless — you'd just get the
same wrong answer twice. But because their errors are *uncorrelated*, there's
a real, argued-for reason to try combining them. Not because "more is better"
— because they're bad at genuinely different things.

I wrote this prediction down for each of my 10 test questions *before* running
anything — which one I expected to win and why. That mattered a lot later.
Keep reading.

---

## Chapter 3 — Fusing two flawed friends (hybrid search)

Say you have two friends helping you find something. Friend A is great with
names and numbers but terrible with vague descriptions. Friend B is the
opposite — great at "vibes," bad with exact details. How do you combine their
opinions into one answer?

The naive idea: average their confidence scores. The problem: Friend A's
"confidence" is measured in a totally different unit than Friend B's. It's
like averaging a temperature in Celsius with a temperature in Fahrenheit and
expecting a sane number — the scales don't line up, and if you tried to
convert them, the conversion itself would keep sliding around depending on the
question. (In real terms: cosine scores live in a tight ~0.5–0.75 band, BM25
scores are unbounded and jump around 0–15+. You can't just add them.)

The trick that dodges this — called **Reciprocal Rank Fusion (RRF)** — throws
away the actual *scores* and only keeps the **ranking**. Not "how confident,"
just "1st place, 2nd place, 3rd place." Then it gives points based on position,
with a twist: it heavily favors things that show up high on *both* friends'
lists over things that show up #1 on just one list.

```python
# TOY RRF — exactly the real formula, simplified inputs.
def rrf_fuse(rank_lists, k_rrf=60):
    scores = {}
    for ranking in rank_lists:            # one ranking per "friend"/retriever
        for rank, item in enumerate(ranking, start=1):
            scores[item] = scores.get(item, 0) + 1 / (k_rrf + rank)
    return sorted(scores.items(), key=lambda kv: -kv[1])

vector_ranking = ["chunkA", "chunkC", "chunkB"]   # friend A's order
bm25_ranking   = ["chunkB", "chunkA", "chunkC"]   # friend B's order

print(rrf_fuse([vector_ranking, bm25_ranking]))
# chunkA shows up 1st and 2nd -> wins, even though neither friend
# put it in first place on its own in a way that dominates.
```

The `60` isn't magic, it's just a number from an old research paper (TREC)
whose whole job is to flatten out the difference between "ranked 1st" and
"ranked 2nd" a little, so one overconfident friend can't just steamroll the
vote.

**Here's the twist my run taught me, and it's the kind of thing you only learn
by measuring, not by reasoning about it on a whiteboard:** because RRF only
cares about *agreement*, it can actively make things worse. If Friend A is
right and Friend B is confidently, plausibly, *totally wrong* about a specific
question, RRF doesn't know that. It just sees two votes and averages the
positions. On my run, this happened **twice** — a query where the vector
search alone had the right answer at rank 1 dropped to rank 2 once BM25's
confident-but-wrong guess got folded in. Fusing a right friend with a wrong
friend can drag the right one down. That's the price of the trick that makes
RRF simple in the first place: by throwing away confidence/magnitude to dodge
the unit-mismatch problem, you also throw away the information that would
have told you *which friend to trust this time.*

Overall hybrid result on my 10-question test: **Recall@4 stayed 1.00 → 1.00**
(no change — both were already perfect), and the ranking-quality score (MRR,
explained in Chapter 5) moved from **0.817 to 0.825** — a nudge so small it's
inside the noise of the measurement itself. Two questions got better, two got
worse. Net: a wash.

---

## Chapter 4 — Hiring an expert judge (reranking)

Now imagine a totally different strategy. Instead of trying to get the *first*
search exactly right, you deliberately cast a wide net — grab, say, 6 decent
candidates instead of the top 4 — and then hand those 6 to an expert judge who
reads each one carefully and re-orders them by hand.

That's **reranking**. Stage 1 (vector + BM25, cheap and fast) just needs to
make sure the right answer is *somewhere* in its net — that's a recall job.
Stage 2 (the expert judge) only has to sort a short list — that's a precision
job. Neither one has to be good at the other's job, which is a nice division
of labor.

In a real production system, the "judge" would be a small, specialized model
called a **cross-encoder** — it's built for exactly one task (score how well
passage X answers question Y) and does it in a single fast pass, in
milliseconds, for all candidates at once.

I didn't have one of those lying around, so I used the same big general-purpose
language model I use for generating answers, and asked it — one candidate at a
time, one at a time, sequentially — "rate 0 to 10, how well does this passage
answer this question." That's a **pointwise LLM reranker**, and it's the slow,
worst-case version of the idea, on purpose, so I could see exactly what it
costs.

```python
# TOY version of what the reranker prompt does.
SYSTEM = "Reply with ONLY an integer 0-10. 0=irrelevant, 10=perfectly answers it."

def score_passage(model, question, passage):
    reply = model.chat(SYSTEM, f"Q: {question}\nPassage: {passage}\nScore:")
    digits = "".join(c if c.isdigit() else " " for c in reply).split()
    return float(digits[0]) if digits else 0.0   # never trust "reply with ONLY X"

# Real code note: never trust an LLM to actually obey "reply with ONLY an
# integer." It's a request, not a rule. Always parse defensively.
```

Here's what it bought, and what it cost, in real numbers:

- It moved the ranking-quality score from 0.825 (hybrid) up to **0.933** — the
  best result of the whole day. It took 8-of-10 questions being answered
  correctly-at-first-try up to 9-of-10.
- It cost **17.1 seconds per question**, versus 83.5 *milliseconds* for the
  original plain search. That's **205 times slower.**

Think about that gap for a second: 205x isn't "a bit slower," it's the
difference between a chat app that feels instant and one where you'd assume
it's broken and refresh the page.

And here's the sharpest lesson buried in that: **a judge can only re-rank the
candidates that were actually handed to them.** One question — "my van's
tracker stopped showing up" — never got fixed by the reranker, because the
first-stage search had already buried the correct chunk down at rank 4 out of
6 *before the judge ever saw it.* You cannot judge a candidate you were never
shown. The ceiling for that question was set before the expert judge even
opened their mouth.

**The honest headline is not "reranking is slow."** It's "*pointwise LLM
reranking, on a small GPU that has to share memory with the CPU, is 205x
slower.*" A real cross-encoder does the same comparison job in one small batch
pass and would cost tens of milliseconds, not 17 seconds. Blaming "reranking"
as a technique for a cost that's actually about *which* reranker I built would
be the wrong lesson to walk away with.

---

## Chapter 5 — How do you even know if it worked? (Recall@k and MRR)

You cannot just *feel* your way to "this got better." You need a number, and
actually you need **two** numbers, because they answer two different
questions.

### Recall@k — "was the answer in the pile at all?"

Out of all your test questions, what fraction had the correct page *somewhere*
in your top-k results? This is the **ceiling** metric — if Recall@4 is 0.6,
that means 40% of your questions are flatly unanswerable, no matter how smart
the AI reading the pile is. No amount of clever prompting fixes a page that
was never handed over.

### MRR (Mean Reciprocal Rank) — "how close to the TOP was it?"

Recall only asks yes/no. But imagine two search systems that both score
Recall@4 = 1.00 — meaning both *always* find the right page somewhere in the
top 4. One of them always puts it at rank 1 (first thing you see). The other
always buries it at rank 4 (last thing you see, right before you'd have
trimmed it to save time/cost). Recall can't tell these two apart *at all* —
they're tied. MRR can. It scores 1/rank per question, averaged: rank 1 scores
1.0, rank 2 scores 0.5, rank 4 scores 0.25, a total miss scores 0.

```python
# TOY versions, exactly the real formulas, small inputs.
def recall_at_k(ranks, total):
    # ranks: a list like [1, 3, None, 2] — rank of the correct chunk per
    # question, or None if it never showed up in top-k
    found = sum(1 for r in ranks if r is not None)
    return found / total

def mrr(ranks):
    return sum((1 / r if r else 0) for r in ranks) / len(ranks)

ranks = [1, 4, 2, 1, None, 1, 3, 1, 2, 1]   # 10 questions
print("Recall@4:", recall_at_k(ranks, 10))   # 0.9 — one miss
print("MRR:     ", mrr(ranks))               # rewards the rank-1's heavily
```

**Why MRR matters even when Recall looks perfect:** a chunk parked at rank 4
survives *today's* setting of k=4 just fine. But the moment someone trims k to
3 to save money on tokens, or the question needs *two* chunks instead of one,
that rank-4 chunk silently falls off a cliff and the system breaks with zero
warning. MRR is the early smoke detector for a fire that Recall can't see yet.

### The k trap — and the finding that flips the story depending on where you look

I recomputed Recall at k=1, 2, 3, and 4 from the same run (not a new run — just
different arithmetic on the same rankings):

| retriever | R@1 | R@2 | R@3 | R@4 |
|---|---|---|---|---|
| vector | 0.70 | 0.80 | **1.00** | 1.00 |
| bm25 | 0.60 | 0.70 | 0.80 | 0.80 |
| hybrid | 0.70 | **0.90** | 0.90 | 1.00 |
| rerank | **0.90** | 0.90 | 1.00 | 1.00 |

Look closely: **at k=2, hybrid wins** (0.90 vs 0.80 for plain vector search).
**At k=3, plain vector search wins** (1.00 vs 0.90 for hybrid). **At k=4, it's
a tie.** Same exact data, three completely different "winners," depending
purely on which k you decided to report.

That's the trap: "Recall@k improved!" is not a real claim unless you say
*which* k, because the answer changes depending on where you're standing. And
if you're the one who gets to pick which k to publish, it's incredibly easy —
and dishonest without meaning to be — to just pick the one that flatters
whatever you built. This is exactly why MRR sits in the same table: it
averages over every k at once, so it can't be cherry-picked the same way, and
it's what told me hybrid and vector were basically tied (0.817 vs 0.825)
rather than "one obviously beats the other."

---

## Chapter 6 — You have to test your test

Before I trusted a single number above, I found a bug — not in the search
systems, in **my own exam.**

I had written a test question about a fix that involves power-cycling a
device: "count to 30 seconds and plug it back in." So I graded that question
by checking if the retrieved chunk contained the phrase `"30 seconds"`.

Except — I had *also* written, in a totally different document, "the tracker
reports a GPS ping every 30 seconds." Same three words, completely unrelated
meaning, two different source documents.

That means: if a search system fetched the *wrong* chunk — the GPS-ping one,
nothing to do with power-cycling — my grader would have shrugged and marked it
**correct**, because the string matched. My test would have lied to me, and it
would have lied to me *silently* — the number would've looked totally normal,
just wrong.

So I built a check that runs *before* any actual measurement, and it looks for
exactly two failure modes:

- **UNWINNABLE** — the phrase I'm grading against doesn't exist in *any*
  chunk. Every search system gets penalized equally for a mistake that isn't
  theirs, which is sneaky because the *comparison* between systems still looks
  fine even though the absolute numbers are garbage.
- **AMBIGUOUS** — the phrase shows up in chunks from *different, unrelated*
  source documents (like my "30 seconds" trap). A wrong answer could score as
  right by accident.

**The rule this teaches, and it applies way outside of AI:** a test is code,
and code has bugs. A test you've never checked can report three decimal places
of confident-looking precision about literally nothing. The first question to
ask about any test someone hands you — a school quiz, a code test suite, a
company's KPI dashboard — is "**does this test check itself?**"

---

## Chapter 7 — The actual results, and the plot twist

Here's the real table from the real run — 10 test questions, k=4, on the
actual model and hardware:

| retriever | Recall@4 | MRR | p50 ms | p95 ms | vs baseline |
|---|---|---|---|---|---|
| vector (baseline) | **1.00** | **0.817** | **83.5** | **190.2** | — |
| bm25 | **0.80** | **0.683** | **0.1** | **0.1** | MRR −0.133, ~0× latency |
| hybrid | **1.00** | **0.825** | **74.5** | **261.2** | MRR **+0.008**, 0.9× latency |
| rerank | **1.00** | **0.933** | **17098.9** | **19069.6** | MRR **+0.117**, **204.9×** latency |

**Read the first column again: the baseline was already at 1.00 recall.** The
plain, simple, Day 4/5 vector search was *already* finding the right page 10
times out of 10, before I built a single line of the fancy stuff. There was no
recall problem to fix. Which means the whole "let's build hybrid search to fix
retrieval" plan was solving a problem that already didn't exist on this test
set — the actual bug from Day 5 (a question that needed two chunks but only
got handed one) was a **k-is-too-small bug**, not a search-quality bug. `k=4`
already fixed it. Hybrid search, RRF, all of that machinery — it wasn't wrong
to build, but it was aimed at the wrong target.

**That "no improvement" line is the single most useful sentence to come out of
this whole day**, because it's the sentence that stopped me from shipping code
that adds complexity — a second search index to keep in sync with the first,
more latency variance, more moving parts — for a benefit that doesn't exist.

### Did my predictions come true?

Remember, I wrote down *before running anything* which search style I expected
to win each question. Scoreboard: **4 of 8 testable predictions confirmed, 1
flat-out wrong, 3 untestable** because both systems tied.

The one I got wrong is the most interesting: I wrote a question — "can a
read-only user draw zones on the map?" — that I *intended* to require
understanding a paraphrase (read-only → "Viewer" role, zones → "geofences" in
the docs). I expected meaning-based search to win. Keyword search won instead,
at rank 1. Turns out my question accidentally reused enough plain words
("map", "user", "zones") that it was solvable by keyword-matching alone — my
double-paraphrase design wasn't actually load-bearing. **That's a bug in my
test question, not a finding about the search systems.** Writing the
prediction down in advance is exactly what let me catch that; if I hadn't
predicted anything, a lucky keyword win would've just looked like a normal
result instead of a red flag.

### Did the better ranking even change what the AI said out loud? (the nothingburger, but an important one)

I took one question — "where do I change the idle threshold, and what is it by
default?" — ran it through both plain search and hybrid search, same prompt,
same model, and compared the final generated answers.

**They were byte-for-byte identical.** Even though hybrid search handed the
model a *different set of source pages* than plain search did (they only
overlapped on 2 out of 4), the answer didn't change one character — because
both systems happened to put the *one page that actually mattered* in the #1
slot, and the model basically ignored the other three.

The lesson: an MRR improvement *below the very top slot* can be completely
invisible to a user, right up until the day someone trims k down to save
money, or a question needs two pages instead of one and the "ballast" pages
suddenly matter. MRR isn't measuring "is today's answer better" — it's
measuring **how much safety margin you have before a future config change
breaks you.** That's a real, worthwhile thing to have bought — I just need to
describe it honestly as "more margin," not "better answers today."

### Two things I learned about my own measuring tools, not about search

1. **My table reported a physically impossible speedup, with a straight
   face.** Hybrid search showed up as *faster* (74.5ms) than plain vector
   search (83.5ms) — except hybrid search literally runs the vector search
   *first*, and then does extra work on top of it. It cannot be faster. It's
   arithmetically impossible. The "speedup" was just measurement noise — 10
   samples with no repeats is nowhere near enough to trust a 9-millisecond
   gap. **Rule of thumb: if your benchmark reports an improvement that the
   actual mechanics of the system forbid, the problem is your sample size, not
   reality.**
2. **The test itself was where the real bugs were hiding**, not the search
   code. Both meaningful mistakes this whole day — the "30 seconds" ambiguity
   trap and the accidentally-not-actually-semantic test question — were bugs
   in *my exam*, caught only because I built a sanity-checker and wrote
   predictions down in advance. The lesson generalizes way past search: when
   you're evaluating anything, budget real time for making sure the ruler
   isn't bent before you trust what it measures.

---

## Chapter 8 — If someone asked you about this in an interview

*(Written the way you'd actually say it out loud — plain words, then the
number backing it up.)*

**"You added hybrid search. How do you know it helped?"**
It didn't, and I can show you the number. On a 10-question labeled test at
k=4: Recall stayed 1.00 → 1.00, the ranking-quality score barely moved (0.817
→ 0.825), and latency was inside measurement noise (84ms → 75ms). It won two
questions and lost two — pulled a hostname lookup up a rank, but also demoted
two paraphrase questions because the fusion trick gave the wrong search
system's confident guess real credit. The root reason there was nothing to
win: my first-stage search was **already** finding the right page 100% of the
time, so I built a fix for a problem this test set didn't have. What I'd
actually claim as the win from this day isn't the retriever — it's the *test
harness*, because that's the thing that stopped me from shipping a change that
only adds complexity. And I'd say clearly: 10 questions over 10 pages of docs
is too small a test to even detect the effect hybrid search is supposed to
help with — the next step is a bigger, messier document set, not more fusion
tricks.

**"When would you NOT use a reranker?"**
When your first-stage search is already finding the right page almost every
time. A reranker can only re-sort candidates it was actually handed — it can't
fix a page that never made it into the pile. It buys you *ordering quality*,
not *coverage*, so if coverage is your actual problem, it's the wrong tool no
matter how good it is. I've got both halves of that from one run: my reranker
pushed the ranking-quality score from 0.825 to 0.933 — but cost **205x the
latency**, 17 seconds per question instead of under a tenth of a second. And
the one question it couldn't fix was the one where the first-stage search had
already buried the answer too deep for the judge to ever see it. Also worth
saying out loud: don't use a big general chat model as your reranker one
candidate at a time — that's what made mine so slow. A purpose-built
cross-encoder does the same comparison job in one fast batched pass. The slow
number indicts my specific implementation, not the technique of reranking.

**"Vector search is 'semantic' — why would you ever want dumb keyword
matching?"**
Because meaning-based search is *lossy compression*, and the very first thing
it throws away is exactly the stuff that has no "meaning" — serial numbers,
hostnames, error codes, file paths. Real example from my run: asking for the
Beacon console's URL, vector search buried the chunk with
`console.beacon.aldritch.example` at rank 3; plain keyword search found it at
rank 1, in a tenth of a millisecond. A hostname doesn't *mean* anything, so a
meaning-based map has nothing to grab onto. In real company documents — error
codes, product SKUs, config keys, ticket numbers — that's a huge share of what
people actually type into a search box. Caveat I'd say out loud unprompted:
on my tiny 10-page test set, vector search still won 3 of 4 identifier
questions at rank 1 anyway, because there was nothing else nearby for a smeared
embedding to get confused with. **The advantage of keyword search scales with
how big and how repetitive your document set is** — "we need hybrid search" is
a claim about *your specific documents*, not a universal law about search.

**"How big should k be?"**
Too small drops half the answer (that was Day 5's lesson). Today added the
other half — and something sharper: **the ranking of "which search system
wins" literally flips depending on what k you pick.** Same run, recomputed: at
k=2, hybrid beats plain vector 0.90 vs 0.80. At k=3, plain vector beats hybrid
1.00 vs 0.90. At k=4, tied. So "Recall@k improved" isn't a real claim until
you name the k — and if you get to choose which k to report, picking the
flattering one is the easiest way to be accidentally dishonest in this field.
That's exactly why MRR sits next to it in the table — it averages over every
k at once, so it can't be gamed the same way, and here it says the two systems
are basically tied. Practically: pick k off of where your recall curve
flattens out (mine flattened around k=3–4), then watch MRR as your smoke
detector, because a chunk sitting at rank 4 survives k=4 today and dies the
moment someone trims k to save money. And k costs something on the other side
too — my Part-B test showed 3 of 4 context pages changing with **zero** change
to the final answer, meaning I was paying extra tokens for pages the model
never even used.

---

## Chapter 9 — So, would I actually ship any of this?

- **Hybrid search — no, not on this evidence.** A +0.008 nudge is inside the
  noise, it caused two real regressions, and it adds a whole second index that
  now has to stay in sync with the first (Day 5 already showed how painfully
  that can go wrong). I'd keep it behind a feature flag and re-run this exact
  test once the document set is big enough for keyword search's advantage to
  actually have room to show up.
- **Reranking — the *result* is worth shipping, this *implementation* isn't.**
  17 seconds a question is not a latency budget, it's a broken product. But
  the +0.117 gain says the *precision* stage really is where the value lives
  here — so the next move is swapping in a real cross-encoder and re-measuring,
  not giving up on reranking entirely. If a fast cross-encoder gets close to
  that same score in under 100ms, ship it.
- **The one thing that's unambiguously worth keeping is the test harness
  itself** (`evaluation.py`) — the gold set, the self-check, the comparison
  tables. It's the only piece of today's work that would have actually caught
  me shipping a no-op change, and it's the exact skeleton Day 7 builds the
  *answer-level* grading on top of.

---

## What comes next (Day 7)

Today measured **retrieval**: did the right page reach the top of the pile at
all? Day 7 measures **answers**: given that page, was what the AI actually
*said* correct? That needs a smarter grader — a 20-question test set graded by
an LLM acting as judge, not a simple substring check — because "did the answer
sound right" isn't something you can check with `in` a string the way
"did the right chunk show up" can.

The two levels can disagree, and that disagreement is the whole point of
building both: **perfect retrieval with a wrong final answer is a generation
bug**, not a search bug — that's Day 5's territory resurfacing. Having both
harnesses side by side is what lets me actually prove which stage broke,
instead of guessing.
