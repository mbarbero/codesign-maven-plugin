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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenResolverTest {

  @TempDir Path tempDir;

  @Test
  void envVarTakesPriorityOverConfigFile() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("env-token", TokenResolver.resolve("env-token", config));
  }

  @Test
  void configFileIsLastResort() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("file-token", TokenResolver.resolve(null, config));
  }

  @Test
  void returnsNullWhenNothingConfigured() {
    Path noConfig = tempDir.resolve("nonexistent.properties");
    assertNull(TokenResolver.resolve(null, noConfig));
  }

  @Test
  void ignoresBlankEnvToken() {
    Path config = createConfig(tempDir, "file-token");
    assertEquals("file-token", TokenResolver.resolve("  ", config));
  }

  @Test
  void ignoresBlankConfigFileToken() {
    Path config = createConfig(tempDir, "   ");
    assertNull(TokenResolver.resolve(null, config));
  }

  @Test
  void handlesNonExistentConfigFileGracefully() {
    assertNull(TokenResolver.resolve(null, tempDir.resolve("missing.properties")));
  }

  @Test
  void parsesConfigFileWithComments() throws IOException {
    Path config = tempDir.resolve("config.properties");
    Files.writeString(config, "# This is a comment\napi.token=my-secret-token\n");
    assertEquals("my-secret-token", TokenResolver.resolve(null, config));
  }

  @Test
  void configFileWithGroupReadPermissionEmitsWarning() throws IOException {
    try (FileSystem fs =
        Jimfs.newFileSystem(
            Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build())) {
      Path config = fs.getPath("/home/user/.config/codesign/config.properties");
      Files.createDirectories(config.getParent());
      Files.writeString(config, "api.token=secret\n");

      Set<PosixFilePermission> perms =
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.GROUP_READ);
      Files.getFileAttributeView(config, PosixFileAttributeView.class).setPermissions(perms);

      List<String> warnings = new ArrayList<>();
      String token = TokenResolver.resolve(null, config, warnings::add);

      assertEquals("secret", token);
      assertTrue(
          warnings.stream().anyMatch(w -> w.contains("chmod 600")),
          "Expected warning mentioning chmod 600, got: " + warnings);
    }
  }

  @Test
  void configFileReadableByOtherUsersEmitsWarningOnWindowsAcl() throws IOException {
    try (FileSystem fs =
        Jimfs.newFileSystem(
            Configuration.windows().toBuilder()
                .setAttributeViews("basic", "owner", "acl", "dos")
                .build())) {
      Path config = fs.getPath("C:\\Users\\user\\.config\\codesign\\config.properties");
      Files.createDirectories(config.getParent());
      Files.writeString(config, "api.token=secret\n");

      AclFileAttributeView aclView = Files.getFileAttributeView(config, AclFileAttributeView.class);
      UserPrincipal owner = aclView.getOwner();
      UserPrincipal otherUser =
          fs.getUserPrincipalLookupService().lookupPrincipalByName("other-user");

      List<AclEntry> acl = new ArrayList<>(aclView.getAcl());
      acl.add(
          AclEntry.newBuilder()
              .setType(AclEntryType.ALLOW)
              .setPrincipal(otherUser)
              .setPermissions(AclEntryPermission.READ_DATA)
              .build());
      aclView.setAcl(acl);

      List<String> warnings = new ArrayList<>();
      String token = TokenResolver.resolve(null, config, warnings::add);

      assertEquals("secret", token);
      assertTrue(
          warnings.stream().anyMatch(w -> w.contains("readable by other users")),
          "Expected warning about other users, got: " + warnings);
    }
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
