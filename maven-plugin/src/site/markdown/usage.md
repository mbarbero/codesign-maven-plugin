# Usage

## Adding the Plugin

Add the plugin to your project's `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.eclipse.csi</groupId>
      <artifactId>codesign-maven-plugin</artifactId>
      <version>VERSION</version>
      <executions>
        <execution>
          <id>sign-artifacts</id>
          <phase>package</phase>
          <goals>
            <goal>codesign</goal>
          </goals>
          <configuration>
            <organizationId>${env.SIGNPATH_ORG_ID}</organizationId>
            <projectId>my-project</projectId>
            <signingPolicy>release-signing</signingPolicy>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

## API Token Configuration

The plugin resolves the API token in the following order (first match wins):

1. **Maven `settings.xml`** — the password of the server entry whose `<id>` matches
   `codesign` (or the value of `<serverId>` in the plugin configuration):

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

2. **Environment variable** — `CSI_CODESIGN_API_TOKEN`.

3. **Config file** — `api.token` key in
   `~/.config/eclipse-csi-codesign/config.properties` (override path with
   `-Dcsi.codesign.configFile`).

If none of these are set, the build fails with an error that lists all three options.

## Selecting Files to Sign

By default the plugin signs the project's main artifact when the project uses a
standard packaging type (`jar`, `war`, `ear`, `rar`, `ejb`, or `maven-plugin`); it does
**not** sign attached artifacts (e.g. `-sources.jar`, `-javadoc.jar`) unless
`<signAttachedArtifacts>true</signAttachedArtifacts>` is set explicitly. This behavior is
controlled by the `signProjectArtifact` parameter (default: `auto`).

Use `<includes>` and `<excludes>` glob patterns (relative to `${project.build.directory}`)
to additionally select arbitrary files from the build directory:

```xml
<configuration>
  <includes>
    <include>*.jar</include>
    <include>*.exe</include>
    <include>*.dmg</include>
  </includes>
  <excludes>
    <exclude>*-sources.jar</exclude>
    <exclude>*-javadoc.jar</exclude>
  </excludes>
</configuration>
```

## Output Directory

By default signed files replace the originals in `${project.build.directory}`.
To write them to a dedicated folder instead, set `<outputDirectory>`:

```xml
<configuration>
  <outputDirectory>${project.build.directory}/signed</outputDirectory>
</configuration>
```

## Controlling Project Artifact Signing

The `signProjectArtifact` parameter controls whether the Maven project's main artifact
is included in signing:

| Value | Behaviour |
| --- | --- |
| `auto` (default) | Signs the main artifact for `jar`, `war`, `ear`, `rar`, `ejb`, `maven-plugin` packaging; skips it for `pom` and all other types |
| `true` | Always includes the main artifact |
| `false` | Never includes the main artifact |

To also sign attached artifacts (sources, javadoc, etc.) set:

```xml
<signAttachedArtifacts>true</signAttachedArtifacts>
```

## Skipping Signing

Signing can be skipped without modifying the POM:

```shell
mvn package -Dcsi.codesign.skip
```

Alternatively, set the environment variable `CSI_CODESIGN_SKIP_SIGNING` to `1`, `true`,
or `yes` (case-insensitive) to skip signing unconditionally (useful in CI pipelines where
signing credentials are absent).

## Passing Additional Parameters to SignPath

Use the `<parameters>` map to attach arbitrary key/value metadata to the signing
request (e.g. build number, Git commit SHA):

```xml
<configuration>
  <parameters>
    <buildNumber>${env.BUILD_NUMBER}</buildNumber>
    <gitCommit>${env.GIT_COMMIT}</gitCommit>
  </parameters>
</configuration>
```

## Complete Goal Reference

See the [codesign:sign goal documentation](./sign-mojo.html) for the full list of
configuration parameters and their defaults.

For authentication options, output modes, retry behaviour, failure conditions, and
troubleshooting guidance, see
[USAGE.md](https://github.com/eclipse-csi/codesign-maven-plugin/blob/main/USAGE.md).
