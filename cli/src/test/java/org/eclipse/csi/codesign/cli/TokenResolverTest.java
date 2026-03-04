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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class TokenResolverTest {

  @TempDir Path tempDir;

  @Test
  void cliTokenTakesPriorityOverAll() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("cli-token", TokenResolver.resolve("cli-token", "env-token", config));
  }

  @Test
  void envVarTakesPriorityOverConfigFile() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("env-token", TokenResolver.resolve(null, "env-token", config));
  }

  @Test
  void configFileIsLastResort() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("file-token", TokenResolver.resolve(null, null, config));
  }

  @Test
  void returnsNullWhenNothingConfigured() {
    Path noConfig = tempDir.resolve("nonexistent.properties");
    assertNull(TokenResolver.resolve(null, null, noConfig));
  }

  @Test
  void ignoresBlankCliToken() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("env-token", TokenResolver.resolve("  ", "env-token", config));
  }

  @Test
  void ignoresBlankEnvToken() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("file-token", TokenResolver.resolve(null, "  ", config));
  }

  @Test
  void ignoresBlankConfigFileToken() {
    Path config = createConfig(tempDir, "   ");
    assertNull(TokenResolver.resolve(null, null, config));
  }

  @Test
  void handlesNonExistentConfigFileGracefully() {
    assertNull(TokenResolver.resolve(null, null, tempDir.resolve("missing.properties")));
  }

  @Test
  void parsesConfigFileWithComments() throws IOException {
    Path config = tempDir.resolve("config.properties");
    Files.writeString(config, "# This is a comment\napi.token=my-secret-token\n");
    assertEquals("my-secret-token", TokenResolver.resolve(null, null, config));
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void configFileWithGroupReadPermissionEmitsWarning() throws IOException {
    Path config = tempDir.resolve("config.properties");
    Files.writeString(config, "api.token=secret\n");

    Set<PosixFilePermission> perms =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);
    Files.setPosixFilePermissions(config, perms);

    List<String> warnings = new ArrayList<>();
    String token = TokenResolver.resolve(null, null, config, warnings::add);

    assertEquals("secret", token);
    assertTrue(
        warnings.stream().anyMatch(w -> w.contains("chmod 600")),
        "Expected warning mentioning chmod 600, got: " + warnings);
  }

  private static Path createConfig(Path dir, String token) {
    Path config = dir.resolve("config.properties");
    try {
      Files.writeString(config, "api.token=" + token + "\n");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return config;
  }
}
