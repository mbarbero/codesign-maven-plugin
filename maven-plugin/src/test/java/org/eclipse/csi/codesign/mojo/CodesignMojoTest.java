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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.csi.codesign.CodesignClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodesignMojoTest {

  private static final String CSI_CODESIGN_API_TOKEN = "CSI_CODESIGN_API_TOKEN";
  private static final String CSI_CODESIGN_SKIP_SIGNING = "CSI_CODESIGN_SKIP_SIGNING";
  private static final String DEFAULT_SERVER_ID = "codesign";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json";
  private static final MavenProjectHelper PROJECT_HELPER =
      new MavenProjectHelper() {
        @Override
        public void attachArtifact(
            MavenProject project, File artifactFile, String artifactClassifier) {
          attachArtifact(project, "jar", artifactClassifier, artifactFile);
        }

        @Override
        public void attachArtifact(MavenProject project, String artifactType, File artifactFile) {
          attachArtifact(project, artifactType, null, artifactFile);
        }

        @Override
        public void attachArtifact(
            MavenProject project,
            String artifactType,
            String artifactClassifier,
            File artifactFile) {
          Artifact mainArtifact = project.getArtifact();
          DefaultArtifact attachedArtifact =
              new DefaultArtifact(
                  mainArtifact.getGroupId(),
                  mainArtifact.getArtifactId(),
                  VersionRange.createFromVersion(mainArtifact.getVersion()),
                  mainArtifact.getScope(),
                  artifactType,
                  artifactClassifier,
                  new DefaultArtifactHandler(artifactType));
          attachedArtifact.setFile(artifactFile);
          project.getAttachedArtifacts().add(attachedArtifact);
        }

        @Override
        public void addResource(
            MavenProject project,
            String resourceDirectory,
            List<String> includes,
            List<String> excludes) {}

        @Override
        public void addTestResource(
            MavenProject project,
            String resourceDirectory,
            List<String> includes,
            List<String> excludes) {}
      };

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
   * Creates a test client via the package-private CodesignClient constructor (bypasses the HTTPS
   * enforcement), accessed via reflection since the test is in a different package.
   */
  private static CodesignClient testClient(String baseUrl, String orgId, String token)
      throws Exception {
    Constructor<CodesignClient> ctor =
        CodesignClient.class.getDeclaredConstructor(
            OkHttpClient.class, String.class, String.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(new OkHttpClient(), baseUrl, orgId, token);
  }

  private CodesignMojo createMojo() throws Exception {
    // Override buildClient to use the package-private constructor (bypasses HTTPS check)
    // so tests can use MockWebServer which runs on plain HTTP.
    CodesignMojo mojo =
        new CodesignMojo(null) {
          @Override
          CodesignClient buildClient(CodesignClient.Config config) {
            try {
              return testClient(config.baseUrl(), config.organizationId(), config.apiToken());
            } catch (Exception e) {
              throw new RuntimeException("Test client creation failed", e);
            }
          }
        };
    setField(mojo, "organizationId", "test-org");
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
    setField(mojo, "serverId", "codesign");
    Settings settings = new Settings();
    Server tokenServer = new Server();
    tokenServer.setId("codesign");
    tokenServer.setPassword("test-token");
    settings.addServer(tokenServer);
    setField(mojo, "settings", settings);
    setField(mojo, "configFile", tempDir.resolve("nonexistent-config.properties").toFile());
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
    CodesignMojo mojo = createMojo();
    setField(mojo, "skip", true);
    assertDoesNotThrow(() -> mojo.execute());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void skipExecutionViaSkipSigningParameter() throws Exception {
    CodesignMojo mojo = createMojo();
    setField(mojo, "skipSigning", true);
    assertDoesNotThrow(() -> mojo.execute());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void skipExecutionViaSkipSigningEnvironmentVariable() {
    CodesignMojo mojo =
        new CodesignMojo(null) {
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
    CodesignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"*.nonexistent"});
    assertDoesNotThrow(() -> mojo.execute());
    assertEquals(0, server.getRequestCount());
  }

  @Test
  void failsWhenNoFilesFoundAndFailOnNoFilesFoundIsTrue() throws Exception {
    CodesignMojo mojo = createMojo();
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

    CodesignMojo mojo = createMojo();
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

    CodesignMojo mojo = createMojo();
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

      CodesignMojo mojo = createMojo();
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

    CodesignMojo mojo = createMojo();
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

    CodesignMojo mojo = createMojo();
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

      CodesignMojo mojo = createMojo();
      setField(mojo, "signProjectArtifact", value);
      setField(mojo, "includes", new String[] {"*.nonexistent"});
      setField(mojo, "project", createProjectWithPackaging(artifact, "jar"));
      mojo.execute();
      assertEquals("signed", Files.readString(artifact), "Failed for value: " + value);
    }
  }

  @Test
  void signProjectArtifactRejectsUnknownValue() throws Exception {
    CodesignMojo mojo = createMojo();
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

    CodesignMojo mojo = createMojo();
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

    CodesignMojo mojo = createMojo();
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

    CodesignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"denied.exe"});

    assertThrows(MojoFailureException.class, () -> mojo.execute());
  }

  @Test
  void failsOnSubmitError() throws Exception {
    Path artifact = tempDir.resolve("error.exe");
    Files.writeString(artifact, "content");

    server.enqueue(new MockResponse().setResponseCode(403).setBody("Forbidden"));

    CodesignMojo mojo = createMojo();
    setField(mojo, "includes", new String[] {"error.exe"});

    assertThrows(MojoExecutionException.class, () -> mojo.execute());
  }

  @Test
  void resolvesApiTokenFromSettingsXml() throws Exception {
    CodesignMojo mojo = createMojoForTokenResolution(DEFAULT_SERVER_ID, "settings-token", null);
    assertEquals("settings-token", mojo.resolveApiToken());
  }

  @Test
  void resolvesApiTokenFromEnvironmentVariable() throws Exception {
    CodesignMojo mojo = createMojoForTokenResolution(DEFAULT_SERVER_ID, null, "env-token");
    assertEquals("env-token", mojo.resolveApiToken());
  }

  @Test
  void settingsXmlTakesPriorityOverEnvVar() throws Exception {
    CodesignMojo mojo =
        createMojoForTokenResolution(DEFAULT_SERVER_ID, "settings-token", "env-token");
    assertEquals("settings-token", mojo.resolveApiToken());
  }

  @Test
  void failsWithClearErrorWhenNoTokenFound() throws Exception {
    CodesignMojo mojo = createMojoForTokenResolution(DEFAULT_SERVER_ID, null, null);
    MojoExecutionException ex =
        assertThrows(MojoExecutionException.class, () -> mojo.resolveApiToken());
    String message = ex.getMessage();
    assertTrue(message.contains("settings.xml"), "Should mention settings.xml");
    assertTrue(message.contains("CSI_CODESIGN_API_TOKEN"), "Should mention env var");
    assertTrue(message.contains("csi.codesign.configFile"), "Should mention configFile parameter");
  }

  @Test
  void resolvesApiTokenFromConfigFile() throws Exception {
    Path configFile = tempDir.resolve("config.properties");
    Files.writeString(configFile, "api.token=file-token\n");

    CodesignMojo mojo = createMojoForTokenResolution(DEFAULT_SERVER_ID, null, null);
    setField(mojo, "configFile", configFile.toFile());

    assertEquals("file-token", mojo.resolveApiToken());
  }

  @Test
  void envVarTakesPriorityOverConfigFile() throws Exception {
    Path configFile = tempDir.resolve("config.properties");
    Files.writeString(configFile, "api.token=file-token\n");

    CodesignMojo mojo = createMojoForTokenResolution(DEFAULT_SERVER_ID, null, "env-token");
    setField(mojo, "configFile", configFile.toFile());

    assertEquals("env-token", mojo.resolveApiToken());
  }

  @Test
  void resolvesApiTokenFromCustomServerId() throws Exception {
    CodesignMojo mojo = createMojoForTokenResolution("my-custom-server", "custom-token", null);
    assertEquals("custom-token", mojo.resolveApiToken());
  }

  private CodesignMojo createMojoForTokenResolution(
      String serverId, String serverPassword, String envToken) throws Exception {
    CodesignMojo mojo =
        new CodesignMojo(null) {
          @Override
          String getEnvironmentVariable(String name) {
            if (CSI_CODESIGN_API_TOKEN.equals(name)) {
              return envToken;
            }
            return null;
          }
        };
    setField(mojo, "serverId", serverId);
    setField(mojo, "configFile", tempDir.resolve("nonexistent-config.properties").toFile());

    Settings settings = new Settings();
    if (serverPassword != null) {
      Server server = new Server();
      server.setId(serverId);
      server.setPassword(serverPassword);
      settings.addServer(server);
    }
    setField(mojo, "settings", settings);

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

    PROJECT_HELPER.attachArtifact(project, "zip", "dist", attachedArtifact.toFile());

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
