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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** HTTP client for all SignPath API operations: submit, poll status, and download. */
public class CodesignClient implements AutoCloseable {

  private static final Gson GSON = new GsonBuilder().create();
  private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");
  private static final String AUTHORIZATION_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  private final OkHttpClient httpClient;
  private final String baseUrl;
  private final String organizationId;
  private final String apiToken;

  /**
   * Configuration for the codesign client.
   *
   * @param baseUrl the SignPath API base URL
   * @param organizationId the SignPath organization identifier
   * @param apiToken the Bearer API token used for authentication
   * @param connectTimeout the HTTP connection timeout
   * @param httpTimeout the HTTP read/write timeout
   * @param retryTimeout the maximum time window for retry attempts
   * @param retryInterval the delay between retry attempts
   * @param maxRetries the maximum number of retry attempts
   */
  public record Config(
      String baseUrl,
      String organizationId,
      String apiToken,
      Duration connectTimeout,
      Duration httpTimeout,
      Duration retryTimeout,
      Duration retryInterval,
      int maxRetries) {}

  /**
   * Creates a new client from the given configuration.
   *
   * @param config the client configuration
   */
  public CodesignClient(Config config) {
    this.baseUrl = config.baseUrl();
    this.organizationId = config.organizationId();
    this.apiToken = config.apiToken();

    RetryInterceptor retryInterceptor =
        new RetryInterceptor(config.retryTimeout(), config.retryInterval(), config.maxRetries());

    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(config.httpTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .writeTimeout(config.httpTimeout().toMillis(), TimeUnit.MILLISECONDS)
            .addInterceptor(retryInterceptor)
            .build();
  }

  // Visible for testing
  CodesignClient(OkHttpClient httpClient, String baseUrl, String organizationId, String apiToken) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl;
    this.organizationId = organizationId;
    this.apiToken = apiToken;
  }

  /**
   * Submits an artifact for signing.
   *
   * @param projectId the SignPath project slug
   * @param signingPolicy the signing policy slug
   * @param artifactConfiguration optional artifact configuration slug, may be {@code null}
   * @param description optional signing request description, may be {@code null}
   * @param parameters optional custom key/value parameters, may be {@code null}
   * @param artifactPath path to the artifact file to sign
   * @return a {@link SigningRequest} containing the status polling URL
   * @throws CodesignException if the API returns a non-201 response
   * @throws IOException on transport-level failures
   */
  public SigningRequest submit(
      String projectId,
      String signingPolicy,
      String artifactConfiguration,
      String description,
      Map<String, String> parameters,
      Path artifactPath)
      throws CodesignException, IOException {
    String url = baseUrl + "/v1/" + organizationId + "/SigningRequests";

    MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("ProjectSlug", projectId)
            .addFormDataPart("SigningPolicySlug", signingPolicy)
            .addFormDataPart(
                "Artifact",
                artifactPath.getFileName().toString(),
                RequestBody.create(artifactPath.toFile(), OCTET_STREAM));

    if (artifactConfiguration != null) {
      bodyBuilder.addFormDataPart("ArtifactConfigurationSlug", artifactConfiguration);
    }
    if (description != null) {
      bodyBuilder.addFormDataPart("Description", description);
    }
    if (parameters != null) {
      for (Map.Entry<String, String> entry : parameters.entrySet()) {
        bodyBuilder.addFormDataPart("Parameters[" + entry.getKey() + "]", entry.getValue());
      }
    }

    Request request =
        new Request.Builder()
            .url(url)
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
            .post(bodyBuilder.build())
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (response.code() != 201) {
        throw new CodesignException(response.code(), readBody(response));
      }
      String location = response.header("Location");
      if (location == null) {
        throw new CodesignException(response.code(), "Missing Location header in submit response");
      }
      return new SigningRequest(URI.create(location));
    }
  }

  /**
   * Polls the status of a signing request.
   *
   * @param signingRequest the signing request to poll
   * @return the current {@link SigningRequestStatus}
   * @throws CodesignException if the API returns an unsuccessful response
   * @throws IOException on transport-level failures
   */
  public SigningRequestStatus getStatus(SigningRequest signingRequest)
      throws CodesignException, IOException {
    Request request =
        new Request.Builder()
            .url(signingRequest.statusUrl().toString())
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
            .get()
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new CodesignException(response.code(), readBody(response));
      }
      String body = readBody(response);
      return GSON.fromJson(body, SigningRequestStatus.class);
    }
  }

  /**
   * Downloads the signed artifact to the given output path.
   *
   * @param status the final signing request status containing the artifact download link
   * @param outputPath the local path where the signed artifact will be written
   * @throws CodesignException if no signed artifact link is available or the API returns an error
   * @throws IOException on transport-level or I/O failures
   */
  public void downloadSignedArtifact(SigningRequestStatus status, Path outputPath)
      throws CodesignException, IOException {
    URI signedArtifactLink = status.signedArtifactLink();
    if (signedArtifactLink == null) {
      throw new CodesignException(-1, "No signed artifact link available");
    }

    Request request =
        new Request.Builder()
            .url(signedArtifactLink.toString())
            .header(AUTHORIZATION_HEADER, BEARER_PREFIX + apiToken)
            .get()
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new CodesignException(response.code(), readBody(response));
      }
      ResponseBody body = response.body();
      if (body == null) {
        throw new CodesignException(response.code(), "Empty response body");
      }
      try (OutputStream out = Files.newOutputStream(outputPath)) {
        body.byteStream().transferTo(out);
      }
    }
  }

  /** Releases HTTP resources held by this client. */
  @Override
  public void close() {
    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
  }

  private static String readBody(Response response) throws IOException {
    ResponseBody body = response.body();
    return body != null ? body.string() : "";
  }
}
