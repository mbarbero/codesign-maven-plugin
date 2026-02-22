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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodesignClientTest {

  private static final String API_TOKEN = "test-api-token";
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_TOKEN = "Bearer " + API_TOKEN;

  private MockWebServer server;
  private CodesignClient client;

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    OkHttpClient httpClient = new OkHttpClient();
    client =
        new CodesignClient(httpClient, server.url("/Api").toString(), "test-org-id", API_TOKEN);
  }

  @AfterEach
  void tearDown() throws IOException {
    client.close();
    server.shutdown();
  }

  @Test
  void submitReturnsSigningRequestWithLocationHeader() throws Exception {
    String statusUrl = server.url("/Api/v1/test-org-id/SigningRequests/123").toString();
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));

    Path artifact = tempDir.resolve("test.jar");
    Files.writeString(artifact, "fake-jar-content");

    SigningRequest result =
        client.submit("my-project", "release-signing", null, null, null, artifact);

    assertNotNull(result);
    assertEquals(URI.create(statusUrl), result.statusUrl());

    RecordedRequest recorded = server.takeRequest();
    assertEquals("POST", recorded.getMethod());
    assertTrue(recorded.getPath().contains("/SigningRequests"));
    assertEquals(BEARER_TOKEN, recorded.getHeader(AUTHORIZATION_HEADER));
    String body = recorded.getBody().readUtf8();
    assertTrue(body.contains("my-project"));
    assertTrue(body.contains("release-signing"));
  }

  @Test
  void submitWithOptionalParameters() throws Exception {
    String statusUrl = server.url("/Api/v1/test-org-id/SigningRequests/456").toString();
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));

    Path artifact = tempDir.resolve("test.exe");
    Files.writeString(artifact, "fake-exe-content");

    SigningRequest result =
        client.submit(
            "my-project",
            "release-signing",
            "my-config",
            "Build 42",
            Map.of("version", "1.0"),
            artifact);

    assertNotNull(result);

    RecordedRequest recorded = server.takeRequest();
    String body = recorded.getBody().readUtf8();
    assertTrue(body.contains("my-config"));
    assertTrue(body.contains("Build 42"));
    assertTrue(body.contains("Parameters[version]"));
    assertTrue(body.contains("1.0"));
  }

  @Test
  void submitThrowsOnNon201() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(400).setBody("Bad Request"));

    Path artifact = tempDir.resolve("test.jar");
    Files.writeString(artifact, "fake-jar-content");

    CodesignException ex =
        assertThrows(
            CodesignException.class,
            () -> client.submit("proj", "policy", null, null, null, artifact));
    assertEquals(400, ex.httpStatus());
    assertTrue(ex.responseBody().contains("Bad Request"));
  }

  @Test
  void submitThrowsOnMissingLocationHeader() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(201));

    Path artifact = tempDir.resolve("test.jar");
    Files.writeString(artifact, "fake-jar-content");

    CodesignException ex =
        assertThrows(
            CodesignException.class,
            () -> client.submit("proj", "policy", null, null, null, artifact));
    assertTrue(ex.responseBody().contains("Missing Location header"));
  }

  @Test
  void getStatusParsesJsonResponse() throws Exception {
    String json =
        """
        {
          "status": "Completed",
          "workflowStatus": "Done",
          "isFinalStatus": true,
          "signedArtifactLink": "https://example.com/signed",
          "unsignedArtifactLink": "https://example.com/unsigned"
        }
        """;
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(json));

    URI statusUrl = server.url("/Api/v1/test-org-id/SigningRequests/123").uri();
    SigningRequest signingRequest = new SigningRequest(statusUrl);

    SigningRequestStatus status = client.getStatus(signingRequest);

    assertTrue(status.isCompleted());
    assertTrue(status.isFinalStatus());
    assertEquals("Done", status.workflowStatus());
    assertEquals(URI.create("https://example.com/signed"), status.signedArtifactLink());
    assertEquals(URI.create("https://example.com/unsigned"), status.unsignedArtifactLink());

    RecordedRequest recorded = server.takeRequest();
    assertEquals("GET", recorded.getMethod());
    assertEquals(BEARER_TOKEN, recorded.getHeader(AUTHORIZATION_HEADER));
  }

  @Test
  void getStatusThrowsOnError() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));

    URI statusUrl = server.url("/test").uri();
    SigningRequest signingRequest = new SigningRequest(statusUrl);

    CodesignException ex =
        assertThrows(CodesignException.class, () -> client.getStatus(signingRequest));
    assertEquals(404, ex.httpStatus());
  }

  @Test
  void downloadSignedArtifactWritesToFile() throws Exception {
    String content = "signed-content-bytes";
    server.enqueue(new MockResponse().setResponseCode(200).setBody(content));

    URI signedLink = server.url("/signed-artifact").uri();
    SigningRequestStatus status =
        new SigningRequestStatus("Completed", "Done", true, signedLink, null);

    Path output = tempDir.resolve("signed.jar");
    client.downloadSignedArtifact(status, output);

    assertTrue(Files.exists(output));
    assertEquals(content, Files.readString(output));

    RecordedRequest recorded = server.takeRequest();
    assertEquals("GET", recorded.getMethod());
    assertEquals(BEARER_TOKEN, recorded.getHeader(AUTHORIZATION_HEADER));
  }

  @Test
  void downloadThrowsWhenNoLink() {
    SigningRequestStatus status = new SigningRequestStatus("Completed", "Done", true, null, null);

    assertThrows(
        CodesignException.class,
        () -> client.downloadSignedArtifact(status, tempDir.resolve("out.jar")));
  }

  @Test
  void downloadThrowsOnHttpError() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

    URI signedLink = server.url("/signed-artifact").uri();
    SigningRequestStatus status =
        new SigningRequestStatus("Completed", "Done", true, signedLink, null);

    CodesignException ex =
        assertThrows(
            CodesignException.class,
            () -> client.downloadSignedArtifact(status, tempDir.resolve("out.jar")));
    assertEquals(500, ex.httpStatus());
  }
}
