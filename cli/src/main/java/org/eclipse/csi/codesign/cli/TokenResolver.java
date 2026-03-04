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

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Resolves the SignPath API token from multiple sources in priority order:
 *
 * <ol>
 *   <li>{@code CSI_CODESIGN_API_TOKEN} environment variable
 *   <li>{@code api.token} key in {@code ~/.config/codesign/config.properties}
 * </ol>
 */
class TokenResolver {

  static final String ENV_VAR = "CSI_CODESIGN_API_TOKEN";

  static final Path DEFAULT_CONFIG_FILE =
      Path.of(System.getProperty("user.home"), ".config", "codesign", "config.properties");

  private TokenResolver() {}

  /**
   * Resolves the API token using the default environment and config-file sources.
   *
   * @return the resolved token, or {@code null} when no source provides a non-blank value
   */
  public static String resolve() {
    return resolve(
        System.getenv(ENV_VAR), DEFAULT_CONFIG_FILE, msg -> System.err.println("[WARNING] " + msg));
  }

  /**
   * Resolves the API token from the provided sources (visible for testing).
   *
   * @param envToken value from the environment variable; may be {@code null}
   * @param configFile path to the properties config file; need not exist
   * @return the resolved token, or {@code null} when no source provides a non-blank value
   */
  static String resolve(String envToken, Path configFile) {
    return resolve(envToken, configFile, ignored -> {});
  }

  /**
   * Resolves the API token from the provided sources (visible for testing).
   *
   * @param envToken value from the environment variable; may be {@code null}
   * @param configFile path to the properties config file; need not exist
   * @param warnLogger consumer for warning messages; called when insecure file permissions detected
   * @return the resolved token, or {@code null} when no source provides a non-blank value
   */
  static String resolve(String envToken, Path configFile, Consumer<String> warnLogger) {
    if (envToken != null && !envToken.isBlank()) {
      return envToken;
    }

    if (Files.isReadable(configFile)) {
      checkConfigFilePermissions(configFile, warnLogger);

      Properties props = new Properties();
      try (Reader reader = Files.newBufferedReader(configFile)) {
        props.load(reader);
        String fileToken = props.getProperty("api.token");
        if (fileToken != null && !fileToken.isBlank()) {
          return fileToken;
        }
      } catch (IOException ignored) {
        // If the file cannot be read, fall through to return null
      }
    }

    return null;
  }

  /**
   * Checks config file permissions and warns if the file is readable by users other than the owner.
   *
   * <p>On POSIX filesystems (Linux, macOS), checks group/other read bits and suggests {@code chmod
   * 600}. On Windows (NTFS/ReFS), checks the ACL for any non-owner {@code ALLOW} entry that grants
   * {@code READ_DATA}.
   */
  private static void checkConfigFilePermissions(Path configFile, Consumer<String> warnLogger) {
    // Try POSIX first
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(configFile);
      boolean groupCanRead = perms.contains(PosixFilePermission.GROUP_READ);
      boolean othersCanRead = perms.contains(PosixFilePermission.OTHERS_READ);
      if (groupCanRead || othersCanRead) {
        warnLogger.accept(
            "Config file "
                + configFile
                + " is readable by group or others. "
                + "Run: chmod 600 "
                + configFile);
      }
      return; // POSIX check succeeded — no need for ACL check
    } catch (UnsupportedOperationException ignored) {
      // Not a POSIX filesystem — fall through to Windows ACL check
    } catch (IOException ignored) {
      return; // Cannot read permissions — skip check
    }

    // Windows (NTFS/ReFS) ACL check
    try {
      AclFileAttributeView aclView =
          Files.getFileAttributeView(configFile, AclFileAttributeView.class);
      if (aclView == null) {
        return;
      }
      UserPrincipal owner = Files.getOwner(configFile);
      List<AclEntry> acl = aclView.getAcl();
      boolean othersCanRead =
          acl.stream()
              .filter(e -> e.type() == AclEntryType.ALLOW)
              .filter(e -> !e.principal().equals(owner))
              .anyMatch(e -> e.permissions().contains(AclEntryPermission.READ_DATA));
      if (othersCanRead) {
        warnLogger.accept(
            "Config file "
                + configFile
                + " may be readable by other users."
                + " Restrict access to the owner only.");
      }
    } catch (IOException ignored) {
      // Cannot read ACL — skip check
    }
  }
}
