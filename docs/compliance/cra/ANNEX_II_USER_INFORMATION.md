# Information and Instructions to the User

* **Product:** Eclipse CSI Codesign Tools (`codesign-api`, `codesign-maven-plugin`, `codesign-cli`)
* **Document version:** 1.0
* **Date:** 2026-03-04
* **Regulation:** Regulation (EU) 2024/2847 (Cyber Resilience Act), Annex II / Article 10(8)

---

## 1. Manufacturer Contact Information

* **Organisation:** Eclipse Foundation AISBL
* **Registered address:** Avenue de Tervueren 64, 1040 Etterbeek, Belgium
* **General website:** <https://www.eclipse.org/>
* **Project website:** <https://projects.eclipse.org/projects/technology.csi>
* **Source repository:** <https://github.com/eclipse-csi/codesign-maven-plugin>

For security matters, use the contact in §2. For general development and usage questions,
use the mailing list: <https://accounts.eclipse.org/mailing-list/csi-dev>

---

## 2. Single Point of Contact for Security Matters

| Channel | Address |
| --- | --- |
| GitHub private vulnerability reporting | <https://github.com/eclipse-csi/codesign-maven-plugin/security/advisories/new> |
| Eclipse Foundation Security Team | <security@eclipse-foundation.org> |
| Eclipse Foundation confidential tracker | <https://gitlab.eclipse.org/security/vulnerability-reports/-/issues/new?issuable_template=new_vulnerability> |

**Please do not report security vulnerabilities through public issues, discussions, or
change requests.** See `SECURITY.md` for the full reporting policy and the information
to include in a vulnerability report.

---

## 3. Product Identification

| Component | Maven group:artifact | Distribution channel | Type |
| --- | --- | --- | --- |
| API library | `org.eclipse.csi:codesign-api` | Maven Central | JAR |
| Maven plugin | `org.eclipse.csi:codesign-maven-plugin` | Maven Central | JAR |
| CLI (JVM) | `org.eclipse.csi:codesign-cli` | Maven Central | Fat JAR (`-bin` classifier) |
| CLI (native) | `org.eclipse.csi:codesign-cli` | GitHub Releases | Native binary + archive |

The current released version is available at:

* Maven Central: <https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin>
* GitHub Releases: <https://github.com/eclipse-csi/codesign-maven-plugin/releases>

All three components share a single version number. See §7 for version identification.

---

## 4. Intended Purpose and Security Environment

**Intended purpose:**

Eclipse CSI Codesign Tools are **software build-toolchain components** that enable software
development teams to submit binary artifacts — JAR files, native executables (EXE, DMG),
and other signed formats — to the [SignPath](https://about.signpath.io/) REST API for
cryptographic code signing. The toolset:

* Integrates into Maven build lifecycles (Maven plugin) to sign artifacts automatically
  during the build.
* Provides a standalone native binary CLI (`codesign`) for signing in any CI/CD pipeline
  or shell script, regardless of build system.
* Exposes a Java API library (`codesign-api`) for programmatic use from any JVM application.

**Intended security environment:**

The product is intended to be operated in:

* Automated CI/CD build environments (e.g., GitHub Actions, Jenkins, GitLab CI) with
  controlled network access and secrets management.
* Developer workstations for manual release preparation, with appropriate credential
  management.

**Not intended for:**

* Direct use by end users of signed software (this is a build-time developer tool).
* Environments where the runner cannot securely store the SignPath API token.
* Signing of artifacts for safety-critical systems without additional controls (see §5
  and `RISK_ASSESSMENT.md` §6).

The product acts exclusively as an **outbound client** to the SignPath REST API. It does
not accept inbound network connections, expose any network service, or interact with the
operating system beyond reading configuration files, reading the artifact file to sign,
and writing the signed artifact to an output path.

---

## 5. Known and Foreseeable Risks

The following risks have been identified through the cybersecurity risk assessment (see
[`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md)). This section summarises the risks that
are most relevant for operators deploying the product. The complete STRIDE analysis,
risk ratings, and mitigation actions are documented in the risk assessment.

### High-priority risks

| Risk | Description | Mitigation |
| --- | --- | --- |
| **Token theft (S2)** | If the SignPath API Bearer token is exposed (e.g., in CI logs or committed configuration), an attacker can use it to sign malicious artifacts under the affected organisation's identity. | Use `CSI_CODESIGN_API_TOKEN` environment variable, `settings.xml` (plugin), or `~/.config/eclipse-csi-codesign/config.properties` to store the token securely. Never commit the token to version control. |
| **Supply chain attack (S3, T3)** | A compromised or counterfeit version of the plugin or CLI could sign malicious artifacts or exfiltrate credentials. | Pin the plugin to an exact released version. Verify the Sigstore signature and SLSA provenance attestation before deploying a new version (see §11(a)). |
| **MITM on signing traffic (S1)** | An attacker intercepting network traffic between the build environment and SignPath could steal the Bearer token or substitute signed artifacts. | HTTPS is enforced by the product; `http://` base URLs are rejected at startup. TLS certificate validation is performed by default using the JVM trust store. |

### Medium-priority risks

| Risk | Description | Mitigation |
| --- | --- | --- |
| **Artifact transmitted to cloud (I5)** | The artifact being signed is transmitted to SignPath's cloud infrastructure. | Inherent to the signing service model. Operators handling highly sensitive artifacts should review SignPath's data processing terms and consider on-premises SignPath deployments. |
| **TOCTOU artifact tampering (T1)** | An attacker with access to the build environment could modify the artifact file between the SHA-256 hash computation and the upload. | The SHA-256 of the artifact is logged before upload. Any discrepancy can be detected by comparing the build log with SignPath's server-side audit records. |
| **Token exposure in process list (I2)** | Neither the CLI nor the Maven plugin accept the API token as a command-line argument, eliminating token exposure via process listings. | Use `CSI_CODESIGN_API_TOKEN` environment variable, `settings.xml` (plugin), or `~/.config/eclipse-csi-codesign/config.properties` to supply the token. |

### Residual risks accepted by operators

The following risks are inherent to the product's use of the SignPath cloud service and
cannot be eliminated by the product itself:

* **Artifact content transmitted to SignPath (I5):** Accepted condition of using a
  cloud signing service.
* **Dependence on SignPath availability (D1):** The product provides configurable
  timeouts and retry logic, but cannot sign artifacts when SignPath is unavailable.

For the complete risk matrix, see [`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md) §4.

---

## 6. Compatibility Requirements

### Maven Plugin

| Requirement | Minimum version |
| --- | --- |
| Java runtime | 21 (LTS) |
| Apache Maven | 3.9 |
| Network | Outbound HTTPS to `app.signpath.io` (or configured `baseUrl`) |
| Operating system | Linux, macOS, Windows (any OS with Java 21 and Maven 3.9) |

### CLI — Native Binary

| Platform | Binary archive name |
| --- | --- |
| Linux x86\_64 | `codesign-linux-x86_64.tar.gz` |
| Linux aarch64 | `codesign-linux-aarch_64.tar.gz` |
| macOS Apple Silicon (aarch64) | `codesign-osx-aarch_64.tar.gz` |
| macOS Intel (x86\_64) | `codesign-osx-x86_64.tar.gz` |
| Windows x86\_64 | `codesign-windows-x86_64.zip` |

No Java installation is required for the native binary. Outbound HTTPS access to the
configured signing endpoint is required.

### CLI — JVM Fat JAR

| Requirement | Minimum version |
| --- | --- |
| Java runtime | 21 (LTS) |
| Network | Outbound HTTPS to `app.signpath.io` (or configured `baseUrl`) |

### API Library

| Requirement | Minimum version |
| --- | --- |
| Java runtime | 21 (LTS) |
| Build system | Maven 3.9 or Gradle (any version supporting Maven Central resolution) |

---

## 7. Version Information

The product version is specified as `MAJOR.MINOR.PATCH` following Semantic Versioning
2.0.0. All three components (`codesign-api`, `codesign-maven-plugin`, `codesign-cli`)
share the same version number in any given release.

**How to identify the version in use:**

| Component | Method |
| --- | --- |
| Maven plugin | `<version>` element in `pom.xml`; or `mvn help:effective-pom` |
| CLI native binary | `codesign --version` |
| CLI fat JAR | `java -jar codesign-cli-<version>-bin.jar --version` |
| API library | Maven artifact version in `pom.xml` / `build.gradle` |

The full version history is documented in [`CHANGELOG.md`](../../CHANGELOG.md) and on
the [GitHub Releases](https://github.com/eclipse-csi/codesign-maven-plugin/releases) page.

---

## 8. How to Install Updates

**Maven Plugin:**

Update the `<version>` value in your `pom.xml` to the latest released version and rebuild.
Maven automatically resolves the updated JAR from Maven Central over HTTPS. Before
updating, verify the new release (see §11(a)).

```xml
<plugin>
  <groupId>org.eclipse.csi</groupId>
  <artifactId>codesign-maven-plugin</artifactId>
  <version>NEW_VERSION</version>  <!-- update this -->
</plugin>
```

**CLI native binary:**

Download the new binary archive for your platform from
[GitHub Releases](https://github.com/eclipse-csi/codesign-maven-plugin/releases), verify
its authenticity (see §11(a)), extract it, and replace the existing binary on your `PATH`.

**CLI JVM fat JAR:**

Download the new `-bin.jar` from GitHub Releases or Maven Central, verify its authenticity,
and replace the existing JAR.

**Update notifications:**

Subscribe to release notifications via **GitHub Watch → "Releases only"** on the
[repository page](https://github.com/eclipse-csi/codesign-maven-plugin). Security-relevant
releases are additionally published as GitHub Security Advisories visible in the
"Security" tab of the repository.

---

## 9. Expected Product Lifetime and End of Support

Security updates are provided for the **latest released version only**. Vulnerability
fixes are not routinely backported to earlier versions (see `SECURITY.md`).

The project intends to maintain active security support for at least **2 years after a new
major version is released** (see action MT-5 in [`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md)).
The end-of-support date for any given major version will be communicated via the GitHub
Releases page and the project mailing list with a minimum of 3 months' notice.

Given the product's use in automated CI/CD pipelines, operators should subscribe to
release notifications (§8) and establish a process to update the plugin or CLI version
within 30 days of a security release.

---

## 10. Support After End of Support

After the end of the supported lifetime of a specific version:

* The source code remains publicly available under the
  [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/), allowing the
  community or downstream users to fork and maintain it independently.
* GitHub Issues and pull requests may continue to be accepted at the committers'
  discretion, but no security support commitments apply.
* Operators relying on an end-of-life version for security-critical pipelines should plan
  migration to the current supported version or evaluate maintaining a supported fork.

---

## 11. Cybersecurity-Relevant User Instructions

### (a) Secure Installation and Artifact Verification

**Step 1 — Pin the version.**

Pin the Maven plugin to an exact version (not a range, not `LATEST`). In `pom.xml`:

```xml
<plugin>
  <groupId>org.eclipse.csi</groupId>
  <artifactId>codesign-maven-plugin</artifactId>
  <version>1.0.0</version>  <!-- exact version, not [1.0,) -->
</plugin>
```

**Step 2 — Verify the release signature before deploying a new version.**

Maven plugin JARs and CLI fat JARs are signed with Sigstore keyless signing. Verify using
[`cosign`](https://github.com/sigstore/cosign):

```bash
# Verify a Maven plugin JAR downloaded from Maven Central
cosign verify-blob \
  --bundle codesign-maven-plugin-VERSION.jar.sigstore.json \
  codesign-maven-plugin-VERSION.jar
```

Native CLI binaries include SLSA provenance attestations. Verify using
[`slsa-verifier`](https://github.com/slsa-framework/slsa-verifier):

```bash
slsa-verifier verify-artifact \
  --provenance-path codesign-linux-x86_64.intoto.jsonl \
  --source-uri github.com/eclipse-csi/codesign-maven-plugin \
  codesign-linux-x86_64
```

GPG signatures (`.asc` files) are also provided for each release artifact and can be
verified against the project's published GPG public key.

**Step 3 — Verify the SBOM.**

The CycloneDX SBOM attached to each release lists all runtime dependencies. Review the
SBOM to confirm no components with known vulnerabilities are included before deploying
in a high-assurance environment.

### (b) Security Features Provided by the Product

The product provides the following security-relevant features:

| Feature | Description | Output |
| --- | --- | --- |
| **HTTPS enforcement** | `http://` base URLs are rejected at startup with a clear error message | `IllegalArgumentException` at build/startup time |
| **No command-line token argument** | Neither the CLI nor the Maven plugin accept the API token as a command-line argument, by design. Token resolution uses environment variables, `settings.xml`, or a config file only. | Architecture-level control; no log output |
| **Token file permission check** | On POSIX systems, warns if the config file is group- or world-readable. On Windows, warns if any non-owner principal has read access (ACL check). Default path: `~/.config/eclipse-csi-codesign/config.properties`. | `[WARNING]` to stderr |
| **Pre-upload SHA-256 logging** | SHA-256 of the input artifact is computed and logged before upload | `[INFO]` in build log / stderr |
| **Post-download SHA-256 logging** | SHA-256 of the signed artifact is computed and logged after download | `[INFO]` in build log / stderr |
| **Signing request audit trail** | Signing request ID and URL are logged after submission | `[INFO]` in build log / stderr |

To use these security features effectively:

* **Preserve build logs** as part of your release audit trail. Build log entries can be
  correlated with SignPath's server-side audit logs to detect discrepancies.
* **Compare SHA-256 values** logged before upload and after download with the hashes
  recorded in the SignPath portal to verify artifact integrity.

### (c) How to Disable Cybersecurity Features

| Feature | Disable mechanism | Recommendation |
| --- | --- | --- |
| Entire signing step | Set `CSI_CODESIGN_SKIP_SIGNING=1` (or `true`/`yes`) | Use only in development builds; never in release pipelines |
| HTTPS enforcement | **Cannot be disabled.** The `https://` scheme requirement is hardcoded. | N/A |
| Token file permission check | No disable mechanism; the check is informational only | N/A |
| SHA-256 audit logging | No disable mechanism | N/A |
| Token source warnings | No disable mechanism | N/A |

Maven's global log level (`-q`, `--quiet`) will suppress all plugin output including
security warnings; this should not be used in release pipelines where audit logging
is required.

### (d) Restoring Secure Configuration

If the product has been used in an insecure configuration, take the following remediation
steps before the next use:

**Token committed to version control or embedded in a shared configuration file:**

1. Immediately rotate the SignPath API token in the SignPath portal.
2. Remove the token from the repository history or shared configuration.
3. Distribute the token only via `CSI_CODESIGN_API_TOKEN` environment variable, `settings.xml`
   (plugin), or `~/.config/eclipse-csi-codesign/config.properties` on the individual runner.

**`config.properties` with insecure permissions:**

1. Run: `chmod 600 ~/.config/eclipse-csi-codesign/config.properties`
2. Rotate the API token if the file was world-readable and the host is shared.

**Unsigned artifact accidentally shipped (e.g., due to `CSI_CODESIGN_SKIP_SIGNING=1`
being set unexpectedly):**

1. Identify all published artifacts that were not signed.
2. Re-run the signing workflow and re-publish signed versions through your normal
   distribution channel.
3. If the unsigned artifacts were already distributed to users, notify them and provide
   signed replacements.

### (e) Recommended Technical Measures to Improve Security

Operators should implement the following additional security measures beyond the product's
built-in controls:

1. **Use per-pipeline API tokens.** Create a separate SignPath API token for each CI/CD
   pipeline or signing policy. This enables attribution of each signing operation to a
   specific pipeline and limits blast radius when a token is compromised.

2. **Rotate API tokens regularly.** Rotate SignPath API tokens at least every 90 days or
   immediately after any suspected compromise.

3. **Store tokens in secrets management.** Use GitHub Actions encrypted secrets,
   HashiCorp Vault, or an equivalent system to inject `CSI_CODESIGN_API_TOKEN`. Never
   hard-code the token in `pom.xml`, workflow YAML, or committed configuration files.

4. **Pin and verify the plugin version on every update.** Before updating the plugin
   version in production pipelines, download the new release, verify its Sigstore
   signature (§11(a)), review the SBOM and changelog, and test in a non-production
   pipeline first.

5. **Restrict CI/CD runner network egress.** Configure the runner's firewall or network
   policy to allow outbound HTTPS only to `app.signpath.io` (or the configured `baseUrl`)
   and Maven Central (`repo.maven.apache.org`). This limits the impact of an SSRF
   vulnerability and reduces data exfiltration risk.

6. **Enable `failOnNoFilesFound=true` in production signing pipelines.** This prevents
   a misconfiguration from silently skipping signing and shipping unsigned artifacts.

7. **Monitor SignPath audit logs.** Review the signing request history in the SignPath
   portal regularly to detect any unauthorised or unexpected signing activity.

8. **Enable SBOM generation.** Activate the `generate-sbom` Maven profile in your
   release pipeline to produce a CycloneDX SBOM covering the artifacts you are building
   and signing.

9. **Set a minimum approved version policy.** If your organisation manages multiple
   Maven projects, consider using Maven Enforcer rules or a company-wide BOM to ensure
   all projects use a version of `codesign-maven-plugin` that meets your minimum security
   patch level.

### (f) Connectivity and Data Transmitted

The product establishes only the following outbound network connections. No inbound
connections are accepted and no network service is exposed.

| Connection | Protocol | Endpoint | Data transmitted (outbound) | Data received (inbound) |
| --- | --- | --- | --- | --- |
| Artifact upload — submit signing request | HTTPS POST | `app.signpath.io/v1/{orgId}/SigningRequests` | Bearer token (Authorization header), artifact bytes (multipart body), organisation ID, project slug, signing policy slug, optional signing description and metadata parameters | HTTP 201 + Location header containing signing request URL and ID |
| Status polling | HTTPS GET | `app.signpath.io/v1/{orgId}/SigningRequests/{id}` | Bearer token (Authorization header), signing request ID (URL path) | JSON body: signing status (`Pending`, `InProgress`, `Completed`, `Failed`, `Denied`, `Canceled`), signed artifact download link |
| Signed artifact download | HTTPS GET | URL returned by SignPath (same host) | Bearer token (Authorization header) | Signed artifact bytes |
| Plugin resolution (Maven plugin only) | HTTPS GET | Maven Central (`repo.maven.apache.org`) | Maven coordinates in request URL | Plugin JAR, POM, SHA checksums, GPG `.asc` signature, Sigstore `.sigstore.json` bundle |

**Data summary:**

* The artifact file to be signed is transmitted in its entirety to SignPath.
* The organisation ID, project slug, and signing policy slug identify the SignPath
  signing context and appear in the SignPath audit log.
* Optional `<parameters>` / `--param` values are user-defined and also transmitted to
  SignPath.
* No data is transmitted to Eclipse Foundation or to any party other than SignPath (and
  Maven Central for plugin resolution).

**Custom `baseUrl`:** If the `baseUrl` configuration parameter is set to a non-default
value, requests are directed to that endpoint instead of `app.signpath.io`. The `https://`
scheme is always enforced.

---

## 12. Product Identification

The product can be identified via:

* **Maven Central:** <https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin>
* **GitHub Releases:** <https://github.com/eclipse-csi/codesign-maven-plugin/releases>
* **CLI version flag:** `codesign --version` (native binary or fat JAR)
* **Maven effective POM:** `mvn help:effective-pom` shows the resolved plugin version
  in the build

---

## 13. Applicable Cybersecurity Standards and Conditions

The product is developed in alignment with the following standards, frameworks, and policies:

| Standard / Framework | Application |
| --- | --- |
| Eclipse Foundation Vulnerability Reporting Policy | Governing policy for coordinated vulnerability disclosure (§2) |
| SLSA Supply-chain Levels for Software Artifacts | Supply chain integrity for native binary releases (provenance attestations) |
| CycloneDX 1.6 | SBOM format for all released components |
| Sigstore / Rekor | Keyless signing of all release artifacts; Rekor transparency log entries |
| Semantic Versioning 2.0.0 | Version numbering for all releases |
| STRIDE threat modelling | Methodology used for the cybersecurity risk assessment |
| BSI TR-03183-1 | Reference methodology informing the risk assessment approach |
| Eclipse Public License 2.0 | Open-source licence for all product source code |
| Eclipse Contributor Agreement | IP governance for all contributions |

No harmonised standards or common specifications formally designated under CRA
Articles 27 or 28 have been applied to date. The product's conformity assessment period
begins upon formal product classification under Article 6 of Regulation (EU) 2024/2847
(see action PL-2 in [`RISK_ASSESSMENT.md`](./RISK_ASSESSMENT.md)).
