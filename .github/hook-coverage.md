# Hook Coverage

This document tracks how hooks in `prek.toml` are enforced in CI to avoid
local-vs-CI drift.

## Hooks Covered by `reviewdog.yml` prek job

- `check-merge-conflict`
- `check-yaml`
- `check-added-large-files`
- `detect-private-key`
- `detect-secrets`
- `gitleaks`
- `end-of-file-fixer`
- `trailing-whitespace`
- `mixed-line-ending`
- `pretty-format-java`
- `ruff-format`

## Hooks Covered by Dedicated CI Jobs

- `zizmor`:
  - `.github/workflows/ci-guardrails.yml` (`Analyze (Zizmor)`)
- `poutine`:
  - `.github/workflows/ci-guardrails.yml` (`Analyze (Poutine)`)
- `check-pom-consistency`:
  - `.github/workflows/ci-guardrails.yml` (`Analyze (POM Consistency)`)
- `check-hook-revisions-frozen`:
  - `.github/workflows/ci-guardrails.yml` (`Validate Hook Revisions Are Frozen`)
- `actionlint`:
  - `.github/workflows/reviewdog.yml` (`reviewdog (actionlint)`)
- `ruff`:
  - `.github/workflows/reviewdog.yml` (`reviewdog (ruff)`)
- `shellcheck`:
  - `.github/workflows/reviewdog.yml` (`reviewdog (shellcheck)`)
- `markdownlint`:
  - `.github/workflows/reviewdog.yml` (`reviewdog (markdownlint)`)
- `unit-tests`:
  - `.github/workflows/ci.yml` (`All Tests`)
- `integration-tests`:
  - `.github/workflows/ci.yml` (`All Tests`)
- `trufflehog`:
  - `.github/workflows/ci-guardrails.yml` (`Analyze (TruffleHog)`)

## Hooks Covered by Local Manual Stage

- `trufflehog`:
  - run manually with `prek run trufflehog --all-files --stage manual`

When adding/removing hooks, update this mapping and the `SKIP` list in
`.github/workflows/reviewdog.yml` together.
