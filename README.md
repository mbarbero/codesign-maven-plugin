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

## Embedded Project Example (Sign Binaries)

For regular project builds, configure the plugin in your `pom.xml` so signing
happens automatically during `package`.

This example signs `.jar`, `.exe`, and `.dmg` files produced in
`${project.build.directory}` and writes signed artifacts to
`${project.build.directory}/signed`.

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.eclipse.csi</groupId>
      <artifactId>codesign-maven-plugin</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <executions>
        <execution>
          <id>sign-binaries</id>
          <phase>package</phase>
          <goals>
            <goal>sign</goal>
          </goals>
          <configuration>
            <!-- Required SignPath coordinates -->
            <organizationId>${env.SIGNPATH_ORG_ID}</organizationId>
            <projectId>my-product</projectId>
            <signingPolicy>release-signing</signingPolicy>
            <artifactConfiguration>default-binary-config</artifactConfiguration>

            <!-- Optional metadata passed to SignPath -->
            <description>${project.artifactId} ${project.version}</description>
            <parameters>
              <buildNumber>${env.BUILD_NUMBER}</buildNumber>
              <gitCommit>${env.GIT_COMMIT}</gitCommit>
            </parameters>

            <!-- Select files to sign from ${project.build.directory} -->
            <includes>
              <include>*.jar</include>
              <include>*.exe</include>
              <include>*.dmg</include>
            </includes>
            <excludes>
              <exclude>*-sources.jar</exclude>
              <exclude>*-javadoc.jar</exclude>
            </excludes>

            <!-- Write signed files to a dedicated folder -->
            <outputDirectory>${project.build.directory}/signed</outputDirectory>

            <!-- Optional polling/HTTP tuning -->
            <pollInterval>5</pollInterval>
            <retryInterval>30</retryInterval>
            <retryTimeout>600</retryTimeout>
            <httpTimeout>300</httpTimeout>
            <connectTimeout>30</connectTimeout>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

After `mvn package`, signed binaries are available in `target/signed`.

Tip: if you omit `outputDirectory`, signed files replace the original files in
`${project.build.directory}`.

## API Token Configuration

The plugin resolves the API token in the following order (first match wins):

1. **Parameter / system property** — `-Dcsi.codesign.apiToken=<TOKEN>` or `<apiToken>` in the plugin configuration
2. **Maven `settings.xml`** — server password with ID `codesign` (or a custom ID via `csi.codesign.serverId`):

   ```xml
   <!-- ~/.m2/settings.xml -->
   <servers>
     <server>
       <id>codesign</id>
       <password>YOUR_API_TOKEN</password>
     </server>
   </servers>
   ```

   Encrypted passwords (via `mvn --encrypt-password`) are supported.

3. **Environment variable** — `CSI_CODESIGN_API_TOKEN`

If none of these are set, the build fails with an error listing all three options.

## Build Failure Conditions

The `sign` goal can fail the build in two distinct ways, or emit warnings without
failing.

### Build errors (configuration and infrastructure)

These conditions throw a `MojoExecutionException` and abort the build immediately.
They indicate a problem in the plugin configuration or in communication with the
SignPath API.

| Condition | Cause |
| --- | --- |
| No API token found | None of the three token sources (`<apiToken>`, `settings.xml`, `CSI_CODESIGN_API_TOKEN`) is configured |
| HTTP error on submit | The signing-request submission returns a non-201 status code after all retries are exhausted |
| Missing `Location` header | The SignPath API returns HTTP 201 but without a `Location` header pointing to the new request |
| HTTP error on status poll | Polling the signing-request status returns a non-2xx status code after all retries are exhausted |
| HTTP error on download | Downloading the signed artifact returns a non-2xx status code |
| No signed artifact link | The status response carries `isFinalStatus: true` and `status: Completed` but `signedArtifactLink` is `null` |
| I/O error writing signed file | A disk or file-system error occurs while writing the temporary file or performing the atomic rename |
| Directory scan error | An I/O error occurs while walking `baseDirectory` to collect files |
| Poll interrupted | The thread sleeping between status-poll attempts is interrupted |

**Retries.** Before an HTTP or network error is treated as fatal, the plugin retries
on HTTP 429, 502, 503, 504 and on connection/read timeouts. Retries stop when
either `maxRetries` attempts have been made or the `retryTimeout` window has
elapsed, whichever comes first. Once retries are exhausted the last observed error
is reported as a build error.

### Build failures (signing outcome)

These conditions throw a `MojoFailureException`. They mean that the artifact was
submitted and processed by SignPath, but signing did not complete successfully.

| Condition | Typical reason |
| --- | --- |
| `Failed` signing status | SignPath rejected the artifact (e.g. policy validation error, internal server error) |
| `Denied` signing status | A required approval step was rejected by a reviewer |
| `Canceled` signing status | The signing request was canceled before completion |
| No files found (opt-in) | No files matched the configured patterns and no project/attached artifacts were selected, with `<failOnNoFilesFound>true</failOnNoFilesFound>` |

For signing-status failures, the build log includes the `status` and
`workflowStatus` fields from the API response to aid diagnosis.

To fail the build when no files are selected for signing, set:

```xml
<failOnNoFilesFound>true</failOnNoFilesFound>
```

or pass `-Dcsi.codesign.failOnNoFilesFound=true` on the command line.

### Non-fatal conditions (warnings, no build failure)

The following situations log a warning and let the build continue successfully.

| Condition | Logged message |
| --- | --- |
| No files match the configured patterns and no project/attached artifacts are selected (default) | `[WARNING] No files selected for signing` |
| A project or attached artifact's file path does not exist on disk | `[WARNING] Skipping <type> because file does not exist: <path>` |
| Signing is skipped via `<skip>true</skip>`, `-Dcsi.codesign.skip`, `-Dcsi.codesign.skipSigning`, or `CSI_CODESIGN_SKIP_SIGNING=1\|true\|yes` | `[INFO] Signing is skipped` |

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
mvn clean verify -Pgenerate-sbom
```

Prerequisites: install **cdxgen** (`npm install -g @cyclonedx/cdxgen`) and **CycloneDX CLI** (`cyclonedx`) to generate and merge SBOMs. See [CONTRIBUTING.md](CONTRIBUTING.md) for installation details.

This writes:

- `target/bom.json` (CycloneDX Maven plugin)
- `target/bom_gha.json` (`cdxgen -t github`)
- `target/bom_all.json` (merged CycloneDX JSON)

The merged file is attached as an additional Maven artifact with classifier `bom-all`.

## Releasing (Maven Central via JReleaser)

This project includes an rlease pipeline based on JReleaser.

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
mvn -Prelease -DskipTests clean verify
mvn -Prelease -Djreleaser.dry.run=true jreleaser:full-release
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to contribute to this project.

## License

[Eclipse Public License - v 2.0](LICENSE)
