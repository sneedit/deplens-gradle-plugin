/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.functional;

import org.gradle.testkit.runner.BuildResult;

/** Reads console text without ANSI styling; layout and wording remain part of the assertion. */
final class ConsoleOutput {
    private ConsoleOutput() { }

    static String plain(BuildResult result) {
        return plain(result.getOutput());
    }

    static String plain(String output) {
        return output.replaceAll("\\x1B\\[[0-9;]*m", "");
    }
}
