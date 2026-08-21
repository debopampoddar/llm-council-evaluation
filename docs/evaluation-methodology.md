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

Develop the harness on `smoke-v1.yml`, `pilot-v1.yml`, and the explicit
`prompt-injection-regression-v1.yml` security regression. `held-out-v1.yml` was
the first 36-case measurement set and its archived results remain historical
evidence. As of 2026-08-20 it is contaminated for future confirmation because an
observed adversarial failure informed application changes. Do not rerun it to
claim the fix generalizes. `held-out-v2.yml` is the new disjoint six-case set,
and `held-out-v2-fast.yml` is its deterministic-only execution plan. It is sized
for rapid diagnosis: 18 candidate answers, no correlated local judge, and no
statistical superiority claim.

Security mechanics checks must not confuse safe discussion with instruction
adoption. The shipped regression therefore uses anchored forbidden patterns for
command markers and separate positive task-content checks. Both the security and
v2 diagnostic sets reject internal council identifiers in candidate answers.

Keep that separation. `pilot-v1` is where thresholds and prompts may be adjusted;
`held-out-v1` is not. The first v2 run is likewise the only unseen v2 run. If an
answer or failure from it is used to change code, prompts, checks, rubric, or
thresholds, mark v2 contaminated and author new confirmation cases — a later v2
number would measure fit to a visible diagnostic, not generalization.

Six cases are enough to find large, actionable regressions quickly but not enough
to estimate small effects or category-level performance. Inspect all outputs
against evaluator requirements and red flags. A later quality claim still needs a
larger frozen set, blinded comparisons, independent judge families, and a human
review subset.

The requirements this set was built against:

1. Define target users and their task distribution.
2. Create at least 30–50 diverse held-out cases; use more when effects are small.
3. Include routine, ambiguous, adversarial, and failure-prone prompts.
4. Keep evaluator-only requirements and facts out of the candidate prompt.
5. Version the dataset and do not tune the system on the final test set.
6. Report category-level results when categories have enough cases.

A bigger biased dataset is not automatically better. Case provenance, coverage,
and independence matter more than a raw count.

### Repetitions

Provider generation is stochastic and the harness seed cannot control it.
Repetitions reduce per-case measurement noise, and the interval calculation first
averages repeated observations within each case, so repetitions do not masquerade
as independent questions.

**Understand what that means before buying repetitions.** Because observations are
averaged within case, `n` is the number of cases. Repetitions make each case's
value more stable; they do not add independent observations and do not narrow the
sampling interval. They can still change per-case averages—and even the observed
direction—by reducing generation and judging noise, so a first result must not be
called sign-stable without replication.

### What a given dataset size can resolve

The 95% interval must exclude 0.5 before a preference means anything. At the
sizes in this repository:

| Cases | Smallest win rate that clears 0.5 |
|---:|---|
| 36 (`held-out-v1`) | 24/36 = **66.7%** |
| 91 | 60% |
| 370 | 55% |

So a stable 58/42 effect cannot produce a narrow case-sampling interval at only 36
cases, regardless of repetitions. If a first pass is inconclusive,
**add cases, not repetitions**: roughly 100 cases at one repetition costs about
what 36 cases at three repetitions costs, and resolves a materially smaller
effect.

Use three repetitions once `n` is adequate for the effect you expect, or when
per-case variance is visibly large. Note that the run id embeds the plan hash, so
changing `repetitions` starts a new run rather than extending an existing one.

## Blinded pairwise judging

Before candidate generation, every enabled judge receives the same small control
pair: one answer correctly states that 2 + 2 = 4 and the other states 2 + 2 = 5.
It must return the normal exact JSON contract and choose the correct answer. This is
a pipeline capability check, not a quality benchmark: it catches blank/exhausted
responses, broken structured-output behavior, and gross instruction failure early.
The evidence is stored under `preflight/judges/` and reused on resume.

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

Prefer judges from model families that are not candidate families and are not
used anywhere in a council candidate's generation, chair, or validation path. A
Gemma judge is therefore correlated with the post-2026-08-20 local council, whose
dedicated validator is Gemma. Two endpoint
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

The shipped held-out plan preregisters `ensemble-vs-rigorous` as primary because it
tests whether the council protocol adds value beyond spending multiple calls on the
same base model. `direct-vs-rigorous` is a key secondary comparison; the remaining
comparisons are exploratory. This hierarchy must be fixed before results are read.

## Runtime measurement conditions

Candidate variants run sequentially. Overlapping a direct or ensemble candidate
while measuring another variant would make latency and failure rates functions of
resource contention rather than the variant alone. Independent blind judgments may
run concurrently after candidate evidence is complete.

Candidate and judgment concurrency plus the harness-visible `OLLAMA_NUM_PARALLEL`
are stored in the manifest without entering the plan hash. New-format runs refuse a
resume under changed settings. A legacy manifest has no such evidence and is allowed
only with an explicit warning. For defensible latency reporting, also state the
hardware and ensure no unrelated model workload shared it during candidate generation.

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
- Three or more stochastic repetitions when feasible, or an independently started
  replication before describing a one-repetition direction as settled.
- Mirrored judging with at least two meaningfully different judge families.
- Current model identifiers and prices captured in the plan.
- Clean source commit and immutable run evidence archived.
- Reliability, quality, uncertainty, latency, calls, tokens, and cost all reported.
- Human review for a defensible subset, kept separate from model judging.
- Limitations and excluded/failed evidence stated without euphemism.

The manifest fingerprints council configuration but cannot prove the binary or
source commit behind a remote council URL. For published work, record the council
release/commit alongside the archived run and do not rely on the catalog hash alone.
