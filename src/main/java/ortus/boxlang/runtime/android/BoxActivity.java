/**
 * [BoxLang]
 *
 * Copyright [2023] [Ortus Solutions, Corp]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ortus.boxlang.runtime.android;

import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.appcompat.app.AppCompatActivity;

import ortus.boxlang.runtime.types.IStruct;

/**
 * The generic, config-driven BoxLang Activity. Apps declare THIS class directly in their
 * manifest (no per-app subclass needed) — all behaviour is customised in {@code Application.bx}
 * via its lifecycle hooks and route handlers.
 * <p>
 * Hosts the <b>WebView track</b>: creates a {@link WebView}, wires up the MVC front
 * controller through {@link BoxWebViewRenderer}, and navigates to the entry route
 * ({@code /} by default). Views are authored as BoxLang {@code .bxm} templates — no
 * Kotlin, no Compose; the app is 100% BoxLang.
 * <p>
 * Each call to {@link BoxWebViewRenderer#navigate} creates a fresh request context,
 * fires the full BoxLang request lifecycle, then shuts the context down — no shared
 * mutable context state leaks across navigations.
 * <p>
 * Every Android lifecycle callback is forwarded to the matching optional hook on
 * {@code Application.bx} through {@link AndroidLifecycleDispatcher}.
 * <p>
 * <b>Deep-linking:</b> if the Activity is started with an {@code ACTION_VIEW} intent
 * the path and query string are extracted from the URI and used as the entry route.
 */
public class BoxActivity extends AppCompatActivity {

	/** Bundle key used to save/restore the current route across configuration changes. */
	private static final String			STATE_ROUTE	= "boxlang.current_route";

	/** The entry route dispatched on first load. Defaults to {@code /}. */
	protected String					entryRoute	= "/";

	private AndroidLifecycleDispatcher	lifecycle;
	private BoxWebViewRenderer			webRenderer;

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	@Override
	protected void onCreate( Bundle savedInstanceState ) {
		super.onCreate( savedInstanceState );

		AndroidBoxRuntime android = AndroidBoxRuntime.getInstance();

		this.lifecycle	= new AndroidLifecycleDispatcher( android.getRuntime() );
		this.lifecycle.invokeHook( "onActivityCreate", savedInstanceState );

		WebView webView = new WebView( this );
		setContentView( webView );

		this.webRenderer = new BoxWebViewRenderer( webView, android.getDispatcher(), android.getRuntime() );

		// Restore the route from a previous configuration change, or resolve from intent.
		String initial = savedInstanceState != null
		    ? savedInstanceState.getString( STATE_ROUTE, entryRoute )
		    : resolveEntryRoute();

		this.webRenderer.navigate( initial, "GET", null );
	}

	@Override
	protected void onSaveInstanceState( Bundle out ) {
		super.onSaveInstanceState( out );
		if ( this.webRenderer != null ) {
			out.putString( STATE_ROUTE, this.webRenderer.getCurrentRoute() );
		}
	}

	@Override
	protected void onNewIntent( Intent intent ) {
		super.onNewIntent( intent );
		setIntent( intent );
		// Deep-link arriving while the Activity is already running (singleTop / singleTask).
		String route = routeFromIntent( intent );
		if ( route != null && this.webRenderer != null ) {
			this.webRenderer.navigate( route, "GET", null );
		}
	}

	// ── Public navigation API ─────────────────────────────────────────────────

	/**
	 * Navigate the WebView track to a route programmatically.
	 *
	 * @param path   The route path
	 * @param method The HTTP method
	 * @param params The params (may be {@code null})
	 */
	public void navigate( String path, String method, IStruct params ) {
		if ( this.webRenderer != null ) {
			this.webRenderer.navigate( path, method, params );
		}
	}

	// ── Android lifecycle → Application.bx hooks ─────────────────────────────

	@Override
	protected void onStart() {
		super.onStart();
		this.lifecycle.invokeHook( "onActivityStart" );
	}

	@Override
	protected void onResume() {
		super.onResume();
		this.lifecycle.invokeHook( "onActivityResume" );
	}

	@Override
	protected void onPause() {
		this.lifecycle.invokeHook( "onActivityPause" );
		super.onPause();
	}

	@Override
	protected void onStop() {
		this.lifecycle.invokeHook( "onActivityStop" );
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		this.lifecycle.invokeHook( "onActivityDestroy" );
		super.onDestroy();
	}

	@Override
	protected void onActivityResult( int requestCode, int resultCode, Intent data ) {
		super.onActivityResult( requestCode, resultCode, data );
		this.lifecycle.invokeHook( "onActivityResult", requestCode, resultCode, data );
	}

	@Override
	public void onRequestPermissionsResult( int requestCode, String[] permissions, int[] grantResults ) {
		super.onRequestPermissionsResult( requestCode, permissions, grantResults );
		this.lifecycle.invokeHook( "onPermissionResult", requestCode, permissions, grantResults );
	}

	@Override
	@SuppressWarnings( "deprecation" )
	public void onBackPressed() {
		// Let Application.bx consume the event first (return false to consume).
		Object handled = this.lifecycle.invokeHook( "onBackPressed" );
		if ( Boolean.FALSE.equals( handled ) ) return;

		// Navigate back within the WebView before popping the Activity stack.
		if ( this.webRenderer != null && this.webRenderer.canGoBack() ) {
			this.webRenderer.goBack();
		} else {
			super.onBackPressed();
		}
	}

	@Override
	public void onConfigurationChanged( Configuration newConfig ) {
		super.onConfigurationChanged( newConfig );
		this.lifecycle.invokeHook( "onConfigurationChanged", newConfig );
	}

	@Override
	public void onLowMemory() {
		super.onLowMemory();
		this.lifecycle.invokeHook( "onLowMemory" );
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/**
	 * Determine the entry route for this launch. Returns the path+query from an
	 * {@code ACTION_VIEW} deep-link intent if present, otherwise the default
	 * {@link #entryRoute} ({@code /}).
	 */
	private String resolveEntryRoute() {
		String route = routeFromIntent( getIntent() );
		return route != null ? route : entryRoute;
	}

	private static String routeFromIntent( Intent intent ) {
		if ( intent == null || !Intent.ACTION_VIEW.equals( intent.getAction() ) ) {
			return null;
		}
		Uri uri = intent.getData();
		if ( uri == null ) return null;
		String path  = uri.getPath();
		String query = uri.getQuery();
		if ( path == null || path.isEmpty() ) return "/";
		return query != null && !query.isEmpty() ? path + "?" + query : path;
	}
}
