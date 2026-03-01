#!/usr/bin/env bash
set -euo pipefail

output_file="${1:-}"
if [ -z "${output_file}" ]; then
  echo "Usage: $0 <github_output_file>" >&2
  exit 2
fi

# Resolve latest external tool versions and the canonical Python dependency pin.
cyclonedx_latest="$(gh api repos/CycloneDX/cyclonedx-cli/releases/latest --jq .tag_name)"
opengrep_latest="$(gh api repos/opengrep/opengrep/releases/latest --jq .tag_name)"
defusedxml_version="$(awk -F'==' '/^defusedxml==/ {print $2; exit}' .github/requirements/python-common.txt)"

if [ -z "${defusedxml_version}" ]; then
  echo "::error::Could not resolve defusedxml version from .github/requirements/python-common.txt"
  exit 1
fi

# Update versions manifest used by workflows.
tmpfile="$(mktemp)"
jq \
  --arg cyclonedx "${cyclonedx_latest}" \
  --arg opengrep "${opengrep_latest}" \
  '.cyclonedx_cli = $cyclonedx | .opengrep = $opengrep' \
  .github/tools/versions.json > "${tmpfile}"
mv "${tmpfile}" .github/tools/versions.json

# Keep pre-commit local hook dependency in sync with python-common.txt.
yq -i \
  '(.repos[] | select(.repo == "local") | .hooks[] | select(.id == "check-pom-consistency") | .additional_dependencies) = ["defusedxml=='"${defusedxml_version}"'"]' \
  .pre-commit-config.yaml

actual_defusedxml_dep="$(yq -r '.repos[] | select(.repo == "local") | .hooks[] | select(.id == "check-pom-consistency") | .additional_dependencies[0]' .pre-commit-config.yaml)"
if [ "${actual_defusedxml_dep}" != "defusedxml==${defusedxml_version}" ]; then
  echo "::error::Could not update defusedxml pin in .pre-commit-config.yaml"
  exit 1
fi

# Freeze hook revisions to immutable SHAs.
prek auto-update --freeze

# Expose whether changes were produced for downstream workflow steps.
if git diff --quiet -- .github/tools/versions.json .pre-commit-config.yaml; then
  echo "changed=false" >> "${output_file}"
  echo "No updates found."
else
  echo "changed=true" >> "${output_file}"
  echo "Updated versions:"
  git --no-pager diff -- .github/tools/versions.json .pre-commit-config.yaml
fi
