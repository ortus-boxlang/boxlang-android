# BoxLang Android Runtime

An Android AAR library that brings the [BoxLang](https://boxlang.io) language to Android.
Write Android apps in 100% BoxLang — no Kotlin required. The UI runs through a WebView fed
by BoxLang `.bxm` templates processed by the same front-controller MVC stack used on the web.

## What's in the box

| Component | Description |
|---|---|
| **AAR library** (root project) | Android runtime glue + portable MVC framework + AOT pipeline |
| **`:simulator`** | Plain-JVM HTTP server — iterate on BoxLang templates without Android Studio |
| **`:android-sample-web`** | Runnable sample app (requires Android SDK) |

## Requirements

| Tool | Version |
|---|---|
| JDK | 21 |
| Gradle | 8.x (wrapper bundled) |
| Android SDK | Only for the sample app — not needed for tests or the simulator |

## Get started in 5 minutes (no Android SDK)

```bash
git clone https://github.com/ortus-boxlang/boxlang-android.git
cd boxlang-android

# Downloads BoxLang 1.15.0-snapshot, compiles, and runs all tests
./gradlew test

# Run the sample app in the simulator at http://localhost:8085
./gradlew :simulator:run --args="--app android-sample-web/src/main/bx --port 8085"
```

## Build the AAR library

```bash
./gradlew assembleRelease
# Output: build/outputs/aar/boxlang-android-release.aar
```

## Publish the AAR to a local Maven repo

```bash
./gradlew publishReleasePublicationToLocalRepoRepository
# Output: build/repo/ortus/boxlang/boxlang-android/
```

Depend on it from another project:

```gradle
repositories {
    maven { url = uri("/path/to/boxlang-android/build/repo") }
}
dependencies {
    implementation 'ortus.boxlang:boxlang-android:1.0.0'
}
```

## Build and deploy the sample Android app

Requires `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to be set.

```bash
export ANDROID_HOME=$HOME/android-sdk   # point at your SDK installation

./gradlew :android-sample-web:assembleDebug   # AOT-compiles BoxLang + builds the APK
./gradlew :android-sample-web:installDebug    # deploy to a running emulator/device
```

## Documentation

| File | Contents |
|---|---|
| [docs/quick-start.md](docs/quick-start.md) | From zero to a running screen in minutes |
| [docs/usage.md](docs/usage.md) | Simulator, runtime config, lifecycle, HTTP/JSON, modules, build |
| [docs/tutorial.md](docs/tutorial.md) | Build a full list + detail + add app step by step |
| [docs/reference.md](docs/reference.md) | API reference: Router, MVCEvent, MVCDispatcher, ViewRenderer, AOT tasks |

## Project layout

```
boxlang-android/
├─ src/main/java/ortus/boxlang/runtime/android/
│   ├─ mvc/          # Router, RoutingService, MVCDispatcher, ViewRenderer, MVCEvent …
│   ├─ aot/          # BoxClassExtractor, PreloadedBoxpiler, PreloadedClassLoader, ModuleArchiver
│   └─ …             # Android glue: BoxAndroidApplication, BoxActivity, BoxWebViewRenderer …
├─ src/test/          # JUnit 5 unit tests (no Android SDK required)
├─ simulator/         # Plain-JVM development HTTP server
├─ android-sample-web/# Sample Android app (100% BoxLang, zero Kotlin)
├─ build.gradle       # AGP library + download BoxLang snapshot + maven-publish
├─ gradle.properties  # boxlangVersion, jdkVersion, version, group
└─ settings.gradle    # always includes :simulator; :android-sample-web needs ANDROID_HOME
```
