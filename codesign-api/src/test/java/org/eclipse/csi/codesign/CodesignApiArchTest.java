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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules for the {@code codesign-api} module.
 *
 * <p>Rules here enforce that implementation details (HTTP plumbing, retry logic) are never exposed
 * as part of the public API surface.
 */
@AnalyzeClasses(
    packages = "org.eclipse.csi.codesign",
    importOptions = ImportOption.DoNotIncludeTests.class)
class CodesignApiArchTest {

  /**
   * {@link RetryInterceptor} is an OkHttp application-level interceptor and is purely an
   * implementation detail of {@link CodesignClient}. It must remain package-private so that
   * callers of the API cannot depend on it.
   */
  @ArchTest
  static final ArchRule retryInterceptorIsPackagePrivate =
      classes()
          .that()
          .haveSimpleName("RetryInterceptor")
          .should()
          .notBePublic()
          .because("RetryInterceptor is an OkHttp implementation detail, not part of the public API");

  /**
   * Fields of API classes must not be public. All state should be accessed through explicit,
   * intentionally designed accessors, preventing callers from depending on internal storage
   * layout.
   *
   * <p>Note: Java record components have private backing fields; their public accessor methods are
   * not affected by this rule.
   */
  @ArchTest
  static final ArchRule noPublicFields =
      fields()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("org.eclipse.csi.codesign")
          .should()
          .notBePublic()
          .because("All state should be encapsulated; prefer accessor methods over public fields");
}
