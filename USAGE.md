# Usage Guide

This guide covers both tools in the Eclipse CSI Codesign project:

- **Maven Plugin** (`codesign-maven-plugin`) — integrates artifact signing into Maven builds
- **CLI** (`codesign`) — standalone binary for signing in any pipeline or shell

> For contributor and developer documentation, see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Installation

### Maven Plugin

Add the plugin to your `pom.xml`. No additional installation is required — Maven resolves it
from Maven Central.

**Minimum requirements:** Java 21, Maven 3.9.

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.eclipse.csi</groupId>
      <artifactId>codesign-maven-plugin</artifactId>
      <version>VERSION</version>
    </plugin>
  </plugins>
</build>
```

Replace `VERSION` with the latest release from
[Maven Central](https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin).

### CLI

Download the pre-built native binary for your platform from the
[GitHub Releases](https://github.com/eclipse-csi/codesign-maven-plugin/releases) page.

| Platform | Binary name |
| --- | --- |
| Linux x86\_64 | `codesign-linux-x86_64` |
| Linux aarch64 | `codesign-linux-aarch64` |
| macOS Apple Silicon | `codesign-macos-aarch64` |
| macOS Intel | `codesign-macos-x86_64` |
| Windows x86\_64 | `codesign-windows-x86_64.exe` |

```bash
# Linux / macOS: make executable and place on PATH
chmod +x codesign-linux-x86_64
sudo mv codesign-linux-x86_64 /usr/local/bin/codesign
```

**JVM alternative:** if you prefer running on the JVM, the `-bin` fat JAR is published to Maven
Central alongside the native binaries:

```bash
java -jar codesign-cli-<version>-bin.jar sign --help
```

---

## Authentication

Both tools authenticate against the SignPath REST API using a Bearer API token.

### Maven Plugin Authentication

The plugin resolves the token in this order (first non-blank value wins):

1. **Plugin parameter / system property** — `<apiToken>` in the plugin `<configuration>`, or
   `-Dcsi.codesign.apiToken=<TOKEN>` on the command line.

2. **Maven `settings.xml`** — password of the server entry whose `<id>` matches `codesign` (or
   the value of the `<serverId>` parameter):

   ```xml
   <!-- ~/.m2/settings.xml -->
   <servers>
     <server>
       <id>codesign</id>
       <password>YOUR_API_TOKEN</password>
     </server>
   </servers>
   ```

   Passwords encrypted with `mvn --encrypt-password` are supported.

3. **Environment variable** — `CSI_CODESIGN_API_TOKEN`.

If none are set, the build fails with an error listing all three options.

> **Security note:** Prefer `settings.xml` or the environment variable over embedding the
> token in the POM or passing it on the command line, to avoid exposing it in build logs
> and version control.

### CLI Authentication

The CLI resolves the token in this order (first non-blank value wins):

1. **`--api-token <token>`** CLI option.
2. **`CSI_CODESIGN_API_TOKEN`** environment variable.
3. **Config file** — `api.token` key in `~/.config/codesign/config.properties`:

   ```properties
   api.token=YOUR_API_TOKEN_HERE
   ```

If none are set, the CLI prints a descriptive error and exits with code 2.

> **Security note:** Prefer the environment variable or config file over `--api-token` to avoid
> the token appearing in shell history.

---

## Quickstart

### CLI: Sign a file

```bash
export CSI_CODESIGN_API_TOKEN=your-token-here

codesign sign app.jar \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --output app-signed.jar
```

The original `app.jar` is unchanged; `app-signed.jar` contains the signed binary.

### Plugin: Ad-hoc invocation

Run signing from the command line without modifying your `pom.xml`:

```shell
mvn org.eclipse.csi:codesign-maven-plugin:codesign \
  -Dcsi.codesign.organizationId=my-org \
  -Dcsi.codesign.projectId=my-product \
  -Dcsi.codesign.signingPolicy=release-signing
```

The main artifact of the current project is submitted for signing and downloaded in place
(assuming standard `jar`/`war`/etc. packaging; see [File Selection](#file-selection-plugin)).

### Plugin: Embedded in the build lifecycle

Configure the plugin in `pom.xml` to sign files automatically during `package`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.eclipse.csi</groupId>
      <artifactId>codesign-maven-plugin</artifactId>
      <version>VERSION</version>
      <executions>
        <execution>
          <id>sign-binaries</id>
          <phase>package</phase>
          <goals>
            <goal>codesign</goal>
          </goals>
          <configuration>
            <!-- Required SignPath coordinates -->
            <organizationId>${env.SIGNPATH_ORG_ID}</organizationId>
            <projectId>my-product</projectId>
            <signingPolicy>release-signing</signingPolicy>
            <!-- artifactConfiguration is optional -->
            <artifactConfiguration>default-binary-config</artifactConfiguration>

            <!-- Optional metadata -->
            <description>${project.artifactId} ${project.version}</description>
            <parameters>
              <buildNumber>${env.BUILD_NUMBER}</buildNumber>
              <gitCommit>${env.GIT_COMMIT}</gitCommit>
            </parameters>

            <!-- Select files from ${project.build.directory} -->
            <includes>
              <include>*.jar</include>
              <include>*.exe</include>
              <include>*.dmg</include>
            </includes>
            <excludes>
              <exclude>*-sources.jar</exclude>
              <exclude>*-javadoc.jar</exclude>
            </excludes>

            <!-- Write signed files to a separate folder (omit to replace in place) -->
            <outputDirectory>${project.build.directory}/signed</outputDirectory>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

After `mvn package`, signed files are available in `target/signed/`.

---

## Configuration Reference

Reference documentation is published on the [project site](https://eclipse-csi.github.io/codesign-maven-plugin/):

- **Maven Plugin parameters**: [`codesign:sign` goal reference](https://eclipse-csi.github.io/codesign-maven-plugin/latest/maven-plugin/sign-mojo.html)
- **CLI options**: [CLI man page](https://eclipse-csi.github.io/codesign-maven-plugin/latest/cli-manpage/)
- **Java API**: [API Javadoc](https://eclipse-csi.github.io/codesign-maven-plugin/latest/api-javadoc/)

> Documentation for past releases and for snapshot (built from the `main` branch) are
> [available](https://eclipse-csi.github.io/codesign-maven-plugin/).

### Environment Variables

| Variable | Tool | Description |
| --- | --- | --- |
| `CSI_CODESIGN_API_TOKEN` | Both | SignPath API token. Lower priority than `--api-token` / `<apiToken>`; higher priority than the CLI config file. |
| `CSI_CODESIGN_SKIP_SIGNING` | Plugin only | Set to `1`, `true`, or `yes` (case-insensitive) to skip signing unconditionally. |

---

## Behavior and Guarantees

### File Selection (Plugin)

Two independent mechanisms are combined; their results are merged and deduplicated before
signing:

1. **File scan** — walks `baseDirectory` recursively, matching files against `<includes>` and
   `<excludes>` glob patterns. Patterns are matched against paths relative to `baseDirectory`.
   The scan only runs when `<includes>` contains at least one pattern; `<excludes>` has no
   effect when `<includes>` is empty.

2. **Maven artifact signing** — controlled by `signProjectArtifact` and `signAttachedArtifacts`:
   - `signProjectArtifact=auto` (default): signs the main artifact for `jar`, `war`, `ear`,
     `rar`, `ejb`, and `maven-plugin` packaging; **skips it for `pom` packaging and all other
     unrecognised types**.
   - `signProjectArtifact=true`: always includes the main artifact.
   - `signProjectArtifact=false`: never includes the main artifact.
   - `signAttachedArtifacts=false` (default): attached artifacts are **not** signed.
   - `signAttachedArtifacts=true`: all attached artifacts are included.

When the combined result is empty, the plugin logs `[WARNING] No files selected for signing`
and exits without calling the SignPath API. Set `<failOnNoFilesFound>true</failOnNoFilesFound>`
to turn this into a build failure instead.

### Output Modes

**Maven Plugin:**

- `<outputDirectory>` set → each signed file is written to `<outputDirectory>/<filename>`.
- `<outputDirectory>` not set → each signed file replaces the original in place. The download
  is first written to a temporary file (`.signing-tmp` suffix, same directory as the target),
  then moved atomically to replace the original.

**CLI:**

| Condition | Output location |
| --- | --- |
| `--output <path>` (single input file) | Written to the specified path |
| `--output-dir <dir>` (any number of files) | `<dir>/<original-filename>` for each file |
| Neither `--output` nor `--output-dir`, with `--force-overwrite` | In-place replacement (same directory, atomic rename) |

In all cases the download is first written to a `.codesign-tmp` temporary file in the same
directory as the destination. On platforms where atomic rename across filesystems is
unsupported, the CLI falls back to a non-atomic copy.

### Retry and Timeout

**Maven Plugin** — configured explicitly:

| Parameter | Default | Description |
| --- | --- | --- |
| `pollInterval` | 5 s | Delay between signing-status poll requests |
| `retryTimeout` | 600 s | Maximum time window for HTTP retries |
| `retryInterval` | 30 s | Delay between HTTP retry attempts |
| `maxRetries` | 10 | Maximum number of HTTP retry attempts |
| `httpTimeout` | 300 s | HTTP read/write timeout |
| `connectTimeout` | 30 s | HTTP connect timeout |

**CLI** — all derived from `--wait-for-completion-timeout` (default: 600 s):

| Parameter | Formula | Example (600 s) |
| --- | --- | --- |
| Poll interval | `clamp(timeout/20, 2 s, 30 s)` | 30 s |
| HTTP read/write timeout | `clamp(timeout/10, 10 s, 60 s)` | 60 s |
| Connect timeout | `min(30 s, timeout)` | 30 s |
| Retry timeout | `max(10 s, timeout/4)` | 150 s |
| Retry interval | `clamp(timeout/20, 2 s, 30 s)` | 30 s |
| Max retries | fixed | 5 |

Use `--verbose` to see the derived values for your chosen timeout.

HTTP retries are triggered by status codes 429, 502, 503, 504, and connection/read
timeouts. Retries stop when either the retry count or the retry time window is exhausted,
whichever comes first.

### Signing Failure Modes

#### Plugin — build errors (`MojoExecutionException`; build aborts immediately)

| Condition | Cause |
| --- | --- |
| No API token found | None of the three token sources is configured |
| HTTP error on submit | Non-201 response after all retries exhausted |
| Missing `Location` header | API returned HTTP 201 without a `Location` header |
| HTTP error on status poll | Non-2xx response after all retries exhausted |
| HTTP error on download | Non-2xx response when downloading the signed artifact |
| No signed artifact link | Status is `Completed` but `signedArtifactLink` is `null` |
| I/O error writing signed file | Disk/filesystem error during temp-file write or atomic rename |
| Directory scan error | I/O error while walking `baseDirectory` |
| Poll interrupted | Thread interrupted during the sleep between status polls |

#### Plugin — build failures (`MojoFailureException`; signing processed but unsuccessful)

| Status | Typical reason |
| --- | --- |
| `Failed` | Policy validation error or SignPath internal error |
| `Denied` | A required approval step was rejected by a reviewer |
| `Canceled` | Signing request was canceled before completion |
| No files found (`failOnNoFilesFound=true`) | No files matched the configured selection |

#### CLI — exit codes

| Code | Meaning |
| --- | --- |
| `0` | All files signed successfully |
| `1` | Execution error (API failure, signing denied/failed/canceled, I/O error) |
| `2` | Bad arguments (missing required option, incompatible flags) |

---

## Troubleshooting

**"No API token found"**
No token source is configured. Set `CSI_CODESIGN_API_TOKEN` in the environment, add a
`codesign` server to `~/.m2/settings.xml` (plugin), or pass `--api-token` / `<apiToken>`.

**HTTP 401 Unauthorized**
The token is invalid, expired, or does not have permission to submit signing requests for
the specified organization. Verify the token in the SignPath portal.

**Signing status `Failed`**
SignPath rejected the artifact — usually a policy violation (wrong file type, policy
mismatch) or a SignPath internal error. Check the `workflowStatus` field printed in the
build/CLI output. Review the signing policy and artifact configuration in the SignPath
portal.

**Signing status `Denied`**
A human approver rejected the signing request. This is expected when a policy requires
manual approval and the reviewer denied it.

**Signing status `Canceled`**
The signing request was canceled (e.g. via the SignPath portal) before it completed.

**"No files selected for signing" warning**
Neither the file scan nor the Maven artifact selection produced any candidates.

- Verify that `<includes>` patterns match files actually present in `${project.build.directory}`.
- Check that `signProjectArtifact` is not `false` when you expect the main artifact to be signed.
- For `pom`-packaging projects (aggregator modules), `signProjectArtifact=auto` (the default)
  produces no artifact. Either set `<signProjectArtifact>false</signProjectArtifact>` to
  suppress the warning, or remove the plugin configuration for that module entirely.

**Signing is skipped unexpectedly**
Check whether `CSI_CODESIGN_SKIP_SIGNING` is set to `1`, `true`, or `yes` in the
environment. Also check `<skip>true</skip>` in the POM or `-Dcsi.codesign.skip` on the
command line.

**Timeout waiting for signing completion**
Signing took longer than the configured timeout. Increase `--wait-for-completion-timeout`
(CLI) or `<retryTimeout>` (plugin). Also check the SignPath portal — the request may be
waiting for manual approval.

**"File(s) not found or not regular files" (CLI)**
The files passed as positional arguments do not exist at the given paths. Check paths and
shell glob expansion. On Windows PowerShell, expand globs manually:

```powershell
$files = Get-ChildItem target\*.jar | ForEach-Object { $_.FullName }
codesign sign @files --organization-id ... --output-dir signed\
```

**`--output` rejected when multiple input files are given**
`--output` is only valid for a single input file. Use `--output-dir` when signing multiple
files at once.

**"No output specified … use --force-overwrite" (CLI)**
When neither `--output` nor `--output-dir` is specified, the CLI requires `--force-overwrite`
as an explicit opt-in for in-place replacement. The same applies when `--output` points to
the same path as the input file.
