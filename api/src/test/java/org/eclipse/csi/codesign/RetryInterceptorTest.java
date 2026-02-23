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

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetryInterceptorTest {

  private static final String TEST_PATH = "/test";
  private static final String OK_BODY = "ok";

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  private OkHttpClient clientWithRetry(Duration timeout, Duration interval, int maxRetries) {
    return new OkHttpClient.Builder()
        .addInterceptor(new RetryInterceptor(timeout, interval, maxRetries))
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build();
  }

  @Test
  void successOnFirstAttempt() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(200).setBody(OK_BODY));

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(10), Duration.ofMillis(50), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
      assertEquals(OK_BODY, response.body().string());
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void retriesOn503ThenSucceeds() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(503));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(OK_BODY));

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(10), Duration.ofMillis(50), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void retriesOn429ThenSucceeds() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(429));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(OK_BODY));

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(10), Duration.ofMillis(50), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void retriesOn502ThenSucceeds() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(502));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(OK_BODY));

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(10), Duration.ofMillis(50), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void retriesOn504ThenSucceeds() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(504));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(OK_BODY));

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(10), Duration.ofMillis(50), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void doesNotRetryOnNonRetryableStatus() throws IOException {
    server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(10), Duration.ofMillis(50), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(400, response.code());
    }
    assertEquals(1, server.getRequestCount());
  }

  @Test
  void stopsRetryingAfterTimeout() throws IOException {
    // Enqueue enough 503s to exceed the very short timeout
    for (int i = 0; i < 10; i++) {
      server.enqueue(new MockResponse().setResponseCode(503));
    }

    OkHttpClient client = clientWithRetry(Duration.ofMillis(100), Duration.ofMillis(60), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(503, response.code());
    }
    // Should have retried a few times but not all 10
    int count = server.getRequestCount();
    assert count >= 1 && count < 10 : "Expected limited retries, got " + count;
  }

  @Test
  void retriesOnIOExceptionThenSucceeds() throws IOException {
    // First request will fail with a socket timeout, second will succeed
    server.enqueue(
        new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
    server.enqueue(new MockResponse().setResponseCode(200).setBody(OK_BODY));

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(10), Duration.ofMillis(50), 10);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(200, response.code());
    }
    assertEquals(2, server.getRequestCount());
  }

  @Test
  void stopsRetryingAfterMaxRetries() throws IOException {
    for (int i = 0; i < 10; i++) {
      server.enqueue(new MockResponse().setResponseCode(503));
    }

    OkHttpClient client = clientWithRetry(Duration.ofSeconds(30), Duration.ofMillis(10), 2);
    Request request = new Request.Builder().url(server.url(TEST_PATH)).build();

    try (Response response = client.newCall(request).execute()) {
      assertEquals(503, response.code());
    }
    assertEquals(3, server.getRequestCount());
  }
}
