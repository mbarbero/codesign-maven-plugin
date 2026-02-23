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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignMojoTest {

  private static final String CSI_CODESIGN_API_TOKEN = "CSI_CODESIGN_API_TOKEN";
  private static final String CSI_CODESIGN_SKIP_SIGNING = "CSI_CODESIGN_SKIP_SIGNING";
  private static final String DEFAULT_SERVER_ID = "codesign";
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

  private SignMojo createMojo() throws Exception {
    SignMojo mojo = new SignMojo(null);
    setField(mojo, "organizationId", "test-org");
    setField(mojo, "apiToken", "test-token");
    setField(mojo, "projectId", "my-project");
    setField(mojo, "signingPolicy", "release");
    setField(mojo, "baseUrl", server.url("/Api").toString());
    setField(mojo, "baseDirectory", tempDir.toString());
    setField(mojo, "pollInterval", 1);
    setField(mojo, "retryInterval", 1);
    setField(mojo, "retryTimeout", 10);
    setField(mojo, "httpTimeout", 10);
    setField(mojo, "connectTimeout", 5);
    setField(mojo, "signProjectArtifact", "false");
    setField(mojo, "signAttachedArtifacts", false);
    setField(mojo, "skip", false);
    return mojo;
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Class<?> clazz = target.getClass();
    while (clazz != null) {
      try {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
        return;
      } catch (NoSuchFieldException e) {
        clazz = clazz.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  @Test
  void skipExecution() throws Exception {
    SignMojo mojo = createMojo();
    setField(mojo, "skip", true);
    assertDoesNotThrow(() -> mojo.execute());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void skipExecutionViaSkipSigningParameter() throws Exception {
    SignMojo mojo = createMojo();
    setField(mojo, "skipSigning", true);
    assertDoesNotThrow(() -> mojo.execute());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void skipExecutionViaSkipSigningEnvironmentVariable() {
    SignMojo mojo =
        new SignMojo(null) {
          @Override
          String getEnvironmentVariable(String name) {
            if (CSI_CODESIGN_SKIP_SIGNING.equals(name)) {
              return "true";
            }
            return null;
          }
        };

    assertDoesNotThrow(mojo::execute);
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void noFilesMatched() throws Exception {
    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"*.nonexistent"});
    assertDoesNotThrow(() -> mojo.execute());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void failsWhenNoFilesFoundAndFailOnNoFilesFoundIsTrue() throws Exception {
    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"*.nonexistent"});
    setField(mojo, "failOnNoFilesFound", true);
    assertThrows(MojoFailureException.class, () -> mojo.execute());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void signsProjectAndAttachedArtifactsWhenBothEnabled() throws Exception {
    Path mainArtifact = tempDir.resolve("main.jar");
    Path attachedArtifact = tempDir.resolve("attached.zip");
    Files.writeString(mainArtifact, "unsigned-main");
    Files.writeString(attachedArtifact, "unsigned-attached");

    String mainStatusUrl = server.url("/Api/v1/test-org/SigningRequests/main").toString();
    String mainSignedUrl = server.url("/Api/signed-main").toString();
    String attachedStatusUrl = server.url("/Api/v1/test-org/SigningRequests/attached").toString();
    String attachedSignedUrl = server.url("/Api/signed-attached").toString();

    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", mainStatusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(
                """
                {
                  "status": "Completed",
                  "workflowStatus": "Done",
                  "isFinalStatus": true,
                  "signedArtifactLink": "%s"
                }
                """
                    .formatted(mainSignedUrl)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-main"));

    server.enqueue(
        new MockResponse().setResponseCode(201).setHeader("Location", attachedStatusUrl));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(
                """
                {
                  "status": "Completed",
                  "workflowStatus": "Done",
                  "isFinalStatus": true,
                  "signedArtifactLink": "%s"
                }
                """
                    .formatted(attachedSignedUrl)));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-attached"));

    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"*.nonexistent"});
    setField(mojo, "project", createProjectWithArtifacts(mainArtifact, attachedArtifact));
    setField(mojo, "signProjectArtifact", "true");
    setField(mojo, "signAttachedArtifacts", true);

    mojo.execute();

    assertEquals("signed-main", Files.readString(mainArtifact));
    assertEquals("signed-attached", Files.readString(attachedArtifact));
    assertEquals(6, server.getRequestCount());
  }

  @Test
  void canDisableSigningOfProjectAndAttachedArtifacts() throws Exception {
    Path mainArtifact = tempDir.resolve("main.jar");
    Path attachedArtifact = tempDir.resolve("attached.zip");
    Files.writeString(mainArtifact, "unsigned-main");
    Files.writeString(attachedArtifact, "unsigned-attached");

    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"*.nonexistent"});
    setField(mojo, "project", createProjectWithArtifacts(mainArtifact, attachedArtifact));
    setField(mojo, "signProjectArtifact", "false");
    setField(mojo, "signAttachedArtifacts", false);

    mojo.execute();

    assertEquals("unsigned-main", Files.readString(mainArtifact));
    assertEquals("unsigned-attached", Files.readString(attachedArtifact));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void autoSignsProjectArtifactForSignablePackagingTypes() throws Exception {
    for (String packaging : new String[] {"jar", "war", "ear", "rar", "ejb", "maven-plugin"}) {
      Path artifact = tempDir.resolve("main-" + packaging + ".bin");
      Files.writeString(artifact, "unsigned");

      String statusUrl = server.url("/Api/v1/test-org/SigningRequests/" + packaging).toString();
      String signedUrl = server.url("/Api/signed-" + packaging).toString();
      server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader(CONTENT_TYPE, APPLICATION_JSON)
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
      server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-" + packaging));

      SignMojo mojo = createMojo();
      setField(mojo, "signProjectArtifact", "auto");
      setField(mojo, "includes", new String[] {"*.nonexistent"});
      setField(mojo, "project", createProjectWithPackaging(artifact, packaging));

      mojo.execute();

      assertEquals(
          "signed-" + packaging,
          Files.readString(artifact),
          "Expected artifact to be signed for packaging: " + packaging);
    }
  }

  @Test
  void autoDoesNotSignProjectArtifactForPomPackaging() throws Exception {
    Path artifact = tempDir.resolve("pom-artifact.pom");
    Files.writeString(artifact, "<project/>");

    SignMojo mojo = createMojo();
    setField(mojo, "signProjectArtifact", "auto");
    setField(mojo, "includes", new String[] {"*.nonexistent"});
    setField(mojo, "project", createProjectWithPackaging(artifact, "pom"));

    mojo.execute();

    assertEquals("<project/>", Files.readString(artifact));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void doesNotSignAttachedArtifactsByDefault() throws Exception {
    Path mainArtifact = tempDir.resolve("main.jar");
    Path attachedArtifact = tempDir.resolve("attached.zip");
    Files.writeString(mainArtifact, "unsigned-main");
    Files.writeString(attachedArtifact, "unsigned-attached");

    SignMojo mojo = createMojo();
    setField(mojo, "signProjectArtifact", "false");
    setField(mojo, "includes", new String[] {"*.nonexistent"});
    setField(mojo, "project", createProjectWithArtifacts(mainArtifact, attachedArtifact));
    // signAttachedArtifacts is false by default — no explicit override

    mojo.execute();

    assertEquals("unsigned-attached", Files.readString(attachedArtifact));
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void signProjectArtifactValueIsCaseInsensitive() throws Exception {
    Path artifact = tempDir.resolve("main.jar");
    String statusUrl = server.url("/Api/v1/test-org/SigningRequests/ci").toString();
    String signedUrl = server.url("/Api/signed-ci").toString();

    for (String value : new String[] {"true", "TRUE", "True"}) {
      Files.writeString(artifact, "unsigned");
      server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader(CONTENT_TYPE, APPLICATION_JSON)
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

      SignMojo mojo = createMojo();
      setField(mojo, "signProjectArtifact", value);
      setField(mojo, "includes", new String[] {"*.nonexistent"});
      setField(mojo, "project", createProjectWithPackaging(artifact, "jar"));
      mojo.execute();
      assertEquals("signed", Files.readString(artifact), "Failed for value: " + value);
    }
  }

  @Test
  void signProjectArtifactRejectsUnknownValue() throws Exception {
    SignMojo mojo = createMojo();
    setField(mojo, "signProjectArtifact", "maybe");
    setField(mojo, "includes", new String[] {"*.nonexistent"});

    MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
    assertTrue(ex.getMessage().contains("maybe"), "Error should mention the bad value");
  }

  @Test
  void signsFileSuccessfully() throws Exception {
    Path artifact = tempDir.resolve("app.exe");
    Files.writeString(artifact, "unsigned-content");

    String statusUrl = server.url("/Api/v1/test-org/SigningRequests/123").toString();

    // Submit response
    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));

    // Status poll - in progress
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(
                """
                {
                  "status": "InProgress",
                  "workflowStatus": "Processing",
                  "isFinalStatus": false
                }
                """));

    // Status poll - completed
    String signedUrl = server.url("/Api/signed-artifact").toString();
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
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

    // Download signed artifact
    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-content"));

    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"app.exe"});

    mojo.execute();

    assertEquals("signed-content", Files.readString(artifact));
    assertEquals(4, server.getRequestCount());
  }

  @Test
  void signsFileToOutputDirectory() throws Exception {
    Path artifact = tempDir.resolve("app.jar");
    Files.writeString(artifact, "unsigned-content");

    Path outputDir = tempDir.resolve("signed-output");
    Files.createDirectories(outputDir);

    String statusUrl = server.url("/Api/v1/test-org/SigningRequests/789").toString();
    String signedUrl = server.url("/Api/signed-artifact").toString();

    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
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

    server.enqueue(new MockResponse().setResponseCode(200).setBody("signed-jar-content"));

    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"app.jar"});
    setField(mojo, "outputDirectory", outputDir.toString());

    mojo.execute();

    // Original should be unchanged
    assertEquals("unsigned-content", Files.readString(artifact));
    // Signed file should be in output directory
    Path signedFile = outputDir.resolve("app.jar");
    assertTrue(Files.exists(signedFile));
    assertEquals("signed-jar-content", Files.readString(signedFile));
  }

  @Test
  void failsOnDeniedStatus() throws Exception {
    Path artifact = tempDir.resolve("denied.exe");
    Files.writeString(artifact, "content");

    String statusUrl = server.url("/Api/v1/test-org/SigningRequests/denied").toString();

    server.enqueue(new MockResponse().setResponseCode(201).setHeader("Location", statusUrl));

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(CONTENT_TYPE, APPLICATION_JSON)
            .setBody(
                """
                {
                  "status": "Denied",
                  "workflowStatus": "PolicyDenied",
                  "isFinalStatus": true
                }
                """));

    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"denied.exe"});

    assertThrows(MojoFailureException.class, () -> mojo.execute());
  }

  @Test
  void failsOnSubmitError() throws Exception {
    Path artifact = tempDir.resolve("error.exe");
    Files.writeString(artifact, "content");

    server.enqueue(new MockResponse().setResponseCode(403).setBody("Forbidden"));

    SignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"error.exe"});

    assertThrows(MojoExecutionException.class, () -> mojo.execute());
  }

  @Test
  void resolvesApiTokenFromSettingsXml() throws Exception {
    SignMojo mojo = createMojoForTokenResolution(null, DEFAULT_SERVER_ID, "settings-token", null);
    assertEquals("settings-token", mojo.resolveApiToken());
  }

  @Test
  void resolvesApiTokenFromEnvironmentVariable() throws Exception {
    SignMojo mojo = createMojoForTokenResolution(null, DEFAULT_SERVER_ID, null, "env-token");
    assertEquals("env-token", mojo.resolveApiToken());
  }

  @Test
  void apiTokenParameterTakesPriorityOverSettingsXml() throws Exception {
    SignMojo mojo =
        createMojoForTokenResolution(
            "param-token", DEFAULT_SERVER_ID, "settings-token", "env-token");
    assertEquals("param-token", mojo.resolveApiToken());
  }

  @Test
  void settingsXmlTakesPriorityOverEnvVar() throws Exception {
    SignMojo mojo =
        createMojoForTokenResolution(null, DEFAULT_SERVER_ID, "settings-token", "env-token");
    assertEquals("settings-token", mojo.resolveApiToken());
  }

  @Test
  void failsWithClearErrorWhenNoTokenFound() throws Exception {
    SignMojo mojo = createMojoForTokenResolution(null, DEFAULT_SERVER_ID, null, null);
    MojoExecutionException ex =
        assertThrows(MojoExecutionException.class, () -> mojo.resolveApiToken());
    String message = ex.getMessage();
    assertTrue(message.contains("apiToken"), "Should mention apiToken parameter");
    assertTrue(message.contains("csi.codesign.apiToken"), "Should mention system property");
    assertTrue(message.contains("settings.xml"), "Should mention settings.xml");
    assertTrue(message.contains("CSI_CODESIGN_API_TOKEN"), "Should mention env var");
  }

  @Test
  void resolvesApiTokenFromCustomServerId() throws Exception {
    SignMojo mojo = createMojoForTokenResolution(null, "my-custom-server", "custom-token", null);
    assertEquals("custom-token", mojo.resolveApiToken());
  }

  private SignMojo createMojoForTokenResolution(
      String apiToken, String serverId, String serverPassword, String envToken) throws Exception {
    SignMojo mojo =
        new SignMojo(null) {
          @Override
          String getEnvironmentVariable(String name) {
            if (CSI_CODESIGN_API_TOKEN.equals(name)) {
              return envToken;
            }
            return null;
          }
        };
    setField(mojo, "apiToken", apiToken);
    setField(mojo, "serverId", serverId);

    Settings settings = new Settings();
    if (serverPassword != null) {
      Server server = new Server();
      server.setId(serverId);
      server.setPassword(serverPassword);
      settings.addServer(server);
    }
    DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
    MavenSession session =
        new MavenSession(null, request, new DefaultMavenExecutionResult(), Collections.emptyList());
    session.getSettings().getServers().addAll(settings.getServers());
    setField(mojo, "session", session);

    return mojo;
  }

  private MavenProject createProjectWithArtifacts(Path mainArtifact, Path attachedArtifact)
      throws Exception {
    MavenProject project = new MavenProject();

    DefaultArtifact main =
        new DefaultArtifact(
            "org.example",
            "demo",
            VersionRange.createFromVersion("1.0.0"),
            "compile",
            "jar",
            null,
            new DefaultArtifactHandler("jar"));
    main.setFile(mainArtifact.toFile());
    project.setArtifact(main);

    DefaultArtifact attached =
        new DefaultArtifact(
            "org.example",
            "demo",
            VersionRange.createFromVersion("1.0.0"),
            "compile",
            "zip",
            "dist",
            new DefaultArtifactHandler("zip"));
    attached.setFile(attachedArtifact.toFile());
    project.addAttachedArtifact(attached);

    return project;
  }

  private MavenProject createProjectWithPackaging(Path artifactFile, String packaging)
      throws Exception {
    MavenProject project = new MavenProject();
    project.setPackaging(packaging);

    DefaultArtifact artifact =
        new DefaultArtifact(
            "org.example",
            "demo",
            VersionRange.createFromVersion("1.0.0"),
            "compile",
            packaging,
            null,
            new DefaultArtifactHandler(packaging));
    artifact.setFile(artifactFile.toFile());
    project.setArtifact(artifact);

    return project;
  }
}
