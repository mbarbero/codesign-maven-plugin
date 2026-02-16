# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Eclipse CSI SignPath Maven Plugin — a Maven plugin that signs build artifacts via the [SignPath](https://app.signpath.io/Api/swagger/index.html) REST API. Part of the Eclipse Common Security Infrastructure (CSI) project. Licensed under EPL-2.0.

## Build & Test Commands

```shell
./mvnw clean verify          # Build and run all tests
./mvnw test                  # Run tests only
./mvnw test -Dtest=SignMojoTest                    # Run a single test class
./mvnw test -Dtest=SignMojoTest#testSignFiles       # Run a single test method
./mvnw -Prelease -DskipTests clean verify          # Build with release profile
```

Requires Java 21+ and Maven 3.9+ (enforced by maven-enforcer-plugin).

## Contribution guideline

Commit messages shall use convential commits format

## Architecture

Single-package project: `org.eclipse.csi.maven.plugins.signing`

### Core Components

- **SignMojo** — The Maven plugin entry point (goal: `sign`, phase: `package`). Orchestrates the full signing workflow: scans for files matching glob patterns, resolves API token (parameter → settings.xml → env var `SIGNPATH_API_TOKEN`), submits artifacts, polls for completion, downloads signed results. Uses Java NIO `PathMatcher` for glob-based file matching.

- **SignPathClient** — HTTP client wrapping OkHttp. Handles three API operations: `submit()` (multipart POST), `getStatus()` (polling GET), and `downloadSignedArtifact()` (binary GET). Implements `AutoCloseable`. Configured via the inner `Config` record.

- **RetryInterceptor** — OkHttp `Interceptor` implementing time-bounded and count-bounded retry logic for transient HTTP failures (429, 502, 503, 504) and `IOException`. Uses JDK logging.

- **SigningRequest** / **SigningRequestStatus** — Records modeling the SignPath API response. `SigningRequestStatus` tracks workflow state with predicates (`isCompleted()`, `isFailed()`, `isDenied()`, `isCanceled()`).

- **SignPathException** — Custom exception carrying HTTP status code and response body for API errors.

### Key Dependencies

- **OkHttp 5** for HTTP (with interceptor-based retry)
- **Gson** for JSON serialization
- **JUnit 5** + **MockWebServer** for testing

### Testing Patterns

Tests use MockWebServer to simulate the SignPath API. SignMojoTest uses reflection-based field injection to set Mojo parameters (no DI framework). All tests use `@TempDir` for file system operations.
