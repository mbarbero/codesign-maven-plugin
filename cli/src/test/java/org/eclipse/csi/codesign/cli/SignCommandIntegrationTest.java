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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.eclipse.csi.codesign.CodesignClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Integration tests for {@link SignCommand} that exercise the full signing workflow against a
 * {@link MockWebServer}.
 */
class SignCommandIntegrationTest {

  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json";

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

  /**
   * A test-only SignCommand that overrides buildClient to use MockWebServer (HTTP) without
   * triggering the HTTPS enforcement check in CodesignClient(Config).
   */
  private final SignCommand testSignCommand =
      new SignCommand() {
        @Override
        String resolveToken() {
          return "test-token";
        }

        @Override
        CodesignClient buildClient(CodesignClient.Config config) {
          try {
            Constructor<CodesignClient> ctor =
                CodesignClient.class.getDeclaredConstructor(
                    OkHttpClient.class, String.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(
                new OkHttpClient(), config.baseUrl(), config.organizationId(), config.apiToken());
          } catch (Exception e) {
            throw new RuntimeException("Test client creation failed", e);
          }
        }
      };

  private CommandLine cli() {
    // Use picocli IFactory to inject the test SignCommand instance so that MockWebServer
    // (HTTP-only) bypasses the HTTPS enforcement in CodesignClient(Config).
    return new CommandLine(
            new CodesignCli(),
            new CommandLine.IFactory() {
              @Override
              @SuppressWarnings("unchecked")
              public <K> K create(Class<K> cls) throws Exception {
                if (cls == SignCommand.class) {
                  return (K) testSignCommand;
                }
                return CommandLine.defaultFactory().create(cls);
              }
            })
        .setOut(new PrintWriter(new StringWriter()))
        .setErr(new PrintWriter(new StringWriter()))
        .setExecutionExceptionHandler(new PrintExceptionMessageHandler());
  }

  private String statusResponse(String downloadUrl) {
    return """
    {
      "status": "Completed",
      "workflowStatus": "Done",
      "isFinalStatus": true,
      "signedArtifactLink": "%s"
    }
    """
        .formatted(downloadUrl);
  }

  @Test
  void signsSingleFileToExplicitOutputPath() throws IOException {
    Path input = tempDir.resolve("app.jar");
    Files.writeString(input, "unsigned-content");
    Path output = tempDir.resolve("signed-app.jar");

    String statusUrl = server.url("/v1/org/SigningRequests/req-001").toString();
    String downloadUrl = server.url("/download/signed.jar").toString();

    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(statusResponse(downloadUrl)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-content"));

    int exit =
        cli()
            .execute(
                "sign",
                "--base-url",
                server.url("").toString().replaceAll("/$", ""),
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "release",
                "--output",
                output.toString(),
                input.toString());

    assertEquals(0, exit);
    assertTrue(Files.exists(output));
    assertEquals("signed-content", Files.readString(output));
    // Input untouched
    assertEquals("unsigned-content", Files.readString(input));
    assertEquals(3, server.getRequestCount());
  }

  @Test
  void signsMultipleFilesToOutputDir() throws IOException {
    Path f1 = tempDir.resolve("app.jar");
    Path f2 = tempDir.resolve("installer.exe");
    Files.writeString(f1, "unsigned-jar");
    Files.writeString(f2, "unsigned-exe");

    Path outDir = tempDir.resolve("signed");

    // Responses for first file
    String status1 = server.url("/v1/org/SigningRequests/r1").toString();
    String dl1 = server.url("/dl/app.jar").toString();
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", status1));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(statusResponse(dl1)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-jar"));

    // Responses for second file
    String status2 = server.url("/v1/org/SigningRequests/r2").toString();
    String dl2 = server.url("/dl/installer.exe").toString();
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", status2));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(statusResponse(dl2)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-exe"));

    int exit =
        cli()
            .execute(
                "sign",
                "--base-url",
                server.url("").toString().replaceAll("/$", ""),
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "release",
                "--output-dir",
                outDir.toString(),
                f1.toString(),
                f2.toString());

    assertEquals(0, exit);
    assertEquals("signed-jar", Files.readString(outDir.resolve("app.jar")));
    assertEquals("signed-exe", Files.readString(outDir.resolve("installer.exe")));
    assertEquals(6, server.getRequestCount());
  }

  @Test
  void signsInPlaceWithForceOverwrite() throws IOException {
    Path input = tempDir.resolve("app.jar");
    Files.writeString(input, "unsigned");

    String statusUrl = server.url("/v1/org/SigningRequests/r1").toString();
    String downloadUrl = server.url("/dl/app.jar").toString();

    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(statusResponse(downloadUrl)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-in-place"));

    int exit =
        cli()
            .execute(
                "sign",
                "--base-url",
                server.url("").toString().replaceAll("/$", ""),
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "release",
                "--force-overwrite",
                input.toString());

    assertEquals(0, exit);
    assertEquals("signed-in-place", Files.readString(input));
  }

  @Test
  void signsWithIntermediateInProgressPoll() throws IOException {
    Path input = tempDir.resolve("app.jar");
    Files.writeString(input, "unsigned");
    Path output = tempDir.resolve("signed.jar");

    String statusUrl = server.url("/v1/org/SigningRequests/r1").toString();
    String downloadUrl = server.url("/dl/app.jar").toString();

    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
    // First poll: in progress
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(
                """
                {"status":"InProgress","workflowStatus":"Processing","isFinalStatus":false}
                """));
    // Second poll: completed
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(statusResponse(downloadUrl)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-after-wait"));

    int exit =
        cli()
            .execute(
                "sign",
                "--base-url",
                server.url("").toString().replaceAll("/$", ""),
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "release",
                "--wait-for-completion-timeout",
                "10",
                "--output",
                output.toString(),
                input.toString());

    assertEquals(0, exit);
    assertEquals("signed-after-wait", Files.readString(output));
    assertEquals(4, server.getRequestCount());
  }

  @Test
  void failsWhenSigningIsDenied() throws IOException {
    Path input = tempDir.resolve("app.jar");
    Files.writeString(input, "unsigned");

    String statusUrl = server.url("/v1/org/SigningRequests/r1").toString();
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(
                """
                {"status":"Denied","workflowStatus":"PolicyDenied","isFinalStatus":true}
                """));

    int exit =
        cli()
            .execute(
                "sign",
                "--base-url",
                server.url("").toString().replaceAll("/$", ""),
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "release",
                "--force-overwrite",
                input.toString());

    assertNotEquals(0, exit);
    // Original file unchanged
    assertEquals("unsigned", Files.readString(input));
  }

  @Test
  void failsWhenSubmitReturnsError() throws IOException {
    Path input = tempDir.resolve("app.jar");
    Files.writeString(input, "unsigned");

    server.enqueue(new MockResponse().setResponseCode(403).setBody("Forbidden"));

    int exit =
        cli()
            .execute(
                "sign",
                "--base-url",
                server.url("").toString().replaceAll("/$", ""),
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "release",
                "--force-overwrite",
                input.toString());

    assertNotEquals(0, exit);
  }

  @Test
  void passesCustomParamsToSignPath() throws Exception {
    Path input = tempDir.resolve("app.jar");
    Files.writeString(input, "unsigned");
    Path output = tempDir.resolve("signed.jar");

    String statusUrl = server.url("/v1/org/SigningRequests/r1").toString();
    String downloadUrl = server.url("/dl/app.jar").toString();

    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(statusResponse(downloadUrl)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed"));

    int exit =
        cli()
            .execute(
                "sign",
                "--base-url",
                server.url("").toString().replaceAll("/$", ""),
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "release",
                "--param",
                "buildNumber=42",
                "--param",
                "gitCommit=abc123",
                "--description",
                "Release build",
                "--output",
                output.toString(),
                input.toString());

    assertEquals(0, exit);
    // Verify the submit request body contained our params
    var submitRequest = server.takeRequest();
    String body = submitRequest.getBody().readUtf8();
    assertTrue(body.contains("buildNumber"), "Custom param 'buildNumber' should be in request");
    assertTrue(body.contains("42"), "Custom param value '42' should be in request");
    assertEquals("signed", Files.readString(output));
  }
}
