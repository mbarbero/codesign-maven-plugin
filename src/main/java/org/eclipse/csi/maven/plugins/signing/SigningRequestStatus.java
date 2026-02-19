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

import java.net.URI;

/**
 * Maps the JSON response from the SignPath signing request status endpoint.
 *
 * @param status              the signing request status (e.g. "Completed", "Failed", "Denied", "Canceled")
 * @param workflowStatus      the detailed workflow processing state
 * @param isFinalStatus       whether the status is terminal (polling should stop)
 * @param signedArtifactLink  URI to download the signed artifact (present when completed)
 * @param unsignedArtifactLink URI to the original unsigned artifact
 */
public record SigningRequestStatus(
		String status,
		String workflowStatus,
		boolean isFinalStatus,
		URI signedArtifactLink,
		URI unsignedArtifactLink) {

	private static final String STATUS_COMPLETED = "Completed";
	private static final String STATUS_FAILED = "Failed";
	private static final String STATUS_DENIED = "Denied";
	private static final String STATUS_CANCELED = "Canceled";

	/**
	 * Returns {@code true} if the signing request completed successfully.
	 *
	 * @return {@code true} when status is {@code "Completed"}
	 */
	public boolean isCompleted() {
		return STATUS_COMPLETED.equals(status);
	}

	/**
	 * Returns {@code true} if the signing request failed.
	 *
	 * @return {@code true} when status is {@code "Failed"}
	 */
	public boolean isFailed() {
		return STATUS_FAILED.equals(status);
	}

	/**
	 * Returns {@code true} if the signing request was denied.
	 *
	 * @return {@code true} when status is {@code "Denied"}
	 */
	public boolean isDenied() {
		return STATUS_DENIED.equals(status);
	}

	/**
	 * Returns {@code true} if the signing request was canceled.
	 *
	 * @return {@code true} when status is {@code "Canceled"}
	 */
	public boolean isCanceled() {
		return STATUS_CANCELED.equals(status);
	}
}
