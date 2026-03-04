# Cybersecurity Risk Assessment

* **Product:** Eclipse CSI Codesign Tools (`codesign-api`, `codesign-maven-plugin`, `codesign-cli`)
* **Assessment date:** 2026-03-04
* **Regulation:** Regulation (EU) 2024/2847 (Cyber Resilience Act), Article 13(2)
* **Assessment basis:** BSI TR-03183-1, STRIDE threat modelling

---

## 1. Intended Purpose and Reasonably Foreseeable Use

### 1.1 Intended Purpose

The Eclipse CSI Codesign Tools (`codesign-maven-plugin`, `codesign-api`, `codesign-cli`) are **build-toolchain components** that allow software development teams to submit binary artifacts — JARs, EXEs, native binaries, and other signed formats — to the [SignPath](https://signpath.io) REST API for cryptographic code signing. The toolset provides:

* A **Maven plugin** (`codesign-maven-plugin`) that integrates transparently into Maven build lifecycles, collecting artifacts via glob patterns or Maven artifact references and submitting them for signing.
* A **Java API library** (`codesign-api`) providing the underlying HTTP client for the SignPath REST API, usable from any JVM project.
* A **standalone CLI** (`codesign-cli`, distributed also as a native binary) for non-Maven CI/CD pipelines (Gradle, shell scripts, GitHub Actions, etc.).

The product is an **open-source Eclipse CSI project** distributed via Maven Central and GitHub Releases, intended primarily for the Eclipse software ecosystem but usable by any Java/Maven-based project requiring code signing.

### 1.2 Reasonably Foreseeable Use

| Use pattern | Frequency | Risk weight |
| --- | --- | --- |
| Embedded in Maven release pipeline (CI/CD, e.g. GitHub Actions, Jenkins) | Primary | High |
| Developer workstation (manual release preparation) | Common | Medium |
| Ad-hoc signing via standalone CLI from shell scripts | Common | Medium |
| Integration into third-party build tooling that depends on `codesign-api` | Possible | Medium |
| Used by projects that redistribute signed artifacts to end users | Likely | High |

**Reasonably foreseeable misuse scenarios:**

* A build engineer embeds the API token in a public repository's `pom.xml` or CI log.
* A build engineer embeds the API token in a public repository's `pom.xml` or CI log.
* An attacker targeting a downstream software project compromises the signing pipeline to sign a malicious artifact.
* A dependency confusion attack delivers a fake `org.eclipse.csi:codesign-maven-plugin` JAR.

### 1.3 Operational Environment and Assets to Protect

**Operational environments:**

* Linux/macOS/Windows CI/CD runners (ephemeral or persistent)
* Developer workstations (persistent)
* Maven build environments with access to `~/.m2/settings.xml`

**Protected assets (in descending criticality):**

1. **API Bearer token** — grants signing authority for the organisation on SignPath; compromise enables signing of arbitrary malicious code.
2. **Pre-signed artifacts** — must not be tampered with between collection and upload (TOCTOU risk).
3. **Post-signed artifacts** — must be verified as genuine outputs from SignPath before being distributed to end users.
4. **Build pipeline integrity** — the plugin/CLI binary itself must not be compromised (supply chain threat).

**Expected product lifetime:** As a Maven plugin published to Maven Central and used in active CI/CD pipelines, the expected support lifetime is 5–10 years, requiring sustained dependency management and security patching.

---

## 2. Threat Model: System Decomposition

### 2.1 Components and Trust Boundaries

```text
┌─────────────────────────────────────────────────────────────┐
│  Build Environment (CI runner / developer workstation)      │
│                                                             │
│  ┌──────────────────┐    ┌──────────────────────────────┐   │
│  │  Credential      │    │  codesign-maven-plugin       │   │
│  │  Store           │───▶│  or codesign-cli             │   │
│  │  (env, settings  │    │                              │   │
│  │   .xml, config   │    │  [codesign-api]              │   │
│  │   .properties)   │    │  OkHttp5 / RetryInterceptor  │   │
│  └──────────────────┘    └──────────┬───────────────────┘   │
│                                     │ HTTPS (enforced)      │
│  ┌──────────────────┐               │                       │
│  │  Artifact Files  │◀──────────────┘                       │
│  │  (unsigned/      │               │                       │
│  │   signed)        │               │                       │
│  └──────────────────┘               │                       │
└─────────────────────────────────────┼─────────────────────--┘
             Trust boundary: Network  │
┌─────────────────────────────────────▼───────────────────────┐
│  SignPath API (app.signpath.io) — External Service          │
│  POST /v1/{orgId}/SigningRequests  (submit)                 │
│  GET  /v1/{orgId}/SigningRequests/{id} (poll)               │
│  GET  {signedArtifactLink}          (download)              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Data Flows

| # | Flow | Protocol | Data |
| --- | --- | --- | --- |
| DF1 | Token resolution | In-process | Bearer token ← env / settings.xml / config.properties |
| DF2 | Artifact collection | Local disk | Unsigned artifact bytes |
| DF3 | Artifact upload | HTTPS POST (multipart) | Token, artifact bytes, project/policy slugs |
| DF4 | Status polling | HTTPS GET | Token, signing request ID |
| DF5 | Signed artifact download | HTTPS GET | Token, signed artifact bytes |
| DF6 | Artifact write | Local disk (atomic) | Signed artifact bytes → temp → final path |
| DF7 | Plugin resolution | Maven Central / HTTPS | Plugin JAR, checksums, signatures |

---

## 3. STRIDE Threat Identification and Risk Rating

Risk is rated **High (H) / Medium (M) / Low (L)** using the DREAD-inspired scale: severity of damage × exploitability × breadth of affected users.

### 3.1 Spoofing

| ID | Threat | Data Flow | Risk | Existing controls |
| --- | --- | --- | --- | --- |
| **S1** | Attacker intercepts HTTPS traffic (MITM) and receives the Bearer token and/or returns a maliciously crafted signed artifact link | DF3, DF4, DF5 | **H** | OkHttp performs TLS certificate validation by default; HTTPS scheme enforced at `CodesignClient` construction time (`CodesignClient.java:73`); SHA-256 of downloaded artifact logged post-download |
| **S2** | Attacker steals Bearer token (from CI log, process list, config file) and impersonates the legitimate CI pipeline to sign malicious artifacts | DF1 | **H** | TruffleHog secret scanning in CI; WARN emitted when token supplied via parameter/flag; env-var preferred path; settings.xml encryption support; SECURITY.md |
| **S3** | Dependency confusion: attacker publishes `org.eclipse.csi:codesign-maven-plugin` to a higher-priority repository with a higher version number, injecting malicious code into build pipelines | DF7 | **H** | Maven Central groupId registration; Sigstore keyless signing; GPG signatures on releases; SLSA attestations; Trivy dependency vulnerability scanning in CI |
| **S4** | Attacker configures `baseUrl` to point to a spoofed server causing the tool to send the Bearer token to an attacker-controlled endpoint (SSRF-like) | DF3 | **L** | `CodesignClient(Config)` constructor rejects any `baseUrl` that does not start with `https://`; `http://` endpoints unreachable; attacker must control a certificate-backed HTTPS endpoint |

### 3.2 Tampering

| ID | Threat | Data Flow | Risk | Existing controls |
| --- | --- | --- | --- | --- |
| **T1** | Attacker modifies the artifact on disk between collection and upload (TOCTOU); a malicious binary is signed by SignPath and distributed as trusted | DF2, DF3 | **M** | SHA-256 of the artifact computed and logged before upload (`SigningWorkflow.submitAndWait()`); any post-hash modification leaves a traceable discrepancy between the build log and SignPath's server-side record |
| **T2** | Attacker intercepts the signed artifact download (MITM) and substitutes a malicious binary; tool writes it atomically to the output path | DF5, DF6 | **M** | HTTPS TLS integrity; SHA-256 of the signed artifact computed and logged after download (Mojo + CLI); tampering produces a build log entry that can be correlated with the server-side record |
| **T3** | Attacker compromises the codesign plugin/CLI JAR in Maven Central or GitHub Releases (e.g., via compromised release key, build pipeline compromise) | DF7 | **H** | Sigstore keyless signing; GPG signatures; SLSA attestations; harden-runner in CI; SHA-pinned Actions; Trivy vulnerability scanning; no long-lived signing secrets in CI |
| **T4** | Attacker modifies `~/.config/eclipse-csi-codesign/config.properties` or `settings.xml` on a shared build server to inject a different token (signing under a different policy) | DF1 | **M** | Standard OS file permissions; outside tool's control |
| **T5** | Malicious `--param key=value` values injected via CI pipeline inputs; server-side interpretation of parameters could trigger unintended signing policy behaviour | DF3 | **L** | Parameters are passed as opaque key-value pairs; no validation of values |

### 3.3 Repudiation

| ID | Threat | Data Flow | Risk | Existing controls |
| --- | --- | --- | --- | --- |
| **R1** | A developer (or attacker) signs a malicious artifact; the client emits no immutable log of what file (with what hash) was submitted and what signing request ID was assigned | DF3, DF4 | **L** | SHA-256 logged before upload and after download; signing request URL logged; build log constitutes a client-side, non-repudiable record of what was sent and received; SignPath additionally maintains server-side logs |
| **R2** | Shared API token: multiple pipelines or developers use the same token; individual signing operations cannot be attributed to a specific actor | DF1 | **M** | Deployment guidance concern; tool design does not prevent per-user tokens |

### 3.4 Information Disclosure

| ID | Threat | Data Flow | Risk | Existing controls |
| --- | --- | --- | --- | --- |
| **I1** | Bearer token committed to version control or embedded in a shared configuration file | DF1 | **M** | Token resolution uses `settings.xml`, environment variables, or a permission-checked config file; the token is never read from the POM or a project-committed file. Operators are advised to use CI secrets management. |
| **I2** | Bearer token exposed in process listing via a command-line argument | DF1 | **L** | Neither the CLI nor the Maven plugin accept the API token as a command-line argument. Token resolution uses `settings.xml`, environment variables, or the config file only. |
| **I3** | Bearer token stored in plaintext in `~/.config/eclipse-csi-codesign/config.properties` with insecure OS file permissions | DF1 | **L** | `TokenResolver` (and `CodesignMojo`) check file permissions at token-read time: POSIX `chmod 600` check on Linux/macOS; ACL-based check on Windows (warns if any non-owner principal has `READ_DATA` access). Both emit a `[WARNING]` with remediation guidance. |
| **I4** | Exception messages from `CodesignException` include the raw HTTP response body from SignPath, which could contain sensitive server-side details | DF3–DF5 | **L** | Exception details are typically caught and logged by Maven/CLI; not transmitted further; formal audit pending (DV-7) |
| **I5** | Proprietary artifact content (compiled code, executable) is transmitted to SignPath's cloud infrastructure | DF3 | **M** | Inherent to service model; not a software defect but must be documented for deployers |
| **I6** | Internal project metadata (project slug, signing policy slug, custom parameters) is transmitted to SignPath and visible in their audit logs | DF3 | **L** | Inherent to service model |

### 3.5 Denial of Service

| ID | Threat | Data Flow | Risk | Existing controls |
| --- | --- | --- | --- | --- |
| **D1** | SignPath API unavailability (outage, network partition) blocks release pipelines indefinitely; no skip-on-unavailable mode | DF3–DF5 | **M** | `retryTimeout` (default 600 s) and `waitForCompletionTimeout` bound the wait; configurable |
| **D2** | Large artifact retried many times consumes significant CI bandwidth and storage I/O; combined with a 10-retry cap, could exhaust bandwidth budgets | DF3 | **L** | Bounded by `maxRetries` and `retryTimeout` |
| **D3** | Token exhaustion via rate limiting: concurrent builds sharing a token all get HTTP 429 and spin in retry loops, delaying all pipelines | DF3–DF5 | **L** | RetryInterceptor handles 429 with configurable backoff |

### 3.6 Elevation of Privilege

| ID | Threat | Data Flow | Risk | Existing controls |
| --- | --- | --- | --- | --- |
| **E1** | `baseUrl` set to an internal service (SSRF): the tool transmits the Bearer token to an internal API endpoint accessible only from the build runner's network | DF3 | **L** | `CodesignClient(Config)` constructor rejects `http://` base URLs; only HTTPS endpoints reachable; HTTPS SSRF requires control of a certificate-backed endpoint |
| **E2** | Arbitrary file write via a compromised signed artifact download response: attacker influences what bytes are atomically written to a build-runner path | DF5, DF6 | **M** | Output path is entirely user-specified and normalised to absolute path; filename not derived from server response; TLS integrity; SHA-256 post-download audit log |
| **E3** | Plugin running with elevated CI runner privileges (e.g., `root`) performs file operations (atomic move) across the filesystem; a path traversal in `outputDirectory` configuration could overwrite system files | DF6 | **L** | `toAbsolutePath().normalize()` is applied; attacker would need control over Maven configuration |

---

## 4. Risk Summary Matrix

| ID | Category | Description | Risk | Priority |
| --- | --- | --- | --- | --- |
| S1 | Spoofing | MITM on SignPath HTTPS (token/artifact theft) | H | P1 |
| S2 | Spoofing | Token theft → impersonation of signing pipeline | H | P1 |
| S3 | Spoofing | Dependency confusion / malicious plugin JAR | H | P1 |
| T3 | Tampering | Compromised plugin release | H | P1 |
| T1 | Tampering | TOCTOU: pre-upload artifact tamper | M | P2 |
| T2 | Tampering | MITM on signed artifact download | M | P2 |
| T4 | Tampering | Credential file tampering on shared host | M | P2 |
| I1 | Info Disclosure | Token committed to version control or embedded in shared config | M | P2 |
| I2 | Info Disclosure | Token exposure via command-line argument (by design: no CLI arg accepted) | L | P3 |
| I5 | Info Disclosure | Artifact content transmitted to cloud | M | P2 |
| R2 | Repudiation | Shared token prevents attribution | M | P2 |
| D1 | Denial of Service | SignPath outage blocks release pipeline | M | P2 |
| E2 | Elevation of Privilege | Malicious download URL → file write | M | P2 |
| S4 | Spoofing | SSRF via configurable `baseUrl` | L | P3 |
| T5 | Tampering | Injected custom parameters | L | P3 |
| R1 | Repudiation | No client-side artifact hash audit log | L | P3 |
| I3 | Info Disclosure | Plaintext token in `config.properties` | L | P3 |
| I4 | Info Disclosure | HTTP response body in exception messages | L | P3 |
| I6 | Info Disclosure | Project metadata in SignPath logs | L | P3 |
| D2 | Denial of Service | Large artifact retry bandwidth exhaustion | L | P3 |
| D3 | Denial of Service | Token rate limiting across concurrent builds | L | P3 |
| E1 | Elevation of Privilege | SSRF via `baseUrl` → internal services | L | P3 |
| E3 | Elevation of Privilege | Path traversal in `outputDirectory` | L | P3 |

---

## 5. Actions by Phase

### 5.1 Planning Phase

**Objective:** Establish security requirements and governance before any new features or releases.

| # | Action | Status | Addresses |
| --- | --- | --- | --- |
| **PL-1** | **Adopt a documented security policy** for the project: define a vulnerability disclosure process (SECURITY.md), a responsible disclosure contact, and a target response SLA (e.g., critical CVE patched within 14 days). | ✅ `SECURITY.md` in place | S2, S3, T3 |
| **PL-2** | **Classify the product under CRA Annex III/IV.** Given that the plugin is a component of software development toolchains and its compromise could affect the integrity of signed artifacts distributed to end users, assess whether it qualifies as an "important product" under Annex III. Document the classification and rationale. | Open | CRA Article 6 |
| **PL-3** | **Define a minimum-viable token scope policy:** document that API tokens used with this tool should be scoped to the minimum required signing permissions in SignPath, and provide guidance on token rotation frequency. | Open | S2, R2 |
| **PL-4** | **Plan for long-term dependency maintenance:** establish a policy for reviewing and updating transitive dependencies (OkHttp, Gson, picocli) at least quarterly, with a security scanner gate in CI. | 🔶 Trivy CI gate in place; Dependabot active; quarterly policy not formalised | T3 |
| **PL-5** | **Establish a threat model review cadence:** re-run STRIDE analysis on each major version or when operational environment assumptions change (new token sources, new API endpoints, new artifact types). | 🔶 This revision constitutes the first review; cadence policy not yet formalised | All |

### 5.2 Design Phase

**Objective:** Embed security controls into the architecture before implementation.

| # | Action | Status | Addresses |
| --- | --- | --- | --- |
| **DS-1** | **Enforce HTTPS-only for `baseUrl`:** add validation in `CodesignClient` that rejects any `baseUrl` that does not start with `https://`. Throw an `IllegalArgumentException` at configuration time, not at request time. | ✅ `CodesignClient.java:73` | S1, S4, E1 |
| **DS-2** | **Design a client-side artifact hash audit record:** before uploading, compute the SHA-256 of the input artifact and log it together with the signing request ID returned by SignPath. After download, re-compute the SHA-256 of the signed artifact and log it. | ✅ `SigningWorkflow`, `CodesignMojo`, `SignCommand` | T1, T2, R1 |
| **DS-3** | **Prevent token exposure via process listings by design:** neither the CLI nor the Maven plugin accept the API token as a command-line argument. Token resolution is restricted to `settings.xml`, environment variables, and the config file. | ✅ Architecture-level control; no CLI token arg | I2 |
| **DS-4** | **`config.properties` permission guidance:** check that `~/.config/eclipse-csi-codesign/config.properties` is not readable by other users and emit a `[WARNING]` if it is. Cover both POSIX (`chmod 600`) and Windows (ACL check). The check is also applied in `CodesignMojo`. | ✅ `TokenResolver.java`, `CodesignMojo.java` | I3 |
| **DS-5** | **Design a `--skip-on-unavailable` / `skipOnSigningServiceUnavailable` option** (false by default): when enabled, if the SignPath service is unavailable after the retry window, the build continues but the artifact is not signed and a clear warning is emitted. | Open | D1 |

### 5.3 Development Phase

**Objective:** Implement security controls with verifiable quality gates.

| # | Action | Status | Addresses |
| --- | --- | --- | --- |
| **DV-1** | **HTTPS enforcement** in `CodesignClient(Config)` constructor; unit test `httpBaseUrlIsRejected` added. | ✅ | S1, S4, E1 |
| **DV-2** | **SHA-256 artifact hash logging** (pre-upload in `SigningWorkflow`; post-download in `CodesignMojo` and `SignCommand`); `SigningWorkflowTest` verifies log ordering and hash format. | ✅ | T1, T2, R1 |
| **DV-3** | **No command-line token argument**: neither `SignCommand` nor `CodesignMojo` expose a CLI/parameter path for the API token. Token resolution is tested via `CodesignMojoTest` (settings.xml, env var, config file paths) and `SignCommandIntegrationTest`. | ✅ | I2 |
| **DV-4** | **File permission check** in `TokenResolver` and `CodesignMojo`: POSIX check (`chmod 600`) + Windows ACL check (warns if any non-owner principal has `READ_DATA` access). Jimfs in-memory filesystem used in tests so checks run on all platforms without `@EnabledOnOs`/`@DisabledOnOs` guards. | ✅ | I3 |
| **DV-5** | **Trivy vulnerability scanner** added as `trivy` job in `ci-guardrails.yml`; scans for HIGH/CRITICAL unfixed vulnerabilities; uploads SARIF to GitHub Security. | ✅ | S3, T3 |
| **DV-6** | **Dependabot** for automated Maven + Actions dependency update PRs. | ✅ `dependabot.yml` in place | T3, S3 |
| **DV-7** | **Audit `CodesignException` for information leakage:** verify no token value, full request URL with embedded credentials, or other sensitive data is included in exception messages. | Open | I4 |
| **DV-8** | **Integration test for HTTPS enforcement:** verify that an `http://` `baseUrl` fails at construction time with a clear error message. | ✅ Covered by `CodesignClientTest.httpBaseUrlIsRejected` and Mojo/CLI `buildClient` override pattern | S1, S4 |
| **DV-9** | **ArchUnit rule:** no `System.out.println` or direct logging of token-related fields, to prevent future code changes from accidentally logging credential values. | Open | I1, I2 |

### 5.4 Production / Build Phase

**Objective:** Ensure the release artefacts themselves are trustworthy and verifiable.

| # | Action | Status | Addresses |
| --- | --- | --- | --- |
| **PR-1** | **CycloneDX SBOM published on every release** (via `generate-sbom` profile). Ensure the SBOM is attached to every GitHub Release and Maven Central deployment. | ✅ Already in place | T3, S3, CRA Art. 13(3) |
| **PR-2** | **Sigstore/Rekor transparency log entries for every release** (via GitHub OIDC + Sigstore in release workflow). Add a step to the release checklist to verify the Rekor entry after release. | ✅ Already in place | T3, S3 |
| **PR-3** | **SLSA Level 2+ provenance attestation for native binaries.** Verify attestations are attached to GitHub Release assets and document verification procedure. | ✅ Attestations in native build step; documentation of verification UX pending | T3 |
| **PR-4** | **All GitHub Actions pinned to full commit SHAs** (not version tags). | ✅ All workflows use `@<sha>` references | T3, S3 |
| **PR-5** | **`harden-runner` egress policy:** enable `egress-policy: block` with an allowlist limited to Maven Central, GitHub, SignPath, and Sonatype endpoints. Currently set to `audit` mode. | Open | T3 |
| **PR-6** | **Build log scan for exposed secrets** before publishing the workflow run. | Open | I1, I2 |

### 5.5 Delivery Phase

**Objective:** Enable users to verify the integrity and provenance of what they receive, and understand the security model.

| # | Action | Status | Addresses |
| --- | --- | --- | --- |
| **DL-1** | **`SECURITY.md`** documenting how to report vulnerabilities, response SLA, past CVEs, and signing identity. | ✅ Already in place | CRA Art. 13(7), S3, T3 |
| **DL-2** | **Security deployment documentation** for operators: what data is transmitted to SignPath, recommended token scoping and storage, `baseUrl` HTTPS requirement, file permission requirements for `config.properties`, how to verify the plugin/CLI signature. | ✅ `ANNEX_II_USER_INFORMATION.md` §11 | I5, I6, I3, S4 |
| **DL-3** | **Machine-readable verification instructions** (exact `cosign` or `slsa-verifier` commands) for users to verify Sigstore signature and SLSA attestation of each release. | ✅ `ANNEX_II_USER_INFORMATION.md` §11(a) | T3, S3 |
| **DL-4** | **CRA-compliant CycloneDX SBOM** alongside each release on GitHub Releases and Maven Central. | ✅ Already in place | CRA Art. 13(3) |
| **DL-5** | **CRA Article 13 technical documentation** in repository (`docs/compliance/`). | ✅ This document and `ANNEX_VII_TECHNICAL_DOCUMENTATION.md` | CRA Art. 13 |

### 5.6 Maintenance Phase

**Objective:** Sustain the security posture throughout the supported lifetime of the product.

| # | Action | Status | Addresses |
| --- | --- | --- | --- |
| **MT-1** | **Vulnerability management process:** subscribe to CVE feeds for all direct dependencies; release a patch within 14 days for CVSS ≥ 7.0 findings. | Open | T3, S3, CRA Art. 13(6) |
| **MT-2** | **Annual threat model re-assessment** or upon trigger: new major Java release, OkHttp major upgrade, new SignPath API version, new deployment pattern, new threat intelligence. | Open | All, CRA Art. 13(2) |
| **MT-3** | **Monitor SignPath API changes** for modifications to authentication schemes, TLS requirements, or response structures. Maintain a contact with SignPath security team. | Open | S1, T1, T2 |
| **MT-4** | **Scheduled integration tests against a real (test-tenant) SignPath environment** (e.g., weekly) to detect breaking API changes. | Open | S1, T2 |
| **MT-5** | **End-of-support policy published:** state which versions receive security backports and for how long (minimum 2 years after a new major version). | Open | CRA Art. 13(6) |
| **MT-6** | **Downstream user notification** via GitHub Security Advisories (GHSA) for security releases. | Open | CRA Art. 14 |
| **MT-7** | **GPG key rotation** on a defined schedule (2 years) or immediately on suspected key compromise; publish revocation certificate location. | Open | T3 |

---

## 6. Health and Safety Considerations

The codesign-maven-plugin is a **build toolchain component** and does not directly interact with health or safety systems. However, the following second-order health and safety considerations apply:

* If the tool is used to sign software deployed in **safety-critical systems** (medical devices, industrial control, automotive), a supply-chain compromise (T1, T2, T3) resulting in a maliciously signed artifact could have indirect health and safety consequences. Such deployments should implement additional controls: independent signature verification, multiple signing witnesses, and air-gapped artifact verification before deployment. The SHA-256 audit log (DS-2) strengthens traceability but does not substitute for these additional controls.
* The risk of build pipeline **denial of service** (D1) could delay security patches to safety-critical software; the `skipOnSigningServiceUnavailable` option (DS-5) should be set to `false` for safety-critical release pipelines to prevent unsigned artifacts from being accidentally shipped.

---

## 7. Summary of Prioritised Open Actions

| Priority | Open actions | Theme |
| --- | --- | --- |
| **P1 — Immediate** | DV-9 (ArchUnit credential-log rule), PR-5 (harden-runner block mode) | Prevent accidental credential exposure; harden CI supply chain |
| **P2 — Short-term** | DS-5 (skip-on-unavailable), DV-7 (exception audit), MT-1 (CVE management process), PL-2 (CRA classification), PL-3 (token scope policy) | Operational resilience; compliance posture; operator guidance |
| **P3 — Medium-term** | MT-2 (annual review), MT-5 (end-of-support policy), MT-7 (key rotation), MT-6 (GHSA notifications), PR-6 (log scanning) | Long-term CRA compliance and maintenance governance |

---

## 8. CRA Annex I Applicability and Implementation

This section fulfils the requirement of Article 13(2) of Regulation (EU) 2024/2847 (Cyber Resilience Act) to document how the essential cybersecurity requirements set out in Annex I apply to this product and how they are implemented.

### 8.1 Application of Part I, Point (1) of Annex I — General Security Principle

**Requirement (paraphrased):** Products with digital elements shall be designed, developed and produced in such a way that they ensure an appropriate level of cybersecurity based on the risks.

**Application:**

The Eclipse CSI Codesign Tools have been designed and implemented following a risk-based approach documented in Sections 2–5 of this assessment:

* A **STRIDE threat model** was applied to identify and rate 22 threats across 6 categories (§3). Each threat is rated by severity, exploitability, and breadth of impact.
* **Security controls are integrated into the design** (§5.2): HTTPS-only enforcement, immutable configuration via Java records, SHA-256 audit logging, token warning system, POSIX permission checking, and bounded retry behaviour.
* **Development security gates** (§5.3) include: unit and integration tests for each security control, ArchUnit API visibility enforcement, Trivy dependency vulnerability scanning, and Dependabot for automated dependency updates.
* **Supply chain integrity** (§5.4) is maintained through SHA-pinned GitHub Actions, GPG + Sigstore signing of all releases, SLSA provenance attestations for native binaries, and CycloneDX SBOMs.
* **Ongoing security maintenance** is planned (§5.6) with a vulnerability management process, annual threat model re-assessments, and downstream notification via GitHub Security Advisories.

The appropriate level of cybersecurity is determined relative to the identified risks: the product handles authentication credentials (Bearer tokens) that grant signing authority over software artifacts, operates in build environments that may be shared, and submits artifacts to an external cloud service. The controls in place address the highest-rated threats (H-rated: S1, S2, S3, T3) and provide detection and traceability mechanisms for medium-rated threats.

### 8.2 Applicability and Implementation of Part I, Point (2) of Annex I — Specific Security Requirements

The following table addresses each of the 12 specific requirements in Part I, point (2) of Annex I.

#### Requirement (2)(a) — No Known Exploitable Vulnerabilities at Time of Placing on Market

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | No known exploitable vulnerabilities are present in the product or its dependencies at the time of this assessment. |
| **Implementation** | Trivy CI scanner (DV-5) scans all dependencies at every build for HIGH/CRITICAL unfixed CVEs and fails the build if any are found. Dependabot (DV-6) automatically creates pull requests when dependency vulnerabilities are published. The `maven-enforcer-plugin` ensures no SNAPSHOT dependencies are included in releases. |
| **Residual risk** | Zero-day vulnerabilities in transitive dependencies (e.g., OkHttp5, Kotlin stdlib) cannot be pre-empted; the vulnerability management process (MT-1) addresses post-release response. |
| **Threat IDs** | T3, S3 |

#### Requirement (2)(b) — Secure by Default Configuration; Possibility to Reset to Original State

| Attribute | Detail |
| --- | --- |
| **Applicable** | Partially |
| **Assessment** | The product has no persistent mutable state of its own beyond user-managed configuration files. "Reset to original state" is not directly applicable; the product always reads its configuration fresh on each invocation. |
| **Implementation** | Default values are designed to be secure: the default token resolution order prioritises the environment variable (less exposed) over the CLI flag (more exposed); `signProjectArtifact=auto` avoids accidentally signing non-binary artifacts; `failOnNoFilesFound=false` emits a warning rather than silently succeeding. HTTPS enforcement is a hard default that cannot be disabled. The `CSI_CODESIGN_SKIP_SIGNING` environment variable allows signing to be skipped in development, but this is intentionally transparent (no silent skip). |
| **Threat IDs** | I1, I2, I3 |

#### Requirement (2)(c) — Protection from Unauthorised Access by Appropriate Control Mechanisms

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | The primary access control surface is the Bearer token used to authenticate with the SignPath API. The product does not itself implement access control but handles credentials through which access to an external service is controlled. |
| **Implementation** | (1) The tool resolves the token from user-controlled secure stores (environment variable, Maven `settings.xml`, config file). (2) Token source warnings (DS-3/DV-3) discourage insecure credential passing. (3) POSIX file permission checking (DS-4/DV-4) detects and warns against overly permissive `config.properties`. (4) HTTPS enforcement (DS-1/DV-1) ensures the token is only transmitted to a TLS-authenticated endpoint. |
| **Residual risk** | Credential management is ultimately the operator's responsibility; the tool provides warnings and guidance but cannot enforce policy on the user's environment. |
| **Threat IDs** | S1, S2, I1, I2, I3 |

#### Requirement (2)(d) — Confidentiality of Stored, Transmitted, or Otherwise Processed Data

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | The product transmits Bearer tokens and artifact bytes over the network to SignPath. It also handles tokens in memory and optionally via configuration files. |
| **Implementation** | (1) All network communication uses HTTPS; `CodesignClient(Config)` rejects `http://` scheme at construction time (DS-1). TLS certificate validation is performed by OkHttp5 using the JVM platform trust store. (2) The Bearer token is held in memory only for the duration of the signing operation and is not persisted by the tool. (3) No telemetry or other data is transmitted to any third party. (4) `config.properties` permission checking warns users of overly permissive file permissions (DS-4). |
| **Residual risk** | Artifact content (I5) and project metadata (I6) are inherently transmitted to SignPath as part of the signing service model. Operators must accept this as a condition of using the service. |
| **Threat IDs** | S1, I1, I2, I3, I5, I6 |

#### Requirement (2)(e) — Integrity of Stored, Transmitted, or Otherwise Processed Data

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | The integrity of the artifact being signed must be preserved from collection through upload, and the integrity of the signed artifact must be verified after download. |
| **Implementation** | (1) SHA-256 of the input artifact is computed and logged before upload in `SigningWorkflow.submitAndWait()` (DS-2/DV-2). (2) SHA-256 of the signed artifact is computed and logged after download in `CodesignMojo` and `SignCommand` (DS-2/DV-2). (3) TLS provides transport-layer integrity for DF3–DF5. (4) Atomic file write (temp file + rename) prevents partial writes from corrupting the output artifact (DF6). The logged hashes allow post-hoc detection of TOCTOU tampering by correlating with SignPath's server-side audit logs. |
| **Threat IDs** | T1, T2 |

#### Requirement (2)(f) — Minimisation of Data

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | The product transmits data to SignPath as part of its core function. Only data necessary for the signing operation is transmitted. |
| **Implementation** | Data transmitted in each request is limited to: the Bearer token (authentication), the artifact bytes (the subject of signing), and the user-specified signing coordinates (organisation ID, project slug, signing policy slug, and optional metadata parameters). No system environment information, no developer identity, and no build environment metadata are collected or transmitted beyond what the user explicitly configures via the `<parameters>` map. |
| **Residual risk** | Custom `<parameters>` values are user-controlled and may inadvertently include sensitive data; operators should review the parameters they configure. |
| **Threat IDs** | I5, I6 |

#### Requirement (2)(g) — Availability of Essential Functions; Resilience Against Denial-of-Service

| Attribute | Detail |
| --- | --- |
| **Applicable** | Partially |
| **Assessment** | The product is a build tool, not a persistent service; "availability" applies in the sense of resilience against transient failures during a signing operation. |
| **Implementation** | (1) `RetryInterceptor` retries HTTP 429/502/503/504 and connection/read timeout errors with configurable backoff, up to `maxRetries` (default 10) or `retryTimeout` (default 600 s). (2) Status polling is bounded by `waitForCompletionTimeout`. (3) HTTP 429 responses trigger backoff without spinning. (4) The `skipOnSigningServiceUnavailable` option (DS-5, open action) will allow release pipelines to continue when SignPath is unavailable, if that is the operator's preference. |
| **Residual risk** | Sustained SignPath unavailability (D1) will block release pipelines; this is inherent to a service-dependent tool. |
| **Threat IDs** | D1, D2, D3 |

#### Requirement (2)(h) — Minimise Negative Impact on Availability of Other Services or Networks

| Attribute | Detail |
| --- | --- |
| **Applicable** | Limited |
| **Assessment** | The product is a client that makes outbound HTTPS requests. Its potential to negatively impact other services is bounded by retry logic. |
| **Implementation** | (1) `maxRetries` (default 10) and `retryTimeout` (default 600 s) cap the total number of outbound requests per signing operation. (2) HTTP 429 responses are respected; the tool backs off rather than overriding rate limits. (3) The product establishes no persistent listeners, no server sockets, and no inbound connections. |
| **Threat IDs** | D2, D3 |

#### Requirement (2)(i) — Limit Attack Surfaces, Including External Interfaces

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | The product's attack surface is its configuration interface (CLI arguments, Maven parameters, environment variables, config file) and its single external network interface (the SignPath REST API). |
| **Implementation** | (1) Internal implementation classes (`RetryInterceptor`, `TokenResolver`, `SignCommand`, `SignProjectArtifact`) are package-private, preventing external instantiation or subclassing. ArchUnit tests enforce this (Annex I §2(a) above). (2) The external interface is limited to a single HTTPS endpoint (user-specified `baseUrl`), which must use the `https://` scheme. (3) No shell command execution and no dynamic class loading reduce the attack surface for injection attacks. (4) The product accepts no inbound network connections and exposes no network service. |
| **Threat IDs** | S4, E1, E2, E3 |

#### Requirement (2)(j) — Reduce the Impact of Incidents; Support Forensic Analysis

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | In the event of a security incident (compromised token, tampered artifact, malicious signing), it must be possible to determine what occurred and limit the damage. |
| **Implementation** | (1) SHA-256 audit log (pre-upload and post-download) provides a tamper-evident record of what artifact was submitted and what was received, correlatable with SignPath's server-side logs (DS-2). (2) The signing request ID and URL are logged, enabling direct lookup in the SignPath portal. (3) Build logs are preserved by CI/CD systems, providing a non-repudiable timestamped audit trail. (4) Atomic file writes ensure partial downloads do not corrupt the artifact at the output path. (5) SBOM publication enables rapid assessment of which components are affected when a transitive vulnerability is published. |
| **Threat IDs** | T1, T2, R1 |

#### Requirement (2)(k) — Security Logging and/or Monitoring, with Opt-Out Possibility, Protecting Log Integrity

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | The product emits security-relevant log entries covering credential warnings, artifact hashes, and signing request identifiers. |
| **Implementation** | The following security-relevant events are logged: (a) SHA-256 of input artifact before upload (INFO level); (b) signing request ID and URL after submission (INFO level); (c) SHA-256 of signed artifact after download (INFO level); (d) file permission warnings for `~/.config/eclipse-csi-codesign/config.properties` on POSIX and Windows (WARNING level). Log output is directed to the Maven logger (plugin) or stderr (CLI). Log level can be suppressed via Maven's `-q` flag or CLI log configuration, but this would also suppress all other output. The product does not independently manage log storage or protect log integrity — this is the responsibility of the CI/CD system and build log retention policy. |
| **Residual risk** | No opt-out mechanism specifically for security audit logging is provided; the CRA requirement for an opt-out possibility is interpreted as applying to optional monitoring features rather than mandatory audit logging. |
| **Threat IDs** | I1, I2, I3, T1, T2, R1 |

#### Requirement (2)(l) — Security Updates; Notification of Available Updates

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Assessment** | The product must be updatable to address discovered vulnerabilities, and users must be notifiable of such updates. |
| **Implementation** | (1) Security updates are released as new versions on Maven Central and GitHub Releases. (2) Users of the Maven plugin receive security updates by incrementing the `<version>` in `pom.xml`. (3) Users of the CLI download the new binary from GitHub Releases. (4) No automatic update mechanism is provided; all updates are opt-in. (5) Users are notified of security-relevant releases via GitHub repository release subscriptions and GitHub Security Advisories (GHSA) — action MT-6. (6) Dependabot automatically proposes version updates to users who have it enabled in their own repositories. |
| **Threat IDs** | T3, S3 |

### 8.3 Application of Part II of Annex I — Vulnerability Handling Requirements

The following table documents how each vulnerability handling requirement in Part II of Annex I is applied.

#### Part II, Point (1) — Identify and Document Vulnerabilities and Components (SBOM)

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | CycloneDX 1.6 SBOMs are generated for each module (`codesign-api`, `codesign-maven-plugin`, `codesign-cli`) and as an aggregate using the `generate-sbom` Maven profile. The SBOM covers all compile-scope and runtime-scope dependencies. An aggregate SBOM merging Maven dependencies and GitHub Actions workflow dependencies is also generated using `cdxgen`. SBOMs are published alongside every release on GitHub Releases and Maven Central. |
| **Status** | ✅ PR-1 in place |

#### Part II, Point (2) — Address and Remediate Vulnerabilities Without Delay; Provide Security Update Information

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | (a) Trivy CI scanner (DV-5) detects HIGH/CRITICAL unfixed CVEs in dependencies on every build. (b) Dependabot (DV-6) automatically opens dependency update PRs when new CVEs are published. (c) The target remediation SLA is: critical CVE (CVSS ≥ 9.0) patched within 7 days; high CVE (CVSS ≥ 7.0) patched within 14 days (action MT-1, open). (d) Security update releases include advisory information published via GitHub Security Advisories (MT-6). |
| **Status** | 🔶 Trivy and Dependabot in place; formal SLA policy (MT-1) open |

#### Part II, Point (3) — Regular Testing and Review of Security

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | (a) Unit tests covering each security control run on every CI build (`maven-surefire-plugin`). (b) Integration tests (WireMock-based) run on every CI build or push. (c) Trivy dependency vulnerability scan runs on every CI build. (d) zizmor and poutine workflow security analysis runs on every commit. (e) Annual threat model re-assessment is planned (MT-2, open). |
| **Status** | ✅ Automated; annual review cadence (MT-2) open |

#### Part II, Point (4) — Publicly Disclose Fixed Vulnerability Information

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | Fixed vulnerabilities are disclosed via GitHub Security Advisories (GHSA) attached to the affected repository. Advisory information includes: description of the vulnerability, affected versions, severity (CVSS score), and corrective actions. The advisory is published once a security release is available. |
| **Status** | 🔶 Process defined; MT-6 open to formalise GHSAs for security releases |

#### Part II, Point (5) — Coordinated Vulnerability Disclosure Policy

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | The project adheres to the [Eclipse Foundation Vulnerability Reporting Policy](https://www.eclipse.org/security/policy/), which defines a coordinated disclosure process. `SECURITY.md` documents the reporting channels (GitHub private vulnerability reporting, Eclipse Foundation Security Team email, confidential GitLab issue tracker) and the disclosure process. |
| **Status** | ✅ PL-1 in place |

#### Part II, Point (6) — Contact Address for Vulnerability Reporting

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | Three reporting channels are provided (see `SECURITY.md`): (1) GitHub private vulnerability reporting at the repository; (2) `security@eclipse-foundation.org`; (3) confidential issue in the Eclipse Foundation GitLab vulnerability tracker. |
| **Status** | ✅ PL-1 in place |

#### Part II, Point (7) — Mechanisms to Securely Distribute Security Updates

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | All releases are distributed via: (1) Maven Central (TLS-secured, with GPG signature verification), and (2) GitHub Releases (TLS-secured, with GPG signatures and Sigstore keyless signatures). Signature verification commands are documented in `ANNEX_II_USER_INFORMATION.md` §11(a). SLSA provenance attestations for native binaries enable supply-chain verification. |
| **Status** | ✅ PR-1–PR-4 in place |

#### Part II, Point (8) — Security Patches Free of Charge; Accompanied by Advisory Messages

| Attribute | Detail |
| --- | --- |
| **Applicable** | Yes |
| **Implementation** | The product is open source under the Eclipse Public License 2.0. All releases, including security patches, are published free of charge to Maven Central and GitHub Releases. Security releases are accompanied by a GitHub Security Advisory (GHSA) and/or release notes describing the vulnerability addressed, its severity, and the recommended remediation action (update to the patched version). |
| **Status** | ✅ Open source / free; advisory process via MT-6 |
