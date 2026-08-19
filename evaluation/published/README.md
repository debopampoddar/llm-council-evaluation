# Published evidence

`evaluation/results/` is gitignored: it is a working directory, it is rewritten by
every run, and it holds raw attempt-by-attempt transcripts that are large and
uninteresting once a run is finished.

This directory is the opposite. **Anything cited in writing — a blog post, a
README claim, a slide — must have its evidence committed here first.** A number
whose provenance is not in the repository cannot be checked by a reader, and an
uncheckable number is worth less than no number at all.

## What to copy

After a completed run, copy the following out of
`evaluation/results/<run-id>/` into `evaluation/published/<run-id>/`:

| Path | Why it is required |
|---|---|
| `manifest.json` | Pins dataset, rubric, and plan hashes, the council-catalog fingerprint, source commit and dirty state, model versions, seed, repetitions, and runtime concurrency. Without it no result is reproducible. |
| `report/report.md` | The findings themselves, including the generated limitations section. |
| `report/metrics.json` and `report/metrics.csv` | The numbers behind the prose, so a reader can recompute rather than trust. |
| `report/judge-independence.json` | Judge/candidate family overlap and position stability — the standing caveat on every margin. |
| `answers/` | The stochastic candidate outputs and usage/latency observations. A manifest can pin their configuration but cannot recreate them. |
| `checks/` | Per-answer deterministic-check evidence used by the report. |
| `judgments/` | Per-pair, per-orientation judge decisions. This is what makes the intervals checkable. |
| `judgment-attempts/` | Raw judge attempts, required to audit invalid responses, retries, and usage claims. |
| `preflight/judges/` | The 2+2 control proving each judge could discriminate at all before candidate work began. |
| `human/human-review-completed.json` and `human/human-review-normalized.json` | Required when human outcomes are cited. Keep them separate from model judgments. |

`state.json` is operational rather than analytical and may be omitted. Never
publish `human/human-review-key.json` alongside a blinded packet you are still
asking someone to review; the key is the reveal. Once review is closed, archive
the key with controlled access if independent audit of the normalization is
required.

## Rules

1. **Never edit a published run.** If something was wrong, publish a new run and
   say what changed. Editing evidence in place destroys the property that makes
   it evidence.
2. **Copy the whole set or none of it.** A report without its manifest invites
   exactly the selective quoting this directory exists to prevent.
3. **Freeze inputs first.** Run from a clean commit so the plan, dataset, and
   rubric hashes in the manifest correspond to something that exists in history.
4. **Read the generated limitations before quoting any number.** The report writes
   them for a reason; they usually constrain the claim more than the headline
   figure suggests.
