# Usage

## The simulator — develop without Android Studio

The `:simulator` subproject starts an embedded HTTP server (Java's built-in `HttpServer`) that
dispatches requests through the same `MVCDispatcher` / `ViewRenderer` pipeline used on the
device. No Android SDK, no emulator, no Kotlin.

```bash
# Point at the bundled sample app
./gradlew :simulator:run --args="--app android-sample-web/src/main/bx --port 8085"

# Point at your own app directory
./gradlew :simulator:run --args="--app /path/to/my/app --port 8085"
```

The simulator:
- Boots `BoxRuntime`, registers the `/app` mapping, loads `Application.bx`
- Calls `configureRouter(router)` and `onApplicationStart()`
- Maintains a shared in-memory application scope (`application.*`) across requests
- Parses POST form bodies exactly as the Android WebView JS bridge does
- Returns `302 Location` for `event.relocate(target)` calls

---

## Runtime configuration (`boxlang.json`)

The `boxlang.json` file under your app's `src/main/bx` directory is loaded on boot. Use the
standard BoxLang config keys:

| Key | Purpose |
|---|---|
| `mappings` | Path mappings (`/handlers`, `/views`, `/layouts`, `/models`, `/`) |
| `modulesDirectory` | Drop-in BoxLang modules folder(s) — auto-registered |
| `javaLibraryPaths` | Third-party JAR folder(s) |
| `customComponentsDirectory` | Custom component (tag) folder(s) |
| `modules` | Per-module settings |

---

## The `Application.bx` lifecycle on Android

Standard BoxLang listener methods fire on the same triggers as web/lambda:
`onApplicationStart` / `onApplicationEnd`, `onRequestStart`, `onRequest`, `onRequestEnd`,
`onError`, `onAbort`, `onSessionStart` / `onSessionEnd`.

**Android-specific hooks** — define any of these and the runtime calls them automatically:

```java
function onActivityCreate( event, savedState ) {}
function onActivityResume( event ) {}
function onActivityPause( event ) {}
function onActivityResult( requestCode, resultCode, data ) {}
function onPermissionResult( requestCode, permissions, grantResults ) {}
function onBackPressed( event ) { return true; }   // return false to consume the event
function onLowMemory() {}
function onConfigurationChanged( newConfig ) {}
```

---

## Handler interceptors

Add optional `preHandler` / `postHandler` methods (and per-action variants) directly to any
handler class. They receive the same `event` and `rc` arguments as an action.

```java
// handlers/Items.bx
class {

    // Runs before EVERY action in this handler.
    function preHandler( event, rc ) {
        if ( !isAuthenticated() ) {
            event.relocate( "/login" );  // short-circuits the action + post hooks
        }
        rc.title = "Items";
    }

    // Runs only before the `edit` action.
    function preEdit( event, rc, required numeric id ) {
        rc.item = loadItem( id );
    }

    function edit( event, rc, required numeric id ) {
        event.setView( "items/edit" );
    }

    // Runs after the `edit` action (and preEdit).
    function postEdit( event, rc ) {
        logAccess( "edit", rc.id );
    }

    // Runs after EVERY action in this handler.
    function postHandler( event, rc ) {
        // e.g. add common response headers or audit logging
    }
}
```

**Execution order:**
`preHandler` → `pre<Action>` → action → `post<Action>` → `postHandler`

If any step calls `event.relocate()`, the chain stops there and the redirect is returned
immediately — no further interceptors or the action itself will run.

---

## Building URLs with `buildLink`

Use `event.buildLink()` in views and layouts to generate URLs without hardcoding paths.

```html
<!-- views/items/list.bxm -->
<ul>
    <cfloop array="#rc.items#" item="item">
        <li>
            <a href="#event.buildLink('item.show', {id: item.id})#">
                #item.name#
            </a>
        </li>
    </cfloop>
</ul>
<a href="#event.buildLink('items')#">Back to list</a>
```

**Named routes** (from `configureRouter`):
```java
// Application.bx
function configureRouter( router ) {
    router.get( "/items" ).withName( "items" ).to( "Items.list" );
    router.get( "/items/:id" ).withName( "item.show" ).to( "Items.show" );
    router.get( "/items/:id/edit" ).withName( "item.edit" ).to( "Items.edit" );
}
```

```java
event.buildLink( "items" )                       // → /items
event.buildLink( "item.show", {id: 42} )         // → /items/42
event.buildLink( "item.edit", {id: 7, tab: 2} )  // → /items/7/edit?tab=2
```

**Convention fallback** (no named route):
```java
event.buildLink( "Items.show" )      // → /items/show
event.buildLink( "Items.show", {id: 42} )  // → /items/show?id=42
event.buildLink( "/about" )          // → /about  (raw path passthrough)
```

---

## Static assets (simulator)

Place CSS, JavaScript, images, and fonts under `src/main/bx/public/`. The simulator serves
them directly, bypassing the MVC dispatcher:

```
src/main/bx/public/
├─ css/app.css
├─ js/main.js
└─ images/logo.svg
```

Reference them in your layout:

```html
<!-- layouts/main.bxm -->
<!DOCTYPE html>
<html>
<head>
    <link rel="stylesheet" href="/css/app.css">
</head>
<body>
    #renderedView#
    <script src="/js/main.js"></script>
</body>
</html>
```

No server configuration needed — the simulator auto-detects the `public/` directory and serves
any file within it with the correct `Content-Type`. Unknown extensions fall back to
`application/octet-stream`.

---

## HTTP / JSON / async

BoxLang's batteries are available on device:

```java
var res  = bx:http( url = "https://api.example.com/items", method = "GET" );
var data = jsonDeserialize( res.fileContent );
```

Add `<uses-permission android:name="android.permission.INTERNET" />` to your manifest (the
library manifest already declares it). Prefer `bx:thread` over heavy executor pools on mobile.

---

## Android SDK interop

The entire Android SDK is reachable via BoxLang's Java interop:

```java
var toast = createObject( "java", "android.widget.Toast" );
// Inject the Activity/Context through a handler argument and call SDK APIs directly.
```

---

## BoxLang modules

Drop modules under `src/main/bx/modules/<name>` (auto-registered via `modulesDirectory`).

On Android, each module keeps its own isolated class loader via per-module DEX:

- **Build:** each module's `.bx` is AOT-compiled + extracted, then packaged with its
  `libs/*.jar` and resources (`META-INF/services`, descriptor, templates) and `d8`-converted
  to `assets/modules/<name>.jar` (a `classes.dex` + resources archive).
- **Runtime:** an `AndroidModuleClassLoader` (`DexClassLoader`) loads each module archive,
  parented to the runtime loader — giving isolation, hierarchy, and working `ServiceLoader`
  discovery for BIFs/components/interceptors.

---

## Build the AAR

```bash
./gradlew assembleRelease
# Output: build/outputs/aar/boxlang-android-release.aar
```

Publish to a local Maven repository for use from other projects:

```bash
./gradlew publishReleasePublicationToLocalRepoRepository
# Output: build/repo/ortus/boxlang/boxlang-android/1.0.0/
```

---

## Build & deploy the sample Android app

Requires `ANDROID_HOME` or `ANDROID_SDK_ROOT`:

```bash
./gradlew :android-sample-web:assembleDebug          # AOT-compile bx + build the APK
./gradlew :android-sample-web:installDebug           # deploy to a running emulator/device
./gradlew :android-sample-web:connectedAndroidTest   # instrumented tests (needs emulator + KVM)
```

---

## Run unit tests (no Android SDK required)

```bash
./gradlew test
```

Tests cover the MVC framework (`RouterTest`, `MVCEventTest`, `MVCDispatcherTest`) and the AOT
pipeline (`AOTPipelineTest`, `ModuleAOTTest`). All run on the JVM with `logback-test.xml`
suppressing BoxLang runtime noise (WARN+ only shown).

---

## Toolchain

| Item | Version |
|---|---|
| BoxLang | 1.15.0-snapshot (auto-downloaded from Ortus CDN) |
| Android Gradle Plugin | 8.7.3 |
| Gradle (wrapper) | bundled |
| JDK | 21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |

The BoxLang JAR is downloaded automatically by `downloadBoxLang` (wired to `preBuild`). To
download it explicitly:

```bash
./gradlew downloadBoxLang
# Output: libs/boxlang-1.15.0-snapshot.jar
```
