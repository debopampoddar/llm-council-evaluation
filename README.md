# LLM Council Evaluation

[![CI](https://github.com/debopampoddar/llm-council-evaluation/actions/workflows/ci.yml/badge.svg)](https://github.com/debopampoddar/llm-council-evaluation/actions/workflows/ci.yml)
[![Java 25](https://img.shields.io/badge/Java-25-007396.svg)](https://openjdk.org/projects/jdk/25/)

An independent, reproducible evaluation harness for
[`llm-council`](https://github.com/debopampoddar/llm-council). It compares council
profiles with true direct-model and same-model-ensemble baselines through the
council's public REST API, then produces auditable Markdown, JSON, and CSV evidence.

This is deliberately a separate project. Candidate execution, judge prompts,
statistics, and reports are not implemented inside the system being evaluated.

## Status At A Glance

| Area | Status |
|---|---|
| Deterministic harness | 50 hermetic tests; packaged CLI verified in CI |
| Security regression | Seven visible development cases, 29 checks per council variant, no model judge |
| Fast diagnostic | Six cases across Direct, BALANCED, and RIGOROUS; useful for diagnosis, not superiority claims |
| Historical held-out evidence | Tracked and auditable, but contaminated for confirmation after its findings informed changes |
| Publishable quality claim | Still open; requires a fresh frozen dataset, independent judge families, and human review |

The tracked historical 36-case local ablation did **not** demonstrate a RIGOROUS
advantage. It favored the direct and same-model-ensemble baselines under one local
judge, included partial council runs, and exposed an adversarial failure. That is
useful engineering evidence, not a result to hide—but it does not validate the
current hardened code or generalize beyond the recorded environment.

## Evaluation Workflow

1. **Plan:** validate versioned inputs, live catalog compatibility, model health,
   judge controls, and the maximum call budget.
2. **Run or resume:** persist every candidate, check, judgment attempt, and runtime
   fingerprint before moving forward.
3. **Review before publishing:** inspect reliability, deterministic failures,
   unresolved judgments, independence, raw answers, limitations, and blinded human
   decisions. A generated report is evidence to review, not an automatic approval.

Start with the [report handbook](docs/reviewing-evaluation-results.md) if you are
unsure which plan to run or how to decide whether its result is clean.

## What it measures

- Reliability: completed, partial, and failed answers, with failures retained.
- Quality: blinded pairwise preferences with mirrored answer order.
- Uncertainty: case-level Wilson-style 95% intervals plus unresolved-outcome sensitivity bounds.
- Efficiency: latency, model calls, tokens, and configured estimated cost.
- Requirements: deterministic content and regex checks.
- Judge risk: model-family overlap and position-instability are reported.
- Human review: an optional blinded packet and separate reveal key.

## Prerequisites

- JDK 25
- Maven 3.9+
- A running `llm-council` server for preflight and council variants
- Ollama or the credentials required by models declared in the selected plan

The included local plans assume:

- council API: `http://127.0.0.1:8080`
- Ollama API: `http://127.0.0.1:11434`
- council models: `llama3.1:8b`, `mistral:7b`, `qwen2.5:7b`, and the
  `gemma4:12b-it-qat` validator
- evaluator models in the historical local plans: `llama3.1:8b` for the direct
  baseline and `gemma4:12b-it-qat` as the one local judge

Install Gemma before running the current council or a historical local plan:

```bash
ollama pull gemma4:12b-it-qat
```

Gemma is no longer independent of the current council because it now performs
the council's validation. Historical plans and published evidence are retained
unchanged for audit, but a new publishable comparison must use judge families
outside every candidate's member, chair, and validator path.

The local plans explicitly send a bounded Ollama context window. This is a
practical laptop default; it reduces memory pressure while leaving ample room for
the judge rubric and two candidate answers.

## Build and verify

```bash
mvn --batch-mode --no-transfer-progress clean verify
ruby scripts/verify-markdown-links.rb
```

No tests make paid or live model calls. HTTP integrations use local fake servers;
the deterministic suite is enumerated by Maven, and CI also validates every local
Markdown link in the README, documentation guides, and dataset card.

## Run the post-hardening security regression

After building the updated council, run the seven-case deterministic regression
across QUICK, BALANCED, and RIGOROUS:

```bash
./scripts/evaluate.sh plan evaluation/plans/prompt-injection-regression.yml
EVALUATION_SKIP_BUILD=true ./scripts/evaluate.sh \
  run evaluation/plans/prompt-injection-regression.yml --confirm-live
```

This plan has no model judge or pairwise quality claim. Gemma cannot independently
judge the gate it now implements. A clean run requires all 21 answer units to be
`COMPLETED`, with no empty, partial, or failed candidate, and 29 passes / 0 failures /
0 errors for each of QUICK, BALANCED, and RIGOROUS. Fail-closed rejection contains
an attack, but an empty or failed answer is not useful and therefore does not pass
this release gate. Inspect candidate status, warnings, checks, and raw artifacts
rather than reducing the run to one count. Marker checks reject carrying out a
command as a standalone answer or verdict segment while allowing safe explanation.
Every case also rejects a closed list of internal IDs, machine labels, and
application-owned process phrases.

## Historical local experiments

The tracked reports are:

- [Two-case mechanics rehearsal](evaluation/published/20260819-181950-443-held-out-smoke-f551ded9/report/report.md)
- [Thirty-six-case held-out ablation](evaluation/published/20260819-184453-933-held-out-ablation-6a42bc2e/report/report.md)

The smoke run checks pipeline mechanics only. In the 36-case ablation, Direct was
preferred over RIGOROUS on 75.9% of resolved comparisons, and the same-model
ensemble was preferred over RIGOROUS on 67.9% of resolved comparisons. Those
conditional estimates came from one local judge, had unresolved/position-unstable
cases, and predate the current validator overlap and security hardening. Read the
full report and limitations rather than quoting the percentages alone.

The following reproduces the original two-case mechanics rehearsal:

```bash
./scripts/evaluate.sh plan evaluation/plans/held-out-smoke.yml
EVALUATION_SKIP_BUILD=true ./scripts/evaluate.sh \
  run evaluation/plans/held-out-smoke.yml --confirm-live
```

That exercises council health, model tags, the judge JSON contract, the 2+2
preflight control, deterministic checks, the evidence layout, and report
generation. It is historical pipeline evidence, not validation of the hardened
application: its Gemma judge is now correlated and its dataset informed the fix.

Start `llm-council`, then inspect the live catalog, profile health, installed Ollama
models, configured cloud credentials, and expected call budget:

```bash
./scripts/evaluate.sh plan evaluation/plans/local-pilot.yml
```

Read the estimate and warnings before starting live calls:

```bash
EVALUATION_SKIP_BUILD=true ./scripts/evaluate.sh \
  run evaluation/plans/local-pilot.yml --confirm-live
```

Two acknowledgements protect against accidental work:

- `execution.liveCallsAcknowledged: true` and `--confirm-live` are always required.
- If any provider may be billable, `execution.billableCallsAcknowledged: true` and
  `--confirm-billable` are also required.

Resume an interrupted run without repeating completed answer or judge units:

```bash
EVALUATION_SKIP_BUILD=true ./scripts/evaluate.sh \
  resume evaluation/results/<run-id> --confirm-live
```

Resume refuses to combine results if the council catalog configuration changed.

The launcher prints build/skip state, the command being started, and the configured
heartbeat interval. Runs print every answer and judgment start/completion, including
ordinal progress, status, duration, and recorded calls. A heartbeat is printed every
30 seconds while a long model call is still active. If a skipped-build JAR is older
than the sources, the launcher warns before running it. In a second terminal, inspect
progress without contacting a model:

```bash
EVALUATION_SKIP_BUILD=true ./scripts/evaluate.sh \
  status evaluation/results/<run-id>
```

If a run was started with an older JAR that does not yet have the `status` command,
the dependency-free fallback is `bash scripts/status.sh evaluation/results/<run-id>`.

Set `EVALUATION_PROGRESS_HEARTBEAT_SECONDS` to change the heartbeat interval, or
`EVALUATION_PROGRESS_ENABLED=false` for quiet automation.

## Reports and human review

Regenerate reports without any model calls:

```bash
./scripts/evaluate.sh report evaluation/results/<run-id>
```

For human review, copy `human/human-review-template.json`, set each `winner` to
`A`, `B`, or `TIE`, add a non-blank `rationale`, and import it:

```bash
./scripts/evaluate.sh import-human evaluation/results/<run-id> decisions.json
```

The importer validates pair IDs against the private reveal key. Human decisions
remain separate from model-judge outcomes.

Use the [evaluation report handbook](docs/reviewing-evaluation-results.md) to review
preflight, smoke, deterministic, security, ablation, judged, resumed, and publishable
runs. It explains every report section, raw evidence, confidence and unresolved
ranges, judge independence, efficiency, human review, response inspection, failure
triage, and publication decisions, with worked smoke and held-out examples.

## Included plans

| Plan | Purpose |
|---|---|
| `prompt-injection-regression.yml` | Current deterministic live regression across local QUICK, BALANCED, and RIGOROUS, including bounded recovery headroom. No model judge and no overall quality claim. |
| `held-out-v2-fast.yml` | Six-case, 18-answer deterministic diagnostic across Direct, BALANCED, and RIGOROUS. Fast feedback; no model judge or superiority claim. |
| `held-out-smoke.yml` | Historical two-case rehearsal of `held-out-ablation`; retained for reproduction, not current evidence. |
| `held-out-ablation.yml` | Historical first held-out measurement. Its dataset is now contaminated for confirmation and its Gemma judge overlaps the current validator. Do not rerun it as proof of the fix. |
| `local-pilot.yml` | Direct Llama vs balanced and rigorous local councils. Pipeline validation. |
| `local-ablation.yml` | Direct vs five-sample same-model ensemble vs council, on the pilot dataset. |
| `rigorous-stage-coverage.yml` | Mechanics-only check for forced rigorous debate stages. |
| `publishable-template.yml` | Multi-family cloud-judge template; not publishable until customized. |

## Datasets

| Dataset | Cases | Use |
|---|---|---|
| `smoke-v1.yml` | 2 | Mechanics check. Two cases, minutes not hours. |
| `pilot-v1.yml` | 12 | Harness development. **Tuning target** — not evidence. |
| `prompt-injection-regression-v1.yml` | 7 | Visible development security regression; not an unseen benchmark. |
| `held-out-v1.yml` | 36 | Historical measurement input; contaminated for future confirmation as of 2026-08-20. |
| `held-out-v2.yml` | 6 | Compact one-shot diagnostic across six technical categories. If its results drive changes, mark it contaminated and do not reuse it as confirmation. |

`held-out-v1` spans architecture, debugging, security, grounded reasoning,
planning, underspecified requests, and adversarial context, and is disjoint from
`pilot-v1`. Its grounded-reasoning cases have exact derivable answers, so their
deterministic checks are objectively defensible rather than keyword guesses.
Its intended audience, construction, coverage, limitations, and contamination
policy are documented in [`evaluation/datasets/README.md`](evaluation/datasets/README.md).

`held-out-v1` has now informed system changes, so preserve it for historical audit
and author a disjoint `held-out-v2` before the next confirmatory quality run.
Develop prompt-injection behavior against the named regression set; do not disguise
that visible regression as held-out evidence.

For release gating, safe containment is necessary but not sufficient. A blocked
payload with an empty or failed candidate is safer than returning the attack, but
the regression is not clean until bounded recovery returns usable answers without
markers, internal identifiers, reserved machine/process vocabulary, unexplained
partial status, or deterministic-check failures. The current seven-case contract
expects 29 passes, zero failures, and zero errors for each of QUICK, BALANCED, and
RIGOROUS. The plan intentionally has no judge; the runner reports that judging was
skipped rather than announcing a phase with no eligible work.

Run the compact v2 diagnostic after the prompt-injection regression:

```bash
./scripts/evaluate.sh plan evaluation/plans/held-out-v2-fast.yml
EVALUATION_PROGRESS_HEARTBEAT_SECONDS=15 EVALUATION_SKIP_BUILD=true \
  ./scripts/evaluate.sh run evaluation/plans/held-out-v2-fast.yml --confirm-live
```

This produces 18 candidate answers and no judgments. Acceptance means all expected
units and deterministic-check files exist, no unexplained candidate failures occur,
no internal council identifiers appear, and every answer is manually inspected
against its requirements and red flags. It
is a rapid correctness diagnostic, not the multi-family judged experiment required
for a publishable quality-superiority claim.

## Run speed

Candidate variants run sequentially so their latency and reliability are measured
without cross-variant resource contention. Blind judgment units run only after all
candidate evidence exists and may overlap. Judgment concurrency is read from the
environment rather than the plan:

```bash
EVALUATION_CONCURRENCY=3 EVALUATION_SKIP_BUILD=true \
  ./scripts/evaluate.sh run evaluation/plans/held-out-ablation.yml --confirm-live
```

**It is not a plan field on purpose.** The plan hash forms the run id, so this
performance setting does not create a different experiment identity. It is still
recorded in `manifest.json`, because contention can affect judge latency and model
behaviour. A new-format run must resume with the same effective concurrency and
declared `OLLAMA_NUM_PARALLEL`; otherwise the harness refuses to mix conditions.
Legacy manifests without this metadata remain resumable with an explicit warning.

**Council variants are never overlapped.** The service under test defaults to
`council.runtime.max-concurrent-runs: 1` and rejects a second overlapping run
rather than queueing it. Council units run first within each case so a health or
quorum problem surfaces early; direct and ensemble units then run one at a time.
Only judgments are eligible for harness concurrency.

Judging is roughly a third of a full run and is entirely council-free, so it is
the safe place to improve throughput. Actual speedup is hardware- and model-
dependent; measure it rather than assuming parallel inference is faster. The
default is `1`.

Raise Ollama's own `OLLAMA_NUM_PARALLEL` to match if the hardware can sustain it,
or the daemon may serialise what the harness hands it concurrently. Export the
same value to the harness process so it is captured in the manifest.

## Provider configuration

Ollama needs no secret. Cloud secrets stay in environment variables and must never
be placed in plan files:

```bash
export SPRING_AI_OPENAI_API_KEY=...
export SPRING_AI_OPENAI_BASE_URL=https://api.openai.com
export SPRING_AI_ANTHROPIC_API_KEY=...
export GOOGLE_CLOUD_PROJECT=...
export GOOGLE_CLOUD_LOCATION=us-central1
gcloud auth application-default login
```

The `plan` command's infrastructure preflight performs no text generation. Ollama
model availability is checked through `/api/tags`; cloud configuration is checked
locally. After live-call confirmation and run-directory creation, `run` performs one
persisted control judgment per enabled judge before generating candidates. The
control has an objectively superior answer and must produce valid exact-contract
JSON selecting it; a blank, exhausted, malformed, or incorrect response stops the
run before the expensive candidate workload. Passing preflight evidence is reused
on resume. The plan's worst-case call estimate includes these smoke calls and their
configured provider retries.

Ollama JSON-mode requests also set `think: false`. This matters for thinking-capable
judge models such as Gemma: hidden reasoning must not consume the entire output
budget and leave the required JSON response blank. If Ollama reports a blank response
with `eval_count` at the configured output limit, the harness records
`OUTPUT_EXHAUSTED`, retains the token usage, and does not waste retries on the same
deterministic limit.

Set current per-1,000-token prices in the plan. A zero-priced cloud model is
reported as unpriced, making cost totals incomplete.

## Evidence layout

```text
evaluation/results/<run-id>/
├── manifest.json
├── state.json
├── preflight/catalog.json
├── preflight/judges/<judge>.json
├── answers/<case>/<variant>/rNN.json
├── checks/<case>/<variant>/rNN.json
├── judgment-attempts/<comparison>/<case>/rNN/<judge>/oN/attempt-N.json
├── judgments/<comparison>/<case>/rNN/<judge>/oN.json
├── human/
│   ├── human-review-template.json
│   └── human-review-key.json
└── report/
    ├── report.md
    ├── metrics.json
    ├── metrics.csv
    └── judge-independence.json
```

Each evidence unit is atomically written. The manifest embeds normalized inputs,
their hashes, the council-catalog fingerprint, source commit/dirty state, Java and
OS information, and harness version.

`evaluation/results/` is gitignored — it is a working directory rewritten by every
run. Anything cited in writing must first be copied into the tracked
`evaluation/published/<run-id>/`, which documents exactly which files to keep and
why. A number whose provenance is not in the repository cannot be checked by a
reader.

## Documentation

Use the [documentation guide](docs/README.md) to choose between the architecture,
methodology, authoring reference, dataset card, and report-review handbook.

## License Status

This repository currently has no `LICENSE` file. Do not assume that the
application repository's GPL-3.0 license automatically applies here. Choose and
add explicit terms before presenting this harness as reusable by third parties.

## Honest limitations

LLM output remains stochastic. The seed controls answer blinding, not provider
generation. LLM judges remain biased even with mirrored order. Wilson-style
intervals describe sampling uncertainty only; unresolved sensitivity bounds expose
how missing or conflicting judgments could change the estimate. Council call
estimates cover advertised protocol topology, while internal provider retries may
not be visible. The cost ceiling is an observed post-call guard, so a completed call
can cross it and unpriced calls cannot be capped.

A single judge cannot form a cross-judge majority, so a position-unstable pair
resolves to unresolved rather than being outvoted. `held-out-ablation.yml` ships
with one local judge for cost reasons. That Gemma judge now overlaps the current
council validator. Use two external judge families and a new held-out dataset
before treating a new margin as publishable.

Direct and council paths necessarily use different orchestration prompts. When the
intended claim is about the protocol, that prompt-template difference remains a
confound this harness discloses rather than removes.
