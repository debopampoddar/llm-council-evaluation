# Evaluation Methodology

## Evaluation question

The primary question is not “does the council produce a plausible answer?” It is:

> For a defined workload, does a named council configuration improve blinded answer
> quality over a meaningful baseline, and at what reliability, latency, call, token,
> and cost trade-off?

Every reported claim must name the dataset, variants, model versions, council
catalog, rubric, repetitions, and judges represented by the run manifest.

## Experimental design

### Baselines

Use `DIRECT` for a genuine one-call baseline. It sends the same case question and
context directly to one declared model. Do not label a council quick mode as a
direct baseline.

The direct and council paths necessarily use different system/orchestration prompts.
Align provider model versions, output limits, temperatures, and other controllable
settings when the intended claim is about the protocol, and disclose the remaining
prompt-template confound.

Use `SAME_MODEL_ENSEMBLE` as an ablation when asking whether gains come from the
council protocol or merely from spending more calls. It generates independent
samples with one model, then asks that same model to synthesize them. This is useful
but not independent evidence.

### Dataset

Develop the harness on `smoke-v1.yml` and `pilot-v1.yml`. Before publishing:

1. Define target users and their task distribution.
2. Create at least 30–50 diverse held-out cases; use more when effects are small.
3. Include routine, ambiguous, adversarial, and failure-prone prompts.
4. Keep evaluator-only requirements and facts out of the candidate prompt.
5. Version the dataset and do not tune the system on the final test set.
6. Report category-level results when categories have enough cases.

A bigger biased dataset is not automatically better. Case provenance, coverage,
and independence matter more than a raw count.

### Repetitions

Provider generation is stochastic and the harness seed cannot control it. Run at
least three repetitions for a publishable experiment when budget permits. The
interval calculation first averages repeated observations within each case, so
repetitions do not masquerade as independent questions.

## Blinded pairwise judging

Candidate variant names are absent from judge prompts. A deterministic seed maps
the two candidates to Answer A and Answer B. With `mirrored: true`, the judge sees
the same pair a second time in reverse order.

Each judge must return the exact JSON contract, including:

- `winner`: `A`, `B`, or `TIE`
- confidence in the allowed range
- an exact score for every rubric criterion for both candidates
- violations for both candidates
- a non-blank rationale

The parser permits surrounding prose only when it can recover exactly one JSON
object, then validates fields and exact criterion coverage. An invalid response may
be retried up to `execution.judgeInvalidRetries`; every raw attempt is retained and
only the final valid or exhausted result becomes canonical evidence.

For one judge, opposing mirrored decisions make that judge's pair result
position-unstable. Across judges, a strict majority is required. Ties,
position-instability, invalid evidence, and no-majority outcomes are preserved
rather than forced into a winner.

Prefer judges from model families that are not candidate families. Two endpoint
names from the same family are correlated evidence, not two independent judges.
The report labels known overlap from configured `modelFamily` values and council
catalog metadata.

## Outcome definitions

Reliability and quality are reported separately:

- Intended pairs: cases × repetitions for the comparison.
- Eligible pairs: intended pairs where both variants produced judgeable answers.
- Judged pairs: eligible pairs with a resolved winner or tie.
- Unresolved: eligible evidence with invalid responses, position conflict, or no
  judge majority.
- Answer rate: completed plus partial attempts divided by attempted units.
- Tie-adjusted left preference: left wins count as 1, ties as 0.5, right wins as 0.

The eligible/intended gap exposes operational failure. Pairwise preference is
conditional on both systems answering and must not be presented alone.

## Confidence intervals

The harness computes a Wilson-style 95% interval over case-level tie-adjusted
preference. It averages repetitions within each case first. Unlike a percentile
bootstrap on a small all-win pilot, this interval does not collapse to 100%–100%.
It communicates sampling uncertainty but does not correct dataset bias, judge bias,
temporal drift, correlated cases, or multiple comparisons.

The report also gives unresolved-outcome sensitivity bounds. The lower bound treats
every unresolved eligible pair as a right-side win; the upper bound treats every
one as a left-side win. This is deliberately conservative and keeps invalid,
missing, position-unstable, and judge-disagreement evidence visible.

Treat overlapping intervals cautiously and avoid turning a pilot estimate into a
binary “wins” claim. If testing many variants or categories, preregister the primary
comparison or account for multiplicity outside the harness.

## Deterministic checks

Checks validate explicit constraints such as required terms, forbidden content,
format regexes, and length. They are not semantic quality substitutes. A brittle
regex can punish a correct paraphrase, so keep checks narrow and review every failure.

## Costs and call limits

The call budget reserves a worst-case upper bound before each evaluation-owned
unit and reconciles against recorded usage afterward. Council estimates derive from
the catalog's protocol stages and force-run settings. Retries occurring inside the
council may not appear in its result usage and therefore cannot be fully reserved.

Token cost is computed from prices stored in the plan. Prices are not fetched
automatically because that would make old runs change meaning over time. Update and
record current prices before a run. Missing or zero cloud prices cause incomplete
cost reporting. Explicit zero prices for Ollama are treated as known local cost of
`$0`; this does not estimate electricity or hardware cost.
`maxEstimatedCostUsd` is a post-call stop, so one finished call can cross the
threshold.

## Human review

The generated review template contains blinded answers and evaluator-only guidance.
The reveal mapping is written to a separate file. Reviewers should avoid opening the
key until decisions are complete. Imported decisions are normalized to variant IDs
only after pair-ID and winner validation and remain a separate section in the report.

For stronger evidence, use multiple reviewers, measure agreement, define an
adjudication policy before revealing variants, and keep reviewer identity or role in
an external controlled record. The current importer intentionally does not claim to
provide multi-rater agreement statistics.

## Minimum publishable checklist

- Held-out and versioned representative dataset, normally 30–50+ cases.
- True direct baseline and an explicit primary comparison.
- Three or more stochastic repetitions when feasible.
- Mirrored judging with at least two meaningfully different judge families.
- Current model identifiers and prices captured in the plan.
- Clean source commit and immutable run evidence archived.
- Reliability, quality, uncertainty, latency, calls, tokens, and cost all reported.
- Human review for a defensible subset, kept separate from model judging.
- Limitations and excluded/failed evidence stated without euphemism.

The manifest fingerprints council configuration but cannot prove the binary or
source commit behind a remote council URL. For published work, record the council
release/commit alongside the archived run and do not rely on the catalog hash alone.
