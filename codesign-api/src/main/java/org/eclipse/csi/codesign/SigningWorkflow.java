/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Orchestrates the full signing workflow: submit an artifact → poll until a final status is
 * reached.
 *
 * <p>After a successful call to {@link #submitAndWait}, callers should download the signed artifact
 * via {@link CodesignClient#downloadSignedArtifact(SigningRequestStatus, Path)}.
 */
public class SigningWorkflow {

  private final CodesignClient client;
  private final Duration pollInterval;
  private final Instant deadline; // null = no deadline
  private final Consumer<String> logger;

  /**
   * Creates a workflow that polls indefinitely (no completion deadline).
   *
   * @param client the SignPath API client
   * @param pollInterval how often to check signing status
   * @param logger consumer for informational progress messages; {@code null} = silent
   */
  public SigningWorkflow(CodesignClient client, Duration pollInterval, Consumer<String> logger) {
    this(client, pollInterval, null, logger);
  }

  /**
   * Creates a workflow with an optional completion timeout.
   *
   * @param client the SignPath API client
   * @param pollInterval how often to check signing status
   * @param completionTimeout maximum time to wait for a final status; {@code null} = no limit
   * @param logger consumer for informational progress messages; {@code null} = silent
   */
  public SigningWorkflow(
      CodesignClient client,
      Duration pollInterval,
      Duration completionTimeout,
      Consumer<String> logger) {
    this.client = client;
    this.pollInterval = pollInterval;
    this.deadline = completionTimeout != null ? Instant.now().plus(completionTimeout) : null;
    this.logger = logger != null ? logger : ignored -> {};
  }

  /**
   * Submits an artifact for signing and polls until a final status is reached.
   *
   * @param projectId SignPath project slug
   * @param signingPolicy SignPath signing policy slug
   * @param artifactConfiguration optional artifact configuration slug; may be {@code null}
   * @param description optional signing request description; may be {@code null}
   * @param parameters optional custom key/value parameters; may be {@code null}
   * @param artifactPath path to the artifact to sign
   * @return the final (terminal) signing request status
   * @throws CodesignException if the API fails or the deadline is exceeded before a final status is
   *     reached
   * @throws IOException on transport or thread-interruption errors
   */
  public SigningRequestStatus submitAndWait(
      String projectId,
      String signingPolicy,
      String artifactConfiguration,
      String description,
      Map<String, String> parameters,
      Path artifactPath)
      throws CodesignException, IOException {
    SigningRequest request =
        client.submit(
            projectId, signingPolicy, artifactConfiguration, description, parameters, artifactPath);
    logger.accept("Signing request submitted: " + request.statusUrl());
    return pollUntilFinal(request);
  }

  private SigningRequestStatus pollUntilFinal(SigningRequest request)
      throws CodesignException, IOException {
    while (true) {
      SigningRequestStatus status = client.getStatus(request);
      logger.accept(
          "Signing status: " + status.status() + " (workflow: " + status.workflowStatus() + ")");

      if (status.isFinalStatus()) {
        return status;
      }

      if (deadline != null && Instant.now().isAfter(deadline)) {
        throw new CodesignException(
            "Timeout waiting for signing request to complete. "
                + "Last status: "
                + status.status()
                + "/"
                + status.workflowStatus()
                + " ["
                + request.statusUrl()
                + "]",
            null);
      }

      try {
        Thread.sleep(pollInterval.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Signing poll interrupted", e);
      }
    }
  }
}
