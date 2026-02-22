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
            <goal>sign</goal>
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

1. **Plugin parameter / system property** — `<apiToken>` in the plugin configuration,
   or `-Dcsi.codesign.apiToken=<TOKEN>` on the command line.

2. **Maven `settings.xml`** — the password of the server entry whose `<id>` matches
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

3. **Environment variable** — `CSI_CODESIGN_API_TOKEN`.

If none of these are set, the build fails with an error that lists all three options.

## Selecting Files to Sign

By default the plugin signs the project's main artifact and all attached artifacts.
Use `<includes>` and `<excludes>` glob patterns (relative to `${project.build.directory}`)
to refine the selection:

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

## Skipping Signing

Signing can be skipped without modifying the POM:

```shell
mvn package -Dcsi.codesign.skip
```

Alternatively, set the environment variable `CSI_CODESIGN_SKIP_SIGNING=true` to skip
signing unconditionally (useful in CI pipelines where signing credentials are absent).

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
configuration parameters.
