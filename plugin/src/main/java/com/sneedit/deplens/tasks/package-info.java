/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Exposes Gradle tasks and connects collection, checks, JSON reports and console output.
 * {@link com.sneedit.deplens.tasks.DepLensInventoryTask} owns the reporting lifecycle;
 * its subclasses add checks or interactive edits. InventoryReportPrinter owns the console layout.
 */
@NullMarked
package com.sneedit.deplens.tasks;

import org.jspecify.annotations.NullMarked;
