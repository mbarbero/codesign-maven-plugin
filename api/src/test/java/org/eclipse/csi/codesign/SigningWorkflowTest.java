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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningWorkflowTest {

  private MockWebServer server;
  private CodesignClient client;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();

    OkHttpClient httpClient = new OkHttpClient();
    client =
        new CodesignClient(httpClient, server.url("/Api").toString(), "test-org-id", "test-token");
  }

  @AfterEach
  void tearDown() throws Exception {
    client.close();
    server.shutdown();
  }

  @Test
  void preUploadSha256IsLoggedBeforeSubmit() throws Exception {
    String statusUrl = server.url("/Api/v1/test-org-id/SigningRequests/42").toString();
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "status": "Completed",
                  "workflowStatus": "Done",
                  "isFinalStatus": true,
                  "signedArtifactLink": null
                }
                """));

    Path artifact = tempDir.resolve("artifact.jar");
    Files.writeString(artifact, "test-artifact-content");

    List<String> logLines = new ArrayList<>();
    SigningWorkflow workflow = new SigningWorkflow(client, Duration.ofMillis(10), logLines::add);

    workflow.submitAndWait("proj", "policy", null, null, null, artifact);

    // Verify that a SHA-256 line was logged before the "Signing request submitted" line
    int sha256Index = -1;
    int submittedIndex = -1;
    for (int i = 0; i < logLines.size(); i++) {
      String line = logLines.get(i);
      if (line.startsWith("Artifact SHA-256 (pre-upload): ")) {
        sha256Index = i;
      }
      if (line.startsWith("Signing request submitted: ")) {
        submittedIndex = i;
      }
    }

    assertTrue(sha256Index >= 0, "Expected SHA-256 log line");
    assertTrue(submittedIndex >= 0, "Expected 'Signing request submitted' log line");
    assertTrue(sha256Index < submittedIndex, "SHA-256 must be logged before submit confirmation");

    String sha256Line = logLines.get(sha256Index);
    String hash = sha256Line.substring("Artifact SHA-256 (pre-upload): ".length());
    assertTrue(
        hash.matches("[0-9a-f]{64}"), "Expected 64-character hex SHA-256 hash, got: " + hash);
  }
}
