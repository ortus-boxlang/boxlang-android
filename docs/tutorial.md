# Tutorial — Build a List + Detail + Add app

We'll build a small items app on the WebView/MVC track — 100% BoxLang, no Kotlin.
Follow along using either the **simulator** (no Android SDK) or an Android emulator/device.

---

## Step 1 — Set up your app directory

Create the folder structure (or use the bundled `android-sample-web/src/main/bx` as your starting point):

```
src/main/bx/
├─ boxlang.json
├─ Application.bx
├─ handlers/Items.bx
├─ views/items/list.bxm
├─ views/items/detail.bxm
└─ layouts/main.bxm
```

---

## Step 2 — Configure routes and seed state (`Application.bx`)

```java
class {
    this.name = "Items";

    function configureRouter( router ) {
        router.setDefaultEvent( "Items.list" );              // "/" → Items.list
        router.get( "/items" ).withName( "items" ).to( "Items.list" );
        router.get( "/items/:id" ).to( "Items.show" );
        router.post( "/items/add" ).to( "Items.add" );
    }

    function onApplicationStart() {
        application.items = [ "Apple", "Banana", "Cherry" ];
        return true;
    }
}
```

---

## Step 3 — Write the handler (`handlers/Items.bx`)

Actions run first. Populate `rc` and choose the view.

```java
class {
    function list( event, rc ) {
        event.setValue( "items", application.items );
        event.setView( "items/list" );
    }

    function show( event, rc, id ) {
        event.setValue( "item", application.items[ val( id ) ] );
        event.setView( "items/detail" );
    }

    function add( event, rc, title ) {
        application.items.append( title );
        // No session needed — carry the notice on the query string.
        event.relocate( "/items?notice=" & urlEncodedFormat( "Added: " & title ) );
    }
}
```

---

## Step 4 — Create the layout and views

```html
<!-- layouts/main.bxm -->
<bx:output>
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Items</title></head>
<body>
  <bx:if rc.keyExists( "notice" )>
    <p class="flash">#encodeForHTML( rc.notice )#</p>
  </bx:if>
  <main>#renderedView#</main>
</body>
</html>
</bx:output>

<!-- views/items/list.bxm -->
<bx:output>
<h1>Items</h1>
<ul>
  <bx:loop from="1" to="#rc.items.len()#" index="i">
    <li><a href="/items/#i#">#encodeForHTML( rc.items[ i ] )#</a></li>
  </bx:loop>
</ul>
<form method="post" action="/items/add">
  <input name="title" placeholder="New item" required>
  <button type="submit">Add</button>
</form>
</bx:output>

<!-- views/items/detail.bxm -->
<bx:output>
<h1>#encodeForHTML( rc.item )#</h1>
<a href="/items">← Back to list</a>
</bx:output>
```

---

## Step 5 — Run it

### With the simulator (no Android SDK)

```bash
./gradlew :simulator:run --args="--app /path/to/your/app --port 8085"
```

Open `http://localhost:8085`. You'll see the item list. Tap an item → detail view. Submit the
form → the item is appended and the list reloads with a notice banner. All in-process, no server.

### On an Android device or emulator

```bash
export ANDROID_HOME=$HOME/android-sdk
./gradlew :android-sample-web:assembleDebug
./gradlew :android-sample-web:installDebug
```

The bundled sample in `android-sample-web/src/main/bx` already implements this app.

---

## How it works

1. A link tap or form submit is intercepted by the `BoxWebViewRenderer` JS bridge in the WebView.
2. The bridge calls `BoxActivity.navigate(path, method, params)`.
3. `MVCDispatcher.dispatch()` resolves the route, builds `rc`, and runs the handler action.
4. The action sets the view (and optionally the layout) or calls `event.relocate()`.
5. For relocates: the WebView navigates to the new URL (and the dispatcher dispatches again).
6. For renders: `ViewRenderer` runs the view template inside the layout template, and the HTML
   is loaded into the WebView.

---

## Limitations and gotchas

- **AOT only.** No `eval` or runtime class generation on device. Compile all `.bx`/`.bxm` at
  build time via the Gradle AOT task chain.
- **No session scope.** This is a single in-process app. Carry data across a `relocate()` via
  the query string — the dispatcher parses it into `rc` automatically.
- **Disabled services on Android:** file watchers, JDBC/Hikari — use SQLite/Room instead.
- **POST bodies** must go through the JS bridge (Android cannot read POST bodies in
  `shouldInterceptRequest`); the runtime injects the hook automatically.
- **Threading:** WebView JS callbacks run off the UI thread — the renderer marshals back via
  `webView.post(...)`. Keep heavy work off the main thread.
- **R8:** keep BoxLang, `boxgenerated.*`, and ServiceLoader providers (keep rules are provided
  in `consumer-rules.pro` and auto-applied to consuming apps).
- **Modules with Java libs** must have their JARs dexed at build time; pure-`.bx` modules just
  work as-is.
