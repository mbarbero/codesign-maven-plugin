#!/usr/bin/env python3
"""Validate that pre-commit hooks, coverage docs, and CI SKIP list stay aligned."""

from __future__ import annotations

import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[2]
PRECOMMIT_CONFIG = ROOT / ".pre-commit-config.yaml"
REVIEWDOG_WORKFLOW = ROOT / ".github" / "workflows" / "reviewdog.yml"
COVERAGE_DOC = ROOT / ".github" / "pre-commit-hook-coverage.md"


def parse_hook_ids(config_text: str) -> set[str]:
    return {
        match.group(1)
        for match in re.finditer(
            r"^\s*-\s+id:\s+([A-Za-z0-9._-]+)\s*(?:#.*)?$", config_text, re.MULTILINE
        )
    }


def parse_skip_ids(reviewdog_text: str) -> set[str]:
    match = re.search(r"^\s*SKIP:\s*(.+)$", reviewdog_text, re.MULTILINE)
    if not match:
        raise ValueError("Could not find SKIP value in .github/workflows/reviewdog.yml")
    return {item.strip() for item in match.group(1).split(",") if item.strip()}


def parse_doc_ids(coverage_text: str) -> tuple[set[str], set[str], set[str]]:
    section: str | None = None
    reviewdog_ids: set[str] = set()
    dedicated_ids: set[str] = set()
    manual_ids: set[str] = set()

    for line in coverage_text.splitlines():
        if line.startswith("## Hooks Covered by `reviewdog.yml` pre-commit job"):
            section = "reviewdog"
            continue
        if line.startswith("## Hooks Covered by Dedicated CI Jobs"):
            section = "dedicated"
            continue
        if line.startswith("## Hooks Covered by Local Manual Stage"):
            section = "manual"
            continue
        if line.startswith("## "):
            section = None
            continue

        match = re.match(r"^- `([^`]+)`", line)
        if not match:
            continue
        hook_id = match.group(1)
        if section == "reviewdog":
            reviewdog_ids.add(hook_id)
        elif section == "dedicated":
            dedicated_ids.add(hook_id)
        elif section == "manual":
            manual_ids.add(hook_id)

    if not reviewdog_ids:
        raise ValueError(
            "No reviewdog hook IDs found in .github/pre-commit-hook-coverage.md"
        )
    if not dedicated_ids:
        raise ValueError(
            "No dedicated hook IDs found in .github/pre-commit-hook-coverage.md"
        )

    return reviewdog_ids, dedicated_ids, manual_ids


def print_set(label: str, values: set[str]) -> None:
    print(f"{label}: {', '.join(sorted(values))}")


def main() -> int:
    hook_ids = parse_hook_ids(PRECOMMIT_CONFIG.read_text())
    skip_ids = parse_skip_ids(REVIEWDOG_WORKFLOW.read_text())
    reviewdog_doc_ids, dedicated_doc_ids, manual_doc_ids = parse_doc_ids(
        COVERAGE_DOC.read_text()
    )
    documented_ids = reviewdog_doc_ids | dedicated_doc_ids | manual_doc_ids

    missing_from_doc = hook_ids - documented_ids
    unknown_in_doc = documented_ids - hook_ids
    skip_not_hook = skip_ids - hook_ids
    skip_not_dedicated = skip_ids - dedicated_doc_ids

    failures = False

    if missing_from_doc:
        failures = True
        print_set("Hooks missing from coverage doc", missing_from_doc)
    if unknown_in_doc:
        failures = True
        print_set("Coverage doc contains unknown hook IDs", unknown_in_doc)
    if skip_not_hook:
        failures = True
        print_set("SKIP contains unknown hook IDs", skip_not_hook)
    if skip_not_dedicated:
        failures = True
        print_set("SKIP IDs not documented as dedicated CI hooks", skip_not_dedicated)

    if failures:
        print(
            "Coverage validation failed. Update .pre-commit-config.yaml, "
            ".github/pre-commit-hook-coverage.md, and reviewdog SKIP together."
        )
        return 1

    print("pre-commit coverage mapping is consistent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
