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
 * Tri-state enum controlling whether the Maven project's main artifact is included in signing.
 *
 * <ul>
 *   <li>{@link #AUTO} – enabled for packaging types that produce signable binary artifacts ({@code
 *       jar}, {@code war}, {@code ear}, {@code rar}, {@code ejb}, {@code maven-plugin}); disabled
 *       for {@code pom} packaging and any other unrecognised type.
 *   <li>{@link #TRUE} – always include the project artifact regardless of packaging.
 *   <li>{@link #FALSE} – never include the project artifact.
 * </ul>
 */
public enum SignProjectArtifact {
  AUTO,
  TRUE,
  FALSE
}
