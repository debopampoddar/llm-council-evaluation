# Authoring Plans and Datasets

All files use schema `version: 1`. Unknown properties are rejected, IDs must match
`[a-z0-9][a-z0-9-]{0,63}`, references are checked, and rubric weights must sum to
exactly 1 within floating-point tolerance.

Start by copying the closest file under `evaluation/plans/`. Paths for `dataset`,
`rubric`, and `outputDirectory` are resolved relative to the plan file.

## Plan structure

```yaml
version: 1
id: my-experiment
description: Direct model against a balanced council.
councilBaseUrl: http://127.0.0.1:8080
dataset: ../datasets/my-held-out-v1.yml
rubric: ../rubrics/general-v1.yml
outputDirectory: ../results
seed: 20260818
repetitions: 3
execution:
  maxCalls: 1000
  maxEstimatedCostUsd: 25.0
  councilRequestTimeoutSeconds: 1800
  judgeInvalidRetries: 1
  continueOnFailure: true
  liveCallsAcknowledged: true
  billableCallsAcknowledged: true
models: []
variants: []
comparisons: []
judges: []
```

`plan` contacts the council, so use it only after the server is running:

```bash
./scripts/evaluate.sh plan evaluation/plans/my-experiment.yml
```

Do not simply raise `maxCalls` until the command passes. Confirm that every variant,
comparison, repetition, judge orientation, and retry is intentional.

The estimate also reserves one control-preflight call per enabled judge, including
that model's configured transport/provider retries. The control runs only after the
same live/billable acknowledgements as the experiment and is persisted for resume.

## Models

```yaml
- id: gemma-judge
  provider: ollama
  providerModelId: gemma4:12b-it-qat
  modelFamily: gemma
  baseUrl: http://127.0.0.1:11434
  maxOutputTokens: 1600
  contextWindowTokens: 16384
  temperature: 0.1
  timeoutSeconds: 240
  retryMaxAttempts: 1
  retryBaseDelayMs: 500
  costPer1kInputTokens: 0.0
  costPer1kOutputTokens: 0.0
```

Supported providers are `ollama`, `openai`, `anthropic`, `gemini`, and `mock`.
`mock` exists for hermetic development and must not be used as evaluation evidence.
`baseUrl` is required and supported only for Ollama. Cloud endpoints and credentials
belong in Spring environment properties, not the plan.

`retryMaxAttempts` means retries after the first attempt, so `1` permits two total
attempts for transport/provider failures. `execution.judgeInvalidRetries` separately
controls fresh calls after a syntactically or semantically invalid judge response;
every attempt is retained in `judgment-attempts/`. Both retry types are included in
the worst-case call estimate. Keep `modelFamily` honest: it drives the
judge-independence warning. Prices are USD per 1,000 input and output tokens and are
part of the evidence.

`contextWindowTokens` is optional and currently applies to Ollama as `num_ctx`.
Specify it when reproducibility matters instead of relying on the daemon default.
The shipped local plans use 16,384 tokens. JSON-mode Ollama judge calls set
`think: false`; a thinking model must spend its output budget on the required JSON
rather than hidden reasoning.

## Variants

Direct baseline:

```yaml
- id: direct-llama
  displayName: Direct Llama
  type: DIRECT
  enabled: true
  modelId: llama
```

Council variant:

```yaml
- id: local-balanced
  displayName: Local Balanced Council
  type: COUNCIL
  enabled: true
  profileId: local
  depthMode: BALANCED
```

`profileId` and `depthMode` must resolve through the live council catalog. Depth is
one of `QUICK`, `BALANCED`, or `RIGOROUS`.

Same-model budget ablation:

```yaml
- id: llama-ensemble
  displayName: Five-sample Llama Ensemble
  type: SAME_MODEL_ENSEMBLE
  enabled: true
  modelId: llama
  samples: 5
```

The ensemble makes `samples` generation calls plus one synthesis call before
retries. It is an ablation, not an independent multi-model council.

## Comparisons and judges

```yaml
comparisons:
  - id: direct-vs-balanced
    left: direct-llama
    right: local-balanced
    enabled: true
    primary: true
judges:
  - id: gemma-local
    modelId: gemma-judge
    mirrored: true
    enabled: true
```

An enabled comparison cannot reference a disabled variant. `primary` is optional
for exploratory plans, but a publishable plan must preregister exactly one enabled
primary comparison; the validator rejects multiple or disabled primaries. Use
mirrored judging unless you have a documented reason not to. A judge model may not
be independent of the candidates; that overlap is reported, not magically removed.

## Dataset structure

```yaml
version: 1
id: held-out-v1
description: Versioned cases not used during system tuning.
cases:
  - id: api-timeout-01
    category: debugging
    tags: [java, reliability]
    question: Diagnose the observed timeout and propose the safest fix.
    context: The caller timeout is 10s and the downstream p99 is 18s.
    requirements:
      - Identify the timeout mismatch.
      - Discuss bounded retries and idempotency.
    referenceFacts:
      - The downstream p99 exceeds the caller timeout.
    redFlags:
      - Recommends unlimited retries.
    deterministicChecks:
      - {type: non-blank}
      - {type: contains-any, values: [timeout, deadline], caseSensitive: false}
    rubricOverrides: {}
```

Only `question` and `context` go to candidate variants. `requirements`,
`referenceFacts`, and `redFlags` are evaluator-only and appear in judge/human-review
instructions. Do not put secret or production data in any field; all evidence is
written to disk.

Questions are limited to 5,000 characters and context to 10,000 to match the council
API contract.

### Deterministic checks

| Type | Required settings | Meaning |
|---|---|---|
| `non-blank` | none | Answer has non-whitespace content. |
| `contains-all` | `value` or `values` | Every literal appears. |
| `contains-any` | `value` or `values` | At least one literal appears. |
| `contains-none` | `value` or `values` | No literal appears. |
| `regex` | `pattern` | Java regex must match somewhere. |
| `forbidden-regex` | `pattern` | Java regex must not match. |
| `max-chars` | positive `max` | Answer length is at most the limit. |

`caseSensitive` defaults to false for literal checks. Regex flags belong in the
pattern, for example `(?i)`.

## Rubric structure

```yaml
version: 1
id: general-v1
description: General answer quality.
criteria:
  - id: correctness
    description: Factually and logically correct.
    weight: 0.40
  - id: clarity
    description: Direct, structured, and understandable.
    weight: 0.60
```

Weights must be positive, no greater than 1, and sum to 1. A case can override
criterion weights, but the effective case weights must still sum to 1 and cannot
name an unknown criterion.

## Safe authoring workflow

1. Copy and rename the nearest dataset, rubric, and plan.
2. Change IDs and relative paths; never edit an old published input in place.
3. Add focused deterministic checks only where objectively defensible.
4. Run `mvn clean verify`; shipped-asset tests prove repository examples parse.
5. Start the council and run `plan`; resolve every health error and warning.
6. Run a two-case mechanics check before spending on a full dataset.
7. Freeze inputs in a clean commit, rerun, and archive the complete result directory.
8. Review the generated limitations before quoting any number in a blog post.
