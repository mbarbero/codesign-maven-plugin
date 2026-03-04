# Eclipse CSI Codesign Tools

Tools for signing artifacts via the [SignPath](https://about.signpath.io/) REST API:

- **Maven Plugin** (`codesign-maven-plugin`) — integrates signing into Maven builds
- **CLI** (`codesign`) — standalone native binary for signing in any CI/CD pipeline

## Quick Start

### CLI

Download the native binary for your platform from the
[Releases](https://github.com/eclipse-csi/codesign-maven-plugin/releases) page and
run:

```bash
codesign sign app.exe \
  --organization-id <ORG_ID> \
  --project-id <PROJECT_SLUG> \
  --signing-policy <POLICY_SLUG> \
  --output app-signed.exe
```

Set `CSI_CODESIGN_API_TOKEN` for authentication.

### Maven Plugin

Add the plugin to your `pom.xml`:

```xml
<plugin>
  <groupId>org.eclipse.csi</groupId>
  <artifactId>codesign-maven-plugin</artifactId>
  <version>VERSION</version>
</plugin>
```

Replace `VERSION` with the latest release from
[Maven Central](https://central.sonatype.com/artifact/org.eclipse.csi/codesign-maven-plugin).

For full installation, authentication, configuration, and troubleshooting documentation,
see **[USAGE.md](USAGE.md)**.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, build instructions, and
the release process.

## License

[Eclipse Public License - v 2.0](LICENSE)
