#!/usr/bin/env bash
set -euo pipefail

output_file="${1:-}"
if [ -z "${output_file}" ]; then
  echo "Usage: $0 <github_output_file>" >&2
  exit 2
fi

require_command() {
  local cmd="${1}"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "::error::Required command not found: ${cmd}"
    exit 1
  fi
}

get_pyproject_pin() {
  local package_name="${1}"
  local version
  version="$(
    perl -ne 'if (/^\s*"'"${package_name}"'==([^"]+)"/) { print "$1\n"; exit }' .github/tools/pyproject.toml
  )"
  if [ -z "${version}" ]; then
    echo "::error::Could not resolve ${package_name} version from .github/tools/pyproject.toml"
    exit 1
  fi
  printf '%s\n' "${version}"
}

assert_equals() {
  local actual="${1}"
  local expected="${2}"
  local message="${3}"
  if [ "${actual}" != "${expected}" ]; then
    echo "::error::${message}"
    exit 1
  fi
}

for required_cmd in gh jq perl prek git mktemp; do
  require_command "${required_cmd}"
done

tracked_files=(
  ".github/tools/versions.json"
  "prek.toml"
)

# Resolve latest external tool versions and the canonical Python dependency pin.
cyclonedx_latest="$(gh api repos/CycloneDX/cyclonedx-cli/releases/latest --jq .tag_name)"
opengrep_latest="$(gh api repos/opengrep/opengrep/releases/latest --jq .tag_name)"
trufflehog_latest="$(gh api repos/trufflesecurity/trufflehog/releases/latest --jq .tag_name | sed 's/^v//')"
defusedxml_version="$(get_pyproject_pin defusedxml)"
prek_version="$(get_pyproject_pin prek)"

# Update versions manifest used by workflows.
tmpfile="$(mktemp)"
trap 'rm -f "${tmpfile}"' EXIT
jq \
  --arg cyclonedx "${cyclonedx_latest}" \
  --arg opengrep "${opengrep_latest}" \
  --arg trufflehog "${trufflehog_latest}" \
  '.cyclonedx_cli = $cyclonedx | .opengrep = $opengrep | .trufflehog = $trufflehog' \
  .github/tools/versions.json > "${tmpfile}"
mv "${tmpfile}" .github/tools/versions.json
trap - EXIT

# Keep the local hook dependency in sync with .github/tools/pyproject.toml.
perl -0pi -e 's/"defusedxml==[^"]+"/"defusedxml=='"${defusedxml_version}"'"/g' prek.toml
perl -0pi -e 's/^minimum_prek_version = "[^"]+"/minimum_prek_version = "'"${prek_version}"'"/m' prek.toml

actual_defusedxml_dep="$(
  perl -0ne 'if (/\{\s*id\s*=\s*"check-pom-consistency".*?additional_dependencies\s*=\s*\[\s*"([^"]+)"\s*\]/s) { print "$1\n"; exit }' prek.toml
)"
actual_minimum_prek_version="$(
  perl -ne 'if (/^minimum_prek_version = "([^"]+)"/) { print "$1\n"; exit }' prek.toml
)"
assert_equals "${actual_defusedxml_dep}" "defusedxml==${defusedxml_version}" "Could not update defusedxml pin in prek.toml"
assert_equals "${actual_minimum_prek_version}" "${prek_version}" "Could not update minimum_prek_version in prek.toml"

# Freeze hook revisions to immutable SHAs.
prek auto-update --freeze

# Expose whether changes were produced for downstream workflow steps.
if git diff --quiet -- "${tracked_files[@]}"; then
  echo "changed=false" >> "${output_file}"
  echo "No updates found."
else
  echo "changed=true" >> "${output_file}"
  echo "Updated versions:"
  git --no-pager diff -- "${tracked_files[@]}"
fi
