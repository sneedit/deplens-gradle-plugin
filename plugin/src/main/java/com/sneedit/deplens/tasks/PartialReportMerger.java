/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.tasks;

import com.google.gson.Gson;
import com.sneedit.deplens.inventory.BuildInventory;
import com.sneedit.deplens.inventory.InventoryEntry;
import com.sneedit.deplens.inventory.PartialCheckReport;
import com.sneedit.deplens.resolution.AnalysisModel;
import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates project report boundaries and merges results in analysis-model order.
 */
final class PartialReportMerger {
    private PartialReportMerger() {
    }

    static void merge(BuildInventory report, List<AnalysisModel> models, Set<File> files) {
        if (files.size() != models.size())
            throw new GradleException("DepLens partial report count does not match the analyzed projects");
        var entries = new LinkedHashMap<String, InventoryEntry>();
        report.entries.forEach(entry -> entries.put(entry.id(), entry));
        var decoder = new Gson();
        var partials = new LinkedHashMap<String, PartialCheckReport>();
        for (var file : files) {
            try {
                var partial = Objects.requireNonNull(decoder.fromJson(Files.readString(file.toPath()),
                        PartialCheckReport.class), "Missing DepLens partial report");
                if (partials.putIfAbsent(partial.build() + "|" + partial.project(), partial) != null)
                    throw new GradleException("Duplicate DepLens project report");
            } catch (IOException failure) {
                throw new GradleException("Cannot read DepLens partial report", failure);
            }
        }
        for (var model : models) {
            var partial = Objects.requireNonNull(partials.get(model.build + "|" + model.project),
                    "Missing DepLens project report");
            partial.report().entries.forEach(entry -> {
                if (!entry.build.equals(model.build) || (!entry.project.equals(model.project)
                        && !(model.project.equals(":") && entry.project.equals("<settings>"))))
                    throw new GradleException("DepLens partial report contains a different project's entries");
                entries.put(entry.id(), entry);
            });
            report.warnings.removeAll(partial.removedWarnings());
            partial.addedWarnings().forEach(warning -> {
                if (!report.warnings.contains(warning)) report.warnings.add(warning);
            });
            report.builds.addAll(partial.report().builds);
            report.fastCheck |= partial.report().fastCheck;
        }
        report.entries.clear();
        report.entries.addAll(entries.values());
    }
}
