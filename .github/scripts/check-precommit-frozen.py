#!/usr/bin/env python3
"""Validate that non-local pre-commit hooks use frozen commit SHAs."""

from __future__ import annotations

import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[2]
YAML_CONFIG = ROOT / ".pre-commit-config.yaml"
TOML_CONFIG = ROOT / "prek.toml"
FROZEN_REV_RE = re.compile(r"^[0-9a-f]{40}$")


def strip_inline_comment(value: str) -> str:
    return value.split("#", 1)[0].strip().strip("'\"")


def main() -> int:
    config_path: pathlib.Path | None = None
    for candidate in (TOML_CONFIG, YAML_CONFIG):
        if candidate.exists():
            config_path = candidate
            break

    if config_path is None:
        print("No hook config found (.pre-commit-config.yaml or prek.toml).")
        return 1

    text = config_path.read_text()
    current_repo: str | None = None
    current_rev: str | None = None
    errors: list[str] = []

    def validate_current() -> None:
        if current_repo is None:
            return
        if current_repo in {"local", "meta"}:
            return
        if current_rev is None:
            errors.append(f"{current_repo}: missing rev")
            return
        if not FROZEN_REV_RE.fullmatch(current_rev):
            errors.append(
                f"{current_repo}: rev '{current_rev}' is not a frozen 40-char SHA"
            )

    if config_path.name == "prek.toml":
        repo_pattern = r"^\s*repo\s*=\s*(.+?)\s*$"
        rev_pattern = r"^\s*rev\s*=\s*(.+?)\s*$"
    else:
        repo_pattern = r"^\s*-\s+repo:\s+(.+?)\s*$"
        rev_pattern = r"^\s*rev:\s+(.+?)\s*$"

    for raw_line in text.splitlines():
        repo_match = re.match(repo_pattern, raw_line)
        if repo_match:
            validate_current()
            current_repo = strip_inline_comment(repo_match.group(1))
            current_rev = None
            continue

        rev_match = re.match(rev_pattern, raw_line)
        if rev_match and current_repo is not None and current_rev is None:
            current_rev = strip_inline_comment(rev_match.group(1))

    validate_current()

    if errors:
        print("Found non-frozen pre-commit revisions:")
        for error in errors:
            print(f"- {error}")
        print(
            "Run `prek auto-update --freeze` and commit the updated "
            f"{config_path.name}."
        )
        return 1

    print("All non-local pre-commit revisions are frozen to commit SHAs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
