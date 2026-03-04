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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class SignCommandTest {

  @TempDir Path tempDir;

  private CommandLine cli() {
    return new CommandLine(new CodesignCli())
        .setOut(new PrintWriter(new StringWriter()))
        .setErr(new PrintWriter(new StringWriter()))
        .setExecutionExceptionHandler(new PrintExceptionMessageHandler());
  }

  // ---- derivePollInterval ----

  @Test
  void derivePollInterval_smallTimeout_clampsToMinimum() {
    assertEquals(Duration.ofSeconds(2), SignCommand.derivePollInterval(10));
  }

  @Test
  void derivePollInterval_typical300() {
    assertEquals(Duration.ofSeconds(15), SignCommand.derivePollInterval(300));
  }

  @Test
  void derivePollInterval_typical600_clampsToMaximum() {
    assertEquals(Duration.ofSeconds(30), SignCommand.derivePollInterval(600));
  }

  @Test
  void derivePollInterval_largeTimeout_clampsToMaximum() {
    assertEquals(Duration.ofSeconds(30), SignCommand.derivePollInterval(9999));
  }

  // ---- deriveHttpTimeout ----

  @Test
  void deriveHttpTimeout_smallTimeout_clampsToMinimum() {
    assertEquals(Duration.ofSeconds(10), SignCommand.deriveHttpTimeout(30));
  }

  @Test
  void deriveHttpTimeout_typical600_clampsToMaximum() {
    assertEquals(Duration.ofSeconds(60), SignCommand.deriveHttpTimeout(600));
  }

  // ---- output validation ----

  @Test
  void failsWhenNoFilesProvided() {
    // picocli enforces arity=1..* before call() is invoked
    int exit =
        cli()
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                "--force-overwrite");
    assertNotEquals(0, exit);
  }

  @Test
  void failsWhenInputFileDoesNotExist() {
    int exit =
        cli()
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                "--force-overwrite",
                tempDir.resolve("nonexistent.jar").toString());
    assertNotEquals(0, exit);
  }

  @Test
  void failsWhenMultipleFilesWithOutput() throws IOException {
    Path f1 = tempDir.resolve("a.jar");
    Path f2 = tempDir.resolve("b.jar");
    Files.writeString(f1, "a");
    Files.writeString(f2, "b");

    int exit =
        cli()
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                "--output",
                tempDir.resolve("signed.jar").toString(),
                f1.toString(),
                f2.toString());
    assertNotEquals(0, exit);
  }

  @Test
  void failsWhenMultipleFilesWithoutOutputDirOrForceOverwrite() throws IOException {
    Path f1 = tempDir.resolve("a.jar");
    Path f2 = tempDir.resolve("b.jar");
    Files.writeString(f1, "a");
    Files.writeString(f2, "b");

    int exit =
        cli()
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                f1.toString(),
                f2.toString());
    assertNotEquals(0, exit);
  }

  @Test
  void failsWhenOutputAndOutputDirBothProvided() throws IOException {
    Path f = tempDir.resolve("a.jar");
    Files.writeString(f, "a");

    int exit =
        cli()
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                "--output",
                tempDir.resolve("out.jar").toString(),
                "--output-dir",
                tempDir.toString(),
                f.toString());
    assertNotEquals(0, exit);
  }

  @Test
  void failsWhenSingleFileNoOutputAndNoForceOverwrite() throws IOException {
    Path f = tempDir.resolve("a.jar");
    Files.writeString(f, "a");

    int exit =
        cli()
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                f.toString());
    assertNotEquals(0, exit);
  }

  @Test
  void failsWhenOutputSameAsInputWithoutForceOverwrite() throws IOException {
    Path f = tempDir.resolve("a.jar");
    Files.writeString(f, "a");

    int exit =
        cli()
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                "--output",
                f.toString(), // same as input
                f.toString());
    assertNotEquals(0, exit);
  }

  @Test
  void acceptsOutputDirWithMultipleFiles() throws IOException {
    // This is valid configuration (would fail later at network stage, not validation)
    // We just verify that the validation itself passes — we test success path in integration test
    // Since there's no API token source in this environment, it fails at token resolution,
    // but the exit code is still non-zero for a different reason (which is fine for this test).
    // What matters is it does NOT fail due to output-option validation.
    Path f1 = tempDir.resolve("a.jar");
    Path f2 = tempDir.resolve("b.jar");
    Files.writeString(f1, "a");
    Files.writeString(f2, "b");

    // With --output-dir, multiple files are valid. It will fail later (no token etc.) but
    // the validation error must NOT mention "multiple input files require --output-dir".
    StringWriter errOut = new StringWriter();
    int exitCode =
        new CommandLine(new CodesignCli())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(errOut))
            .setExecutionExceptionHandler(new PrintExceptionMessageHandler())
            .execute(
                "sign",
                "--organization-id",
                "org",
                "--project-id",
                "proj",
                "--signing-policy",
                "policy",
                "--output-dir",
                tempDir.resolve("out").toString(),
                f1.toString(),
                f2.toString());
    assertNotEquals(0, exitCode, "Command should fail (non-zero exit code expected)");
    // Will fail (no real server), but NOT due to output-option validation
    String err = errOut.toString();
    assertFalse(
        err.contains("Multiple input files require"),
        "Output-dir validation error should not fire");
  }
}
