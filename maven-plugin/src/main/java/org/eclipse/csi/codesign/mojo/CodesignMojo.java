/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign.mojo;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.eclipse.csi.codesign.CodesignClient;
import org.eclipse.csi.codesign.CodesignException;
import org.eclipse.csi.codesign.SigningRequestStatus;
import org.eclipse.csi.codesign.SigningWorkflow;

/**
 * Maven goal that signs build artifacts via the SignPath REST API.
 *
 * <p>The mojo scans the configured build directory using include/exclude glob patterns, submits
 * matching files for signing, polls each request until a final status is reached, and downloads the
 * signed artifact (either in-place or to the configured output directory).
 *
 * <p>API authentication is resolved from Maven {@code settings.xml}, the {@code
 * CSI_CODESIGN_API_TOKEN} environment variable, or a config file.
 */
@Mojo(name = "codesign", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class CodesignMojo extends AbstractMojo {

  private static final String CSI_CODESIGN_API_TOKEN = "CSI_CODESIGN_API_TOKEN";
  private static final String CSI_CODESIGN_SKIP_SIGNING = "CSI_CODESIGN_SKIP_SIGNING";

  /**
   * Maven native packaging types that produce a signable binary artifact. {@code pom} is
   * intentionally absent: it has no binary output worth code-signing.
   */
  private static final Set<String> SIGNABLE_PACKAGING_TYPES =
      Set.of("jar", "war", "ear", "rar", "ejb", "maven-plugin");

  private SettingsDecrypter settingsDecrypter;

  /**
   * SignPath organization identifier.
   *
   * <p>Required. Mapped to {@code -Dcsi.codesign.organizationId}.
   */
  @Parameter(property = "csi.codesign.organizationId", required = true)
  private String organizationId;

  /**
   * Maven server ID used to resolve the API token from {@code settings.xml} server password.
   *
   * <p>Optional. Default is {@code codesign}.
   */
  @Parameter(property = "csi.codesign.serverId", defaultValue = "codesign")
  private String serverId;

  /**
   * Path to a properties file containing {@code api.token}.
   *
   * <p>Optional. Default is {@code ~/.config/eclipse-csi-codesign/config.properties}. Mapped to
   * {@code -Dcsi.codesign.configFile}.
   */
  @Parameter(
      property = "csi.codesign.configFile",
      defaultValue = "${user.home}/.config/eclipse-csi-codesign/config.properties")
  private File configFile;

  @Parameter(defaultValue = "${settings}", readonly = true)
  private Settings settings;

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;

  /**
   * SignPath project slug that owns the signing configuration.
   *
   * <p>Required. Mapped to {@code -Dcsi.codesign.projectId}.
   */
  @Parameter(property = "csi.codesign.projectId", required = true)
  private String projectId;

  /**
   * SignPath signing policy slug used for submitted artifacts.
   *
   * <p>Required. Mapped to {@code -Dcsi.codesign.signingPolicy}.
   */
  @Parameter(property = "csi.codesign.signingPolicy", required = true)
  private String signingPolicy;

  /**
   * Optional SignPath artifact configuration slug.
   *
   * <p>Mapped to {@code -Dcsi.codesign.artifactConfiguration}.
   */
  @Parameter(property = "csi.codesign.artifactConfiguration")
  private String artifactConfiguration;

  /**
   * Optional signing request description shown in SignPath.
   *
   * <p>Mapped to {@code -Dcsi.codesign.description}.
   */
  @Parameter(property = "csi.codesign.description")
  private String description;

  /**
   * SignPath API base URL.
   *
   * <p>Optional. Default targets the hosted SignPath API endpoint.
   */
  @Parameter(property = "csi.codesign.baseUrl", defaultValue = "https://app.signpath.io/Api")
  private String baseUrl;

  /**
   * Base directory scanned for input artifacts.
   *
   * <p>Optional. Defaults to {@code ${project.build.directory}}.
   */
  @Parameter(defaultValue = "${project.build.directory}")
  private String baseDirectory;

  /**
   * Include glob patterns used to select files under {@link #baseDirectory}.
   *
   * <p>Optional. If empty, no files are selected by the file scan; use {@link #signProjectArtifact}
   * or {@link #signAttachedArtifacts} to sign Maven artifacts explicitly.
   */
  @Parameter private String[] includes;

  /**
   * Exclude glob patterns applied after includes.
   *
   * <p>Optional. Has no effect when {@link #includes} is empty, since an empty {@link #includes}
   * produces no candidates for excludes to filter.
   */
  @Parameter private String[] excludes;

  /**
   * Optional output directory for signed artifacts.
   *
   * <p>If unset, signed files overwrite original files in place.
   */
  @Parameter(property = "csi.codesign.outputDirectory")
  private String outputDirectory;

  /**
   * Controls whether the Maven project's main artifact is included in signing.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.signProjectArtifact}. Accepted values: {@code
   * auto}, {@code true}, {@code false} (case-insensitive). Default is {@code auto}.
   *
   * <p>When {@code auto}, signing is enabled for packaging types that produce signable binary
   * artifacts ({@code jar}, {@code war}, {@code ear}, {@code rar}, {@code ejb}, {@code
   * maven-plugin}) and disabled for {@code pom} packaging and any other unrecognised type.
   */
  @Parameter(property = "csi.codesign.signProjectArtifact", defaultValue = "auto")
  private String signProjectArtifact;

  /**
   * Whether to include Maven attached artifacts in signing.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.signAttachedArtifacts}. Default is {@code false}.
   */
  @Parameter(property = "csi.codesign.signAttachedArtifacts", defaultValue = "false")
  private boolean signAttachedArtifacts;

  /**
   * Polling interval in seconds when checking signing request status.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.pollInterval}. Default is 5.
   */
  @Parameter(property = "csi.codesign.pollInterval", defaultValue = "5")
  private int pollInterval;

  /**
   * Delay in seconds between HTTP retry attempts.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.retryInterval}. Default is 30.
   */
  @Parameter(property = "csi.codesign.retryInterval", defaultValue = "30")
  private int retryInterval;

  /**
   * Maximum retry time window in seconds for transient HTTP failures.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.retryTimeout}. Default is 600.
   */
  @Parameter(property = "csi.codesign.retryTimeout", defaultValue = "600")
  private int retryTimeout;

  /**
   * Maximum number of retry attempts for transient HTTP failures.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.maxRetries}. Default is 10.
   */
  @Parameter(property = "csi.codesign.maxRetries", defaultValue = "10")
  private int maxRetries;

  /**
   * Read/write HTTP timeout in seconds for SignPath API calls.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.httpTimeout}. Default is 300.
   */
  @Parameter(property = "csi.codesign.httpTimeout", defaultValue = "300")
  private int httpTimeout;

  /**
   * HTTP connection timeout in seconds for SignPath API calls.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.connectTimeout}. Default is 30.
   */
  @Parameter(property = "csi.codesign.connectTimeout", defaultValue = "30")
  private int connectTimeout;

  /** Optional custom key/value parameters forwarded to SignPath in the submit request. */
  @Parameter private Map<String, String> parameters;

  /**
   * Fails the build when no files are found to sign.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.failOnNoFilesFound}. Default is {@code false}.
   */
  @Parameter(property = "csi.codesign.failOnNoFilesFound", defaultValue = "false")
  private boolean failOnNoFilesFound;

  /**
   * Skips plugin execution.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.skip}. Default is {@code false}.
   */
  @Parameter(property = "csi.codesign.skip", defaultValue = "false")
  private boolean skip;

  /**
   * Alias for skipping plugin execution.
   *
   * <p>Optional. Mapped to {@code -Dcsi.codesign.skipSigning}. Default is {@code false}.
   */
  @Parameter(property = "csi.codesign.skipSigning", defaultValue = "false")
  private boolean skipSigning;

  /**
   * Creates the mojo with an injected Maven settings decrypter.
   *
   * @param settingsDecrypter component used to decrypt server credentials from {@code settings.xml}
   */
  @Inject
  public CodesignMojo(SettingsDecrypter settingsDecrypter) {
    this.settingsDecrypter = settingsDecrypter;
  }

  /**
   * Executes the signing goal: collects artifacts, submits them to the SignPath API, polls until
   * completion, and writes the signed artifacts to the output location.
   *
   * @throws MojoExecutionException on configuration or API errors
   * @throws MojoFailureException when signing completes with a non-success status
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip || skipSigning || isSkipSigningFromEnvironment()) {
      getLog().info("Signing is skipped");
      return;
    }

    List<Path> filesToSign = collectFilesToSign();
    if (filesToSign.isEmpty()) {
      if (failOnNoFilesFound) {
        throw new MojoFailureException("No files found to sign");
      }
      getLog().warn("No files selected for signing");
      return;
    }

    String resolvedApiToken = resolveApiToken();

    CodesignClient.Config config =
        new CodesignClient.Config(
            baseUrl,
            organizationId,
            resolvedApiToken,
            Duration.ofSeconds(connectTimeout),
            Duration.ofSeconds(httpTimeout),
            Duration.ofSeconds(retryTimeout),
            Duration.ofSeconds(retryInterval),
            maxRetries);

    try (CodesignClient client = buildClient(config)) {
      for (Path filePath : filesToSign) {
        signFile(client, filePath);
      }
    }
  }

  /**
   * Creates a {@link CodesignClient} from the given configuration.
   *
   * <p>Visible for testing: subclasses (anonymous in tests) may override this to inject a pre-built
   * client backed by a mock server without triggering the HTTPS URL check.
   */
  CodesignClient buildClient(CodesignClient.Config config) {
    return new CodesignClient(config);
  }

  private List<Path> collectFilesToSign() throws MojoExecutionException {
    Set<Path> files = new LinkedHashSet<>();

    Path basePath = Path.of(baseDirectory);
    for (String relativePath : scanFiles()) {
      files.add(basePath.resolve(relativePath).toAbsolutePath().normalize());
    }

    if (resolveSignProjectArtifact() && project != null && project.getArtifact() != null) {
      addArtifactFile(files, project.getArtifact().getFile(), "project artifact");
    }

    if (signAttachedArtifacts && project != null && project.getAttachedArtifacts() != null) {
      for (var artifact : project.getAttachedArtifacts()) {
        addArtifactFile(files, artifact.getFile(), "attached artifact");
      }
    }

    return new ArrayList<>(files);
  }

  /**
   * Resolves the effective value of {@link #signProjectArtifact}.
   *
   * <p>When {@code auto}, returns {@code true} only when the project's packaging is one of the
   * Maven native types that produce a signable binary artifact.
   *
   * @return {@code true} when the project artifact should be included in signing
   * @throws MojoExecutionException if the configured value is not a recognised token
   */
  private boolean resolveSignProjectArtifact() throws MojoExecutionException {
    SignProjectArtifact mode;
    try {
      mode = SignProjectArtifact.valueOf(signProjectArtifact.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new MojoExecutionException(
          "Invalid signProjectArtifact value: '"
              + signProjectArtifact
              + "'. Expected one of: auto, true, false");
    }
    return switch (mode) {
      case TRUE -> true;
      case FALSE -> false;
      case AUTO -> project != null && SIGNABLE_PACKAGING_TYPES.contains(project.getPackaging());
    };
  }

  private void addArtifactFile(Set<Path> files, File artifactFile, String artifactType) {
    if (artifactFile == null) {
      return;
    }

    Path artifactPath = artifactFile.toPath().toAbsolutePath().normalize();
    if (!Files.isRegularFile(artifactPath)) {
      getLog().warn("Skipping " + artifactType + " because file does not exist: " + artifactPath);
      return;
    }

    files.add(artifactPath);
  }

  /**
   * Checks whether signing should be skipped based on the {@value #CSI_CODESIGN_SKIP_SIGNING}
   * environment variable.
   *
   * <p>If set to {@code 1}, {@code true}, or {@code yes} (case-insensitive), execution is skipped.
   *
   * @return {@code true} when environment-based skip is enabled, otherwise {@code false}
   */
  private boolean isSkipSigningFromEnvironment() {
    String envValue = getEnvironmentVariable(CSI_CODESIGN_SKIP_SIGNING);
    if (envValue == null || envValue.isBlank()) {
      return false;
    }

    String normalized = envValue.trim();
    boolean skipRequested =
        "1".equals(normalized)
            || "true".equalsIgnoreCase(normalized)
            || "yes".equalsIgnoreCase(normalized);

    if (skipRequested) {
      getLog().debug("Skipping signing because " + CSI_CODESIGN_SKIP_SIGNING + " is set");
    }

    return skipRequested;
  }

  /**
   * Resolves the SignPath API token in the following order:
   *
   * <ol>
   *   <li>Maven {@code settings.xml} server password for {@code serverId}
   *   <li>{@value #CSI_CODESIGN_API_TOKEN} environment variable
   *   <li>{@code api.token} key in the config file ({@code csi.codesign.configFile})
   * </ol>
   *
   * @return resolved API token
   * @throws MojoExecutionException if no token source is configured
   */
  String resolveApiToken() throws MojoExecutionException {
    if (settings != null) {
      Server server = settings.getServer(serverId);
      if (server != null) {
        String password = decryptServerPassword(server);
        if (password != null && !password.isBlank()) {
          getLog().debug("Using API token from settings.xml server '" + serverId + "'");
          return password;
        }
      }
    }

    String envToken = getEnvironmentVariable(CSI_CODESIGN_API_TOKEN);
    if (envToken != null && !envToken.isBlank()) {
      getLog().debug("Using API token from " + CSI_CODESIGN_API_TOKEN + " environment variable");
      return envToken;
    }

    if (configFile != null && configFile.canRead()) {
      Path configPath = configFile.toPath();
      checkConfigFilePermissions(configPath, msg -> getLog().warn(msg));
      Properties props = new Properties();
      try (Reader reader = Files.newBufferedReader(configPath)) {
        props.load(reader);
        String fileToken = props.getProperty("api.token");
        if (fileToken != null && !fileToken.isBlank()) {
          getLog().debug("Using API token from config file " + configFile);
          return fileToken;
        }
      } catch (IOException ignored) {
        // Cannot read the file — fall through
      }
    }

    throw new MojoExecutionException(
        "No API token found. Provide it via one of the following (in priority order):\n"
            + "  1. Server password in settings.xml with server ID '"
            + serverId
            + "'\n"
            + "  2. "
            + CSI_CODESIGN_API_TOKEN
            + " environment variable\n"
            + "  3. api.token key in "
            + configFile
            + " (override with -Dcsi.codesign.configFile)");
  }

  /**
   * Checks config file permissions and warns if the file is readable by users other than the owner.
   *
   * <p>On POSIX filesystems (Linux, macOS), checks group/other read bits and suggests {@code chmod
   * 600}. On Windows (NTFS/ReFS), checks the ACL for any non-owner {@code ALLOW} entry that grants
   * {@code READ_DATA}.
   */
  private static void checkConfigFilePermissions(Path configFile, Consumer<String> warnLogger) {
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(configFile);
      if (perms.contains(PosixFilePermission.GROUP_READ)
          || perms.contains(PosixFilePermission.OTHERS_READ)) {
        warnLogger.accept(
            "Config file "
                + configFile
                + " is readable by group or others. "
                + "Run: chmod 600 "
                + configFile);
      }
      return;
    } catch (UnsupportedOperationException ignored) {
      // Not a POSIX filesystem — fall through to Windows ACL check
    } catch (IOException ignored) {
      return;
    }

    try {
      AclFileAttributeView aclView =
          Files.getFileAttributeView(configFile, AclFileAttributeView.class);
      if (aclView == null) {
        return;
      }
      UserPrincipal owner = Files.getOwner(configFile);
      List<AclEntry> acl = aclView.getAcl();
      boolean othersCanRead =
          acl.stream()
              .filter(e -> e.type() == AclEntryType.ALLOW)
              .filter(e -> !e.principal().equals(owner))
              .anyMatch(e -> e.permissions().contains(AclEntryPermission.READ_DATA));
      if (othersCanRead) {
        warnLogger.accept(
            "Config file "
                + configFile
                + " may be readable by other users."
                + " Restrict access to the owner only.");
      }
    } catch (IOException ignored) {
      // Cannot read ACL — skip check
    }
  }

  /**
   * Returns the decrypted password for the given server definition.
   *
   * @param server Maven server definition
   * @return decrypted password, or plain password when no decrypter is available
   */
  private String decryptServerPassword(Server server) {
    if (settingsDecrypter == null) {
      return server.getPassword();
    }
    SettingsDecryptionResult result =
        settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
    for (var problem : result.getProblems()) {
      getLog().warn("Settings decryption problem: " + problem);
    }
    return result.getServer().getPassword();
  }

  /**
   * Looks up an environment variable.
   *
   * <p>Kept non-private to support tests overriding token lookup behavior.
   *
   * @param name environment variable name
   * @return environment variable value, or {@code null} if unset
   */
  String getEnvironmentVariable(String name) {
    return System.getenv(name);
  }

  /**
   * Submits a single artifact for signing, waits for completion, and writes the signed artifact to
   * the output location.
   *
   * @param client SignPath client
   * @param filePath artifact path to sign
   * @throws MojoExecutionException on API or I/O failures
   * @throws MojoFailureException when signing completes with a non-success status
   */
  private void signFile(CodesignClient client, Path filePath)
      throws MojoExecutionException, MojoFailureException {
    getLog().info("Submitting for signing: " + filePath);

    SigningWorkflow workflow =
        new SigningWorkflow(client, Duration.ofSeconds(pollInterval), msg -> getLog().info(msg));

    try {
      SigningRequestStatus status =
          workflow.submitAndWait(
              projectId, signingPolicy, artifactConfiguration, description, parameters, filePath);

      if (!status.isCompleted()) {
        throw new MojoFailureException(
            "Signing request did not complete successfully. Status: "
                + status.status()
                + ", workflow status: "
                + status.workflowStatus());
      }

      Path outputPath = resolveOutputPath(filePath);
      Path tmpPath = outputPath.resolveSibling(outputPath.getFileName() + ".signing-tmp");
      try {
        getLog().info("Downloading signed artifact to: " + outputPath);
        client.downloadSignedArtifact(status, tmpPath);
        Files.move(
            tmpPath,
            outputPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        getLog().info("Signed artifact SHA-256: " + sha256Hex(outputPath));
      } finally {
        Files.deleteIfExists(tmpPath);
      }

      getLog().info("Successfully signed: " + outputPath);
    } catch (CodesignException e) {
      throw new MojoExecutionException("SignPath API error while signing " + filePath, e);
    } catch (IOException e) {
      throw new MojoExecutionException("I/O error while signing " + filePath, e);
    }
  }

  /**
   * Resolves the output location for a signed artifact.
   *
   * @param inputPath original artifact path
   * @return target output path
   */
  private Path resolveOutputPath(Path inputPath) {
    if (outputDirectory != null) {
      return Path.of(outputDirectory).resolve(inputPath.getFileName());
    }
    return inputPath;
  }

  /**
   * Scans the base directory and returns relative paths of files matching include and exclude glob
   * filters.
   *
   * @return matching relative file paths
   * @throws MojoExecutionException if file system scanning fails
   */
  private String[] scanFiles() throws MojoExecutionException {
    Path basePath = Path.of(baseDirectory);
    if (!Files.isDirectory(basePath)) {
      return new String[0];
    }

    FileSystem fs = basePath.getFileSystem();
    List<PathMatcher> includeMatchers = toPathMatchers(fs, includes);
    List<PathMatcher> excludeMatchers = toPathMatchers(fs, excludes);

    if (includeMatchers.isEmpty()) {
      if (!excludeMatchers.isEmpty()) {
        getLog().warn("<excludes> is configured but has no effect because <includes> is empty");
      }
      return new String[0];
    }

    try (Stream<Path> stream = Files.walk(basePath)) {
      return stream
          .filter(Files::isRegularFile)
          .map(basePath::relativize)
          .filter(p -> includeMatchers.stream().anyMatch(m -> m.matches(p)))
          .filter(p -> excludeMatchers.stream().noneMatch(m -> m.matches(p)))
          .map(Path::toString)
          .toArray(String[]::new);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to scan files in " + baseDirectory, e);
    }
  }

  private static String sha256Hex(Path path) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] bytes = Files.readAllBytes(path);
      byte[] digest = md.digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      return "(unavailable)"; // SHA-256 is guaranteed by JDK spec
    }
  }

  /**
   * Converts glob pattern strings to file-system-specific path matchers.
   *
   * @param fs file system used to create matchers
   * @param patterns glob patterns (without {@code glob:} prefix)
   * @return list of path matchers, possibly empty
   */
  private static List<PathMatcher> toPathMatchers(FileSystem fs, String[] patterns) {
    List<PathMatcher> matchers = new ArrayList<>();
    if (patterns != null) {
      for (String pattern : patterns) {
        matchers.add(fs.getPathMatcher("glob:" + pattern));
      }
    }
    return matchers;
  }

  /**
   * Tri-state enum controlling whether the Maven project's main artifact is included in signing.
   *
   * <ul>
   *   <li>{@link #AUTO} enables signing for packaging types that produce signable binary artifacts
   *       and disables it otherwise.
   *   <li>{@link #TRUE} always includes the project artifact.
   *   <li>{@link #FALSE} never includes the project artifact.
   * </ul>
   */
  private static enum SignProjectArtifact {
    AUTO,
    TRUE,
    FALSE
  }
}
