# Contributing to Eclipse CSI SignPath Maven Plugin

Thanks for your interest in this project.

## Project Description

The Eclipse CSI SignPath Maven Plugin is a Maven plugin that signs
artifacts via the SignPath REST API. It is part of the Eclipse Common
Security Infrastructure (CSI) project.

- https://projects.eclipse.org/projects/technology.csi

## Developer Resources

Information regarding source code management, builds, coding standards,
and more.

- https://projects.eclipse.org/projects/technology.csi/developer

The project maintains the following source code repository:

- https://github.com/eclipse-csi/signpath-maven-plugin

## Eclipse Development Process

This Eclipse Foundation open project is governed by the Eclipse Foundation
Development Process and operates under the terms of the Eclipse IP Policy.

- https://eclipse.org/projects/dev_process
- https://www.eclipse.org/org/documents/Eclipse_IP_Policy.pdf

## Eclipse Contributor Agreement

In order to be able to contribute to Eclipse Foundation projects you
must electronically sign the Eclipse Contributor Agreement (ECA):

- https://www.eclipse.org/legal/ECA.php

The ECA provides the Eclipse Foundation with a permanent record that you
agree that each of your contributions will comply with the commitments
documented in the Developer Certificate of Origin (DCO). Having an ECA
on file associated with the email address matching the "Author" field of
your contribution's Git commits fulfills the DCO's requirement that you
sign-off on your contributions.

For more information, please see the Eclipse Committer Handbook:

- https://www.eclipse.org/projects/handbook/#resources-commit

## Development Setup

This project uses [prek](https://prek.j178.dev/) to run quality checks
automatically as Git hooks. prek is a fast, dependency-free drop-in
replacement for [pre-commit](https://pre-commit.com/) written in Rust.

### Installing prek

Install prek using your preferred method:

| Platform | Command |
|---|---|
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
  - `actionlint` and YAML validation for GitHub workflows
  - `ruff`/`ruff-format` for Python scripts under `.github/scripts`
  - `google-java-format` for Java sources under `src/`
  - the local POM consistency check and Maven unit tests
  on every `git commit`.
- **pre-push** — runs the Maven integration tests on every `git push`.

### Pull request checks

CI also runs the same pre-commit checks on pull requests. In addition,
reviewdog posts granular inline comments/suggestions for workflow and
Python lint findings.

## Contact

Contact the project developers via the project's "dev" list:

- https://accounts.eclipse.org/mailing-list/csi-dev

## Issues

For bug reports and feature requests, please use GitHub Issues:

- https://github.com/eclipse-csi/signpath-maven-plugin/issues
