Generate a single self-contained Java practice file for the coding problem given at the bottom. Follow this structure exactly, in this order.

1. PROBLEM DESCRIPTION (block comment at the top)
   - Title, platform/source, difficulty
   - Problem statement in plain language
   - Key definitions, with ✓ valid and ✗ invalid examples where the problem has a definition worth illustrating
   - Sample input, a short analysis, and the sample output
   - Constraints
   - Run command: javac <FileName>.java && java Judge

2. class Solution
   - One public method with the correct signature and a Javadoc comment
   - Body is only a "// TODO: Implement your solution here" plus a placeholder return so the file compiles
   - 3-5 short hints as comments, conceptual only: no pseudocode and no code

3. class Judge (with main)
   - private static final boolean CHECK_FULL = false;
   - private static final int[] SELECTED_TESTS = {};  // run only these test numbers when non-empty
   - Header banner: title, difficulty, mode, selected tests
   - runTests(): 6-8 basic tests that always run, then 8-12 edge-case tests inside if (CHECK_FULL)
   - Every test has a number, a one-line description, and an expected value
   - A test(...) helper that takes a supplier lambda, catches exceptions, and prints
     "✓ PASS [#n]: desc" or "✗ FAIL [#n]: desc" followed by Expected / Got
   - Compare results correctly for the return type: Arrays.equals / Arrays.deepEquals for arrays, .equals for objects and lists, order-insensitive comparison when the problem allows any order. For in-place problems, pass a copy of the input and compare the mutated copy.
   - printSummary(): passed/total, plus the reminders about CHECK_FULL and SELECTED_TESTS
   - Edge cases must cover: empty or minimum-size input, boundaries from the constraints, duplicates, negatives where relevant, a no-answer case, and one larger input

4. ALGORITHM HINTS (block comment at the very bottom, after a line of "SPOILER BELOW")
   - Optimal approach as commented-out Java code
   - Key insights (3-5 bullets)
   - One clean walkthrough of the sample input
   - Time and space complexity with a one-line justification
   - Common mistakes

Correctness rules, which matter more than formatting:
- Before writing any expected value, work out the answer from the problem definition, then check it against the reference solution in section 4. If the two disagree, fix whichever is wrong. Never ship a test whose expected value you have not verified.
- If you can execute code, compile the file with the reference solution temporarily placed in Solution, run with CHECK_FULL = true, confirm every test passes, then restore the TODO stub before giving me the file.
- Test descriptions must agree with their expected values. No "X or Y" descriptions.
- The walkthrough contains only the final, correct trace. No visible self-correction ("wait, that's wrong", "let me recalculate").
- The file must compile as delivered with the stub in place, using only java.util.
- Output only the file, in one code block, named <ProblemNameInPascalCase>.java.

PROBLEM:
<paste problem here>
