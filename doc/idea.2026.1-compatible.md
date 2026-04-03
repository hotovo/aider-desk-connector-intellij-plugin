# IntelliJ IDEA 2026.1 Compatibility Summary

## Verified Environment
- IntelliJ IDEA 2026.1
- Build: `IU-261.22158.277`
- Main build line: `261`

## Current Status

Compatibility testing for IntelliJ IDEA 2026.1 has been completed successfully. The plugin works as expected in the tested environment.

The current release is **0.6.1**.

## Upgrade Summary

The compatibility update for IDEA 2026.1 is based on the current implementation in this repository, not on every item from the original draft plan.

### Applied changes

#### 1. Build configuration
The project is currently built with the following setup:

- `org.jetbrains.kotlin.jvm` version `2.3.20`
- `org.jetbrains.intellij.platform` version `2.10.5`
- IntelliJ platform target: `intellijIdea("2026.1")`
- Bundled dependency retained: `bundledPlugin("Git4Idea")`
- Java target version: `21`
- Kotlin JVM target: `21`
- Logging dependency: `org.slf4j:slf4j-simple:2.0.17`
- Jackson Kotlin module: `tools.jackson.module:jackson-module-kotlin:3.1.1`

#### 2. Plugin compatibility declaration
The plugin declares compatibility with the IntelliJ IDEA 2026.1 build line through:

- `<idea-version since-build="261"/>`

This means the plugin is now intended for IntelliJ IDEA 2026.1 and newer builds in the same compatible range determined during packaging.

#### 3. Build stability workaround
The build keeps the following configuration:

- `buildSearchableOptions { enabled = false }`

This is a build-time stability workaround. It does not affect the main plugin features, packaging, or runtime behavior for this project.

## Release Policy

- The latest version is **0.6.1**
- The minimum supported IntelliJ IDEA version for this release is **2026.1**
- Users on older IntelliJ IDEA versions are **strongly discouraged** from using this latest release
- Older IDEA versions have **not been tested**

In short, version `0.6.1` should be treated as a release for IntelliJ IDEA 2026.1+ only.

## Conclusion

The IDEA 2026.1 upgrade is complete and verified.

The important outcome is:

- the plugin builds against IntelliJ IDEA 2026.1
- the plugin has been tested on IDEA 2026.1
- the current functionality works correctly in the tested environment
- version `0.6.1` should be used with IDEA 2026.1, not with older IDE versions

## Recommended Validation Steps

Recommended validation steps for future checks:

1. Run `buildPlugin`
2. Run `runIde`
3. Install the plugin manually in IntelliJ IDEA 2026.1
4. Verify:
   - automatic connection on startup
   - status bar widget visibility and behavior
   - project and editor context menu actions
   - Git4Idea dependency loading
