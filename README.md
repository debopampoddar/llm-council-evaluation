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

No tests make paid or live model calls. HTTP integrations use local fake servers.

## Run a local pilot

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
| `local-pilot.yml` | Direct Llama vs balanced and rigorous local councils. |
| `local-ablation.yml` | Direct vs five-sample same-model ensemble vs council. |
| `rigorous-stage-coverage.yml` | Mechanics-only check for forced rigorous debate stages. |
| `publishable-template.yml` | Multi-family cloud-judge template; not publishable until customized. |

The shipped 12-case pilot is useful for pipeline validation, not for a general
quality claim. Use a held-out, versioned dataset of at least 30–50 representative
cases before publishing conclusions.

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

The plan preflight performs no text generation. Ollama model availability is checked
through `/api/tags`; cloud configuration is checked locally and final authentication
still occurs on the first provider request.

Set current per-1,000-token prices in the plan. A zero-priced cloud model is
reported as unpriced, making cost totals incomplete.

## Evidence layout

```text
evaluation/results/<run-id>/
├── manifest.json
├── state.json
├── preflight/catalog.json
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
