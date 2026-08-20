# Evaluation Report: held-out-ablation

## Run validity

- Run: `20260819-184453-933-held-out-ablation-6a42bc2e`
- Created: 2026-08-19T18:44:53.981091Z
- Dataset: `held-out-v1` (`e6fbaab260dc42b10d03189bde836b674843927024e01d2a8e83b54044c6d014`)
- Rubric: `general-v1` (`a302ffc36c13f901fd2c5fab604617b84637659c1065ff92db8cbe38ca0f9bf0`)
- Source commit: `6e07a12f13c4953914ae947563ba4793f80d3849` — clean worktree
- Prompt versions: `direct-v1`, `same-model-ensemble-v1`, `pairwise-judge-v2`
- Cases × repetitions: 36 × 1
- Candidate concurrency: 1
- Judgment concurrency: 1
- Declared `OLLAMA_NUM_PARALLEL`: not exported to the harness
- Preregistered primary comparison: `ensemble-vs-rigorous` (`llama-ensemble` vs `local-rigorous`)

## Reliability and efficiency

| Variant | Attempts | Completed | Partial | Failed | Answer rate | Avg calls | Avg tokens | Avg latency | Cost |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| direct-llama | 36 | 36 | 0 | 0 | 100.0% | 1.0 | 536.4 | 11.3 s | $0.000000 |
| llama-ensemble | 36 | 36 | 0 | 0 | 100.0% | 6.0 | 5166.2 | 201.9 s | $0.000000 |
| local-balanced | 36 | 31 | 5 | 0 | 100.0% | 6.0 | 7453.0 | 328.1 s | — |
| local-rigorous | 36 | 32 | 4 | 0 | 100.0% | 10.7 | 23438.4 | 784.8 s | — |

Judge usage: 288 calls, 592144 tokens, average 19.5 s, $0.000000 estimated cost.

**Total recorded estimated cost:** —

A `+` suffix means the shown cost is a known subtotal with unpriced or missing usage.

## Blind pairwise quality

| Comparison | Eligible / intended | Resolved | Left wins | Right wins | Ties | Position unstable | Judge disagreement | Invalid | Missing | Left preference (Wilson 95% CI) | Unresolved sensitivity |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| direct-llama vs local-balanced | 36 / 36 | 32 | 13 | 5 | 14 | 4 | 0 | 0 | 0 | 62.5% (45.3%–77.1%) | 55.6%–66.7% |
| direct-llama vs local-rigorous | 36 / 36 | 27 | 14 | 0 | 13 | 9 | 0 | 0 | 0 | 75.9% (57.3%–88.1%) | 56.9%–81.9% |
| llama-ensemble vs local-rigorous | 36 / 36 | 28 | 10 | 0 | 18 | 8 | 0 | 0 | 0 | 67.9% (49.3%–82.1%) | 52.8%–75.0% |
| local-balanced vs local-rigorous | 36 / 36 | 29 | 5 | 3 | 21 | 7 | 0 | 0 | 0 | 53.4% (36.0%–70.1%) | 43.1%–62.5% |

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
| direct-llama | 30 | 6 | 0 |
| llama-ensemble | 31 | 5 | 0 |
| local-balanced | 30 | 6 | 0 |
| local-rigorous | 31 | 5 | 0 |

## Limitations

- Model generations are stochastic; the seed controls blinding, not provider generation.
- LLM judges can exhibit position, verbosity, and family-preference bias; mirrored order and independence labels expose but do not eliminate it.
- Council self-scores and validation are retained as evidence but are not the primary quality outcome.
- Direct and council variants use different orchestration prompt templates; align models and generation settings when isolating protocol effects.
- Council call estimates use the advertised protocol topology; provider retries internal to llm-council may not be exposed by its result API.
- The cost ceiling is an observed post-call guard, not a prepaid reservation; unpriced calls are excluded and one completed call can cross the threshold.
- Judge responses still invalid after bounded retries remain invalid evidence and are never converted to ties.
