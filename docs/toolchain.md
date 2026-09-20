# Android toolchain baseline

Verified working configuration for building Sonora, and the non-obvious traps that
produced it. Established 2026-09-20 by building and running a throwaway Compose app on
an API 35 emulator, then reproducing it as this project.

## Verified versions

| Component | Version | Notes |
| --------- | ------- | ----- |
| Gradle | 9.7.1 | pinned by `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 | `gradle/libs.versions.toml` |
| Kotlin (KGP) | 2.2.10 | **supplied by AGP**, not chosen by us |
| Compose BOM | 2026.09.00 | Compose 1.12+, requires `compileSdk 37` |
| `compileSdk` | 37 | platform installs as `android-37.0` |
| `minSdk` | 26 | |
| `targetSdk` | 35 | provisional — see "Open decisions" |
| JDK | 21 | Android Studio's bundled JBR |

## The four traps

These are the things that break a build and are not obvious from AGP 8-era docs or
tutorials. Every one of them was hit during the smoke test.

### 1. AGP 9 has built-in Kotlin — do not apply the Kotlin plugin

Applying `org.jetbrains.kotlin.android` is now a hard failure:

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

AGP compiles Kotlin sources itself. Remove the plugin from both the root and module build
files. To use a Kotlin version *newer* than AGP's bundled one, add KGP to a `buildscript`
classpath block — not to the `plugins` block.

### 2. The Compose compiler plugin is still required, and must match AGP's Kotlin

`buildFeatures.compose = true` alone fails with:

```
Starting in Kotlin 2.0, the Compose Compiler Gradle plugin is required when compose is enabled.
```

So `org.jetbrains.kotlin.plugin.compose` is applied — but its version must equal the KGP
that AGP bundles (**2.2.10**), not the newest Kotlin release. Reaching for the latest
Kotlin (2.4.20) causes a version conflict. Bumping AGP means re-checking this pairing.

### 3. Compose 1.12+ requires `compileSdk 37`

Compose BOM `2026.09.00` pulls Compose 1.12+, which requires `compileSdk 37` and AGP 9.
The platform is installed as `android-37.0` — part of Android's newer minor-version
scheme (`36.1`, `37.0`), not a plain `android-37`.

### 4. `JAVA_HOME` must be a real JDK

The system JVM is a headless **JRE 25** (`java-25-openjdk`) with no `javac`. If Gradle
launches on it, it tries to use it as the Java toolchain and fails:

```
Toolchain installation '/usr/lib/jvm/java-25-openjdk' does not provide the required capabilities: [JAVA_COMPILER]
```

Point `JAVA_HOME` at Android Studio's bundled JDK instead — no separate install needed:

```bash
export JAVA_HOME="$HOME/development/android-studio/jbr"   # OpenJDK 21
```

Android Studio and an interactive shell (which sources `~/.bashrc`) both get this right.
Non-interactive shells that skip `.bashrc` do not, so a build script invoking `./gradlew`
should set `JAVA_HOME` explicitly.

## Now-unnecessary settings

AGP 9 changed these defaults, so they are deliberately absent from this project:

- `android.useAndroidX` — defaults to `true`.
- `kotlin { compilerOptions { jvmTarget } }` — defaults to
  `android.compileOptions.targetCompatibility`.

## Open decisions

- **`targetSdk`.** Currently 35. The PRD's D3 (Android 15's `dataSync`
  foreground-service time cap) is enforced based on `targetSdk`, so this value is a
  design input, not boilerplate. Verify the exact behaviour at the chosen `targetSdk`
  before relying on it.
- **`applicationId` / `namespace`.** `dev.sonora` is a placeholder. Changing it is cheap
  now and awkward after any public release.
