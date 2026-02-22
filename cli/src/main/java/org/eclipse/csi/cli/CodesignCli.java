/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.eclipse.csi.codesign.CodesignClient;
import org.eclipse.csi.codesign.CodesignException;
import org.eclipse.csi.codesign.SigningRequest;
import org.eclipse.csi.codesign.SigningRequestStatus;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "codesign",
    mixinStandardHelpOptions = true,
    subcommands = CodesignCli.SignCommand.class)
public class CodesignCli implements Runnable {

  public static void main(String[] args) {
    int exitCode = new CommandLine(new CodesignCli()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }

  @Command(name = "sign", mixinStandardHelpOptions = true)
  static class SignCommand implements Callable<Integer> {
    private static final String CSI_CODESIGN_API_TOKEN = "CSI_CODESIGN_API_TOKEN";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(600);
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 10;

    @Option(names = "--organization-id", required = true)
    private String organizationId;

    @Option(names = "--project-id", required = true)
    private String projectId;

    @Option(names = "--signing-policy", required = true)
    private String signingPolicy;

    @Option(names = "--artifact-configuration", required = true)
    private String artifactConfiguration;

    @Option(names = "--api-token")
    private String apiToken;

    @Option(names = "--base-url", defaultValue = "https://app.signpath.io/Api")
    private String baseUrl;

    @Option(names = "--description")
    private String description;

    @Option(names = "--param")
    private List<String> parameterEntries;

    @Option(names = "--output-dir")
    private Path outputDir;

    @Option(names = "--output")
    private Path outputFile;

    @Option(names = "--force-overwrite", defaultValue = "false")
    private boolean forceOverwrite;

    @Option(names = "--wait-for-completion-timeout", defaultValue = "1800")
    private long waitForCompletionTimeout;

    @Parameters(paramLabel = "FILES")
    private List<Path> inputFiles;

    private final PrintWriter out;
    private final PrintWriter err;

    SignCommand() {
      this(System.out, System.err);
    }

    SignCommand(java.io.OutputStream out, java.io.OutputStream err) {
      this.out = new PrintWriter(out, true);
      this.err = new PrintWriter(err, true);
    }

    @Override
    public Integer call() {
      try {
        execute();
        return 0;
      } catch (Exception e) {
        err.println("Error: " + e.getMessage());
        return 1;
      }
    }

    private void execute() throws IOException, CodesignException {
      if (inputFiles == null || inputFiles.isEmpty()) {
        throw new IllegalArgumentException("No files selected for signing (nothing to do).");
      }
      if (waitForCompletionTimeout <= 0) {
        throw new IllegalArgumentException("--wait-for-completion-timeout must be greater than 0.");
      }

      List<Path> normalizedInputs =
          inputFiles.stream().map(this::normalizeAbsolute).map(this::validateInputFile).toList();
      Map<Path, Path> outputMapping = resolveOutputMapping(normalizedInputs);
      Map<String, String> parameters = parseParameters();
      String resolvedToken = resolveApiToken();

      CodesignClient.Config config =
          new CodesignClient.Config(
              baseUrl,
              organizationId,
              resolvedToken,
              CONNECT_TIMEOUT,
              HTTP_TIMEOUT,
              RETRY_TIMEOUT,
              RETRY_INTERVAL,
              MAX_RETRIES);

      try (CodesignClient client = new CodesignClient(config)) {
        for (Path inputFile : normalizedInputs) {
          signSingleFile(client, inputFile, outputMapping.get(inputFile), parameters);
        }
      }
    }

    private void signSingleFile(
        CodesignClient client, Path inputFile, Path outputFilePath, Map<String, String> parameters)
        throws IOException, CodesignException {
      out.println("Submitting for signing: " + inputFile);
      SigningRequest signingRequest =
          client.submit(
              projectId, signingPolicy, artifactConfiguration, description, parameters, inputFile);
      SigningRequestStatus finalStatus = pollUntilFinal(client, signingRequest);
      if (!finalStatus.isCompleted()) {
        throw new IllegalStateException(
            "Signing request did not complete successfully. Status: "
                + finalStatus.status()
                + ", workflow status: "
                + finalStatus.workflowStatus());
      }
      downloadAndReplaceAtomically(client, finalStatus, outputFilePath);
      out.println("Successfully signed: " + outputFilePath);
    }

    private SigningRequestStatus pollUntilFinal(CodesignClient client, SigningRequest signingRequest)
        throws IOException, CodesignException {
      Instant deadline = Instant.now().plusSeconds(waitForCompletionTimeout);
      while (true) {
        SigningRequestStatus status = client.getStatus(signingRequest);
        out.println(
            "Signing status: " + status.status() + " (workflow: " + status.workflowStatus() + ")");
        if (status.isFinalStatus()) {
          return status;
        }
        if (Instant.now().isAfter(deadline)) {
          throw new IllegalStateException(
              "Signing request did not reach a final status within --wait-for-completion-timeout.");
        }
        try {
          Thread.sleep(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Polling interrupted", e);
        }
      }
    }

    private void downloadAndReplaceAtomically(
        CodesignClient client, SigningRequestStatus status, Path outputPath)
        throws IOException, CodesignException {
      Path parent = outputPath.toAbsolutePath().normalize().getParent();
      if (parent == null) {
        parent = Path.of(".").toAbsolutePath().normalize();
      }
      Files.createDirectories(parent);
      String prefix = outputPath.getFileName().toString() + ".";
      Path tempFile = Files.createTempFile(parent, prefix, ".signing-tmp");
      try {
        client.downloadSignedArtifact(status, tempFile);
        try {
          Files.move(
              tempFile,
              outputPath,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tempFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(tempFile);
      }
    }

    private Map<Path, Path> resolveOutputMapping(List<Path> normalizedInputs) {
      if (normalizedInputs.size() > 1 && outputFile != null) {
        throw new IllegalArgumentException("--output is only supported when signing exactly one file.");
      }
      if (outputFile != null && outputDir != null) {
        throw new IllegalArgumentException("Use either --output or --output-dir, not both.");
      }

      Map<Path, Path> mapping = new LinkedHashMap<>();
      if (outputFile != null) {
        Path inputPath = normalizedInputs.getFirst();
        Path resolvedOutput = normalizeAbsolute(outputFile);
        if (resolvedOutput.equals(inputPath) && !forceOverwrite) {
          throw new IllegalArgumentException(
              "--output resolves to the input file. Use --force-overwrite for in-place replacement.");
        }
        mapping.put(inputPath, resolvedOutput);
        return mapping;
      }

      if (outputDir != null) {
        Path resolvedOutputDir = normalizeAbsolute(outputDir);
        for (Path inputPath : normalizedInputs) {
          mapping.put(inputPath, resolvedOutputDir.resolve(inputPath.getFileName()));
        }
        return mapping;
      }

      if (!forceOverwrite) {
        throw new IllegalArgumentException(
            "In-place replacement requires explicit opt-in. Pass --force-overwrite or set --output-dir.");
      }
      for (Path inputPath : normalizedInputs) {
        mapping.put(inputPath, inputPath);
      }
      return mapping;
    }

    private Map<String, String> parseParameters() {
      Map<String, String> parameters = new LinkedHashMap<>();
      if (parameterEntries == null) {
        return parameters;
      }
      for (String entry : parameterEntries) {
        int separator = entry.indexOf('=');
        if (separator <= 0 || separator == entry.length() - 1) {
          throw new IllegalArgumentException("Invalid --param value: '" + entry + "'. Expected key=value.");
        }
        parameters.put(entry.substring(0, separator), entry.substring(separator + 1));
      }
      return parameters;
    }

    private String resolveApiToken() {
      if (apiToken != null && !apiToken.isBlank()) {
        return apiToken;
      }
      String envToken = System.getenv(CSI_CODESIGN_API_TOKEN);
      if (envToken != null && !envToken.isBlank()) {
        return envToken;
      }
      throw new IllegalArgumentException(
          "No API token found. Provide --api-token or set CSI_CODESIGN_API_TOKEN.");
    }

    private Path validateInputFile(Path inputPath) {
      if (!Files.isRegularFile(inputPath)) {
        throw new IllegalArgumentException("Input file does not exist: " + inputPath);
      }
      return inputPath;
    }

    private Path normalizeAbsolute(Path path) {
      return path.toAbsolutePath().normalize();
    }
  }
}
