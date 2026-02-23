# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

* Initial implementation
* CI/release/codeql/zizmor workflows
* Sigstore keyless signing of all release artifacts via `sigstore-maven-plugin` (`.sigstore.json` bundles published to Maven Central alongside each artifact)
* SLSA build level 3 provenance attestation generated for every release via `actions/attest-build-provenance`

### Changed

### Removed
