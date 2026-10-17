# Isolated composite build

This example checks seven project contexts in four builds:

```text
isolated-composite/       root project
├── app/                 depends on project :lib
├── lib/
├── buildSrc/            separate build, checked independently
├── included-one/        includeBuild, with subproject :tool
│   └── tool/
└── included-two/        second includeBuild
```

Each project configures itself. DepLens is applied through the settings plugin;
root rules also apply to subprojects, `buildSrc` and included builds. The local
DepLens checkout is loaded through `pluginManagement.includeBuild('../..')`, so
publishing to Maven Local is unnecessary. That development build is excluded
from the consumer inventory.

## Prepare

Run all commands below **from the DepLens repository root**. Use **JDK 21** with
the checked-in Gradle 8.14 wrapper, and have Python 3 available. On macOS, select
JDK 21 with:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

Generate the local Maven fixture once, before the two runs:

```sh
python3 examples/isolated-composite/prepare_repository.py
```

The fixture contains empty JARs for `com.example:demo-library` versions `1.0.0`,
`1.1.0` and `2.0.0` under `build/fixture-repository`. All seven projects declare
`1.0.0`; the root's `allowedUpdates = 'minor'` rule permits `1.1.0`.
The generated repository and reports are ignored by Git. Gradle may still need
internet access to build DepLens itself on the first invocation.

## First run: store the configuration cache

```sh
./gradlew -p examples/isolated-composite depLensCheck \
  --configuration-cache --configuration-cache-problems=fail \
  -Dorg.gradle.unsafe.isolated-projects=true --console=plain

cp examples/isolated-composite/build/reports/deplens/depLensCheck.json \
  examples/isolated-composite/build/depLensCheck-first.json
```

On a fresh example, Gradle reports `Configuration cache entry stored.` Expect
one deduplicated update, `com.example:demo-library 1.0.0 -> 1.1.0`, with `2.0.0`
shown as the latest version, and a complete check with zero failures. The JSON
contains all seven consumers in their original projects and builds.

## Second run: reuse the cache and compare results

Do not change build files or regenerate the fixture between these runs. Use the
same command and flags:

```sh
./gradlew -p examples/isolated-composite depLensCheck \
  --configuration-cache --configuration-cache-problems=fail \
  -Dorg.gradle.unsafe.isolated-projects=true --console=plain

cmp examples/isolated-composite/build/depLensCheck-first.json \
  examples/isolated-composite/build/reports/deplens/depLensCheck.json
```

Expect `Reusing configuration cache.` and `Configuration cache entry reused.`
The update task still executes. `cmp` exits with status zero and prints nothing
when the complete JSON reports are byte-for-byte identical. With `--info`, DepLens
also shows the individual consumer contexts.

If a cache entry already exists, the first command may reuse it too. Editing a
build script invalidates the relevant cache entry; `--rerun-tasks` alone does not
force configuration to run again. `clean` removes the fixture repository, so run
the preparation script again afterwards.

## Gradle 9.7 or newer

With a compatible Gradle installation, replace `./gradlew` with `gradle` and
replace `-Dorg.gradle.unsafe.isolated-projects=true` with `--isolated-projects`
in **both** commands. For example:

```sh
gradle -p examples/isolated-composite depLensCheck \
  --configuration-cache --configuration-cache-problems=fail \
  --isolated-projects --console=plain
```

The same report paths and `cmp` command apply. JDK 21 works for these runs too;
JDK 25 requires a Gradle runtime that supports it.

## CI verification

The build matrix runs this example on Gradle 8.8 / JDK 21, Gradle 8.14 / JDK 21,
and Gradle 9.8.0 / JDK 21 and 25. Run the same verification locally with:

```sh
python3 scripts/test-isolated-example.py "$PWD/gradlew" --gradle-version 8.14
```

The script prepares the fixture repository and uses a fresh project cache for
two runs with project isolation and strict Configuration Cache checking. It fails
unless the second run reuses the cache, both JSON reports are byte-identical,
and all seven consumer contexts across the four builds return the expected update
with complete checks. CI uploads both logs and JSON reports from
`examples/isolated-composite/build/isolation-ci/`, including on failure.
