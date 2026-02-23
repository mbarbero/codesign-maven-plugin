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

import java.util.Properties;
import picocli.CommandLine;

/** Reads the CLI version from the bundled {@code version.properties} resource. */
class VersionProvider implements CommandLine.IVersionProvider {

  @Override
  public String[] getVersion() throws Exception {
    Properties props = new Properties();
    try (var stream = VersionProvider.class.getResourceAsStream("/version.properties")) {
      if (stream == null) {
        return new String[] {"codesign (unknown version)"};
      }
      props.load(stream);
    }
    String version = props.getProperty("version", "unknown");
    return new String[] {"codesign " + version};
  }
}
