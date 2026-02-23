# AGENTS.md

This file provides guidance to AI agents when working with code in this repository.

## Project Overview

Eclipse CSI Codesign Maven Plugin — a Maven plugin that signs build artifacts via the [SignPath](https://app.signpath.io/Api/swagger/index.html) REST API. Part of the Eclipse Common Security Infrastructure (CSI) project. Licensed under EPL-2.0.

## Build & Test Commands

```shell
./mvnw clean verify                                      # Build all modules; runs unit + integration tests
./mvnw test                                              # Unit tests only (stops before integration-test phase)
./mvnw -P'!integration-tests' verify                     # Verify without integration tests
./mvnw test -Dtest=SignMojoTest                          # Run a single test class
./mvnw test -Dtest=SignMojoTest#testSignFiles             # Run a single test method
./mvnw verify -pl codesign-maven-plugin --also-make      # Plugin module (+ its codesign-api dependency)
./mvnw verify -pl codesign-api                           # API module only
./mvnw package -pl cli --also-make                       # CLI fat JAR only
./mvnw -Pnative package -pl cli --also-make -DskipTests  # Native CLI binary (requires GraalVM 21)
./mvnw -Prelease -DskipTests clean verify                # Build with release profile
```

Requires **Java 21+** and **Maven 3.9+** (enforced by `maven-enforcer-plugin`).
The Maven wrapper (`./mvnw`) downloads Maven automatically; a JDK must already be on `PATH`.

> **Integration tests run by default.** The `integration-tests` profile in
> `codesign-maven-plugin/pom.xml` has `<activeByDefault>true</activeByDefault>`.
> Running `./mvnw verify` at the root always runs them. Use `-P'!integration-tests'`
> to skip.

## Development Setup

This project uses [prek](https://prek.j178.dev/) for Git hooks.

- **prek install** — Initialize hooks
- **prek run --all-files** — Run all quality checks

### Required System Tools

The following tools must be installed on the host:

| Tool | Version | Required for |
| --- | --- | --- |
| **Java JDK** | 21+ | `./mvnw`, `unit-tests`/`integration-tests` hooks (`language: system`), `pretty-format-java` hook |
| **poutine** | any | `poutine` hook (`language: system`) — workflow security analysis |
| **cdxgen** | any | SBOM generation (`generate-sbom` profile only) — install via `npm install -g @cyclonedx/cdxgen` (requires Node.js) |
| **cyclonedx-cli** | any | SBOM merging (`generate-sbom` profile only) |

## Contribution guideline

Commit messages shall use [convential commits format](https://www.conventionalcommits.org/en/v1.0.0/#specification)

A changelog should be kept following recommendations from [keepachangelog](https://keepachangelog.com/en/1.1.0/).

## Architecture

Three-module Maven aggregator (`codesign-parent`):

| Module directory | Artifact ID | Root package |
| --- | --- | --- |
| `codesign-api/` | `codesign-api` | `org.eclipse.csi.codesign` |
| `codesign-maven-plugin/` | `codesign-maven-plugin` | `org.eclipse.csi.maven.plugins.signing` |
| `codesign-cli/` | `codesign-cli` | `org.eclipse.csi.codesign.cli` |

### Core Components

**`codesign-api`** (`org.eclipse.csi.codesign`):

- **CodesignClient** + nested `Config` record — public entry point; OkHttp-based HTTP client for `submit()` (multipart POST), `getStatus()` (polling GET), `downloadSignedArtifact()` (binary GET). Implements `AutoCloseable`.
- **SigningWorkflow** — shared submit→poll→download orchestration used by both the plugin and CLI.
- **RetryInterceptor** — OkHttp `Interceptor` for time/count-bounded retry on 429/502/503/504 and `IOException` (package-private; not part of public API).
- **SigningRequest** / **SigningRequestStatus** — DTOs modelling SignPath API responses. `SigningRequestStatus` has predicates `isCompleted()`, `isFailed()`, `isDenied()`, `isCanceled()`.
- **CodesignException** — Checked exception carrying HTTP status code and response body.

**`codesign-maven-plugin`** (`org.eclipse.csi.maven.plugins.signing`):

- **SignMojo** — Maven plugin entry point (goal: `sign`, phase: `package`). Scans for files via glob patterns, resolves API token (parameter → `settings.xml` → env var `CSI_CODESIGN_API_TOKEN`), delegates to `SigningWorkflow`.
- **SignProjectArtifact** — internal enum for Mojo config parsing (package-private).

**`cli`** (`org.eclipse.csi.codesign.cli`):

- **CodesignCli** — picocli root `@Command`; main entry point for both the fat JAR and the native binary.
- **SignCommand** — picocli `sign` subcommand; delegates to `SigningWorkflow` (package-private).
- **TokenResolver** — CLI-internal token resolution (package-private).
- **VersionProvider** — reads version from `META-INF/version.properties` (Maven-filtered at build time).

### Key Dependencies

- **OkHttp 5** (5.3.2) for HTTP with interceptor-based retry
- **Gson** for JSON serialisation
- **picocli** (4.7.7) for CLI argument parsing
- **JUnit 5** + **MockWebServer** (OkHttp) for unit tests
- **WireMock** (standalone JAR, downloaded by Maven) for integration tests
- **ArchUnit** (1.3.0) for architecture constraint tests

### Testing Patterns

- **Unit tests** (`./mvnw test`): `MockWebServer` simulates the SignPath API. `SignMojoTest` uses reflection-based field injection to set Mojo parameters (no DI framework). All tests use `@TempDir`.
- **Integration tests** (`integration-tests` profile, active by default): maven-invoker plugin runs projects under `codesign-maven-plugin/src/it/` against a WireMock stub on a reserved random port. Check `codesign-maven-plugin/target/wiremock.log` on failures.
- **Architecture tests**: `CodesignApiArchTest`, `MavenPluginArchTest`, `CliArchTest` (ArchUnit) enforce visibility rules — e.g. only `@Mojo`-annotated classes may be `public` in the plugin module; only `CodesignCli` may be `public` in the CLI module.
