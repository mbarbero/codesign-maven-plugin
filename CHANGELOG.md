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
* HTTPS enforcement: `http://` base URLs are rejected at startup
* SHA-256 of input artifact is logged before upload and SHA-256 of signed artifact is logged after download
* `--config-file` CLI option to override the default config file path
* `csi.codesign.configFile` Maven plugin parameter to override the default config file path
* Config file (`api.token`) as a token source for the Maven plugin (lowest priority, after `settings.xml` and `CSI_CODESIGN_API_TOKEN`)
* Config file permission check on Windows (NTFS/ReFS ACL-based) in addition to the existing POSIX check; warns when any non-owner principal has read access
* Release CI blocks on unfixed HIGH/CRITICAL vulnerabilities detected in the generated SBOM (Trivy)

### Changed

* Default config file path is `~/.config/eclipse-csi-codesign/config.properties` (avoids namespace collision with unrelated tools that use the generic `codesign` namespace)

### Removed
