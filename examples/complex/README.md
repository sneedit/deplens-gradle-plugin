# Complex dependency build

A dependency-only example with the module boundaries of an event-streaming
system. It does not implement or start a broker. The modules intentionally contain
no application sources: the build demonstrates dependency inventory, update
discovery and shared version sources in a larger Gradle project.

## Layout

```text
complex/
├── platform/          Java platform: Jackson BOM and a Metrics constraint
├── clients/           Client API, logging and a compression bundle
├── server-common/     Shared server API and platform-managed Metrics
├── metadata/          Metadata layer with BOM-managed Jackson libraries
├── storage/           Storage layer with RocksDB and compression
├── core/              Broker assembly: metadata + storage
├── streams/           Stream processing: clients + RocksDB + Jackson
│   ├── upgrade-system-tests-0110/   Streams compatibility 0.11.0.x
│   ├── upgrade-system-tests-10/     Streams compatibility 1.0.x
│   ├── upgrade-system-tests-20/     Streams compatibility 2.0.x
│   ├── upgrade-system-tests-28/     Streams compatibility 2.8.x
│   ├── upgrade-system-tests-39/     Streams compatibility 3.9.x
│   └── upgrade-system-tests-40/     Streams compatibility 4.0.x
├── connect/
│   ├── api/           Connector API built on clients
│   └── runtime/       Connector runtime, JSON and an ApacheDS test dependency
├── tools/             Command-line dependencies for clients, streams and Connect
└── gradle/
    ├── libs.versions.toml    Shared versions, aliases and bundles
    └── java-library.gradle  Common Java 21 and test task configuration
```

The `connect` parent project is a grouping project. The root and all seventeen
subprojects, including that parent and the platform, are inventoried through the
DepLens settings plugin. Each module configures itself; there are no
`allprojects` or `subprojects` configuration blocks.

DepLens is loaded directly from this checkout with
`pluginManagement.includeBuild('../..')`. Publishing to Maven Local is unnecessary.
The included plugin development build is excluded from the consumer inventory.

## Run

Run these commands from the **DepLens repository root** with **JDK 21**. Maven
Central access is required to resolve the example dependencies. The intentionally
older versions are demonstration inputs, not deployment recommendations.

```sh
# On macOS, select JDK 21 first:
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Prepare deterministic release-line dependencies (repeat after clean).
python3 examples/complex/prepare_repository.py

# Inspect declarations, resolved versions, platforms and project dependencies.
./gradlew -p examples/complex :depLensInventory --console=plain

# Discover dependency updates across all modules without editing files.
./gradlew -p examples/complex :depLensCheckDependencies --console=plain

# Open the interactive dependency update console in a terminal.
./gradlew -p examples/complex :depLensUpdateDependencies
```

JSON reports are written to `examples/complex/build/reports/deplens/`, including
`inventory.json` and `depLensCheckDependencies.json`.

## What to inspect

- The compression bundle expands into Zstandard, LZ4 and Snappy in `clients`.
  Zstandard is also used by `storage`; RocksDB is shared by `storage` and `streams`.
- The local `platform` imports the Jackson BOM. JSON dependencies omit versions,
  and `server-common` obtains the Metrics version from a platform constraint.
  The platform also has a resolvable `platformClasspath` to let DepLens inspect
  its constraint against an actual resolved library version.
- Every Java module imports the JUnit BOM for its test dependencies and declares
  the JUnit Platform launcher separately at runtime.
- `slf4j-api` and `slf4j-simple` share the catalog's `slf4j` version. The root pins
  the API, illustrating why a shared version source requires care when updating
  the runtime binding. Review the interactive console's shared-source diagnostics.
- Root rules allow stable minor updates, restrict the exact Jackson core group to
  patch updates and restrict `clients` to patch updates. These rules are inherited
  by the modules.

## ApacheDS milestone normalization

`connect:runtime` declares the test dependency
`org.apache.directory.server:apacheds-core-api:2.0.0-M24`. ApacheDS changed its
milestone suffix from `-M24` to `.AM25` and later. The root configures this exact
dependency with:

```groovy
dependency('org.apache.directory.server:apacheds-core-api') {
    versionNormalizationPattern = /[.]AM(?=[0-9]+$)/
    versionNormalizationReplacement = '-M'
    preReleasePattern = /(?i)[.-](?:AM|M)[0-9]+$/
    includePreReleases = true
}
```

The normalization creates comparison keys such as `2.0.0-M25` for the original
version `2.0.0.AM25`, so `2.0.0-M24 < 2.0.0.AM25 < 2.0.0.AM26` is ordered by
milestone number. Repository requests, reports and source edits retain the
original `.AM` versions. This uses `versionNormalizationPattern`, rather than
`versionPattern`, which controls major/minor/patch classification. The explicit
prerelease opt-in applies only to ApacheDS; other dependencies retain the root's
stable-only policy.

## Patch-only upgrade scenarios

The six `streams:upgrade-system-tests-*` projects model representative
stream-processing release lines. These are dependency fixtures, with no
executable system tests. Each has its own explicit root rule, for example:

```groovy
project(':streams:upgrade-system-tests-28') { allowedUpdates = 'patch' }
project(':streams:upgrade-system-tests-39') { allowedUpdates = 'patch' }
```

Each fixture declares the local `com.example.eventlog:streams-compat` dependency
at the start of its release line. For example, `2.8.0` may advance within `2.8.x`, while
`3.9.0` may advance within `3.9.x`. Minor and major upgrades are excluded from
recommendations. The `0.11.0.0` baseline also exercises four-part version numbering.

The versions are deliberately independent declarations. Updating one scenario
must not move another scenario to a different baseline. The fixtures do not depend
on the local `:streams` project, keeping compatibility classpaths separate
from the current example's dependency graph. Modern JUnit dependencies remain
available on each fixture's test classpath.

Inspect just one scenario with:

```sh
./gradlew -p examples/complex :streams:upgrade-system-tests-28:depLensCheckDependencies \
  --console=plain
```

The root `:depLensCheckDependencies` report includes all six scenarios, their
selected project rules, and the permitted patch recommendations.

The preparation script creates empty JARs, POMs and Maven metadata under
`build/fixture-repository`. Each release line offers a patch update as well as
higher minor and major candidates, making the project-rule behavior repeatable.
Run preparation again after `clean`, which removes this generated repository.

## Configuration Cache and Isolated Projects

Run the following command twice without changing build files between runs:

```sh
./gradlew -p examples/complex :depLensInventory :depLensCheckDependencies build \
  --configuration-cache --configuration-cache-problems=fail \
  -Dorg.gradle.unsafe.isolated-projects=true --console=plain
```

The second run should report `Configuration cache entry reused.` Inventory and
update checks still execute. `build` validates the module graph and platform;
there is no application code or test suite in this dependency-only example.
Both the GitHub example job and the local CI matrix runner use the same helper:

```sh
python3 scripts/test-complex-example.py ./gradlew
```

It prepares the fixture and uses a fresh project cache for two identical runs.
Verification fails unless the first run stores an entry and the second reports
both `Reusing configuration cache.` and `Configuration cache entry reused.`
Recalculating the task graph on the second run also fails verification. Logs are
saved under `examples/complex/build/reports/complex-example/` and uploaded by CI,
including on failure.
