# Project conventions

Keep all user-facing output, labels, diagnostics, task descriptions, and generated report text in English.

## Project and layout

DepLens is a Gradle plugin written in Java 21, supporting Gradle 8.8 or newer.
Prefer the Gradle wrapper (`./gradlew`).

- `plugin/`: plugin implementation, unit tests and Gradle TestKit tests.
- `examples/`: consumer builds and compatibility examples.
- `scripts/`: verification and release helpers.

## Build and verification

```sh
./gradlew build
./gradlew :plugin:test --tests 'com.sneedit.deplens.console.SourceEditorTest'
./gradlew :plugin:nullAway :plugin:validatePlugins
```

Start with tests covering the affected behavior. Use unit tests for local logic,
TestKit for Gradle integration and terminal tests for interactive behavior. Run
the full compatibility matrix (`./scripts/test-ci-matrix.sh`) for changes to
Gradle integration, dependency resolution or cache behavior. Documentation-only
changes need link and formatting checks rather than a full build.

## Change conventions

- Preserve Java 21 bytecode compatibility and the supported Gradle/Java matrix.
- Preserve Configuration Cache and Isolated Projects support.
- Treat the public DSL, task names and JSON report schema as compatibility
  boundaries; document intentional changes and add relevant regression coverage.
- Keep source edits conservative: protect shared version sources and preserve
  surrounding formatting and comments.
- Follow the SPDX copyright and Apache-2.0 license headers in existing sources
  when adding source files.

See [README.md](README.md) for usage and testing.
