# API Reference

## Android entry points (`ortus.boxlang.runtime.android`)

### `BoxAndroidApplication`
Generic `android.app.Application`. Declare it in your manifest's `android:name`. Boots the
runtime on `onCreate` — no subclass required.

### `BoxActivity`
Generic, config-driven `Activity` (100% Java). Declare it directly in the manifest. Hosts
the WebView and forwards every Android lifecycle callback to the matching `Application.bx` hook.
- `navigate(path, method, params)` — dispatch a route into the in-process runtime.

### `AndroidBoxRuntime`
Singleton entry point (used internally by `BoxAndroidApplication`).
- `boot(Context)` — boots the runtime (AOT/NoOp mode), seeds the app home from APK assets,
  loads `boxlang.json`, and builds the MVC dispatcher.
- `getInstance()`, `getRuntime()`, `getAppHome()`, `getRoutingService()`, `getDispatcher()`,
  `shutdown()`.

---

## MVC front controller (`ortus.boxlang.runtime.android.mvc`)

### `Router` / `Route`
Fluent route table and resolution.

```java
// Fluent registration
router.setDefaultEvent( "Main.index" );          // "/" maps to Main.index
router.get( "/items" ).withName( "items" ).to( "Items.list" );
router.get( "/items/:id" ).to( "Items.show" );   // path param → rc.id
router.post( "/items/add" ).to( "Items.add" );
router.route( "/rss" ).withMethods( "GET", "HEAD" ).to( "Feed.rss" );
```

Resolution order: root → explicit table → **convention** (`/handler/action` →
`Handler.action`, `/handler` → `Handler.index`).

### `RoutingService` (IService)
Owns the singleton `Router`. Registered like any BoxLang service via the runtime.

### `MVCEvent` — the `event` object passed to every handler action

**Collection / RC:**
```java
event.getValue( "key" )           // read from rc
event.getValue( "key", default )  // with fallback
event.setValue( "key", value )    // write to rc
event.valueExists( "key" )
event.getRC()                     // the full IStruct
event.getCollection()             // alias for getRC()
```

**Rendering:**
```java
event.setView( "items/list" )     // relative to views root; no extension
event.getView()
event.setLayout( "main" )         // relative to layouts root; default "main"
event.noLayout()                  // render view only, no wrapper
```

**Redirect:**
```java
event.relocate( "/items?notice=Done" )   // triggers a 302 on web / WebView navigate on device
event.isRelocating()
event.getRelocateTarget()
```

**Meta:**
```java
event.getHTTPMethod()    // "GET" or "POST"
event.getCurrentEvent()  // resolved "Handler.action" string
```

> There is no flash/session scope — this is a single in-process app. To carry data across a
> `relocate()`, append it to the query string; the dispatcher parses query strings into `rc`
> automatically.

### `MVCDispatcher`
`dispatch(context, path, method, params)` → `DispatchResult`

Flow: parse query string → resolve route → build `rc` → **run handler action first** →
relocate or render view-in-layout. The action receives `event`, `rc`, and every `rc` entry
spread as named arguments.

### `ViewRenderer`
`render(context, event)` — renders the chosen view (under `viewsRoot`), wraps it in the
layout (under `layoutsRoot`), and exposes:
- `event` and `rc` to both view and layout templates
- `renderedView` to the layout template (the rendered view HTML)

Methods: `renderView(context, event)`, `renderTemplate(context, absolutePath)`.

---

## AOT pipeline (`ortus.boxlang.runtime.android.aot`)

### Gradle task chain (wired in `android-sample-web/build.gradle`)

Three tasks turn `src/main/bx` into dexed bytecode, chained ahead of AGP's dexing so they
run automatically on `assembleDebug` / `assembleRelease`:

| Task | Tool | What it does |
|---|---|---|
| `compileBoxLangAot` | `ortus.boxlang.compiler.BXCompiler` | Compiles `.bx`/`.bxm` to the BoxLang AOT class container |
| `extractBoxLangClasses` | `BoxClassExtractor` | Unpacks containers into standard `.class` files |
| `jarBoxLangAot` | Gradle `Jar` | Packages `.class` files into `boxlang-aot.jar` for AGP's D8 to dex |

`stageBoxApp` mirrors `src/main/bx` into the APK assets so templates are readable on device.

### `BoxClassExtractor`
CLI tool: `BoxClassExtractor <containerDir> <outputDir>` — walks container files produced by
`BXCompiler` and extracts all embedded `.class` files into standard `outputDir`.

### `PreloadedBoxpiler`
Extends `Boxpiler`, registered via `META-INF/services/ortus.boxlang.compiler.IBoxpiler`.
`compileClassInfo()` throws `BoxRuntimeException` — ensures ART-safe operation (AOT only,
no runtime `defineClass()`).

### `PreloadedClassLoader`
Parent-delegation only — never calls `defineClass()`. Resolves classes from what was loaded
into the APK's class loader hierarchy at install time.

### `ModuleArchiver`
Packages module classes and resources into a JAR for module isolation.

---

## Starter-template directory layout

```
src/main/bx/
├─ boxlang.json            # runtime config (loaded on boot)
├─ Application.bx          # lifecycle + configureRouter()
├─ handlers/               # Handler.bx files (actions run first)
├─ views/                  # .bxm template files
├─ layouts/                # .bxm layout wrappers
├─ models/                 # app services and domain objects
├─ modules/                # drop-in BoxLang modules (auto-registered)
└─ lib/                    # third-party JARs (dexed at build time on Android)
```

---

## R8 / ProGuard

`consumer-rules.pro` (auto-applied to consuming apps) keeps:
- `ortus.boxlang.**` — entire BoxLang runtime
- `boxgenerated.**` — AOT-compiled BoxLang classes
- ServiceLoader provider descriptors
- Annotations and the `@JavascriptInterface` bridge

---

## `gradle.properties` keys

| Key | Default | Purpose |
|---|---|---|
| `boxlangVersion` | `1.15.0-snapshot` | BoxLang JAR version downloaded from Ortus CDN |
| `jdkVersion` | `21` | Java source/target compatibility |
| `version` | `1.0.0` | AAR artifact version |
| `group` | `ortus.boxlang` | Maven group ID for published AAR |
