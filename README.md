# LLM Council Evaluation

An independent, reproducible evaluation harness for
[`llm-council`](https://github.com/debopampoddar/llm-council). It compares council
profiles with true direct-model and same-model-ensemble baselines through the
council's public REST API, then produces auditable Markdown, JSON, and CSV evidence.

This is deliberately a separate project. Candidate execution, judge prompts,
statistics, and reports are not implemented inside the system being evaluated.

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
- council models: `llama3.1:8b`, `mistral:7b`, and `qwen2.5:7b`
- evaluator models: `llama3.1:8b` for the direct baseline and
  `gemma4:12b-it-qat` for an independent local judge

Install the additional judge before running a local plan:

```bash
ollama pull gemma4:12b-it-qat
```

The local plans explicitly send a 16,384-token Ollama context window. This is a
practical laptop default; it reduces memory pressure while leaving ample room for
the judge rubric and two candidate answers.

## Build and verify

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

No tests make paid or live model calls. HTTP integrations use local fake servers;
the deterministic suite is enumerated by Maven so this documentation cannot drift
when coverage is added.

## Run a local pilot

Rehearse the whole pipeline on two cases before spending a night on it:

```bash
./scripts/evaluate.sh plan evaluation/plans/held-out-smoke.yml
EVALUATION_SKIP_BUILD=true ./scripts/evaluate.sh \
  run evaluation/plans/held-out-smoke.yml --confirm-live
```

That exercises council health, model tags, the judge JSON contract, the 2+2
preflight control, deterministic checks, the evidence layout, and report
generation. It is the cheapest insurance in this repository: minutes against a
hardware-dependent measurement run that normally takes many hours.

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

## Included plans

| Plan | Purpose |
|---|---|
| `held-out-smoke.yml` | **Run this first.** Two-case rehearsal of `held-out-ablation` — identical variants, comparisons, and judge. ~25 min. |
| `held-out-ablation.yml` | **First held-out measurement.** Direct, same-model ensemble, and both balanced and rigorous councils over 36 held-out cases. `ensemble-vs-rigorous` is preregistered as primary; the one-repetition result still requires replication before a settled claim. Runtime is hardware-dependent and normally many hours. |
| `local-pilot.yml` | Direct Llama vs balanced and rigorous local councils. Pipeline validation. |
| `local-ablation.yml` | Direct vs five-sample same-model ensemble vs council, on the pilot dataset. |
| `rigorous-stage-coverage.yml` | Mechanics-only check for forced rigorous debate stages. |
| `publishable-template.yml` | Multi-family cloud-judge template; not publishable until customized. |

## Datasets

| Dataset | Cases | Use |
|---|---|---|
| `smoke-v1.yml` | 2 | Mechanics check. Two cases, minutes not hours. |
| `pilot-v1.yml` | 12 | Harness development. **Tuning target** — not evidence. |
| `held-out-v1.yml` | 36 | Measurement input. Held out from tuning; publication still requires the methodology checklist. |

`held-out-v1` spans architecture, debugging, security, grounded reasoning,
planning, underspecified requests, and adversarial context, and is disjoint from
`pilot-v1`. Its grounded-reasoning cases have exact derivable answers, so their
deterministic checks are objectively defensible rather than keyword guesses.
Its intended audience, construction, coverage, limitations, and contamination
policy are documented in [`evaluation/datasets/README.md`](evaluation/datasets/README.md).

**Do not tune the system against `held-out-v1`.** The moment a threshold is
adjusted until these numbers improve, it stops being a held-out set and its
result stops meaning anything. Develop against `pilot-v1`, measure against
`held-out-v1`, and version a new dataset if the held-out set becomes
contaminated.

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

- [Architecture](docs/architecture.md)
- [Evaluation methodology](docs/evaluation-methodology.md)
- [Authoring plans and datasets](docs/authoring-plans-and-datasets.md)

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
with one local judge for cost reasons; add a second independent family before
treating a narrow margin as settled.

Direct and council paths necessarily use different orchestration prompts. When the
intended claim is about the protocol, that prompt-template difference remains a
confound this harness discloses rather than removes.
