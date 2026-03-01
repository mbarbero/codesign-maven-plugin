#!/usr/bin/env python3
"""Validate that non-local hooks use frozen commit SHAs."""

from __future__ import annotations

import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[2]
PREK_CONFIG = ROOT / "prek.toml"
FROZEN_REV_RE = re.compile(r"^[0-9a-f]{40}$")


def main() -> int:
    if not PREK_CONFIG.exists():
        print("No hook config found (prek.toml).")
        return 1

    text = PREK_CONFIG.read_text()
    errors: list[str] = []
    current_repo: str | None = None
    current_rev: str | None = None

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

    for raw_line in text.splitlines():
        repo_match = re.match(r'^\s*repo\s*=\s*"([^"]+)"\s*(?:#.*)?$', raw_line)
        if repo_match:
            validate_current()
            current_repo = repo_match.group(1).strip()
            current_rev = None
            continue

        rev_match = re.match(r'^\s*rev\s*=\s*"([^"]+)"\s*(?:#.*)?$', raw_line)
        if rev_match and current_repo is not None and current_rev is None:
            current_rev = rev_match.group(1).strip()

    validate_current()

    if errors:
        print("Found non-frozen hook revisions:")
        for error in errors:
            print(f"- {error}")
        print("Run `prek auto-update --freeze` and commit the updated prek.toml.")
        return 1

    print("All non-local hook revisions are frozen to commit SHAs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
