# Building OpenCode Term

## Prerequisites

- JDK 17
- Android SDK with platforms;android-35, build-tools 34+
- Android NDK r26+ (r27 recommended), CMake 3.22.1
- Gradle 8.7+ (use the wrapper if present, otherwise `gradle wrapper --gradle-version 8.7`)

## Build

```bash
# debug APK
./gradlew assembleDebug

# release APK (unsigned → uses debug signing config as shipped; replace for distribution)
./gradlew assembleRelease

# verify the APK contains ONLY armeabi-v7a native libs (shell, any OS)
 unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "lib/"

# unit tests (JVM, no device)
./gradlew testDebugUnitTest

# instrumentation tests (device/emulator required)
./gradlew connectedDebugAndroidTest
```

Outputs land in `app/build/outputs/apk/{debug,release}/`.

## ABI guarantee

`app/build.gradle.kts` sets `ndk.abiFilters += "armeabi-v7a"`. The GitHub Actions
workflow (`​.github/workflows/build.yml`) fails any build where the packaged APK
contains an ABI directory other than armeabi-v7a. A 32-bit-only APK
will **not** install on devices that ship arm64-only userspace — that is expected.

## Signing for distribution

Replace the debug signing config in `app/build.gradle.kts` `release` block:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("OC_SIGN_STORE") ?: "keystore.jks")
        storePassword = System.getenv("OC_SIGN_PASS")
        keyAlias = System.getenv("OC_SIGN_ALIAS")
        keyPassword = System.getenv("OC_SIGN_KEYPASS")
    }
}
```

then `signingConfig = signingConfigs.getByName("release")`.

## Toolchain notes

- `compileSdk = 35` (API available at compile time), `targetSdk = 34` (runtime behavior),
  `minSdk = 24` (Android 7.0+).
- CMake builds only `NativeTerminalJNI.c` against `log`/`android` — no STL needed.
- R8 shrink is enabled for release only; keep rules protect JNI symbols and
  kotlinx.serialization serializers (see `proguard-rules.pro`).
