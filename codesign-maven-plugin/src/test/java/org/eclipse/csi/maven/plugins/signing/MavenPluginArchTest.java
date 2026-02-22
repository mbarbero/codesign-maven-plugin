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

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Architecture rules for the {@code codesign-maven-plugin} module.
 *
 * <p>Maven instantiates Mojo classes reflectively, so they must be public. All other helper types
 * (enums, utility classes, configuration holders) are internal implementation details and should
 * remain package-private.
 */
@AnalyzeClasses(
    packages = "org.eclipse.csi.maven.plugins.signing",
    importOptions = ImportOption.DoNotIncludeTests.class)
class MavenPluginArchTest {

  /**
   * Only classes annotated with {@link Mojo} may be public. Supporting types like
   * {@link SignProjectArtifact} are internal configuration details that must not be exposed to
   * consumers.
   */
  @ArchTest
  static final ArchRule onlyMojoClassesArePublic =
      classes()
          .that()
          .resideInAPackage("org.eclipse.csi.maven.plugins.signing")
          .and()
          .areNotAnnotatedWith(Mojo.class)
          .should()
          .notBePublic()
          .because(
              "Only Maven Mojo classes need to be public (Maven instantiates them reflectively);"
                  + " all other types are implementation details");
}
