# Eclipse CSI Codesign

Tools for signing artifacts via the [SignPath](https://about.signpath.io/) REST API:

- **Maven Plugin** (`codesign-maven-plugin`) — integrates signing into Maven builds
- **CLI** (`codesign`) — standalone native binary for signing in any CI/CD pipeline

## CLI — Quick Start

Download the native binary for your platform from the
[Releases](https://github.com/eclipse-csi/codesign-maven-plugin/releases) page and
run:

```bash
codesign sign app.jar \
  --organization-id <ORG_ID> \
  --project-id <PROJECT_SLUG> \
  --signing-policy <POLICY_SLUG> \
  --output app-signed.jar
```

Set `CSI_CODESIGN_API_TOKEN` (or use `--api-token`) for authentication.

See [`cli/README.md`](cli/README.md) for the full CLI reference.

---

## Maven Plugin — Installation

Add the plugin to your Maven project:

```xml
<plugin>
  <groupId>org.eclipse.csi</groupId>
  <artifactId>codesign-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</plugin>
```

## Usage

```shell
mvn org.eclipse.csi:codesign-maven-plugin:sign \
  -Dcsi.codesign.organizationId=<ORG_ID> \
  -Dcsi.codesign.projectId=<PROJECT_SLUG> \
  -Dcsi.codesign.signingPolicy=<POLICY_SLUG> \
  -Dcsi.codesign.artifactConfiguration=<CONFIG_SLUG>
```

For a full configuration example, all plugin parameters, authentication options, output
modes, failure conditions, and troubleshooting guidance, see **[USAGE.md](USAGE.md)**.

## Building

```shell
# Full build (Maven plugin + CLI JVM JAR, all tests)
./mvnw clean verify

# Build the CLI JVM fat JAR only
./mvnw package -pl cli --also-make

# Build a native CLI binary (requires GraalVM with native-image)
./mvnw -Pnative package -pl cli --also-make -DskipTests
# Output: cli/target/codesign  (or codesign.exe on Windows)
```

To generate both SBOMs in one build (plugin dependencies + GitHub Actions),
enable the `generate-sbom` profile. This profile runs both generators
in the `package` phase:

```shell
./mvnw clean verify -Pgenerate-sbom
```

Prerequisites: install **Node.js** (required by npm), then **cdxgen** (`npm install -g @cyclonedx/cdxgen`) and **CycloneDX CLI** (`cyclonedx`) to generate and merge SBOMs. See [CONTRIBUTING.md](CONTRIBUTING.md) for installation details.

This writes:

- `target/bom.json` (CycloneDX Maven plugin)
- `target/bom_gha.json` (`cdxgen -t github`)
- `target/bom_all.json` (merged CycloneDX JSON)

The merged file is attached as an additional Maven artifact with classifier `bom-all`.

## Releasing (Maven Central via JReleaser)

This project includes a release pipeline based on JReleaser.

- Workflow: `.github/workflows/release.yml`
- Trigger: push a version tag (for example `v1.0.0`) or run manually via
  `workflow_dispatch`
- Build profile: `release`
- SBOMs: the `release` profile includes both generators, produces merged `target/bom_all.json`, and attaches it with classifier `bom-all`

### Required GitHub Secrets

Configure these repository/environment secrets before running the release
workflow:

- `JRELEASER_MAVENCENTRAL_USERNAME`
- `JRELEASER_MAVENCENTRAL_PASSWORD`
- `JRELEASER_GPG_PUBLIC_KEY` (ASCII-armored)
- `JRELEASER_GPG_SECRET_KEY` (ASCII-armored)
- `JRELEASER_GPG_PASSPHRASE`

### Dry Run

Use the manual workflow trigger and set `dry-run=true` to validate release
configuration without publishing.

### Local Dry Run

```shell
./mvnw -Prelease -DskipTests clean verify
./mvnw -Prelease -Djreleaser.dry.run=true jreleaser:full-release
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to contribute to this project.

## License

[Eclipse Public License - v 2.0](LICENSE)
