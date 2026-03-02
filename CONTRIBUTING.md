# Contributing to Eclipse CSI Codesign Maven Plugin

Thanks for your interest in this project.

## Project Description

The Eclipse CSI Codesign Maven Plugin is a Maven plugin that signs
artifacts via the SignPath REST API. It is part of the Eclipse Common
Security Infrastructure (CSI) project.

- <https://projects.eclipse.org/projects/technology.csi>

## Developer Resources

Information regarding source code management, builds, coding standards,
and more.

- <https://projects.eclipse.org/projects/technology.csi/developer>

The project maintains the following source code repository:

- <https://github.com/eclipse-csi/codesign-maven-plugin>

## Eclipse Development Process

This Eclipse Foundation open project is governed by the Eclipse Foundation
Development Process and operates under the terms of the Eclipse IP Policy.

- <https://eclipse.org/projects/dev_process>
- <https://www.eclipse.org/org/documents/Eclipse_IP_Policy.pdf>

## Eclipse Contributor Agreement

In order to be able to contribute to Eclipse Foundation projects you
must electronically sign the Eclipse Contributor Agreement (ECA):

- <https://www.eclipse.org/legal/ECA.php>

The ECA provides the Eclipse Foundation with a permanent record that you
agree that each of your contributions will comply with the commitments
documented in the Developer Certificate of Origin (DCO). Having an ECA
on file associated with the email address matching the "Author" field of
your contribution's Git commits fulfills the DCO's requirement that you
sign-off on your contributions.

For more information, please see the Eclipse Committer Handbook:

- <https://www.eclipse.org/projects/handbook/#resources-commit>

## Development Setup

### Prerequisites

Install the following tools **before** running any build or quality check:

| Tool | Version | Required for |
| --- | --- | --- |
| **Java JDK** | 21+ | Maven wrapper (`./mvnw`), `unit-tests` and `integration-tests` git hooks (`language: system`), `pretty-format-java` Java formatter |
| **prek** | any | Running Git hooks (see below) |
| **poutine** | any | `poutine` git hook — workflow security analysis |

`cdxgen` and `cyclonedx-cli` are additionally needed for the `generate-sbom`
profile; see [Additional Development Tools](#additional-development-tools) below.

This project uses [prek](https://prek.j178.dev/) to run quality checks
automatically as Git hooks. prek is written in Rust and does not require
Python to install itself — but the hooks it drives require a Java JDK on
the `PATH` (for Maven builds and the Java formatter) and the `poutine`
binary for workflow security analysis.

The hook config lives in `prek.toml`.

### Installing prek

Install prek using your preferred method:

| Platform | Command |
| --- | --- |
| Homebrew (macOS/Linux) | `brew install prek` |
| uv | `uv tool install prek` |
| pip / pipx | `pip install prek` |
| Scoop (Windows) | `scoop install main/prek` |

See the [prek installation docs](https://prek.j178.dev/installation/) for
all options (Nix, conda, cargo, Docker, …).

### Installing the Git hooks

Once prek is installed, wire it into the repository's Git hooks:

```sh
prek install
```

This installs two hooks driven by `prek.toml`:

- **commit-time hook** — runs:
  - [zizmor](https://github.com/woodruffw/zizmor) (GitHub Actions security linter)
  - [poutine](https://github.com/boostsecurityio/poutine) (Build pipeline security analyzer)
  - `check-merge-conflict`, `check-yaml`, `check-added-large-files`,
    `detect-private-key`, and line-ending/whitespace hygiene checks
  - `actionlint` and YAML validation for GitHub workflows
  - `ruff`/`ruff-format` for Python scripts under `.github/scripts`
  - `google-java-format` (via `pretty-format-java`) for Java sources under `src/`
  - `markdownlint-cli` for Markdown files (configured via `.markdownlint.yaml`)
  - the local POM consistency check
  on every `git commit`.
- **pre-push** — runs Maven unit tests and integration tests on every `git push`.

### Additional Development Tools

Some quality checks and build profiles require tools to be installed on your
system:

| Tool | Purpose | Installation |
| --- | --- | --- |
| **poutine** | Workflow security analysis (run by the commit-time hook) | `brew install boostsecurityio/tap/poutine` |
| **cdxgen** | GitHub Actions SBOM generation (used in `generate-sbom` profile) | Requires Node.js; `npm install -g @cyclonedx/cdxgen` |
| **cyclonedx-cli** | SBOM merging (used in `generate-sbom` profile) | `brew install cyclonedx-cli` |
| **GraalVM** | Native CLI binary builds (optional) | [graalvm.org](https://www.graalvm.org/) |

### Running the Python scripts manually

The scripts under `.github/scripts/` require `defusedxml`. Use `uv` to run
them without a permanent virtual environment:

```sh
uv run --with defusedxml python3 .github/scripts/check-pom-consistency.py
uv run --with defusedxml python3 .github/scripts/generate-site-index.py
```

### Pull request checks

CI also runs the same hook checks on pull requests. In addition,
reviewdog posts granular inline comments/suggestions for workflow, Python,
and Markdown lint findings.

Hook/tool revisions are updated automatically by
`.github/workflows/update-tool-versions.yml`, which also runs
`prek auto-update --freeze` to keep hook revisions immutable.

## Building

```shell
# Default full build (unit tests only)
./mvnw clean verify

# Full build with integration tests
./mvnw clean verify -Pintegration-tests

# Build the CLI JVM fat JAR only
./mvnw package -pl cli --also-make

# Build a native CLI binary (requires GraalVM with native-image)
./mvnw -Pnative package -pl cli --also-make -DskipTests
# Output: cli/target/codesign  (or codesign.exe on Windows)
```

To generate SBOMs (CycloneDX) in a build, enable the `generate-sbom` profile
(requires `cdxgen` and `cyclonedx-cli`; see [Additional Development Tools](#additional-development-tools)):

```shell
./mvnw clean verify -Pgenerate-sbom
```

## Releasing (Maven Central via JReleaser)

This project publishes to Maven Central via JReleaser.

- Workflow: `.github/workflows/release.yml`
- Trigger: push a version tag (e.g., `v1.0.0`) or run manually via `workflow_dispatch`
- Build profile: `release`

### Required GitHub Secrets

Configure these repository/environment secrets before running the release workflow:

- `JRELEASER_MAVENCENTRAL_USERNAME`
- `JRELEASER_MAVENCENTRAL_PASSWORD`
- `JRELEASER_GPG_PUBLIC_KEY` (ASCII-armored)
- `JRELEASER_GPG_SECRET_KEY` (ASCII-armored)
- `JRELEASER_GPG_PASSPHRASE`

Use the `mode=dry-run` option on the manual trigger to validate release configuration
without publishing.

### Local Dry Run

```shell
./mvnw -Prelease -DskipTests -Daether.checksums.omitChecksumsForExtensions=.asc,.sigstore.json clean verify
./mvnw -Prelease -Djreleaser.dry.run=true jreleaser:full-release
```

## Contact

Contact the project developers via the project's "dev" list:

- <https://accounts.eclipse.org/mailing-list/csi-dev>

## Issues

For bug reports and feature requests, please use GitHub Issues:

- <https://github.com/eclipse-csi/codesign-maven-plugin/issues>
