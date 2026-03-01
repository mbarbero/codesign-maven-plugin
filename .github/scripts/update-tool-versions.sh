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
trufflehog_latest="$(gh api repos/trufflesecurity/trufflehog/releases/latest --jq .tag_name | sed 's/^v//')"
defusedxml_version="$(
  perl -ne 'if (/^\s*"defusedxml==([^"]+)"/) { print "$1\n"; exit }' .github/tools/pyproject.toml
)"

if [ -z "${defusedxml_version}" ]; then
  echo "::error::Could not resolve defusedxml version from .github/tools/pyproject.toml"
  exit 1
fi

# Update versions manifest used by workflows.
tmpfile="$(mktemp)"
jq \
  --arg cyclonedx "${cyclonedx_latest}" \
  --arg opengrep "${opengrep_latest}" \
  --arg trufflehog "${trufflehog_latest}" \
  '.cyclonedx_cli = $cyclonedx | .opengrep = $opengrep | .trufflehog = $trufflehog' \
  .github/tools/versions.json > "${tmpfile}"
mv "${tmpfile}" .github/tools/versions.json

# Keep the local hook dependency in sync with .github/tools/pyproject.toml.
perl -0pi -e 's/"defusedxml==[^"]+"/"defusedxml=='"${defusedxml_version}"'"/g' prek.toml

actual_defusedxml_dep="$(
  perl -ne 'if (/additional_dependencies\s*=\s*\[\s*"([^"]+)"\s*\]/) { print "$1\n"; exit }' prek.toml
)"
if [ "${actual_defusedxml_dep}" != "defusedxml==${defusedxml_version}" ]; then
  echo "::error::Could not update defusedxml pin in prek.toml"
  exit 1
fi

# Freeze hook revisions to immutable SHAs.
prek auto-update --freeze

# Expose whether changes were produced for downstream workflow steps.
if git diff --quiet -- .github/tools/versions.json prek.toml; then
  echo "changed=false" >> "${output_file}"
  echo "No updates found."
else
  echo "changed=true" >> "${output_file}"
  echo "Updated versions:"
  git --no-pager diff -- .github/tools/versions.json prek.toml
fi
