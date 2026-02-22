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

import java.net.URI;

/**
 * Represents a submitted signing request, holding the URL to poll for status.
 *
 * @param statusUrl the URL from the Location header of the submit response
 */
public record SigningRequest(URI statusUrl) {}
