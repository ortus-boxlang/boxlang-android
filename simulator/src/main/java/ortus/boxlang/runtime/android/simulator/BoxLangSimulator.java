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
import ortus.boxlang.runtime.application.ApplicationClassListener;
import ortus.boxlang.runtime.application.BaseApplicationListener;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Development HTTP server that runs a BoxLang MVC app on the JVM with no Android SDK required.
 * <p>
 * Mirrors the {@code WebRequestExecutor} pattern from {@code boxlang-web-support}:
 * each HTTP request creates a fresh {@link ScriptingRequestBoxContext}, fires the full
 * BoxLang request lifecycle ({@code onRequestStart} → dispatch → {@code onRequestEnd}),
 * then shuts the context down. The BoxLang application service manages the application
 * scope and fires {@code onApplicationStart} exactly once per process lifetime —
 * no manual scope injection needed.
 * <p>
 * The {@code configureRouter(router)} convention hook is called once at startup after
 * the application descriptor is loaded for the first time.
 * Relocations ({@code event.relocate(target)}) are returned as HTTP 302 redirects.
 * POST form bodies ({@code application/x-www-form-urlencoded}) are parsed and passed
 * as request params, matching the Android WebView JS bridge behaviour.
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
		this.appPath = Paths.get( appPath ).toAbsolutePath().toString();
		this.port    = port;

		// Boot BoxRuntime with the app directory as home.
		System.setProperty( "boxlang.home", this.appPath );
		this.runtime = BoxRuntime.getInstance( false, null, this.appPath );

		// Register "/app" so createObject("app.handlers.Items") → <appPath>/handlers/Items.bx
		// — same mapping convention used by MVCDispatcherTest.
		this.runtime.getConfiguration().registerMapping( "/app", Struct.of(
		    Key.path, this.appPath,
		    Key.external, true
		) );

		// Build the MVC stack pointing at the app's views and layouts.
		RoutingService	routingService	= new RoutingService();
		ViewRenderer	viewRenderer	= new ViewRenderer(
		    this.runtime,
		    this.appPath + "/views",
		    this.appPath + "/layouts"
		);
		this.dispatcher = new MVCDispatcher( this.runtime, routingService, viewRenderer, "app.handlers" );

		// Load Application.bx for the first time: fires onApplicationStart automatically,
		// then call our configureRouter(router) convention hook if defined.
		bootstrapRouter( routingService );
	}

	// ── Bootstrap ─────────────────────────────────────────────────────────────

	/**
	 * Create a bootstrap request context to load the application descriptor.
	 * The BoxLang application service fires {@code onApplicationStart} automatically.
	 * We then call the {@code configureRouter(router)} hook if the app defines it.
	 */
	private void bootstrapRouter( RoutingService routingService ) {
		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( this.runtime.getRuntimeContext(), true );
		RequestBoxContext.setCurrent( ctx );
		try {
			BaseApplicationListener appListener = ctx.getApplicationListener();

			if ( appListener instanceof ApplicationClassListener acl ) {
				IClassRunnable	listenerClass	= acl.getListenerClass();
				Key				routerKey		= Key.of( "configureRouter" );

				if ( listenerClass.getThisScope().containsKey( routerKey ) ) {
					Map<Key, Object> args = new LinkedHashMap<>();
					args.put( Key.of( "router" ), routingService.getRouter() );
					listenerClass.dereferenceAndInvoke( ctx, routerKey, args, false );
					log.info( "Application.bx bootstrapped — configureRouter() complete, onApplicationStart fired." );
				} else {
					log.info( "Application.bx loaded — no configureRouter(), using convention routing." );
				}
			} else {
				log.warn( "No Application.bx found — using convention routing only." );
			}
		} catch ( Exception e ) {
			log.warn( "Could not bootstrap Application.bx ({}): using convention routing only.", e.getMessage() );
		} finally {
			ctx.shutdown();
			RequestBoxContext.removeCurrent();
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

		Thread.currentThread().join();
	}

	// ── Request handling ──────────────────────────────────────────────────────

	private void handle( HttpExchange exchange ) throws IOException {
		String method = exchange.getRequestMethod().toUpperCase();
		String uri    = exchange.getRequestURI().toString();

		// Parse POST form params from the request body.
		IStruct params = null;
		if ( "POST".equals( method ) ) {
			byte[] body = exchange.getRequestBody().readAllBytes();
			params = new Struct();
			parseFormBody( new String( body, StandardCharsets.UTF_8 ), params );
		}

		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( this.runtime.getRuntimeContext(), true );
		RequestBoxContext.setCurrent( ctx );
		try {
			BaseApplicationListener appListener = ctx.getApplicationListener();

			boolean proceed = appListener.onRequestStart( ctx, new Object[] { uri } );

			DispatchResult result = null;
			if ( proceed ) {
				result = this.dispatcher.dispatch( ctx, uri, method, params );
			}

			appListener.onRequestEnd( ctx, new Object[] { uri } );

			if ( result == null ) {
				exchange.sendResponseHeaders( 204, -1 );
				return;
			}

			if ( result.isRelocate() ) {
				log.debug( "{} {} → 302 {}", method, uri, result.getRelocateTarget() );
				exchange.getResponseHeaders().set( "Location", result.getRelocateTarget() );
				exchange.sendResponseHeaders( 302, -1 );
			} else {
				log.debug( "{} {} → 200", method, uri );
				byte[] response = result.getHtml().getBytes( StandardCharsets.UTF_8 );
				exchange.getResponseHeaders().set( "Content-Type", "text/html; charset=UTF-8" );
				exchange.sendResponseHeaders( 200, response.length );
				try ( OutputStream out = exchange.getResponseBody() ) {
					out.write( response );
				}
			}
		} catch ( Exception e ) {
			log.error( "Error dispatching {} {}", method, uri, e );
			sendError( exchange, e );
		} finally {
			ctx.shutdown();
			RequestBoxContext.removeCurrent();
			exchange.close();
		}
	}

	private static void sendError( HttpExchange exchange, Exception e ) throws IOException {
		String	msg		= e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		String	body	= "<html><body style='font-family:monospace;padding:16px'>"
		    + "<h2 style='color:#c00'>BoxLang Simulator Error</h2>"
		    + "<pre>" + escapeHtml( msg ) + "</pre>"
		    + "</body></html>";
		byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
		exchange.getResponseHeaders().set( "Content-Type", "text/html; charset=UTF-8" );
		exchange.sendResponseHeaders( 500, bytes.length );
		try ( OutputStream out = exchange.getResponseBody() ) {
			out.write( bytes );
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

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
