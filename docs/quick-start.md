# Quick Start

## Option A — No Android SDK: use the simulator

The fastest way to start. You only need JDK 21 — no Android Studio, no emulator.

```bash
git clone https://github.com/ortus-boxlang/boxlang-android.git
cd boxlang-android

# Start the simulator pointing at the bundled sample app
./gradlew :simulator:run --args="--app android-sample-web/src/main/bx --port 8085"
```

Open `http://localhost:8085` — the BoxLang MVC app is live. Edit any `.bx` or `.bxm` file
under `android-sample-web/src/main/bx` and reload the page; changes are reflected immediately
(no rebuild needed for templates).

---

## Option B — Full Android: emulator or physical device

Requires the Android SDK. Set `ANDROID_HOME` before running Gradle.

### 1. Install the Android SDK (no Android Studio required)

```bash
SDK=$HOME/android-sdk
curl -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
mkdir -p "$SDK/cmdline-tools" && unzip -q cmdtools.zip -d "$SDK/cmdline-tools" \
  && mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"

export ANDROID_HOME=$SDK
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;34.0.0"
```

### 2. Build and install the sample app

```bash
./gradlew :android-sample-web:assembleDebug   # AOT-compiles BoxLang + produces the APK
./gradlew :android-sample-web:installDebug    # deploy to a running emulator or device
```

The APK bundles:
- The BoxLang runtime (dexed)
- The AOT-compiled BoxLang app classes (`boxgenerated.*`, dexed)
- The raw `src/main/bx` payload under `assets/bx/` (for on-device template reads)

---

## Create your own BoxLang Android app

### App directory layout

```
src/main/bx/
├─ boxlang.json          # runtime config (loaded on boot)
├─ Application.bx        # lifecycle + router configuration
├─ handlers/
│   └─ Main.bx
├─ views/
│   └─ main/index.bxm
└─ layouts/
    └─ main.bxm
```

### `boxlang.json`

```json
{
  "mappings": {
    "/":         "./",
    "/handlers": "./handlers",
    "/views":    "./views",
    "/layouts":  "./layouts"
  },
  "modulesDirectory": [ "./modules" ],
  "javaLibraryPaths": [ "./lib" ]
}
```

### `Application.bx` — wire routes and seed state

```java
class {
    this.name = "MyApp";

    function configureRouter( router ) {
        router.setDefaultEvent( "Main.index" );   // "/" → Main.index
    }

    function onApplicationStart() {
        return true;
    }
}
```

### `handlers/Main.bx` — action runs first, sets the view

```java
class {
    function index( event, rc ) {
        event.setValue( "title", "Hello from BoxLang" );
        event.setView( "main/index" );
    }
}
```

### `views/main/index.bxm` and `layouts/main.bxm`

```html
<!-- views/main/index.bxm -->
<bx:output><h2>#rc.title#</h2></bx:output>

<!-- layouts/main.bxm -->
<bx:output>
<html><body>
  <main>#renderedView#</main>
</body></html>
</bx:output>
```

### Android manifest — point at the generic entry points (no subclassing)

```xml
<application android:name="ortus.boxlang.runtime.android.BoxAndroidApplication" ...>
    <activity
        android:name="ortus.boxlang.runtime.android.BoxActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

### `build.gradle` — depend on the runtime AAR

```gradle
dependencies {
    // From a local publish (see README):
    implementation 'ortus.boxlang:boxlang-android:1.0.0'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'
}
```

### Verify everything works first

```bash
./gradlew test    # all unit tests — no Android SDK required
```

See [tutorial.md](tutorial.md) for a full list + detail + add walkthrough.
