# Test Quality Techniques

Companion to `SKILL.md`. Coverage measures what code *ran*, not what was *verified* — a test can execute every
branch of a function and still assert nothing that would catch a real bug in it. These techniques close that gap.

## Mutation testing — the primary technique

Introduces small synthetic bugs ("mutants": `>` becomes `>=`, `+` becomes `-`, a negation removed, a constant
changed) and reruns the suite against each mutated version.

- **Killed mutant** — some test failed. Good: the suite catches this class of bug.
- **Surviving mutant** — every test still passed. Bad: nothing in the suite actually verifies that behavior, no
  matter what the line/branch coverage number says.

The **mutation score** (percentage of mutants killed) is a stronger signal than coverage precisely because it
exposes assertion-free or over-permissive tests that a coverage tool counts as "tested." Concrete setup for this
repo's stacks (PIT via `pitest-maven`) lives in `pragmatic-tdd-quarkus/references/testing-toolbox.md` and
`pragmatic-tdd-spring/references/testing-toolbox.md` — this file is the concept and when to reach for it.

Tools by language, for reference outside this repo's JVM projects:

| Language | Tool |
|---|---|
| Java | PIT (pitest) |
| JavaScript/TypeScript | Stryker |
| Python | mutmut, Cosmic Ray |
| .NET | Stryker.NET |
| PHP | Infection |

## Coverage, with its limits

- **Line/statement coverage** — % of lines executed. Weak alone: proves a line ran, not that anything checked it.
- **Branch coverage** — covers both sides of every conditional; more informative than line coverage.
- **Path coverage** — covers combinations of paths; rigorous, but explodes combinatorially on complex code.

Use branch coverage as a floor, never as the goal — it tells you what's *untested*, not what's *undertested*.

## Property-based testing and fuzzing

- **Property-based testing** (jetCheck, fast-check, Hypothesis, QuickCheck) — state an invariant instead of fixed
  examples; the tool generates hundreds of inputs, including edge cases a human wouldn't think to write, and tries
  to break the property. Use it when the function under test has a real mathematical/invariant property — see the
  "Property-based testing" section in `SKILL.md`.
- **Fuzzing** — (semi-)random or coverage-guided input generation aimed at crashes and unhandled exceptions. Most
  useful on parsers, endpoints, and anything else that consumes untrusted external input.

## Structural signals worth checking

- **Assertion-free / assertion-density detection** — the direct symptom of a decorative test: code executes,
  nothing verifies the result. The usual reason coverage is high while the mutation score is low.
- **Flaky test detection** — rerun the suite (or run it in parallel) to surface non-deterministic tests before they
  erode trust in the whole suite; an ignored flaky test is a real regression waiting to slip through unnoticed.
- **Test impact analysis** — which tests actually exercise the code a change touched. Useful for judging whether a
  PR's tests match its diff — not a license to skip tests in CI.

## History-driven techniques

- **Regression corpus** — every bug that reached production becomes a test before the fix lands. This is the same
  discipline as `SKILL.md`'s "a bug fix starts with a failing repro," generalized to any bug, not only the one
  currently being fixed.
- **Manual bug seeding** — like mutation testing but with plausible domain bugs instead of syntactic mutations.
  Useful when no mutation tool exists for the stack, or the risk is domain-specific (an off-by-one in a billing
  calculation, not a generic operator swap a mutation tool would find anyway).

## Human review

Review the tests themselves, not only the production code they cover: do the edge cases and failure scenarios
actually match what the domain needs, or do they just exist to pad a coverage or mutation number?

## Practical default

1. Branch coverage as the floor.
2. Mutation testing to validate the assertions are real, not just present.
3. Property-based testing on functions that have a genuine invariant to state.
