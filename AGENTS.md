# Repository Guidelines

## Project Structure & Module Organization

- `app/` is the sample Android application; it currently depends on `gma-lib`.
- `gma-lib/` integrates the Google Mobile Ads Next-Gen SDK, billing, consent, and analytics. Ad helpers live under `src/main/java/com/lib/ads/gma/ads/helper/`.
- `adlib/` maintains the legacy ads integration. Keep it off the sample app’s classpath alongside `gma-lib`: the modules expose overlapping packages with incompatible SDK types.
- Each module stores source in `src/main/java/` and Android resources in `src/main/res/`. Tests belong in `src/test/` or `src/androidTest/`.
- `Document/` contains integration guides. Shared dependency aliases live in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper with JDK 17 and the Android SDK versions declared in module build files.

- `./gradlew :app:assembleAppDevDebug` builds the development sample APK.
- `./gradlew :app:installAppDevDebug` installs it on a connected emulator or device.
- `./gradlew :gma-lib:assembleRelease :adlib:assembleRelease` builds both library AARs.
- `./gradlew test` runs local unit tests across modules.
- `./gradlew :app:connectedAppDevDebugAndroidTest` runs sample instrumentation tests on a device.
- `./gradlew :app:lintAppDevDebug :gma-lib:lintDebug` checks Android lint findings; inspect reports even when lint does not fail the build.

## Coding Style & Naming Conventions

Use four-space indentation and follow surrounding Kotlin/Java style. Name classes and files in `PascalCase`, functions and properties in `camelCase`, and Android resources in `snake_case`. Preserve existing public APIs and lifecycle-aware coroutine patterns. No dedicated ktlint or Detekt configuration is checked in; use Android Studio formatting and Android lint.

## Testing Guidelines

Use JUnit 4 for local tests and AndroidX JUnit/Espresso for instrumentation; `gma-lib` also declares Compose UI testing dependencies. Name test classes `*Test` and cover changed behavior. No coverage threshold is configured. The `.gitignore` excludes `gma-lib/src/test` and `gma-lib/src/androidTest`; explicitly address those exclusions when contributing library tests so they are tracked.

## Commit & Pull Request Guidelines

History uses short, descriptive subjects, sometimes prefixed with a module, such as `gma-lib: sync gma-sdk changes onto master`. Follow that pattern. PRs should describe the behavior change, affected modules, validation commands and results, and related issues. Include screenshots for sample UI changes.

## Security & Configuration

Keep SDK paths in ignored `local.properties`; keep package tokens in user Gradle properties or environment variables. Never commit signing keys or credentials. Use test ad IDs during development.
