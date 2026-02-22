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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CodesignCliTest {
  private MockWebServer server;
  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void failsWhenNoFilesAreProvided() {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    CommandLine cmd =
        new CommandLine(new CodesignCli.SignCommand(stdout, stderr))
            .setUnmatchedArgumentsAllowed(false);

    int exitCode =
        cmd.execute(
            "--organization-id",
            "org",
            "--project-id",
            "proj",
            "--signing-policy",
            "release",
            "--artifact-configuration",
            "default",
            "--api-token",
            "token");

    assertEquals(1, exitCode);
    assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("nothing to do"));
  }

  @Test
  void failsFastWithoutForceOverwriteForMultipleInputs() throws IOException {
    Path first = tempDir.resolve("a.jar");
    Path second = tempDir.resolve("b.jar");
    Files.writeString(first, "unsigned-a");
    Files.writeString(second, "unsigned-b");

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    CommandLine cmd = new CommandLine(new CodesignCli.SignCommand(stdout, stderr));

    int exitCode =
        cmd.execute(
            "--organization-id",
            "org",
            "--project-id",
            "proj",
            "--signing-policy",
            "release",
            "--artifact-configuration",
            "default",
            "--api-token",
            "token",
            first.toString(),
            second.toString());

    assertEquals(1, exitCode);
    assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("--force-overwrite"));
  }

  @Test
  void signsSingleFileToCustomOutputPath() throws IOException {
    Path input = tempDir.resolve("app.jar");
    Path output = tempDir.resolve("signed").resolve("renamed.jar");
    Files.writeString(input, "unsigned");

    String statusUrl = server.url("/Api/v1/org/SigningRequests/1").toString();
    String signedUrl = server.url("/Api/signed/1").toString();
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(
                """
                {
                  "status": "Completed",
                  "workflowStatus": "Done",
                  "isFinalStatus": true,
                  "signedArtifactLink": "%s"
                }
                """
                    .formatted(signedUrl)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed"));

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    CommandLine cmd = new CommandLine(new CodesignCli.SignCommand(stdout, stderr));

    int exitCode =
        cmd.execute(
            "--organization-id",
            "org",
            "--project-id",
            "proj",
            "--signing-policy",
            "release",
            "--artifact-configuration",
            "default",
            "--api-token",
            "token",
            "--base-url",
            server.url("/Api").toString(),
            "--output",
            output.toString(),
            input.toString());

    assertEquals(0, exitCode);
    assertTrue(Files.exists(output));
    assertEquals("signed", Files.readString(output));
    assertEquals("unsigned", Files.readString(input));
    assertEquals("", stderr.toString(StandardCharsets.UTF_8));
  }
}
