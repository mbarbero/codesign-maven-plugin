# codesign CLI

> For a full guide covering both the CLI and the Maven plugin, see
> [USAGE.md](../USAGE.md) at the repository root.

A native command-line tool for signing artifacts via the
[SignPath](https://about.signpath.io/) REST API. Supports all common OS/CPU
targets as a single self-contained binary (no JVM required).

## Installation

### Download a native binary (recommended)

Download the pre-built binary for your platform from the
[GitHub Releases](https://github.com/eclipse-csi/codesign-maven-plugin/releases) page:

| Platform | Asset |
| --- | --- |
| Linux x86\_64 | `codesign-linux-x86_64` |
| Linux aarch64 | `codesign-linux-aarch64` |
| macOS aarch64 (Apple Silicon) | `codesign-macos-aarch64` |
| macOS x86\_64 (Intel) | `codesign-macos-x86_64` |
| Windows x86\_64 | `codesign-windows-x86_64.exe` |

Make the binary executable and place it on your `PATH`:

```bash
# Linux / macOS
chmod +x codesign-linux-x86_64
sudo mv codesign-linux-x86_64 /usr/local/bin/codesign
```

### Run from the fat JAR (JVM)

If you prefer running on the JVM:

```bash
java -jar codesign-cli-<version>-bin.jar sign --help
```

The `-bin` shaded JAR is published to Maven Central alongside the other artifacts.

## Authentication

Provide the SignPath API token via one of the following (first match wins):

1. **CLI option**: `--api-token <token>`
2. **Environment variable**: `CSI_CODESIGN_API_TOKEN`
3. **Config file** — `~/.config/codesign/config.properties`:

   ```properties
   api.token=YOUR_API_TOKEN_HERE
   ```

> **Tip**: Prefer the environment variable or config file over the CLI option to
> avoid the token appearing in shell history.

## Usage

```text
codesign sign [OPTIONS] <file> [<file>...]
```

### Required options

| Option | Description |
| --- | --- |
| `--organization-id` / `-O` | SignPath organization identifier |
| `--project-id` / `-p` | SignPath project slug |
| `--signing-policy` / `-s` | SignPath signing policy slug |

### Common options

| Option | Description |
| --- | --- |
| `--artifact-configuration` | SignPath artifact configuration slug |
| `--description` | Description shown in the signing request |
| `--param key=value` | Custom parameter (repeatable) |
| `--api-token` | API token (see Authentication above) |
| `--base-url` | SignPath API base URL (default: `https://app.signpath.io/Api`) |
| `--output` | Output file path (single-file mode only) |
| `--output-dir` | Output directory (preserves original filenames) |
| `--force-overwrite` | Allow in-place replacement of input files |
| `--wait-for-completion-timeout` | Seconds to wait for signing (default: 600) |
| `--verbose` / `-v` | Show derived timing parameters and progress |
| `--help` / `-h` | Show help |
| `--version` / `-V` | Show version |

## Examples

### Sign explicit files and write to an output directory

```bash
codesign sign app.jar installer.exe \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --artifact-configuration default \
  --output-dir signed/
```

Signed files are written to `signed/app.jar` and `signed/installer.exe`.
The originals are unchanged.

### Sign via shell glob (Unix)

```bash
codesign sign target/*.jar \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --output-dir signed/
```

> On Windows (PowerShell), expand globs explicitly:
>
> ```powershell
> $files = Get-ChildItem target\*.jar | ForEach-Object { $_.FullName }
> codesign sign @files --organization-id ... --output-dir signed\
> ```

### Sign a single file to an explicit output path

```bash
codesign sign dist/myapp-1.0.exe \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --output dist/myapp-1.0-signed.exe
```

The output file can be in any directory and may have a different name.

### In-place replacement (requires `--force-overwrite`)

```bash
# Single file
codesign sign app.jar \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --force-overwrite

# Multiple files
codesign sign app.jar installer.exe \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --force-overwrite
```

The signed artifact is first downloaded to a temporary file in the same
directory as the target, then moved atomically to replace the original.

### Passing custom parameters

```bash
codesign sign app.jar \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --param buildNumber=42 \
  --param gitCommit=abc1234 \
  --description "Release v1.2.3" \
  --output app-signed.jar
```

### Token from environment variable

```bash
export CSI_CODESIGN_API_TOKEN=your-token-here
codesign sign app.jar \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --output app-signed.jar
```

### Token from config file

Create `~/.config/codesign/config.properties`:

```properties
api.token=your-token-here
```

Then omit `--api-token` from the command:

```bash
codesign sign app.jar \
  --organization-id my-org \
  --project-id my-product \
  --signing-policy release-signing \
  --output app-signed.jar
```

## Timeout and polling

A single `--wait-for-completion-timeout <seconds>` (default: `600`) controls the
maximum time the CLI waits for signing to complete. All other timing parameters
(poll interval, HTTP timeouts, retry limits) are derived automatically:

| Parameter | Formula | Example (600 s) |
| --- | --- | --- |
| Poll interval | `clamp(timeout/20, 2s, 30s)` | 30 s |
| Retry interval | `clamp(timeout/20, 2s, 30s)` | 30 s |
| HTTP read/write timeout | `clamp(timeout/10, 10s, 60s)` | 60 s |
| Connect timeout | `min(30s, timeout)` | 30 s |
| Retry timeout | `max(10s, timeout/4)` | 150 s |

The number of retries is fixed at **5** and is not configurable via the CLI.

Use `--verbose` to see the derived values for a given timeout.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | All files signed successfully |
| `1` | Execution error (API failure, signing denied/failed/canceled, I/O error) |
| `2` | Bad arguments (missing required option, invalid flag combination) |

## Building from source

```bash
# JVM fat JAR
./mvnw package -pl cli --also-make

# Native binary (requires GraalVM with native-image)
./mvnw -Pnative package -pl cli --also-make -DskipTests
```

The native binary is written to `codesign-cli/target/codesign` (or `codesign.exe` on Windows).
