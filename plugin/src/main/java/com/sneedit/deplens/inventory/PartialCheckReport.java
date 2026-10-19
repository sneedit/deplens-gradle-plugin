/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sneedit.deplens.inventory;

import java.util.List;

/**
 * Warning deltas preserve successful baseline refreshes when independently checked projects are merged.
 *
 * @param build directory identifying the owning build
 * @param project Gradle project path within the build
 * @param report updated inventory for this project
 * @param removedWarnings baseline warnings resolved by the project check
 * @param addedWarnings warnings discovered by the project check
 */
public record PartialCheckReport(String build, String project, BuildInventory report,
                                 List<String> removedWarnings, List<String> addedWarnings) {
}
