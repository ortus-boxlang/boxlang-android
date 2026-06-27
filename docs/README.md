# BoxLang Android — Documentation

Build Android apps in **BoxLang**. One language for web, serverless, CLI — and now Android —
with batteries-included runtime (HTTP, JSON, async, caching) and 100% Java interop. **Zero
Kotlin: the app is 100% BoxLang.**

## Contents

- **[quick-start.md](quick-start.md)** — From zero to a running screen in minutes.
- **[usage.md](usage.md)** — Simulator, runtime config, the `Application.bx` lifecycle on
  Android, HTTP/JSON/async, SDK interop, modules, build & deploy.
- **[tutorial.md](tutorial.md)** — Build a real list+detail+add app step by step.
- **[reference.md](reference.md)** — API reference: `Router`, `MVCEvent`, `MVCDispatcher`,
  `ViewRenderer`, the AOT task chain, config keys, and R8 keep rules.

## The UI model

The UI is the **WebView + BoxLang templating** track:

| | WebView (MVC) |
|---|---|
| UI authored as | BoxLang **`.bxm` templates** + layouts |
| Rendered by | `WebView` (HTML from the templating engine) |
| Kotlin needed? | **None** — 100% BoxLang |
| Best for | Fast delivery, web-skill reuse, cross-target portability |

A handler action runs first, populates the request collection (`rc`) and chooses the view +
layout; the framework renders the view inside the layout and loads the HTML into a `WebView`.
Links and forms route back into the in-process runtime — no web server.

## Architecture & execution model

**AOT only on device.** ART (the Android runtime) cannot `defineClass()` raw JVM bytecode
at runtime. All `.bx`/`.bxm` sources are compiled to standard `.class` files at Gradle build
time, D8-dexed into the APK, and resolved at runtime by `PreloadedBoxpiler` /
`PreloadedClassLoader` — no on-device parsing or class generation.

## Module map

| Path | Role |
|---|---|
| `src/main/java/…/mvc/` | Portable front-controller (Router, RoutingService, MVCDispatcher, ViewRenderer, MVCEvent …) |
| `src/main/java/…/aot/` | AOT pipeline (BoxClassExtractor, PreloadedBoxpiler, PreloadedClassLoader, ModuleArchiver) |
| `src/main/java/…/` (root) | Android glue: BoxAndroidApplication, BoxActivity, BoxWebViewRenderer, lifecycle bridge |
| `simulator/` | Plain-JVM HTTP server for developing without Android Studio |
| `android-sample-web/` | Runnable sample app + starter template |
