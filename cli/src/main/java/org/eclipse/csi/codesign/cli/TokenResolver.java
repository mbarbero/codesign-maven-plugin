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
import java.util.Properties;

/**
 * Resolves the SignPath API token from multiple sources in priority order:
 *
 * <ol>
 *   <li>{@code --api-token} CLI option
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
   * @param cliToken value from {@code --api-token} option; may be {@code null}
   * @return the resolved token, or {@code null} when no source provides a non-blank value
   */
  public static String resolve(String cliToken) {
    return resolve(cliToken, System.getenv(ENV_VAR), DEFAULT_CONFIG_FILE);
  }

  /**
   * Resolves the API token from the provided sources (visible for testing).
   *
   * @param cliToken value from {@code --api-token}; may be {@code null}
   * @param envToken value from the environment variable; may be {@code null}
   * @param configFile path to the properties config file; need not exist
   * @return the resolved token, or {@code null} when no source provides a non-blank value
   */
  static String resolve(String cliToken, String envToken, Path configFile) {
    if (cliToken != null && !cliToken.isBlank()) {
      return cliToken;
    }

    if (envToken != null && !envToken.isBlank()) {
      return envToken;
    }

    if (Files.isReadable(configFile)) {
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
}
