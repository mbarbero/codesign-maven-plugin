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

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.ParseResult;

/**
 * Prints the exception message to stderr rather than a full stack trace, keeping error output
 * readable for end users.
 */
class PrintExceptionMessageHandler implements IExecutionExceptionHandler {

  @Override
  public int handleExecutionException(
      Exception ex, CommandLine commandLine, ParseResult parseResult) {
    String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
    commandLine.getErr().println(commandLine.getColorScheme().errorText("Error: " + message));
    return commandLine.getCommandSpec().exitCodeOnExecutionException();
  }
}
