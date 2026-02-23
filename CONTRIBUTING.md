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

This installs two hooks driven by `.pre-commit-config.yaml`:

- **pre-commit** — runs:
  - [zizmor](https://github.com/woodruffw/zizmor) (GitHub Actions security linter)
  - [poutine](https://github.com/boostsecurityio/poutine) (Build pipeline security analyzer)
  - `actionlint` and YAML validation for GitHub workflows
  - `ruff`/`ruff-format` for Python scripts under `.github/scripts`
  - `google-java-format` (via `pretty-format-java`) for Java sources under `src/`
  - `markdownlint-cli` for Markdown files (configured via `.markdownlint.yaml`)
  - the local POM consistency check and Maven unit tests
  on every `git commit`.
- **pre-push** — runs the Maven integration tests on every `git push`.

### Additional Development Tools

Some quality checks and build profiles require tools to be installed on your
system:

| Tool | Purpose | Installation |
| --- | --- | --- |
| **poutine** | Workflow security analysis (run by `pre-commit`) | `brew install boostsecurityio/tap/poutine` |
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

CI also runs the same pre-commit checks on pull requests. In addition,
reviewdog posts granular inline comments/suggestions for workflow, Python,
and Markdown lint findings.

## Contact

Contact the project developers via the project's "dev" list:

- <https://accounts.eclipse.org/mailing-list/csi-dev>

## Issues

For bug reports and feature requests, please use GitHub Issues:

- <https://github.com/eclipse-csi/codesign-maven-plugin/issues>
