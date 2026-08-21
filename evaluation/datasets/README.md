# Dataset card

## Intended use

These datasets evaluate technical answers for experienced backend, platform, and
security practitioners. They cover architecture, debugging, security, grounded
reasoning, planning, underspecified requests, and adversarial context. They do not
represent consumer chat, creative writing, multilingual use, medical, legal, or
other regulated advice.

`pilot-v1.yml` is the development and tuning set.
`prompt-injection-regression-v1.yml` is a development security-regression set;
it is intentionally visible to implementers and cannot support an unseen-quality
claim. `held-out-v1.yml` was the first measurement set: 36 project-authored cases, disjoint from the pilot, with six
architecture, six debugging, five security, six grounded-reasoning, five planning,
four underspecified, and four adversarial-context cases. `smoke-v1.yml` tests only
pipeline mechanics. `held-out-v2.yml` is a compact six-case, one-shot diagnostic
covering architecture, Java debugging, security, exact quantitative reasoning,
rollout planning, and an underspecified database decision. Its small size shortens
feedback time but cannot establish broad category performance or a small quality
advantage.

## Construction and provenance

The cases are synthetic, project-authored scenarios rather than sampled production
traffic or copied benchmark questions. As of 2026-08-20, `held-out-v1` is
**contaminated for future confirmation** because its observed failure directly
informed council prompt, trust-boundary, and validator changes. Preserve its
published runs as historical evidence; author `held-out-v2` with new cases before
the next confirmatory quality claim. Each case contains a candidate-visible
question and context plus evaluator-only requirements, reference facts, red flags,
and narrow deterministic checks. Grounded-reasoning cases use derivable answers;
technical cases encode review expectations chosen during dataset authoring.

Because the set is hand-authored, its cases may share author assumptions and style.
The repository does not claim population representativeness or independence from
public model-training data after publication. Dataset results apply to this declared
workload, not to all software-engineering questions.

## Inclusion and exclusion

Cases were selected to exercise correctness under ambiguity, trade-off analysis,
failure diagnosis, adversarial instructions, and security-sensitive reasoning.
They exclude tasks that require browsing, proprietary context, execution of a live
system, subjective creative preference, or specialist regulated advice. Category
counts are coverage targets, not estimates of real-world task frequency.

## Contamination policy

Prompts, thresholds, protocols, and rubrics may be developed against `pilot-v1`.
Do not tune them after inspecting held-out outcomes. If any result from
`held-out-v1` influences system or evaluator design, mark that version contaminated,
retain it for historical audit, and author a newly versioned measurement set before
the next confirmatory claim. Apply the same rule to `held-out-v2`: its first run is
the only unseen run. If its answers or checks drive a code, prompt, rubric, or
threshold change, label v2 as diagnostic/contaminated and use new cases for later
confirmation instead of rerunning v2 as proof of the fix.

Public release also makes future training-data contamination possible. Record the
model identifiers and evaluation date, and do not describe later runs on this same
public set as fully unseen evidence.

## Interpretation

Report category results only when the category has enough cases to make the number
meaningful. Preserve failures and unresolved judgments. Human review remains
necessary where correctness cannot be derived from the supplied facts, and model
judge results must be presented with their family-independence limitations.

`held-out-v2-fast.yml` has only one case per category and no model judge. Compare
its deterministic pass rates and inspect all 18 answers, but do not report a
category win rate or claim that one protocol is superior from this plan.
The v2 checks also reject internal `draft-*`, `review-*`, `score-*`, and `turn-*`
identifiers. Prompt-injection marker checks distinguish executing a marker at the
start of an answer from safely quoting it later while rejecting its authority.
The benign-analysis check also accepts accurate descriptions such as unauthorized
override or manipulation rather than requiring the literal label “prompt injection.”
