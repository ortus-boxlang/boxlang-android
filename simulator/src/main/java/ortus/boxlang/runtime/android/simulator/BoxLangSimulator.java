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
package ortus.boxlang.runtime.android.simulator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.android.mvc.DispatchResult;
import ortus.boxlang.runtime.android.mvc.MVCDispatcher;
import ortus.boxlang.runtime.android.mvc.RoutingService;
import ortus.boxlang.runtime.android.mvc.ViewRenderer;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Development HTTP server that runs a BoxLang MVC app on the JVM with no Android SDK required.
 * <p>
 * Boots {@link BoxRuntime} with the BoxLang app directory as its home, configures the router
 * from {@code Application.bx#configureRouter()}, seeds application state via
 * {@code onApplicationStart()}, then serves HTTP requests through the same
 * {@link MVCDispatcher} / {@link ViewRenderer} pipeline that runs on the device.
 * <p>
 * Relocates ({@code event.relocate(target)}) are forwarded as HTTP 302 redirects; POST form
 * bodies are parsed exactly as the Android WebView JS bridge does.
 * <p>
 * A shared {@link IStruct} stands in for BoxLang's application scope and is injected into
 * every request's variables scope as {@code application}. Writes to {@code application.items}
 * (or any other key) are therefore visible across subsequent requests.
 *
 * <pre>
 * ./gradlew :simulator:run --args="--app ../android-sample-web/src/main/bx --port 8085"
 * </pre>
 */
public class BoxLangSimulator {

	private static final Logger	log	= LoggerFactory.getLogger( BoxLangSimulator.class );

	private final String		appPath;
	private final int			port;
	private final BoxRuntime	runtime;
	private final MVCDispatcher	dispatcher;
	// Shared application-scope struct — injected into every request context.
	private final IStruct		applicationScope;

	// ── Entry point ───────────────────────────────────────────────────────────

	public static void main( String[] args ) throws Exception {
		int		port	= 8085;
		String	appPath	= resolveDefaultAppPath();

		for ( int i = 0; i < args.length - 1; i++ ) {
			if ( "--port".equalsIgnoreCase( args[ i ] ) ) port    = Integer.parseInt( args[ i + 1 ] );
			if ( "--app".equalsIgnoreCase( args[ i ] ) )  appPath = args[ i + 1 ];
		}

		new BoxLangSimulator( appPath, port ).start();
	}

	// ── Constructor ───────────────────────────────────────────────────────────

	public BoxLangSimulator( String appPath, int port ) {
		this.appPath          = Paths.get( appPath ).toAbsolutePath().toString();
		this.port             = port;
		this.applicationScope = new Struct();

		// Boot BoxRuntime with the app directory as home.
		System.setProperty( "boxlang.home", this.appPath );
		this.runtime = BoxRuntime.getInstance( false, null, this.appPath );

		// Register "/app" so createObject("app.handlers.Items") → <appPath>/handlers/Items.bx
		// — same mapping convention used by MVCDispatcherTest.
		runtime.getConfiguration().registerMapping( "/app", Struct.of(
		    Key.path, this.appPath,
		    Key.external, true
		) );

		// Build the MVC stack pointing at the app's views and layouts.
		RoutingService	routingService	= new RoutingService();
		ViewRenderer	viewRenderer	= new ViewRenderer(
		    runtime,
		    this.appPath + "/views",
		    this.appPath + "/layouts"
		);
		this.dispatcher = new MVCDispatcher( runtime, routingService, viewRenderer, "app.handlers" );

		// Load Application.bx, configure the router, and fire onApplicationStart.
		bootstrapApplication( routingService );
	}

	// ── Bootstrap ─────────────────────────────────────────────────────────────

	private void bootstrapApplication( RoutingService routingService ) {
		IBoxContext ctx = newRequestContext();
		try {
			Object raw = ctx.invokeFunction( Key.createObject, new Object[] { "app.Application" } );
			if ( raw instanceof IClassRunnable app ) {
				// configureRouter(router) — BoxLang calls Java methods through its interop layer.
				Map<Key, Object> routerArgs = new LinkedHashMap<>();
				routerArgs.put( Key.of( "router" ), routingService.getRouter() );
				app.dereferenceAndInvoke( ctx, Key.of( "configureRouter" ), routerArgs, false );

				// onApplicationStart() — seeds application.items (and any other state).
				app.dereferenceAndInvoke( ctx, Key.of( "onApplicationStart" ), new LinkedHashMap<>(), false );

				log.info( "Application.bx bootstrapped — router configured, onApplicationStart complete." );
			}
		} catch ( Exception e ) {
			log.warn( "Could not bootstrap Application.bx ({}): using convention routing only.", e.getMessage() );
		}
	}

	// ── HTTP server ───────────────────────────────────────────────────────────

	public void start() throws Exception {
		HttpServer server = HttpServer.create( new InetSocketAddress( port ), 0 );
		server.createContext( "/", this::handle );
		server.start();

		System.out.println();
		System.out.println( "  ╔═══════════════════════════════════════════════════════════╗" );
		System.out.println( "  ║          BoxLang Android Simulator                        ║" );
		System.out.println( "  ╠═══════════════════════════════════════════════════════════╣" );
		System.out.printf ( "  ║  App:  %-51s ║%n", abbreviate( appPath, 51 ) );
		System.out.printf ( "  ║  URL:  http://localhost:%-33d ║%n", port );
		System.out.println( "  ║  Press Ctrl-C to stop                                     ║" );
		System.out.println( "  ╚═══════════════════════════════════════════════════════════╝" );
		System.out.println();

		// Block the main thread until the process is killed.
		Thread.currentThread().join();
	}

	private void handle( HttpExchange exchange ) throws IOException {
		String method = exchange.getRequestMethod().toUpperCase();
		String uri    = exchange.getRequestURI().toString();

		try {
			// Decode POST form params from the request body.
			IStruct params = null;
			if ( "POST".equals( method ) ) {
				byte[] body = exchange.getRequestBody().readAllBytes();
				params = new Struct();
				parseFormBody( new String( body, StandardCharsets.UTF_8 ), params );
			}

			IBoxContext		ctx		= newRequestContext();
			DispatchResult	result	= dispatcher.dispatch( ctx, uri, method, params );
			log.debug( "{} {} → {}", method, uri, result.isRelocate() ? "302 " + result.getRelocateTarget() : "200" );

			if ( result.isRelocate() ) {
				exchange.getResponseHeaders().set( "Location", result.getRelocateTarget() );
				exchange.sendResponseHeaders( 302, -1 );
			} else {
				byte[] response = result.getHtml().getBytes( StandardCharsets.UTF_8 );
				exchange.getResponseHeaders().set( "Content-Type", "text/html; charset=UTF-8" );
				exchange.sendResponseHeaders( 200, response.length );
				try ( OutputStream out = exchange.getResponseBody() ) {
					out.write( response );
				}
			}
		} catch ( Exception e ) {
			log.error( "Error dispatching {} {}", method, uri, e );
			String	errorHtml	= "<h1>500 — Internal Server Error</h1><pre>" + escapeHtml( e.getMessage() ) + "</pre>";
			byte[]	bytes		= errorHtml.getBytes( StandardCharsets.UTF_8 );
			exchange.getResponseHeaders().set( "Content-Type", "text/html; charset=UTF-8" );
			exchange.sendResponseHeaders( 500, bytes.length );
			try ( OutputStream out = exchange.getResponseBody() ) {
				out.write( bytes );
			}
		} finally {
			exchange.close();
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	/**
	 * Create a fresh per-request context with the shared application scope injected into
	 * the variables scope as {@code application}. BoxLang handlers access {@code application.*}
	 * directly (e.g. {@code application.items}), which resolves to this shared struct.
	 */
	private IBoxContext newRequestContext() {
		IBoxContext ctx = new ScriptingRequestBoxContext();
		ctx.getScopeNearby( Key.variables ).put( Key.of( "application" ), applicationScope );
		return ctx;
	}

	private static void parseFormBody( String body, IStruct target ) {
		if ( body == null || body.isBlank() ) return;
		for ( String pair : body.split( "&" ) ) {
			int eq = pair.indexOf( '=' );
			if ( eq > 0 ) {
				String key   = URLDecoder.decode( pair.substring( 0, eq ), StandardCharsets.UTF_8 );
				String value = URLDecoder.decode( pair.substring( eq + 1 ), StandardCharsets.UTF_8 );
				target.put( Key.of( key ), value );
			}
		}
	}

	/** Walk common relative paths to find the BoxLang app directory. */
	private static String resolveDefaultAppPath() {
		String[] candidates = {
		    "../android-sample-web/src/main/bx",
		    "android-sample-web/src/main/bx",
		    "src/main/bx"
		};
		for ( String c : candidates ) {
			if ( Paths.get( c ).toFile().isDirectory() ) return c;
		}
		return candidates[ 0 ];
	}

	private static String abbreviate( String s, int max ) {
		return s.length() <= max ? s : "…" + s.substring( s.length() - ( max - 1 ) );
	}

	private static String escapeHtml( String s ) {
		if ( s == null ) return "(null)";
		return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
	}
}
