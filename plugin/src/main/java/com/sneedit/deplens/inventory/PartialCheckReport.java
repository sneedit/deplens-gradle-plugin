/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.inventory;

import java.util.List;

/**
 * Warning deltas preserve successful baseline refreshes when independently checked projects are merged.
 */
public record PartialCheckReport(String build, String project, BuildInventory report,
                                 List<String> removedWarnings, List<String> addedWarnings) {
}
