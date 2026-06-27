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
Owns the singleton `Router`. Registered as a BoxLang global service so it participates in the
runtime lifecycle and is reachable from BoxLang scripts:

```java
// Inside any handler or Application.bx:
var routingService = getBoxRuntime().getGlobalService( "RoutingService" );
var router         = routingService.getRouter();
```

The service lifecycle (`onConfigurationLoad`, `onStartup`, `onShutdown`) is managed by the
runtime — `onShutdown` is called automatically when the runtime shuts down.

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

**Reverse URL lookup — `buildLink`:**
```java
// By named route (registered with .withName("items"))
event.buildLink( "items" )                    // → "/items"
event.buildLink( "item.show", {id: 42} )      // → "/items/42"  (fills :id placeholder)

// By Handler.action convention (no named route needed)
event.buildLink( "Items.show" )               // → "/items/show"
event.buildLink( "Items.show", {page: 2} )    // → "/items/show?page=2"

// Raw path passthrough
event.buildLink( "/about" )                   // → "/about"
```

Named route params that match a `:placeholder` in the pattern are consumed as path segments.
Any leftover params become a query string (`?key=value`). Values are percent-encoded.

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

**Request flow (per dispatch):**
1. Parse query string → resolve route → build `rc`
2. `preHandler(event, rc)` — optional method on the handler; runs before any action
3. `pre<Action>(event, rc)` — e.g. `preShow`, `preAdd`; action-specific pre-hook
4. **Handler action** — receives `event`, `rc`, and each `rc` entry as a named argument
5. `post<Action>(event, rc)` — e.g. `postShow`; action-specific post-hook
6. `postHandler(event, rc)` — runs after any action
7. Relocate or render view-in-layout

Any interceptor (step 2, 3) can call `event.relocate(target)` to short-circuit the rest of
the chain — the action and post-hooks are skipped and the relocate is returned immediately.

**Handler interceptors example:**
```java
// handlers/Main.bx
class {
    // Runs before every action in this handler
    function preHandler( event, rc ) {
        if ( !application.loggedIn ) {
            event.relocate( "/login" );
        }
    }

    // Runs only before the `dashboard` action
    function preDashboard( event, rc ) {
        rc.title = "Dashboard";
    }

    function dashboard( event, rc ) {
        event.setView( "main/dashboard" );
    }
}
```

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
├─ public/                 # static assets (CSS, JS, images, fonts)
└─ lib/                    # third-party JARs (dexed at build time on Android)
```

### Static asset serving (simulator only)

The simulator serves files placed under `src/main/bx/public/` **before** attempting MVC
dispatch. A request for `/public/style.css` is unnecessary — just reference the path directly:

```html
<link rel="stylesheet" href="/css/app.css">
```

resolves to `<appPath>/public/css/app.css`.

Supported MIME types are detected from the file extension: `css`, `js`, `mjs`, `html`,
`json`, `xml`, `png`, `jpg`, `gif`, `svg`, `ico`, `woff`, `woff2`, `ttf`, `otf`, `webp`.
Unknown extensions are served as `application/octet-stream`.

A canonical-path check prevents path-traversal attacks (requests cannot escape `public/`).

> **On-device:** the WebView loads HTML into a sandboxed origin (`https://boxlang.local/`),
> so inline CSS/JS or `data:` URIs are the easiest approach for on-device styling. If you need
> separate asset files on device, bundle them in `assets/` and serve them via a
> `WebViewAssetLoader` or embed them inside your layout template.

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
