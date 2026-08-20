# Evaluation Report: held-out-smoke

> **Pilot only.** This run has fewer than 30 cases and does not support a general quality claim.

## Run validity

- Run: `20260819-181950-443-held-out-smoke-f551ded9`
- Created: 2026-08-19T18:19:52.569545Z
- Dataset: `smoke-v1` (`89d17e8a68e4b6df2241751eb5c142346c3bfcaa5fe9b9fa0a98440ad7ad97ec`)
- Rubric: `general-v1` (`a302ffc36c13f901fd2c5fab604617b84637659c1065ff92db8cbe38ca0f9bf0`)
- Source commit: `6e07a12f13c4953914ae947563ba4793f80d3849` — clean worktree
- Prompt versions: `direct-v1`, `same-model-ensemble-v1`, `pairwise-judge-v2`
- Cases × repetitions: 2 × 1
- Candidate concurrency: 1
- Judgment concurrency: 1
- Declared `OLLAMA_NUM_PARALLEL`: not exported to the harness
- Preregistered primary comparison: `ensemble-vs-rigorous` (`llama-ensemble` vs `local-rigorous`)

## Reliability and efficiency

| Variant | Attempts | Completed | Partial | Failed | Answer rate | Avg calls | Avg tokens | Avg latency | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| direct-llama | 2 | 2 | 0 | 0 | 100.0% | 1.0 | 272.5 | 5.8 s | $0.000000 |
| llama-ensemble | 2 | 2 | 0 | 0 | 100.0% | 6.0 | 2290.5 | 28.2 s | $0.000000 |
| local-balanced | 2 | 2 | 0 | 0 | 100.0% | 6.0 | 6182.0 | 69.5 s | — |
| local-rigorous | 2 | 2 | 0 | 0 | 100.0% | 14.0 | 28092.0 | 225.7 s | — |

Judge usage: 16 calls, 25848 tokens, average 18.0 s, $0.000000 estimated cost.

**Total recorded estimated cost:** —

A `+` suffix means the shown cost is a known subtotal with unpriced or missing usage.

## Blind pairwise quality

| Comparison | Eligible / intended | Resolved | Left wins | Right wins | Ties | Position unstable | Judge disagreement | Invalid | Missing | Left preference (Wilson 95% CI) | Unresolved sensitivity |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| direct-llama vs local-balanced | 2 / 2 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 100.0% (34.2%–100.0%) | 100.0%–100.0% |
| direct-llama vs local-rigorous | 2 / 2 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 100.0% (34.2%–100.0%) | 100.0%–100.0% |
| llama-ensemble vs local-rigorous | 2 / 2 | 2 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 100.0% (34.2%–100.0%) | 100.0%–100.0% |
| local-balanced vs local-rigorous | 2 / 2 | 1 | 1 | 0 | 0 | 1 | 0 | 0 | 0 | 100.0% (20.7%–100.0%) | 50.0%–100.0% |

The Wilson interval is conditional on resolved cases. The unresolved-sensitivity range assigns every unresolved eligible pair first against, then in favour of, the left variant. Operational failures are not dropped: the eligible/intended gap is reported separately.

## Judge independence

| Comparison | Judge | Assessment | Detail |
|---|---|---|---|
| direct-vs-balanced | gemma-local | INDEPENDENT | No provider model id or declared family overlaps the evaluated variants. |
| direct-vs-rigorous | gemma-local | INDEPENDENT | No provider model id or declared family overlaps the evaluated variants. |
| ensemble-vs-rigorous | gemma-local | INDEPENDENT | No provider model id or declared family overlaps the evaluated variants. |
| balanced-vs-rigorous | gemma-local | INDEPENDENT | No provider model id or declared family overlaps the evaluated variants. |

## Blinded human review

No human decisions have been imported. Fill a decision file and run `import-human`.


## Deterministic checks

| Variant | Pass | Fail | Error |
|---|---:|---:|---:|
| direct-llama | 4 | 0 | 0 |
| llama-ensemble | 4 | 0 | 0 |
| local-balanced | 4 | 0 | 0 |
| local-rigorous | 4 | 0 | 0 |

## Limitations

- Model generations are stochastic; the seed controls blinding, not provider generation.
- LLM judges can exhibit position, verbosity, and family-preference bias; mirrored order and independence labels expose but do not eliminate it.
- Council self-scores and validation are retained as evidence but are not the primary quality outcome.
- Direct and council variants use different orchestration prompt templates; align models and generation settings when isolating protocol effects.
- Council call estimates use the advertised protocol topology; provider retries internal to llm-council may not be exposed by its result API.
- The cost ceiling is an observed post-call guard, not a prepaid reservation; unpriced calls are excluded and one completed call can cross the threshold.
- Judge responses still invalid after bounded retries remain invalid evidence and are never converted to ties.
