/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.eclipse.csi.codesign.CodesignClient;
import org.eclipse.csi.codesign.CodesignException;
import org.eclipse.csi.codesign.SigningRequestStatus;
import org.eclipse.csi.codesign.SigningWorkflow;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Signs one or more artifact files via the SignPath REST API.
 *
 * <h2>Output modes</h2>
 *
 * <ul>
 *   <li><b>Single file + {@code --output}</b>: write the signed artifact to the given path. If
 *       {@code --output} resolves to the same path as the input, {@code --force-overwrite} is
 *       required.
 *   <li><b>Any number of files + {@code --output-dir}</b>: write each signed artifact to the
 *       directory, preserving the original filename.
 *   <li><b>In-place</b> (no {@code --output} or {@code --output-dir}): replace original files
 *       atomically. Requires {@code --force-overwrite} as explicit opt-in.
 * </ul>
 *
 * <h2>Token resolution (in priority order)</h2>
 *
 * <ol>
 *   <li>{@code --api-token}
 *   <li>{@code CSI_CODESIGN_API_TOKEN} environment variable
 *   <li>{@code api.token} key in {@code ~/.config/codesign/config.properties}
 * </ol>
 */
@Command(
    name = "sign",
    description = "Sign one or more artifact files via the SignPath REST API.",
    sortOptions = false,
    usageHelpAutoWidth = true,
    mixinStandardHelpOptions = true)
class SignCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  // --- Input files ---

  @Parameters(
      arity = "1..*",
      paramLabel = "<file>",
      description =
          "One or more files to sign (e.g. app.jar installer.exe)."
              + " On Unix shells, glob patterns are expanded by the shell.")
  List<Path> inputFiles;

  // --- Required SignPath coordinates ---

  @Option(
      names = {"--organization-id", "-O"},
      required = true,
      paramLabel = "<id>",
      description = "SignPath organization identifier.")
  String organizationId;

  @Option(
      names = {"--project-id", "-p"},
      required = true,
      paramLabel = "<slug>",
      description = "SignPath project slug.")
  String projectId;

  @Option(
      names = {"--signing-policy", "-s"},
      required = true,
      paramLabel = "<slug>",
      description = "SignPath signing policy slug.")
  String signingPolicy;

  @Option(
      names = "--artifact-configuration",
      paramLabel = "<slug>",
      description = "SignPath artifact configuration slug (optional).")
  String artifactConfiguration;

  // --- Optional metadata ---

  @Option(
      names = "--description",
      paramLabel = "<text>",
      description = "Description shown in the SignPath signing request (optional).")
  String description;

  @Option(
      names = "--param",
      paramLabel = "<key>=<value>",
      description =
          "Custom parameter forwarded to SignPath (repeatable)."
              + " Format: --param key=value --param key2=value2")
  Map<String, String> parameters;

  // --- Authentication ---

  @Option(
      names = "--api-token",
      paramLabel = "<token>",
      description =
          "SignPath API token. Falls back to ${CSI_CODESIGN_API_TOKEN} environment variable,"
              + " then to api.token in ~/.config/codesign/config.properties.")
  String apiToken;

  // --- Output ---

  @Option(
      names = "--output-dir",
      paramLabel = "<dir>",
      description =
          "Directory where signed artifacts are written, preserving original filenames."
              + " Cannot be combined with --output.")
  Path outputDir;

  @Option(
      names = "--output",
      paramLabel = "<file>",
      description =
          "Output path for the signed artifact."
              + " Only valid when a single input file is provided."
              + " Cannot be combined with --output-dir.")
  Path output;

  @Option(
      names = "--force-overwrite",
      description =
          "Allow in-place replacement of input files."
              + " Required when neither --output nor --output-dir is specified.")
  boolean forceOverwrite;

  // --- Timeout ---

  @Option(
      names = "--wait-for-completion-timeout",
      paramLabel = "<seconds>",
      defaultValue = "600",
      description =
          "Maximum seconds to wait for signing to complete (default: ${DEFAULT-VALUE})."
              + " Poll interval, HTTP timeouts, and retry limits are derived automatically from"
              + " this value.")
  int waitForCompletionTimeout;

  // --- API ---

  @Option(
      names = "--base-url",
      paramLabel = "<url>",
      defaultValue = "https://app.signpath.io/Api",
      description = "SignPath API base URL (default: ${DEFAULT-VALUE}).")
  String baseUrl;

  // --- Verbosity ---

  @Option(
      names = {"--verbose", "-v"},
      description = "Enable verbose output (shows derived timing parameters and download progress).")
  boolean verbose;

  @Override
  public Integer call() throws Exception {
    validateFiles();
    validateOutputOptions();

    String resolvedToken = TokenResolver.resolve(apiToken);
    if (resolvedToken == null) {
      throw new ParameterException(
          spec.commandLine(),
          "No API token found. Provide it via one of the following (in priority order):\n"
              + "  1. --api-token <token>\n"
              + "  2. CSI_CODESIGN_API_TOKEN environment variable\n"
              + "  3. api.token key in ~/.config/codesign/config.properties");
    }

    int timeout = Math.max(1, waitForCompletionTimeout);
    Duration pollInterval = derivePollInterval(timeout);
    Duration httpTimeout = deriveHttpTimeout(timeout);
    Duration connectTimeout = Duration.ofSeconds(Math.min(30, timeout));
    Duration retryTimeout = Duration.ofSeconds(Math.max(10, timeout / 4));
    Duration retryInterval = Duration.ofSeconds(Math.min(30, Math.max(2, timeout / 20)));
    int maxRetries = 5;

    if (verbose) {
      PrintWriter out = spec.commandLine().getOut();
      out.printf("poll-interval:   %ds%n", pollInterval.toSeconds());
      out.printf("http-timeout:    %ds%n", httpTimeout.toSeconds());
      out.printf("connect-timeout: %ds%n", connectTimeout.toSeconds());
      out.printf("retry-timeout:   %ds%n", retryTimeout.toSeconds());
      out.printf("retry-interval:  %ds%n", retryInterval.toSeconds());
      out.printf("max-retries:     %d%n", maxRetries);
      out.flush();
    }

    CodesignClient.Config config =
        new CodesignClient.Config(
            baseUrl,
            organizationId,
            resolvedToken,
            connectTimeout,
            httpTimeout,
            retryTimeout,
            retryInterval,
            maxRetries);

    try (CodesignClient client = new CodesignClient(config)) {
      SigningWorkflow workflow =
          new SigningWorkflow(
              client,
              pollInterval,
              Duration.ofSeconds(timeout),
              msg -> spec.commandLine().getOut().println(msg));

      for (Path inputFile : inputFiles) {
        signFile(client, workflow, inputFile);
      }
    }

    return 0;
  }

  private void signFile(CodesignClient client, SigningWorkflow workflow, Path inputFile)
      throws CodesignException, IOException {
    Path absInput = inputFile.toAbsolutePath().normalize();

    if (verbose) {
      spec.commandLine().getOut().println("Signing: " + absInput);
    }

    SigningRequestStatus status =
        workflow.submitAndWait(
            projectId, signingPolicy, artifactConfiguration, description, parameters, absInput);

    if (!status.isCompleted()) {
      throw new RuntimeException(
          "Signing did not complete successfully for '"
              + absInput.getFileName()
              + "'. Status: "
              + status.status()
              + ", workflow: "
              + status.workflowStatus());
    }

    Path outputPath = resolveOutputPath(inputFile);

    // Ensure the output directory exists (e.g. --output-dir may point to a new dir)
    Files.createDirectories(outputPath.getParent());

    // Download to a temp file in the same directory as the destination → atomic move
    Path tmpPath = outputPath.resolveSibling(outputPath.getFileName() + ".codesign-tmp");
    try {
      if (verbose) {
        spec.commandLine().getOut().println("Downloading signed artifact → " + outputPath);
      }
      client.downloadSignedArtifact(status, tmpPath);
      try {
        Files.move(
            tmpPath, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        // Fallback for cross-filesystem moves or platforms that don't support atomic rename
        Files.move(tmpPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmpPath);
    }

    spec.commandLine().getOut().println("Signed: " + outputPath);
  }

  private Path resolveOutputPath(Path inputFile) {
    if (inputFiles.size() == 1 && output != null) {
      return output.toAbsolutePath().normalize();
    }
    if (outputDir != null) {
      return outputDir.toAbsolutePath().normalize().resolve(inputFile.getFileName());
    }
    return inputFile.toAbsolutePath().normalize(); // in-place
  }

  private void validateFiles() {
    List<String> missing = new ArrayList<>();
    for (Path f : inputFiles) {
      if (!Files.isRegularFile(f)) {
        missing.add(f.toString());
      }
    }
    if (!missing.isEmpty()) {
      throw new ParameterException(
          spec.commandLine(),
          "File(s) not found or not regular files: " + String.join(", ", missing));
    }
  }

  private void validateOutputOptions() {
    if (inputFiles.size() > 1) {
      if (output != null) {
        throw new ParameterException(
            spec.commandLine(),
            "--output cannot be used when multiple input files are provided;"
                + " use --output-dir instead.");
      }
      if (outputDir == null && !forceOverwrite) {
        throw new ParameterException(
            spec.commandLine(),
            "Multiple input files require either --output-dir <dir>"
                + " or --force-overwrite for in-place replacement.");
      }
    } else {
      // Single file
      if (output != null && outputDir != null) {
        throw new ParameterException(
            spec.commandLine(), "--output and --output-dir are mutually exclusive.");
      }
      Path absInput = inputFiles.get(0).toAbsolutePath().normalize();
      boolean samePathAsInput =
          output != null && output.toAbsolutePath().normalize().equals(absInput);
      boolean isInPlace = output == null && outputDir == null;

      if (samePathAsInput && !forceOverwrite) {
        throw new ParameterException(
            spec.commandLine(),
            "--output '"
                + output
                + "' resolves to the same path as the input file."
                + " Use --force-overwrite to allow in-place replacement.");
      }
      if (isInPlace && !forceOverwrite) {
        throw new ParameterException(
            spec.commandLine(),
            "No output specified. Use --output <path>, --output-dir <dir>,"
                + " or --force-overwrite for in-place replacement.");
      }
    }
  }

  /**
   * Derives the poll interval from the overall completion timeout.
   *
   * <p>Formula: {@code clamp(timeout / 20, 2, 30)} seconds. Examples:
   *
   * <ul>
   *   <li>30s timeout → 2s poll interval (minimum)
   *   <li>300s timeout → 15s poll interval
   *   <li>600s timeout → 30s poll interval (maximum)
   * </ul>
   */
  static Duration derivePollInterval(int timeoutSeconds) {
    long seconds = Math.max(2L, Math.min(30L, timeoutSeconds / 20));
    return Duration.ofSeconds(seconds);
  }

  /**
   * Derives the HTTP read/write timeout from the overall completion timeout.
   *
   * <p>Formula: {@code clamp(timeout / 10, 10, 60)} seconds.
   */
  static Duration deriveHttpTimeout(int timeoutSeconds) {
    long seconds = Math.max(10L, Math.min(60L, timeoutSeconds / 10));
    return Duration.ofSeconds(seconds);
  }
}
