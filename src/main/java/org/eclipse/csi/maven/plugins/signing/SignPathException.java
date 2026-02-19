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

/**
 * Exception carrying HTTP status code and response body from a failed SignPath API call.
 */
public class SignPathException extends Exception {

	private final int httpStatus;
	private final String responseBody;

	/**
	 * Constructs a new exception with the HTTP status code and response body from a failed API call.
	 *
	 * @param httpStatus   the HTTP status code returned by the SignPath API
	 * @param responseBody the response body returned by the SignPath API
	 */
	public SignPathException(int httpStatus, String responseBody) {
		super("SignPath API error (HTTP " + httpStatus + "): " + responseBody);
		this.httpStatus = httpStatus;
		this.responseBody = responseBody;
	}

	/**
	 * Constructs a new exception with the specified detail message and cause.
	 *
	 * @param message the detail message
	 * @param cause   the cause of this exception
	 */
	public SignPathException(String message, Throwable cause) {
		super(message, cause);
		this.httpStatus = -1;
		this.responseBody = null;
	}

	/**
	 * Returns the HTTP status code from the failed SignPath API call, or {@code -1} if not applicable.
	 *
	 * @return the HTTP status code
	 */
	public int httpStatus() {
		return httpStatus;
	}

	/**
	 * Returns the response body from the failed SignPath API call, or {@code null} if not applicable.
	 *
	 * @return the response body
	 */
	public String responseBody() {
		return responseBody;
	}
}
