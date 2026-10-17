/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Collects declared components, their source locations and Gradle's resolution evidence.
 * ConfigurationInventoryCollector handles configuration declarations and graph evidence. SourceIndex handles
 * attribution, SourceText handles offset-preserving scans, and BuildFilesInventory
 * reads wrapper and catalog files. PluginVersionInheritance reconciles captured plugin records.
 */
@NullMarked
package com.sneedit.deplens.inventory;

import org.jspecify.annotations.NullMarked;
