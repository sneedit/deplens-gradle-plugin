/*
 * SPDX-FileCopyrightText: 2026 Sneed IT
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sneedit.deplens.functional;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static com.sneedit.deplens.functional.ConsoleOutput.plain;

/**
 * Live analysis must survive a cache hit without freezing update candidates or visiting foreign outer projects.
 */
class CacheExecutionFunctionalTest {
    @TempDir
    Path dir;

    /**
     * Provides the read-only tasks covered by cache reuse tests.
     */
    static java.util.stream.Stream<Arguments> readOnlyTasks() {
        return List.of("depLensInventory", "depLensCheckDependencies", "depLensCheckPlugins",
                        "depLensCheckGradle", "depLensCheck").stream()
                .flatMap(task -> java.util.stream.Stream.of(Arguments.of(task, false), Arguments.of(task, true)));
    }

    /**
     * Verifies that composite tasks reuse identical results across every build.
     */
    @ParameterizedTest(name = "{0}, isolation={1}")
    @MethodSource("readOnlyTasks")
    @Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void compositeTasksReuseIdenticalResultsAcrossEveryBuild(String task, boolean isolated) throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "2.0.0");
        fakePlugin();
        write("settings.gradle", "pluginManagement { repositories { maven { url=uri('repo') } } }\n" + """
                plugins { id 'com.sneedit.deplens.settings' }
                rootProject.name='isolated-composite'
                include 'app', 'lib'
                includeBuild 'included-one'
                includeBuild 'included-two'
                """);
        for (String project : List.of("", "app/", "lib/", "buildSrc/", "included-one/", "included-one/tool/", "included-two/")) {
            String repository = project.isEmpty() ? "repo" : project.equals("included-one/tool/") ? "../../repo" : "../repo";
            write(project + "build.gradle", "plugins { id 'java' }\nrepositories { maven { url=uri('" + repository
                    + "') } }\ndependencies { implementation 'org.test:lib:1.0.0' }\n");
        }
        Files.writeString(dir.resolve("build.gradle"), "depLens { rules { dependency('org.test:lib') { allowedUpdates='minor' } } }\n", StandardOpenOption.APPEND);
        // A real external plugin marker and a local wrapper feed exercise both dedicated checkers.
        Files.writeString(dir.resolve("build.gradle"), Files.readString(dir.resolve("build.gradle"))
                .replace("id 'java'", "id 'java'; id 'org.test.fake' version '1.0.0'")
                + "depLens { gradle { versionsUrl=file('feed.json').toURI().toString() } }\n");
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("feed.json", "[{\"version\":\"8.8\"},{\"version\":\"8.9\"}]");
        Files.writeString(dir.resolve("app/build.gradle"), "dependencies { implementation project(':lib') }\n", StandardOpenOption.APPEND);
        write("buildSrc/settings.gradle", "rootProject.name='buildSrc'\n");
        write("included-one/settings.gradle", "rootProject.name='included-one'\ninclude 'tool'\n");
        write("included-two/settings.gradle", "rootProject.name='included-two'\n");
        String firstJson = null;
        String reportName = task.equals("depLensInventory") ? "inventory" : task;
        for (int attempt = 0; attempt < 2; attempt++) {
            Files.deleteIfExists(dir.resolve("build/reports/deplens/" + reportName + ".json"));
            var result = run(task, isolated ? new String[]{isolationFlag()} : new String[0]).build();
            var output = plain(result);
            if (isolated) assertThat(output).containsIgnoringCase("Isolated projects is an incubating feature");
            assertThat(result.task(":" + task).getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(output).contains(attempt == 0 ? "Configuration cache entry stored" : "Reusing configuration cache");
            if (attempt == 1)
                assertThat(output).contains("Configuration cache entry reused").doesNotContain("Calculating task graph");
            String json = Files.readString(dir.resolve("build/reports/deplens/" + reportName + ".json"));
            var report = JsonParser.parseString(json).getAsJsonObject();
            assertThat(report.get("complete").getAsBoolean()).as(json).isTrue();
            if (!task.equals("depLensInventory"))
                assertThat(report.get("checksComplete").getAsBoolean()).as(json).isTrue();
            var all = report.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject).toList();
            assertThat(all).noneMatch(e -> e.getAsJsonArray("statuses").toString().contains("FAILED"));
            var entries = all.stream()
                    .filter(e -> e.get("coordinate").getAsString().equals("org.test:lib")).toList();
            assertThat(entries).hasSize(7);
            if (Set.of("depLensCheckDependencies", "depLensCheck").contains(task))
                assertThat(entries).allSatisfy(e -> assertThat(e.get("recommendedVersion").getAsString()).isEqualTo("1.1.0"));
            if (Set.of("depLensCheckPlugins", "depLensCheck").contains(task))
                assertThat(all.stream().filter(e -> e.get("coordinate").getAsString().equals("org.test.fake")).toList())
                        .isNotEmpty().allSatisfy(e -> assertThat(e.get("recommendedVersion").getAsString()).isEqualTo("1.1.0"));
            if (Set.of("depLensCheckGradle", "depLensCheck").contains(task))
                assertThat(output).contains("8.8 → 8.9");
            assertThat(entries.stream().map(e -> Path.of(e.get("build").getAsString())).distinct())
                    .containsExactlyInAnyOrder(dir.toRealPath(), dir.resolve("buildSrc").toRealPath(),
                            dir.resolve("included-one").toRealPath(), dir.resolve("included-two").toRealPath());
            if (firstJson == null) firstJson = json;
            else assertThat(json).isEqualTo(firstJson);
        }
    }

    /**
     * Verifies that configuration cache without isolation reuses identical update results.
     */
    @Test
    void configurationCacheWithoutIsolationReusesIdenticalUpdateResults() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0");
        write("settings.gradle", "plugins { id 'com.sneedit.deplens.settings' }\ninclude 'sub'\nincludeBuild 'nested'\n");
        for (String prefix : List.of("", "sub/", "nested/"))
            write(prefix + "build.gradle",
                    "plugins { id 'java' }\nrepositories { maven { url=uri('" + (prefix.isEmpty() ? "repo" : "../repo")
                            + "') } }\ndependencies { implementation 'org.test:lib:1.0.0' }\n");
        write("nested/settings.gradle", "rootProject.name='nested'\n");
        // Kafka applies Scala; its built-in Zinc rule is a hidden Gradle lambda.
        Files.writeString(dir.resolve("build.gradle"), """
                
                apply plugin: 'scala'
                dependencies { zinc 'org.test:lib:1.0.0' }
                // The synthetic Maven module has no incremental Scala analysis variant.
                configurations.matching { it.name.startsWith('incrementalScalaAnalysisFor') }.configureEach { exclude group: 'org.test' }
                """, StandardOpenOption.APPEND);
        assertThat(plain(run("depLensCheck").build())).contains("Configuration cache entry stored", "1.0.0 → 1.1.0");
        assertComplete("depLensCheck", 3);
        String first = Files.readString(dir.resolve("build/reports/deplens/depLensCheck.json"));
        assertThat(plain(run("depLensCheck").build())).contains("Reusing configuration cache", "1.0.0 → 1.1.0");
        assertThat(JsonParser.parseString(Files.readString(dir.resolve("build/reports/deplens/depLensCheck.json")))).isEqualTo(JsonParser.parseString(first));
    }

    /**
     * Verifies that cache reuse checks fresh versions and preserves all build contexts and rules.
     */
    @Test
    void cacheReuseChecksFreshVersionsAndPreservesAllBuildContextsAndRules() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "2.0.0");
        write("settings.gradle", "new File(settingsDir, '.analysis-processes').append(ProcessHandle.current().pid().toString() + '\\n')\nrootProject.name='cache-test'\ninclude 'sub'\nincludeBuild 'nested'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { rules { dependency('org.test:lib') { allowedUpdates='minor' } } }
                """);
        write("sub/build.gradle", "plugins { id 'java' }\nrepositories { maven { url = uri('../repo') } }\ndependencies { implementation 'org.test:lib:1.0.0' }\n");
        for (String build : List.of("nested", "buildSrc")) {
            write(build + "/settings.gradle", "rootProject.name='" + build + "'\n");
            write(build + "/build.gradle", "plugins { id 'java' }\nrepositories { maven { url = uri('../repo') } }\ndependencies { implementation 'org.test:lib:1.0.0' }\n");
        }
        var first = run("depLensCheck", isolationFlag()).build();
        assertThat(plain(first)).contains("Configuration cache entry stored", "1.0.0 → 1.1.0");
        assertComplete("depLensCheck", 4);
        assertThat(Files.readAllLines(dir.resolve(".analysis-processes"))).hasSize(1);
        // No build script changes: the outer task graph must be reused, but candidates must be queried again.
        modules("org.test", "lib", "1.0.0", "1.1.0", "1.2.0", "2.0.0");
        var second = run("depLensCheck", isolationFlag()).build();
        assertThat(plain(second)).contains("Reusing configuration cache", "1.0.0 → 1.2.0");
        assertThat(plain(second)).doesNotContain("1.0.0 → 2.0.0");
        assertComplete("depLensCheck", 4);
        assertThat(Files.readAllLines(dir.resolve(".analysis-processes"))).hasSize(1);
        assertThat(plain(second)).doesNotContain("Download ", "Starting a Gradle Daemon", "Calculating task graph");
    }

    /**
     * Verifies that settings entry point supports isolation and reuses cache.
     */
    @Test
    void settingsEntryPointSupportsIsolationAndReusesCache() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0");
        write("settings.gradle", "plugins { id 'com.sneedit.deplens.settings' }\nrootProject.name='isolated-test'\ninclude 'sub'\n");
        for (String prefix : List.of("", "sub/"))
            write(prefix + "build.gradle",
                    "plugins { id 'java' }\nrepositories { maven { url = uri('" + (prefix.isEmpty() ? "repo" : "../repo") + "') } }\ndependencies { implementation 'org.test:lib:1.0.0' }\n");
        String isolation = isolationFlag();
        var first = run("depLensCheckDependencies", isolation).build();
        assertThat(plain(first)).contains("Configuration cache entry stored", "1.0.0 → 1.1.0");
        assertComplete("depLensCheckDependencies", 2);
        var second = run("depLensCheckDependencies", isolation).build();
        assertThat(plain(second)).contains("Reusing configuration cache", "1.0.0 → 1.1.0");
    }

    /**
     * Verifies that cache reuse preserves failures and updated configuration.
     */
    @Test
    void cacheReusePreservesFailuresAndUpdatedConfiguration() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0");
        write("settings.gradle", "rootProject.name='failure-test'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url = uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { dependencies { failIfUpdatesFound=true } }
                """);
        assertThat(plain(run("depLensCheckDependencies").buildAndFail())).contains("Updates available", "Configuration cache entry stored");
        assertThat(plain(run("depLensCheckDependencies").buildAndFail())).contains("Updates available", "Reusing configuration cache");
        assertComplete("depLensCheckDependencies", 1);
        Files.writeString(dir.resolve("build.gradle"), Files.readString(dir.resolve("build.gradle")).replace("failIfUpdatesFound=true", "allowedUpdates='patch'"));
        assertThat(plain(run("depLensCheckDependencies").build())).contains("0 recommended updates, 0 available only").doesNotContain("1.0.0 → 1.1.0");
        Files.writeString(dir.resolve("settings.gradle"), "include 'sub'\n", StandardOpenOption.APPEND);
        write("sub/build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url=uri('../repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                depLens { dependencies { failIfUpdatesFound=true } }
                """);
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).buildAndFail())).contains("Updates available", "Configuration cache entry stored");
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).buildAndFail())).contains("Updates available", "Reusing configuration cache");
    }

    /**
     * Verifies that inventory plugin and wrapper tasks keep reports and feed fresh on cache reuse.
     */
    @Test
    void inventoryPluginAndWrapperTasksKeepReportsAndFeedFreshOnCacheReuse() throws Exception {
        fakePlugin();
        write("settings.gradle", "pluginManagement { repositories { maven { url=uri('repo') } } }\nrootProject.name='operations'\n");
        write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");
        write("feed.json", "[{\"version\":\"8.8\"},{\"version\":\"8.9\"}]");
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens'; id 'org.test.fake' version '1.0.0' }
                depLens { gradle { versionsUrl = file('feed.json').toURI().toString() } }
                """);
        String[] others = {":depLensCheckGradle", ":depLensInventory"};
        var first = run(":depLensCheckPlugins", others).build();
        assertThat(plain(first)).contains("1.0.0 → 1.1.0", "8.8 → 8.9", "Inventory:", "Configuration cache entry stored");
        assertThat(Files.isRegularFile(dir.resolve("build/reports/deplens/inventory.json"))).isTrue();
        write("feed.json", "[{\"version\":\"8.8\"},{\"version\":\"8.9\"},{\"version\":\"8.10\"}]");
        var second = run(":depLensCheckPlugins", others).build();
        assertThat(plain(second)).contains("Reusing configuration cache", "8.8 → 8.10", "1.0.0 → 1.1.0", "Inventory:");
    }

    /**
     * Publishes the plugin fixture used by cache reuse checks.
     */
    private void fakePlugin() throws Exception {
        modules("org.test.fake", "org.test.fake.gradle.plugin", "1.0.0", "1.1.0");
        modules("org.test", "implementation", "1.0.0");
        try (var jar = new JarOutputStream(Files.newOutputStream(dir.resolve("repo/org/test/implementation/1.0.0/implementation-1.0.0.jar")))) {
            jar.putNextEntry(new JarEntry("META-INF/gradle-plugins/org.test.fake.properties"));
            jar.write("implementation-class=org.gradle.api.plugins.JavaPlugin\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        for (String version : List.of("1.0.0", "1.1.0"))
            write("repo/org/test/fake/org.test.fake.gradle.plugin/" + version + "/org.test.fake.gradle.plugin-" + version + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>org.test.fake</groupId><artifactId>org.test.fake.gradle.plugin</artifactId><version>" + version
                            + "</version><packaging>pom</packaging><dependencies><dependency><groupId>org.test</groupId><artifactId>implementation</artifactId><version>1.0.0</version></dependency></dependencies></project>");
    }

    /**
     * Verifies that opaque component selection rules survive repeated invocations without unsafe cache reuse.
     */
    @Test
    void opaqueComponentSelectionRulesDisableCacheAndPreserveFreshResults() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "2.0.0");
        write("settings.gradle", "rootProject.name='selection-rules'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url=uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                configurations.configureEach {
                    resolutionStrategy.componentSelection.all { selection ->
                        if (selection.candidate.version.startsWith('2.')) selection.reject('unsupported series')
                    }
                }
                """);
        var firstOutput = plain(runWithStatefulRules("depLensCheckDependencies").build());
        assertThat(firstOutput).contains("1.0.0 → 1.1.0");
        assertStatefulCacheDisabled(firstOutput);
        modules("org.test", "lib", "1.0.0", "1.1.0", "1.2.0", "2.0.0");
        var secondOutput = plain(runWithStatefulRules("depLensCheckDependencies").build());
        assertThat(secondOutput).contains("1.0.0 → 1.2.0");
        assertStatefulCacheDisabled(secondOutput);
        assertComplete("depLensCheckDependencies", 1);
    }

    /**
     * Verifies that cache hit refreshes dynamic current versions and recovers missing modules.
     */
    @Test
    void cacheHitRefreshesDynamicCurrentVersionsAndRecoversMissingModules() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0");
        write("settings.gradle", "rootProject.name='changing-resolution'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url=uri('repo') } }
                dependencies { implementation 'org.test:lib:1.+'; implementation 'org.test:missing:1.0.0' }
                """);
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).build())).contains("inventory incomplete", "Configuration cache entry stored");
        modules("org.test", "lib", "1.0.0", "1.1.0", "1.2.0");
        modules("org.test", "missing", "1.0.0", "1.1.0");
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).build())).contains("org.test:missing 1.0.0 → 1.1.0");
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).build())).contains("Reusing configuration cache").doesNotContain(" failed", " not checked");
        assertComplete("depLensCheckDependencies", 1);
        var report = JsonParser.parseString(Files.readString(dir.resolve("build/reports/deplens/depLensCheckDependencies.json"))).getAsJsonObject();
        var lib = report.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(e -> e.get("coordinate").getAsString().equals("org.test:lib")).findFirst().orElseThrow();
        assertThat(lib.getAsJsonArray("resolutions").asList()).allMatch(r -> r.getAsJsonObject().get("version").getAsString().equals("1.2.0"));
    }

    /**
     * Verifies that native queries retain repository configuration filters.
     */
    @Test
    void nativeQueriesRetainRepositoryConfigurationFilters() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0");
        write("settings.gradle", "rootProject.name='filtered-repository'\n");
        write("build.gradle", """
                plugins { id 'com.sneedit.deplens' }
                configurations { probe { canBeResolved=true; canBeConsumed=false } }
                repositories { maven { url=uri('repo'); content { onlyForConfigurations('probe'); includeGroup('org.test') } } }
                dependencies { probe 'org.test:lib:1.0.0' }
                """);
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).build())).contains("1.0.0 → 1.1.0", "Configuration cache entry stored");
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).build())).contains("1.0.0 → 1.1.0", "Reusing configuration cache");
        assertComplete("depLensCheckDependencies", 1);
    }

    /**
     * Verifies that native queries retain ivy pattern layouts.
     */
    @Test
    void nativeQueriesRetainIvyPatternLayouts() throws Exception {
        for (String version : List.of("1.0.0", "1.1.0")) {
            write("ivy/org.test/lib/" + version + "/ivy.xml", "<ivy-module version='2.0'><info organisation='org.test' module='lib' revision='" + version + "'/><publications><artifact name='lib' type='jar' ext='jar'/></publications></ivy-module>");
            try (var ignored = new JarOutputStream(Files.newOutputStream(dir.resolve("ivy/org.test/lib/" + version + "/lib.jar")))) {
            }
        }
        write("settings.gradle", "rootProject.name='ivy-layout'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { ivy { url=uri('ivy'); patternLayout {
                    ivy '[organisation]/[module]/[revision]/ivy.xml'
                    artifact '[organisation]/[module]/[revision]/[artifact].[ext]'
                }; metadataSources { ivyDescriptor() } } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                """);
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).build())).contains("1.0.0 → 1.1.0", "Configuration cache entry stored");
        assertThat(plain(run("depLensCheckDependencies", isolationFlag()).build())).contains("1.0.0 → 1.1.0", "Reusing configuration cache");
        assertComplete("depLensCheckDependencies", 1);
    }

    /**
     * Verifies that native queries retain constraints exported by project dependencies.
     */
    @Test
    void nativeQueriesRetainConstraintsExportedByProjectDependencies() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "2.0.0");
        write("settings.gradle", "rootProject.name='project-constraints'\ninclude 'policy'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url=uri('repo') } }
                dependencies { implementation project(':policy'); implementation 'org.test:lib:1.0.0' }
                """);
        write("policy/build.gradle", """
                plugins { id 'java-library' }
                dependencies { constraints { api('org.test:lib') { version { strictly '1.1.0' } } } }
                """);
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = plain(run("depLensCheckDependencies", isolationFlag()).build());
            assertThat(output).contains(attempt == 0 ? "Configuration cache entry stored" : "Reusing configuration cache");
            var report = JsonParser.parseString(Files.readString(dir.resolve("build/reports/deplens/depLensCheckDependencies.json"))).getAsJsonObject();
            var lib = report.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("project").getAsString().equals(":") && e.get("coordinate").getAsString().equals("org.test:lib")).findFirst().orElseThrow();
            assertThat(lib.getAsJsonArray("updateContexts").asList()).isNotEmpty().allMatch(c -> c.getAsJsonObject().get("latestAllowedByBuild").getAsString().equals("1.1.0"));
        }
    }

    /**
     * Verifies that test fixtures keep variant constraints separate with consistent resolution.
     */
    @Test
    void testFixturesKeepVariantConstraintsSeparateWithConsistentResolution() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "1.2.0");
        modules("org.test", "tool", "1.0.0", "1.1.0");
        for (String version : List.of("1.0.0", "1.1.0"))
            write("repo/org/test/tool/" + version + "/tool-" + version + ".pom",
                    "<project><modelVersion>4.0.0</modelVersion><groupId>org.test</groupId><artifactId>tool</artifactId><version>" + version
                            + "</version><dependencies><dependency><groupId>org.test</groupId><artifactId>lib</artifactId><version>1.0.0</version></dependency></dependencies></project>");
        write("settings.gradle", "rootProject.name='fixture-variants'\n");
        write("build.gradle", """
                plugins { id 'java-library'; id 'java-test-fixtures'; id 'com.sneedit.deplens' }
                repositories { maven { url=uri('repo') } }
                java { consistentResolution { useCompileClasspathVersions() } }
                dependencies {
                    implementation 'org.test:lib:1.1.0'
                    testFixturesImplementation 'org.test:tool:1.0.0'
                    testImplementation 'org.test:tool:1.0.0'
                }
                """);
        JsonElement first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = plain(run("depLensCheckDependencies", isolationFlag()).build());
            assertThat(output).contains(attempt == 0 ? "Configuration cache entry stored" : "Reusing configuration cache");
            assertComplete("depLensCheckDependencies", 1);
            var report = JsonParser.parseString(Files.readString(dir.resolve("build/reports/deplens/depLensCheckDependencies.json")));
            var tool = report.getAsJsonObject().getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("coordinate").getAsString().equals("org.test:tool")).findFirst().orElseThrow();
            assertThat(tool.getAsJsonArray("updateContexts").asList()).isNotEmpty().allMatch(c -> c.getAsJsonObject().get("latestAllowedByBuild").getAsString().equals("1.1.0"));
            if (first == null) first = report;
            else assertThat(report).isEqualTo(first);
        }
    }

    /**
     * Verifies that opaque dependency rules retain their original captured values without unsafe cache reuse.
     */
    @Test
    void opaqueDependencyRulesDisableCacheAndRetainCapturedValues() throws Exception {
        modules("org.test", "lib", "1.0.0", "1.1.0", "2.0.0");
        write("settings.gradle", "rootProject.name='dependency-rules'\n");
        write("build.gradle", """
                plugins { id 'java'; id 'com.sneedit.deplens' }
                repositories { maven { url=uri('repo') } }
                dependencies { implementation 'org.test:lib:1.0.0' }
                def accepted = '1.1.0'
                configurations.configureEach {
                    resolutionStrategy.eachDependency { details ->
                        if (details.requested.group == 'org.test') details.useVersion(accepted)
                    }
                }
                """);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertStatefulCacheDisabled(plain(runWithStatefulRules("depLensCheckDependencies").build()));
            var report = JsonParser.parseString(Files.readString(dir.resolve("build/reports/deplens/depLensCheckDependencies.json"))).getAsJsonObject();
            var lib = report.getAsJsonArray("entries").asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(e -> e.get("coordinate").getAsString().equals("org.test:lib")).findFirst().orElseThrow();
            assertThat(lib.getAsJsonArray("updateContexts").asList()).isNotEmpty().allMatch(c -> c.getAsJsonObject().get("latestAllowedByBuild").getAsString().equals("1.1.0"));
        }
    }

    /**
     * Returns the project isolation setting used by the test build.
     */
    private static String isolationFlag() {
        return org.gradle.util.GradleVersion.version(System.getProperty("deplens.test.gradleVersion"))
                .compareTo(org.gradle.util.GradleVersion.version("9.7")) >= 0
                ? "-Dorg.gradle.isolated-projects=true" : "-Dorg.gradle.unsafe.isolated-projects=true";
    }

    /** Older Gradle versions require an explicit opt-out for original, invocation-local rules. */
    private GradleRunner runWithStatefulRules(String task) {
        return automaticCacheDegradation() ? run(task, isolationFlag()) : run(task, "--no-configuration-cache");
    }

    private static boolean automaticCacheDegradation() {
        return org.gradle.util.GradleVersion.version(System.getProperty("deplens.test.gradleVersion"))
                .compareTo(org.gradle.util.GradleVersion.version("9.7")) >= 0;
    }

    private static void assertStatefulCacheDisabled(String output) {
        assertThat(output).doesNotContain("Reusing configuration cache", "Configuration cache entry stored");
        if (automaticCacheDegradation())
            assertThat(output).contains("Configuration cache disabled because incompatible tasks were found");
    }

    /**
     * Runs the requested task against the fixture project.
     */
    private GradleRunner run(String task, String... extra) {
        var args = new ArrayList<>(List.of(task, "--configuration-cache", "--configuration-cache-problems=fail", "--offline", "--stacktrace"));
        args.addAll(List.of(extra));
        return GradleRunner.create().withGradleVersion(System.getProperty("deplens.test.gradleVersion"))
                .withProjectDir(dir.toFile()).withPluginClasspath().withArguments(args);
    }

    /**
     * Checks report completeness and the expected consumer count.
     */
    private void assertComplete(String name, int consumers) throws Exception {
        var report = JsonParser.parseString(Files.readString(dir.resolve("build/reports/deplens/" + name + ".json"))).getAsJsonObject();
        assertThat(report.get("complete").getAsBoolean()).as(report.toString()).isTrue();
        assertThat(report.get("checksComplete").getAsBoolean()).as(report.toString()).isTrue();
        assertThat(report.getAsJsonArray("entries").asList().stream().filter(e ->
                e.getAsJsonObject().get("coordinate").getAsString().equals("org.test:lib"))).hasSize(consumers);
    }

    /**
     * Publishes the requested versions into the local test repository.
     */
    private void modules(String group, String name, String... versions) throws Exception {
        String base = "repo/" + group.replace('.', '/') + "/" + name + "/";
        for (String version : versions) {
            String stem = base + version + "/" + name + "-" + version;
            write(stem + ".pom", "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version></project>");
            try (var ignored = new JarOutputStream(Files.newOutputStream(dir.resolve(stem + ".jar")))) {
            }
        }
        write(base + "maven-metadata.xml", "<metadata><versioning><versions>" + String.join("", Arrays.stream(versions).map(v -> "<version>" + v + "</version>").toList()) + "</versions></versioning></metadata>");
    }

    /**
     * Writes fixture text under the temporary project directory.
     */
    private void write(String path, String text) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
