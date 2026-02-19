/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.maven.plugins.signing;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp application-level interceptor that retries requests on transient failures.
 * <p>
 * Retries on HTTP 429, 502, 503, 504 and {@link IOException} (connection failures).
 * Retries are time-bounded by a deadline computed from the configured timeout.
 */
public class RetryInterceptor implements Interceptor {

	private static final Logger LOG = Logger.getLogger(RetryInterceptor.class.getName());
	private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);

	private final Duration retryTimeout;
	private final Duration retryInterval;
	private final int maxRetries;

	/**
	 * Creates a new retry interceptor.
	 *
	 * @param retryTimeout  the maximum time window for all retry attempts
	 * @param retryInterval the delay between consecutive retry attempts
	 * @param maxRetries    the maximum number of retry attempts
	 */
	public RetryInterceptor(Duration retryTimeout, Duration retryInterval, int maxRetries) {
		this.retryTimeout = retryTimeout;
		this.retryInterval = retryInterval;
		this.maxRetries = maxRetries;
	}

	/**
	 * Intercepts the request and retries on transient failures.
	 * <p>
	 * Retries on HTTP {@code 429}, {@code 502}, {@code 503}, {@code 504} and
	 * {@link IOException}, up to {@code maxRetries} times or until the deadline is exceeded.
	 *
	 * @param chain the OkHttp interceptor chain
	 * @return the HTTP response
	 * @throws IOException if the request ultimately fails after all retry attempts
	 */
	@Override
	public Response intercept(Chain chain) throws IOException {
		Request request = chain.request();
		Instant deadline = Instant.now().plus(retryTimeout);
		int retryCount = 0;

		while (true) {
			Response response;
			try {
				response = chain.proceed(request);
			} catch (IOException e) {
				if (Instant.now().isAfter(deadline) || retryCount >= maxRetries) {
					throw e;
				}
				retryCount++;
				LOG.log(Level.WARNING, "Request failed with IOException: {0}. Retrying...", e.getMessage());
				sleep();
				continue;
			}

			if (!RETRYABLE_STATUS_CODES.contains(response.code())) {
				return response;
			}

			if (Instant.now().isAfter(deadline) || retryCount >= maxRetries) {
				return response;
			}

			retryCount++;
			LOG.log(Level.WARNING, "Request returned HTTP {0}. Retrying...",
					Integer.toString(response.code()));
			response.close();
			sleep();
		}
	}

	private void sleep() {
		try {
			LOG.log(Level.INFO, "Waiting {0} seconds before next retry",
					Long.toString(retryInterval.toSeconds()));
			Thread.sleep(retryInterval.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Retry interrupted", e);
		}
	}
}
