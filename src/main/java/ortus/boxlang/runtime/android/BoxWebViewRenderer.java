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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.annotation.SuppressLint;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.android.mvc.DispatchResult;
import ortus.boxlang.runtime.android.mvc.MVCDispatcher;
import ortus.boxlang.runtime.application.BaseApplicationListener;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Renders the WebView track: dispatches virtual routes through the in-process
 * {@link MVCDispatcher} and loads the resulting HTML into an Android {@link WebView}.
 * There is NO web server — form submissions and link navigation are captured in the
 * WebView and routed back into the BoxLang runtime as synthetic requests.
 * <p>
 * Each call to {@link #navigate} creates a fresh {@link ScriptingRequestBoxContext},
 * fires the full BoxLang request lifecycle ({@code onRequestStart} → dispatch →
 * {@code onRequestEnd}), then shuts the context down — exactly mirroring the web
 * executor pattern from {@code boxlang-web-support}.
 * <p>
 * Two capture paths for inbound navigation:
 * <ul>
 * <li><b>Link nav / GET forms</b> → {@link WebViewClient#shouldOverrideUrlLoading}</li>
 * <li><b>POST forms</b> → {@code BoxBridge.submit(route, json)}, because Android's
 * {@code WebResourceRequest} never exposes the request body.</li>
 * </ul>
 */
public class BoxWebViewRenderer {

	private static final Logger	log				= LoggerFactory.getLogger( BoxWebViewRenderer.class );

	/** Maximum redirect depth before aborting to avoid infinite loops. */
	private static final int	MAX_REDIRECTS	= 10;

	/**
	 * JS injected after each page load to intercept POST form submissions via the
	 * {@code BoxBridge} Java interface instead of the URL loading path.
	 */
	private static final String	FORM_HOOK_JS	= "javascript:(function(){" +
	    "if(window.__boxHooked)return; window.__boxHooked=true;" +
	    "document.addEventListener('submit',function(e){" +
	    "var f=e.target; if((f.method||'get').toLowerCase()==='post'){" +
	    "e.preventDefault();" +
	    "var d={}; new FormData(f).forEach(function(v,k){d[k]=v;});" +
	    "BoxBridge.submit(f.getAttribute('action')||'/',JSON.stringify(d));" +
	    "}},true);" +
	    "})()";

	private final WebView		webView;
	private final MVCDispatcher	dispatcher;
	private final BoxRuntime	runtime;

	/** The route last successfully navigated to; used for state save/restore. */
	private String				currentRoute	= "/";

	/**
	 * @param webView    The Android WebView to render into
	 * @param dispatcher The MVC front-controller dispatcher
	 * @param runtime    The BoxLang runtime (used to create per-request contexts)
	 */
	@SuppressLint( "SetJavaScriptEnabled" )
	public BoxWebViewRenderer( WebView webView, MVCDispatcher dispatcher, BoxRuntime runtime ) {
		this.webView	= webView;
		this.dispatcher	= dispatcher;
		this.runtime	= runtime;

		this.webView.getSettings().setJavaScriptEnabled( true );
		this.webView.addJavascriptInterface( new Bridge(), "BoxBridge" );
		this.webView.setWebViewClient( new RouterClient() );
	}

	// ── Navigation ────────────────────────────────────────────────────────────

	/**
	 * Dispatch a route through the full BoxLang request lifecycle and load the
	 * rendered HTML into the WebView.
	 *
	 * @param path   The virtual route path (e.g. {@code /items/42})
	 * @param method The HTTP method ({@code "GET"} or {@code "POST"})
	 * @param params Incoming form / JSON params (may be {@code null})
	 */
	public void navigate( String path, String method, IStruct params ) {
		navigate( path, method, params, 0 );
	}

	/** @return The route path of the last successful navigation. */
	public String getCurrentRoute() {
		return this.currentRoute;
	}

	/** @return Whether the WebView has a page to go back to. */
	public boolean canGoBack() {
		return this.webView.canGoBack();
	}

	/** Navigate back within the WebView history. */
	public void goBack() {
		this.webView.goBack();
	}

	// ── Internal dispatch ─────────────────────────────────────────────────────

	private void navigate( String path, String method, IStruct params, int redirectDepth ) {
		if ( redirectDepth > MAX_REDIRECTS ) {
			loadError( path, new RuntimeException( "Too many redirects (>" + MAX_REDIRECTS + ")" ) );
			return;
		}

		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( this.runtime.getRuntimeContext(), true );
		RequestBoxContext.setCurrent( ctx );
		try {
			BaseApplicationListener appListener = ctx.getApplicationListener();

			boolean proceed = appListener.onRequestStart( ctx, new Object[] { path } );

			DispatchResult result = null;
			if ( proceed ) {
				result = this.dispatcher.dispatch( ctx, path, method, params );
			}

			appListener.onRequestEnd( ctx, new Object[] { path } );

			if ( result == null ) {
				// onRequestStart returned false — the app intercepted the request.
				// In production, onRequestStart typically writes its own output or navigates
				// elsewhere before returning false. We leave the WebView on the current page
				// rather than blanking it out.
				log.debug( "BoxLang Android: onRequestStart blocked {} {} — WebView unchanged.", method, path );
				return;
			}

			if ( result.isRelocate() ) {
				String target = result.getRelocateTarget();
				log.debug( "BoxLang Android: {} {} → 302 {}", method, path, target );
				// Relocate is a new request; recurse with incremented depth.
				navigate( target, "GET", null, redirectDepth + 1 );
				return;
			}

			this.currentRoute = path;
			log.debug( "BoxLang Android: {} {} → 200", method, path );
			webView.loadDataWithBaseURL( "https://boxlang.local/", result.getHtml(), "text/html", "UTF-8", null );

		} catch ( Throwable t ) {
			log.error( "BoxLang Android: error dispatching {} {}", method, path, t );
			loadError( path, t );
		} finally {
			ctx.shutdown();
			RequestBoxContext.removeCurrent();
		}
	}

	private void loadError( String path, Throwable t ) {
		String msg  = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
		String html = "<html><body style='font-family:monospace;padding:16px'>"
		    + "<h2 style='color:#c00'>BoxLang Error</h2>"
		    + "<p><b>Route:</b> " + escapeHtml( path ) + "</p>"
		    + "<pre>" + escapeHtml( msg ) + "</pre>"
		    + "</body></html>";
		webView.loadDataWithBaseURL( "https://boxlang.local/", html, "text/html", "UTF-8", null );
	}

	// ── Form hook ─────────────────────────────────────────────────────────────

	/** Inject the POST-form interceptor after each page load. Guard prevents double-injection. */
	private void installFormHook() {
		this.webView.evaluateJavascript( FORM_HOOK_JS, null );
	}

	// ── WebViewClient ─────────────────────────────────────────────────────────

	private final class RouterClient extends WebViewClient {

		@Override
		public boolean shouldOverrideUrlLoading( WebView view, WebResourceRequest request ) {
			String url = request.getUrl().toString().replaceFirst( "^https?://boxlang\\.local", "" );
			navigate( url, "GET", null );
			return true;
		}

		@Override
		public void onPageFinished( WebView view, String url ) {
			installFormHook();
		}
	}

	// ── JS Bridge ─────────────────────────────────────────────────────────────

	private final class Bridge {

		@JavascriptInterface
		public void submit( String route, String json ) {
			IStruct params = jsonToStruct( json );
			// WebView JS callbacks run on a background thread; marshal to the UI thread.
			webView.post( () -> navigate( route, "POST", params ) );
		}

		private IStruct jsonToStruct( String json ) {
			// Use a minimal context (no app descriptor needed) just to invoke JSONDeserialize.
			ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( runtime.getRuntimeContext() );
			try {
				Object parsed = ctx.invokeFunction( Key.of( "JSONDeserialize" ), new Object[] { json } );
				return parsed instanceof IStruct s ? s : new Struct();
			} catch ( Exception e ) {
				log.warn( "BoxLang Android: could not parse POST form JSON — {}", e.getMessage() );
				return new Struct();
			} finally {
				ctx.shutdown();
			}
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private static String escapeHtml( String s ) {
		if ( s == null ) return "(null)";
		return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
	}
}
