# Pre-commit Hook Coverage

This document tracks how hooks in `.pre-commit-config.yaml` are enforced in CI
to avoid local-vs-CI drift.

## Hooks Covered by `reviewdog.yml` pre-commit job

- `check-merge-conflict`
- `check-yaml`
- `check-added-large-files`
- `detect-private-key`
- `end-of-file-fixer`
- `trailing-whitespace`
- `mixed-line-ending`
- `pretty-format-java`

## Hooks Covered by Dedicated CI Jobs

- `zizmor`:
  - `.github/workflows/ci-guardrails.yml` (`Analyze (Zizmor)`)
- `poutine`:
  - `.github/workflows/ci-guardrails.yml` (`Analyze (Poutine)`)
- `check-pom-consistency`:
  - `.github/workflows/ci-guardrails.yml` (`Analyze (POM Consistency)`)
- `actionlint`:
  - `.github/workflows/reviewdog.yml` (`reviewdog (actionlint)`)
- `ruff` and `ruff-format`:
  - `.github/workflows/reviewdog.yml` (`reviewdog (ruff)`)
- `markdownlint`:
  - `.github/workflows/reviewdog.yml` (`reviewdog (markdownlint)`)
- `unit-tests`:
  - `.github/workflows/ci.yml` (`All Tests`)
- `integration-tests`:
  - `.github/workflows/ci.yml` (`All Tests`)

When adding/removing pre-commit hooks, update this mapping and the `SKIP` list
in `.github/workflows/reviewdog.yml` together.
