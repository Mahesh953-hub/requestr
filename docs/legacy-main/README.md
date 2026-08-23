# Archived pre-reconciliation main

The repository's `main` and `backup` lines have unrelated Git histories. The exact pre-reconciliation `main` tree is preserved by the local `archive/main-v5.7` tag. The advanced `backup` line is the implementation base for v6.

This directory preserves the valuable content unique to that `main` tree:

- `MASTER_PLAN_AND_FIX_SPEC.md`
- the source snapshot for its orthogonal 10-accent theme model

Build APKs, encoded keystores, and stale scaffold tests were intentionally excluded. The theme snapshots use `.txt` extensions so they remain reference material and never enter Android compilation.
