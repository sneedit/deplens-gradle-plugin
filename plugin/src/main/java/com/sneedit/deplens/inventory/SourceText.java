/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.inventory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small text-scanning tools that keep source offsets intact for later edits.
 */
final class SourceText {
    /**
     * Keeps this utility class static-only.
     */
    private SourceText() {
    }

    /**
     * Finds the first occurrence's one-based line, or zero when the value is absent.
     */
    static int referenceLine(String text, String value) {
        int i = text.indexOf(value);
        return i < 0 ? 0 : line(text, i);
    }

    /**
     * Converts a character offset to a one-based source line.
     */
    static int line(String text, int offset) {
        return 1 + (int) text.substring(0, Math.max(0, offset)).chars().filter(c -> c == '\n').count();
    }

    /**
     * Mask comments, preserving offsets and newlines; string literals are left intact.
     */
    static String withoutComments(String text) {
        StringBuilder out = new StringBuilder(text);
        char quote = 0;
        boolean block = false, comment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (block || comment) {
                if (c != '\n' && c != '\r') out.setCharAt(i, ' ');
                if (block && c == '*' && next == '/') {
                    out.setCharAt(++i, ' ');
                    block = false;
                } else if (comment && c == '\n') comment = false;
            } else if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '/' && (next == '/' || next == '*')) {
                block = next == '*';
                comment = !block;
                out.setCharAt(i, ' ');
                out.setCharAt(++i, ' ');
            }
        }
        return out.toString();
    }

    /**
     * Checks whether the offset sits inside a named brace block.
     */
    static boolean insideBlock(String text, int offset, String name) {
        Matcher start = Pattern.compile("\\b" + name + "\\s*\\{").matcher(text);
        while (start.find() && start.start() < offset) {
            int depth = 1;
            for (int i = start.end(); i < offset; i++) {
                if (text.charAt(i) == '{') depth++;
                else if (text.charAt(i) == '}') {
                    if (--depth == 0) break;
                }
            }
            if (depth > 0) return true;
        }
        return false;
    }

    /**
     * Counts open braces before the offset, skipping braces in quoted strings.
     */
    static int blockDepth(String text, int offset) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < offset; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth;
    }
}
