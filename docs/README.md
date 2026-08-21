# Evaluation Documentation Guide

The harness separates experiment design, execution, evidence, and interpretation.
Use the document that matches the decision you are making.

| Goal | Document |
|---|---|
| Build and run an included plan | [Project README](../README.md) |
| Understand components and evidence flow | [Architecture](architecture.md) |
| Design a defensible experiment | [Evaluation methodology](evaluation-methodology.md) |
| Create or modify YAML plans and datasets | [Authoring guide](authoring-plans-and-datasets.md) |
| Decide whether a finished run is clean | [Evaluation report handbook](reviewing-evaluation-results.md) |
| Understand dataset provenance and contamination | [Dataset card](../evaluation/datasets/README.md) |

## Recommended Reading Order

1. Read the root README's status and limitations.
2. Use `plan` before every live run and review its maximum-call and billable-provider warnings.
3. Read the methodology before designing a comparison or quality claim.
4. Use the report handbook after every completed run; do not infer acceptance from
   `COMPLETED` or from a generated report alone.
5. Publish only selected evidence copied from the gitignored results directory
   into `evaluation/published/`, with clean source commits and preserved limitations.

## Claim Boundary

- A mechanics-only plan can establish that declared cases completed and satisfied
  deterministic contracts. It cannot establish model superiority.
- A small diagnostic can expose large failures. It cannot support a broad quality claim.
- A blinded pairwise result is conditional on its dataset, judges, unresolved
  decisions, model versions, prompts, and runtime conditions.
- One local judge cannot establish judge-independent truth. Human review and judge
  families outside every candidate path are required for stronger evidence.
- Once observed results influence code, prompts, checks, rubrics, or thresholds,
  that dataset is contaminated for confirmation and must be versioned accordingly.
