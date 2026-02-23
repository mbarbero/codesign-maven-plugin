/*
 * Copyright (c) 2026 Eclipse Foundation AISBL and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.csi.codesign.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules for the {@code cli} module.
 *
 * <p>The CLI is an application, not a library. Only its entry point ({@link CodesignCli}) needs to
 * be public. All command classes, helpers, and internal types should be package-private so that
 * they cannot be imported or extended by external code.
 */
@AnalyzeClasses(
    packages = "org.eclipse.csi.codesign.cli",
    importOptions = ImportOption.DoNotIncludeTests.class)
class CliArchTest {

  /**
   * Only {@link CodesignCli} (the entry point) may be public. All other top-level classes in the
   * CLI package — picocli command classes, token resolver, version provider, etc. — are
   * implementation details and must remain package-private.
   */
  @ArchTest
  static final ArchRule onlyEntryPointIsPublic =
      classes()
          .that()
          .resideInAPackage("org.eclipse.csi.codesign.cli")
          .and()
          .areTopLevelClasses()
          .and()
          .doNotHaveSimpleName("CodesignCli")
          .should()
          .notBePublic()
          .because(
              "The CLI is an application, not a library; only the main entry point (CodesignCli)"
                  + " needs to be public");
}
