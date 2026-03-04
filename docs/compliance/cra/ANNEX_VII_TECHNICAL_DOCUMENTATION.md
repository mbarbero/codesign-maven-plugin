# Technical Documentation

* **Product:** Eclipse CSI Codesign Tools (`codesign-api`, `codesign-maven-plugin`, `codesign-cli`)
* **Document version:** 1.0
* **Date:** 2026-03-04
* **Regulation:** Regulation (EU) 2024/2847 (Cyber Resilience Act), Annex VII / Article 31

---

## 1. General Description of the Product

### 1(a) Intended Purpose

Eclipse CSI Codesign Tools are **software build-toolchain components** that enable software
development teams to submit binary artifacts to the [SignPath](https://about.signpath.io/)
REST API for cryptographic code signing. The product suite consists of three distributable
components published under the `org.eclipse.csi` Maven groupId:

| Component | Artifact ID | Type | Role |
| --- | --- | --- | --- |
| API library | `codesign-api` | JAR | HTTP client for the SignPath REST API; used by the other two components and by third-party JVM applications |
| Maven plugin | `codesign-maven-plugin` | Maven plugin JAR | Integrates signing into Maven build lifecycles; collects artifacts via glob patterns or Maven artifact references |
| CLI | `codesign-cli` | Native binary + fat JAR | Standalone signing tool for non-Maven CI/CD pipelines, shell scripts, and GitHub Actions |

The product is an open-source project of the Eclipse Common Security Infrastructure (CSI)
initiative, distributed via Maven Central and GitHub Releases under the Eclipse Public
License 2.0.

### 1(b) Version Identification

All three components share the same version number in any given release, specified as
`MAJOR.MINOR.PATCH` per Semantic Versioning 2.0.0.

The authoritative version is declared in the root `pom.xml`:

```xml
<groupId>org.eclipse.csi</groupId>
<artifactId>codesign-parent</artifactId>
<version><!-- MAJOR.MINOR.PATCH --></version>
```

The version is embedded in the CLI at build time via the filtered resource
`cli/src/main/resources/version.properties` and exposed through `codesign --version`.

Release tags in the Git repository follow the format `v<MAJOR>.<MINOR>.<PATCH>`
(e.g., `v1.0.0`). Each GitHub Release includes the full set of distributed artifacts
for that version.

### 1(c) System Integration Context

The product is designed to be embedded within larger software build and release systems:

**Maven plugin:** Declared in a project's `pom.xml` and invoked during the Maven `package`
lifecycle phase. It operates within Maven's build environment, accessing Maven's project
model, artifact registry, and `settings.xml` for configuration and credential resolution.

**CLI binary:** Invoked as a build step in CI/CD workflow definitions (e.g., a GitHub
Actions `run:` step). It receives configuration via command-line arguments, environment
variables, and a user-managed configuration file (default:
`~/.config/eclipse-csi-codesign/config.properties`, overridable via `--config-file`).

**API library:** Added as a Maven or Gradle dependency and used programmatically from
JVM-based build tooling or applications.

In all cases, the product operates as an **outbound HTTPS client** of the SignPath REST
API. It does not expose any network service, accept inbound connections, or execute with
privileges beyond those of the invoking process.

---

## 2. Design, Development, and Production Description

### 2(a) Design and Development Description

#### Module structure

The repository is a Maven multi-module project with a parent aggregator POM at the root:

```text
/ (codesign-parent, packaging=pom)
├── api/           codesign-api          (packaging=jar)
│   └── src/main/java/org/eclipse/csi/codesign/
│       CodesignClient, CodesignException, RetryInterceptor,
│       SigningRequest, SigningRequestStatus, SigningWorkflow
├── maven-plugin/  codesign-maven-plugin (packaging=maven-plugin)
│   └── src/main/java/org/eclipse/csi/maven/plugins/signing/
│       CodesignMojo, SignProjectArtifact
└── cli/           codesign-cli          (packaging=jar + shade + native)
    └── src/main/java/org/eclipse/csi/codesign/cli/
        CodesignCli, SignCommand, TokenResolver,
        VersionProvider, PrintExceptionMessageHandler
```

#### Key classes and their security relevance

**`CodesignClient`** (`api` module) — The entry point for all SignPath API interactions.
Holds an immutable `Config` record. The constructor validates that `baseUrl` begins with
`https://`; any `http://` URL causes an `IllegalArgumentException` before any network
activity. All requests are made via OkHttp5 with standard TLS certificate validation.

**`SigningWorkflow`** (`api` module) — Orchestrates the three-phase signing operation:
(1) compute SHA-256 of input artifact and log it; (2) submit the artifact to SignPath and
obtain a signing request ID; (3) poll status until a terminal state is reached; (4) download
the signed artifact and log its SHA-256. This class is the implementation of the
SHA-256 audit log control (DS-2).

**`RetryInterceptor`** (`api` module, package-private) — OkHttp5 interceptor providing
retry with configurable exponential-like backoff for HTTP 429, 502, 503, 504 responses
and connection/read timeout errors. Package-private to prevent external instantiation.

**`CodesignMojo`** (`maven-plugin` module) — The Maven plugin's `@Mojo`-annotated entry
point. Resolves the Bearer token from the priority-ordered chain (`settings.xml` →
`CSI_CODESIGN_API_TOKEN` environment variable → config file), applies the same POSIX/ACL
permission check to the config file as the CLI, constructs a `CodesignClient`, delegates
to `SigningWorkflow`, and logs the post-download SHA-256.

**`SignCommand`** (`cli` module, package-private) — The picocli `@Command`-annotated class
implementing the `sign` subcommand. Resolves the Bearer token via `TokenResolver`,
constructs a `CodesignClient`, delegates to `SigningWorkflow`, and logs the post-download
SHA-256. The `--config-file` option is available to override the config file path.

**`TokenResolver`** (`cli` module, package-private) — Implements the CLI's priority-ordered
token resolution: `CSI_CODESIGN_API_TOKEN` environment variable →
`~/.config/eclipse-csi-codesign/config.properties` (or the path from `--config-file`).
On POSIX systems, checks file permissions and emits a warning if group- or world-readable.
On Windows, checks NTFS/ReFS ACLs and warns if any non-owner principal has `READ_DATA`
access.

#### Software design explanation (relevant elements)

The design follows these security-relevant principles:

* **Fail-fast at configuration time:** TLS scheme validation and token resolution failures
  occur before any network I/O, providing immediate feedback and preventing partial execution.
* **Immutable configuration:** `CodesignClient.Config` is a Java `record`; configuration
  cannot be mutated after construction, eliminating a class of TOCTOU risks in configuration.
* **Atomic output writes:** Signed artifacts are written to a `.codesign-tmp` / `.signing-tmp`
  temporary file in the same directory as the output path, then atomically renamed. This
  ensures the output path always contains either the original file or a complete signed
  artifact, never a partial download.
* **No dynamic code loading:** No `Class.forName`, `URLClassLoader`, or equivalent patterns
  are used. All code is statically compiled.
* **No shell command invocation:** The product performs all operations in-process via the
  JVM, eliminating shell injection risks.
* **Package-private visibility:** `RetryInterceptor`, `TokenResolver`, `SignCommand`, and
  `SignProjectArtifact` (plugin enum) are package-private. ArchUnit tests enforce this
  visibility boundary in CI.

#### Technology stack

| Component | Technology | Version (at time of assessment) |
| --- | --- | --- |
| Language | Java | 21 (LTS) |
| HTTP client | OkHttp | 5.3.x |
| JSON parsing | Gson | 2.13.x |
| CLI framework | picocli | 4.7.x |
| Native compilation | GraalVM Native Image | via native-maven-plugin 0.11.x |
| Build system | Apache Maven | 3.9.x |
| Release management | JReleaser | 1.22.x |
| SBOM generation | CycloneDX Maven Plugin | 2.9.x (schema 1.6) |

### 2(b) Production and Monitoring Processes

#### Build process (how it ensures compliance with Annex I requirements)

The build process is fully automated via GitHub Actions and the Maven wrapper (`./mvnw`).
The following steps apply to every CI run and release:

| Phase | Step | Annex I requirement addressed |
| --- | --- | --- |
| Compile | `maven-compiler-plugin` with `--release 21` | Baseline code quality |
| Test | `maven-surefire-plugin`: unit tests including security control tests (`CodesignClientTest.httpBaseUrlIsRejected`, `SigningWorkflowTest`, `TokenResolverTest`, `CodesignMojoTest`, `SignCommandTest`) | Annex I §2(a)(e)(k) |
| Test | ArchUnit tests (`CodesignApiArchTest`, `CliArchTest`, `MavenPluginArchTest`): enforce package-private visibility invariants | Annex I §2(i) |
| Integration test | `maven-invoker-plugin` + WireMock: end-to-end signing workflow test | Annex I §2(a)(e) |
| Dependency scan | Trivy: HIGH/CRITICAL unfixed CVE scan; SARIF uploaded to GitHub Security | Annex I §2(a), Part II §3 |
| Workflow security | zizmor: GitHub Actions workflow security analysis | Annex I §2(i), T3 |
| Workflow security | poutine: build pipeline security analysis | Annex I §2(i), T3 |
| Secret scan | TruffleHog: accidental credential detection | Annex I §2(d), I1, I2 |
| SBOM | CycloneDX 1.6 SBOM generated per module + aggregate | CRA Art. 13(3), Part II §1 |
| Release signing | Sigstore keyless signing of all release artifacts | Annex I §2(l), T3 |
| Provenance | SLSA provenance attestation for native binaries | Annex I §2(l), T3 |
| GPG signing | GPG signatures on all Maven Central artifacts | Annex I §2(l) |
| Dependency updates | Dependabot: automated Maven + Actions dependency update PRs | Part II §2, T3 |

All GitHub Actions in CI/CD workflows are pinned to full commit SHAs to prevent tag-mutable
dependency injection. The `harden-runner` action monitors runner egress (currently in audit
mode; upgrading to block mode is tracked as action PR-5 in the risk assessment).

#### Native binary production

Native binaries are compiled on platform-specific GitHub Actions runners using GraalVM
Native Image. The build process:

1. Compiles and tests the JVM JAR on the platform runner.
2. Invokes `native-maven-plugin` (0.11.x) with `native-image` to produce the binary.
3. Uploads the binary as a GitHub Actions artifact with a 1-day retention period.
4. A separate assembly job downloads all platform binaries and runs JReleaser to produce
   `.zip` / `.tar.gz` archives, applies GPG signatures, generates SLSA attestations, and
   publishes to GitHub Releases.

The GraalVM reflect configuration (`cli/src/main/resources/META-INF/native-image/...
/reflect-config.json`) is maintained to ensure picocli annotation processing works
correctly in the native image.

### 2(c) Explanation of Cybersecurity Design Choices

#### Security by design

| Design choice | Rationale | Annex I requirement |
| --- | --- | --- |
| **HTTPS enforcement at construction time** — `CodesignClient(Config)` throws `IllegalArgumentException` if `baseUrl` does not begin with `https://`. | Prevents accidental or malicious redirection of the Bearer token or artifact data to a non-TLS endpoint. Fail-fast at configuration time provides immediate feedback and no partial-operation state. | §2(c)(d) |
| **Immutable `Config` record** — `CodesignClient.Config` is a Java `record`; all fields are final. | Eliminates TOCTOU risks in configuration; ensures the same security parameters apply throughout a signing operation. | §2(e) |
| **SHA-256 audit log (pre-upload and post-download)** — `SigningWorkflow` computes and logs SHA-256 of the input artifact before upload, and of the signed artifact after download. | Provides a tamper-evident client-side record that can be correlated with SignPath's server-side audit logs. Enables forensic detection of TOCTOU tampering and MITM substitution. | §2(e)(j)(k) |
| **Token source warning system** — explicit `[WARN]` / `[WARNING]` emitted when the token is supplied via CLI flag or Maven system property. | Guides operators away from credential paths that expose the token in process listings or build logs, without breaking backward compatibility. | §2(c)(d) |
| **File permission check on `config.properties`** — `TokenResolver` (and `CodesignMojo`) warn if the config file is group- or world-readable on POSIX systems; on Windows, warns if any non-owner principal has `READ_DATA` access via ACL inspection. | Reduces the risk of token exposure from misconfigured file permissions on shared build hosts across all supported platforms. | §2(c) |
| **Package-private internal classes** — `RetryInterceptor`, `TokenResolver`, `SignCommand`, `SignProjectArtifact` are not `public`. | Minimises the API attack surface; external code cannot instantiate or subclass internal implementation classes. | §2(i) |
| **Atomic file writes** — temporary file with `.codesign-tmp` suffix, then atomic rename. | Prevents partially-written signed artifacts from being treated as valid outputs if the process is interrupted. | §2(e) |
| **No shell invocation, no dynamic class loading** | Eliminates command injection and class loading attack vectors. | §2(i) |

#### Security by default

| Default behaviour | Security rationale |
| --- | --- |
| Token resolution order: `settings.xml` → env var → config file; no command-line token argument | The token is never passed as a CLI argument, eliminating exposure via process listings. The environment variable is not visible in process listings. |
| `signProjectArtifact=auto` — skips `pom` packaging | Avoids accidentally uploading non-binary Maven POM artifacts for signing. |
| `failOnNoFilesFound=false` — warns but does not fail | Build continues if no files match; operators in release pipelines should override to `true`. |
| `maxRetries=10`, `retryTimeout=600s` | Bounds retry behaviour to prevent excessive resource consumption. |
| `CSI_CODESIGN_SKIP_SIGNING` — defaults to unset (signing active) | Signing must be explicitly opted out of, not opted into. |

### 2(d) Cybersecurity Risk Assessment

The full cybersecurity risk assessment, conducted in accordance with CRA Article 13(2),
is maintained in [`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md).

**Summary of risk assessment methodology and findings:**

**(i) Threat overview and security environment:**

The threat model (RISK_ASSESSMENT.md §2–3) applies the STRIDE framework across 7
identified data flows (DF1–DF7) and 4 trust boundaries. 22 individual threats are
identified across 6 STRIDE categories. The security environment comprises CI/CD build
runners (ephemeral and persistent) and developer workstations, operating in the context
of outbound-only HTTPS communication with the SignPath cloud API.

The highest-rated threats (High severity) are:

* S1: MITM on SignPath HTTPS traffic
* S2: Bearer token theft and impersonation
* S3: Dependency confusion / counterfeit plugin
* T3: Compromised plugin release artifacts

**(ii) Known vulnerabilities and their treatment:**

No known exploitable vulnerabilities are present in the product or its runtime dependencies
at the time of this assessment. Trivy CI scanning provides ongoing detection. All
identified risks and their current mitigation status are tracked in the risk assessment
action tables (§5).

**(iii) Risk management information:**

Each threat is rated H/M/L based on damage severity, exploitability, and breadth of
impact. Risks are assigned to one of three priority tiers (P1–P3) and tracked through
phased action items (Planning, Design, Development, Production, Delivery, Maintenance).
The risk assessment is subject to annual review or earlier review upon trigger events
(see MT-2).

### 2(e) Applicability and Implementation of Annex I, Part I Security Requirements

A full per-requirement applicability and implementation analysis is documented in
[`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md) §8.2.

**Summary table:**

| Annex I Part I requirement | Applicable | Primary implementation |
| --- | --- | --- |
| (a) No known exploitable vulnerabilities | Yes | Trivy CI scan (DV-5); Dependabot (DV-6) |
| (b) Secure by default; reset to original state | Partially | HTTPS-only default; conservative artifact selection defaults; no persistent mutable state |
| (c) Protection from unauthorised access | Yes | Bearer token via secure stores; POSIX permission check; HTTPS enforcement |
| (d) Confidentiality of data in transit | Yes | HTTPS enforced; OkHttp5 TLS; `http://` rejected at startup |
| (e) Integrity of data | Yes | SHA-256 pre-upload + post-download logging; TLS integrity; atomic file write |
| (f) Data minimisation | Yes | Only necessary signing data transmitted; no telemetry |
| (g) Availability of essential functions | Partially | Configurable retry/timeout; HTTP 429 backoff |
| (h) Minimise negative impact on other services | Limited | Bounded retry count and timeout; rate-limit-aware backoff |
| (i) Limit attack surfaces | Yes | Package-private internals; HTTPS-only; no shell invocation; no dynamic class loading |
| (j) Reduce impact of incidents; forensic support | Yes | SHA-256 audit log; signing request ID logging; atomic writes; SBOM |
| (k) Security logging and monitoring | Yes | Token warnings; SHA-256 logs; signing request ID; no opt-out for audit logging |
| (l) Security updates; notification to users | Yes | Maven Central + GitHub Releases; GHSA notifications; Sigstore/GPG signed releases |

### 2(f) Security Update Delivery Mechanism

Security updates are delivered as new releases published to two channels:

1. **Maven Central** — `codesign-api`, `codesign-maven-plugin`, and `codesign-cli` (fat JAR)
   are published to Maven Central via JReleaser with the `release` Maven profile. All
   artifacts are GPG-signed and Sigstore keyless-signed.

2. **GitHub Releases** — Native CLI binaries (`.zip` / `.tar.gz`), GPG signatures (`.asc`),
   Sigstore signature bundles (`.sigstore.json`), SLSA provenance attestations
   (`.intoto.jsonl`), and CycloneDX SBOMs are published to GitHub Releases.

**No automatic update mechanism is provided.** All updates are opt-in. Users update by:

* Changing the `<version>` in `pom.xml` (Maven plugin and API library).
* Downloading the new binary from GitHub Releases (CLI native binary).

**User notification of security updates:**

* GitHub repository release subscriptions (Watch → Releases only).
* GitHub Security Advisories (GHSA) for security-relevant releases (action MT-6).

Security patches are released free of charge as part of the open-source release process.

The JReleaser configuration in the root `pom.xml` defines the release workflow, with
GPG signing active on `RELEASE` versions (inactive for `SNAPSHOT` versions) and SLSA
attestations enabled via the `catalog.github` configuration.

### 2(g) Standards and Specifications Applied

The following European or international standards and specifications have been applied
or referenced in the development of this product:

| Standard / Specification | Scope | Application status |
| --- | --- | --- |
| **CycloneDX 1.6** | SBOM format | Fully applied; SBOMs generated per module and as aggregate for every release |
| **SLSA Supply-chain Levels for Software Artifacts** | Supply chain integrity | Applied at Level 2+ for native binary provenance attestations via GitHub OIDC |
| **Sigstore / Rekor** | Artifact signing and transparency | Fully applied; keyless signing of all release artifacts with Rekor log entries |
| **Semantic Versioning 2.0.0** | Version scheme | Fully applied across all modules |
| **Eclipse Foundation Vulnerability Reporting Policy** | Vulnerability handling governance | Fully applied as the governing disclosure policy |
| **STRIDE threat modelling** | Risk assessment methodology | Fully applied (see RISK_ASSESSMENT.md §3) |
| **BSI TR-03183-1** | Risk assessment guidance | Referenced in risk assessment methodology |
| **Eclipse Public License 2.0** | Open-source licensing | Applied to all source code |
| **Eclipse Contributor Agreement** | IP governance | Applied to all contributions |
| **OWASP Secure Coding Practices** | Implementation guidance | Informally applied during code review |

No harmonised standards or common specifications formally designated under CRA Article 27
or 28 have been applied to date. The product will be assessed against applicable harmonised
standards once they are formally designated for the product category.

### 2(h) Vulnerability Handling Requirements (Annex I, Part II)

The full vulnerability handling framework is documented in
[`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md) §8.3.

**Summary of implementation per Part II requirement:**

| Part II requirement | Implementation | Status |
| --- | --- | --- |
| **(1) SBOM — identify and document components** | CycloneDX 1.6 JSON SBOMs generated per module (`codesign-api`, `codesign-maven-plugin`, `codesign-cli`) and as an aggregate. Covers all compile-scope and runtime-scope dependencies. Published with every release to GitHub Releases and Maven Central. | ✅ |
| **(2) Address and remediate vulnerabilities without delay** | Trivy CI scanner detects HIGH/CRITICAL CVEs in dependencies on every build. Dependabot creates automated dependency update PRs. Target SLA: CVSS ≥ 9.0 within 7 days; CVSS ≥ 7.0 within 14 days (action MT-1, open). Security releases accompanied by GHSA advisory with severity and remediation guidance. | 🔶 Scanners in place; SLA formalisation open |
| **(3) Regular testing and review** | Unit tests, ArchUnit tests, and integration tests run on every CI build. Trivy dependency scan on every build. zizmor and poutine workflow security analysis on every commit. Annual threat model review planned (MT-2). | ✅ Automated; annual cadence open |
| **(4) Public disclosure of fixed vulnerabilities** | Fixed vulnerabilities published via GitHub Security Advisories including: CVE description, affected versions, CVSS severity, exploitation status (where known), and corrective actions. Advisory timed to coincide with release of the patched version. | 🔶 Process defined; MT-6 open |
| **(5) Coordinated vulnerability disclosure policy** | The project adheres to the Eclipse Foundation Vulnerability Reporting Policy. `SECURITY.md` documents reporting channels and the coordinated disclosure process. | ✅ PL-1 in place |
| **(6) Contact address for vulnerability reporting** | Three channels: (1) GitHub private vulnerability reporting; (2) `security@eclipse-foundation.org`; (3) Eclipse Foundation confidential GitLab tracker. | ✅ PL-1 in place |
| **(7) Secure distribution of security updates** | Maven Central (TLS + GPG signature verification) and GitHub Releases (TLS + GPG + Sigstore + SLSA). Verification commands documented in `ANNEX_II_USER_INFORMATION.md` §11(a). | ✅ PR-1–PR-4 in place |
| **(8) Security patches free of charge; advisory messages** | Open source under EPL-2.0; all releases free of charge. Security releases include advisory messages via GHSA and release notes describing the vulnerability, severity, and recommended remediation. | ✅ / 🔶 Free; GHSA formalisation open (MT-6) |

### 2(i) EU Declaration of Conformity

> To be completed upon completion of the applicable conformity assessment procedure.

The EU declaration of conformity required under Article 28 of Regulation (EU) 2024/2847
will be drawn up once:

1. The product has been formally classified under Article 6 (assessment pending under
   action PL-2 in [`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md)).
2. The applicable conformity assessment procedure has been identified and completed.
3. The manufacturer has drawn up the technical documentation required under Article 31.

Upon completion, the EU declaration of conformity will be appended to this document and
published in the `docs/compliance/` directory of the repository.

### 2(j) Cybersecurity Risk Assessment

The cybersecurity risk assessment required under Article 13(2) of Regulation (EU)
2024/2847 is maintained in [`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md) in this directory.

The risk assessment:

* Was performed using the STRIDE threat modelling methodology and DREAD-inspired risk
  rating.
* Covers 22 identified threats across 6 STRIDE categories.
* Documents the security environment, data flows, trust boundaries, and protected assets.
* Provides an explicit per-requirement applicability analysis for Annex I, Part I, point
  (2) and Part II (§8 of the risk assessment).
* Is subject to review upon trigger events and at minimum annually (action MT-2).

---

## 3. EU Declaration of Conformity

*(Placeholder — to be completed upon conclusion of the conformity assessment procedure
as described in §2(i) above.)*
