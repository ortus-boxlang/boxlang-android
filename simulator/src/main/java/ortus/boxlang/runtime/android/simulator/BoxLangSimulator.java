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

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
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

	private static final Logger				log			= LoggerFactory.getLogger( BoxLangSimulator.class );

	/** MIME types for static file serving from {@code <appPath>/public/}. */
	private static final Map<String, String>	MIME_TYPES;

	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put( "css",   "text/css" );
		m.put( "js",    "application/javascript" );
		m.put( "mjs",   "application/javascript" );
		m.put( "html",  "text/html; charset=UTF-8" );
		m.put( "htm",   "text/html; charset=UTF-8" );
		m.put( "txt",   "text/plain" );
		m.put( "json",  "application/json" );
		m.put( "xml",   "application/xml" );
		m.put( "png",   "image/png" );
		m.put( "jpg",   "image/jpeg" );
		m.put( "jpeg",  "image/jpeg" );
		m.put( "gif",   "image/gif" );
		m.put( "svg",   "image/svg+xml" );
		m.put( "ico",   "image/x-icon" );
		m.put( "woff",  "font/woff" );
		m.put( "woff2", "font/woff2" );
		m.put( "ttf",   "font/ttf" );
		m.put( "otf",   "font/otf" );
		m.put( "webp",  "image/webp" );
		MIME_TYPES = Collections.unmodifiableMap( m );
	}

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

		// Extract the framework's core BX files (FrameworkSupertype, Handler …) to
		// <appPath>/bxandroid/ and register the /bxandroid mapping so handlers can use
		// class extends="bxandroid.system.Handler"
		extractFrameworkFiles( this.appPath );
		this.runtime.getConfiguration().registerMapping( "/bxandroid", Struct.of(
		    Key.path, this.appPath + "/bxandroid",
		    Key.external, true
		) );

		// Read config/Coldbox.bx (if present) for framework settings.
		// Runs in a bare context — does NOT load Application.bx / fire onApplicationStart.
		FrameworkConfig cfg = readColdboxConfig( this.runtime, this.appPath );

		// Build the MVC stack using values from config/Coldbox.bx (or defaults).
		// Register RoutingService as a BoxLang global service so scripts can call
		// getService('RoutingService') and the runtime fires onShutdown() automatically.
		RoutingService routingService = new RoutingService();
		routingService.onConfigurationLoad();
		routingService.onStartup();
		this.runtime.putGlobalService( RoutingService.NAME, routingService );
		// Apply the defaultEvent from config (Router.bx may override it during bootstrapRouter).
		routingService.getRouter().setDefaultEvent( cfg.defaultEvent() );

		ViewRenderer viewRenderer = new ViewRenderer(
		    this.runtime,
		    this.appPath + "/" + cfg.viewsLocation(),
		    this.appPath + "/" + cfg.layoutsLocation(),
		    cfg.viewExtension()
		);
		this.dispatcher = new MVCDispatcher( this.runtime, routingService, viewRenderer, "app." + cfg.handlersLocation(), cfg.defaultLayout() );

		// Load Application.bx (fires onApplicationStart) then wire routes from
		// config/Router.bx or Application.bx configureRouter().
		bootstrapRouter( routingService );
	}

	// ── Bootstrap ─────────────────────────────────────────────────────────────

	/**
	 * Create a bootstrap request context to load the application descriptor.
	 * The BoxLang application service fires {@code onApplicationStart} automatically.
	 * Routes are then wired using the first convention that matches:
	 * <ol>
	 * <li><b>{@code config/Router.bx}</b> (preferred) — loaded as a BoxLang class;
	 * its {@code configure(router)} method is called.</li>
	 * <li><b>{@code configureRouter(router)}</b> in {@code Application.bx} (inline fallback).</li>
	 * </ol>
	 */
	private void bootstrapRouter( RoutingService routingService ) {
		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( this.runtime.getRuntimeContext(), true );
		RequestBoxContext.setCurrent( ctx );
		try {
			// Load Application.bx — fires onApplicationStart automatically.
			BaseApplicationListener appListener = ctx.getApplicationListener();

			// Convention 1: config/Router.bx — preferred separation-of-concerns approach.
			if ( new File( this.appPath, "config/Router.bx" ).exists() ) {
				@SuppressWarnings( "unchecked" )
				IClassRunnable routerClass = ( IClassRunnable ) ctx.invokeFunction(
				    Key.of( "createObject" ),
				    new Object[] { "component", "app.config.Router" }
				);
				Map<Key, Object> args = new LinkedHashMap<>();
				args.put( Key.of( "router" ), routingService.getRouter() );
				routerClass.dereferenceAndInvoke( ctx, Key.of( "configure" ), args, false );
				log.info( "config/Router.bx loaded — routes registered, onApplicationStart fired." );
			}
			// Convention 2: configureRouter(router) inline in Application.bx — inline fallback.
			else if ( appListener instanceof ApplicationClassListener acl ) {
				IClassRunnable	listenerClass	= acl.getListenerClass();
				Key				routerKey		= Key.of( "configureRouter" );
				if ( listenerClass.getThisScope().containsKey( routerKey ) ) {
					Map<Key, Object> args = new LinkedHashMap<>();
					args.put( Key.of( "router" ), routingService.getRouter() );
					listenerClass.dereferenceAndInvoke( ctx, routerKey, args, false );
					log.info( "Application.bx configureRouter() called — routes registered, onApplicationStart fired." );
				} else {
					log.info( "Application.bx loaded — no router config found, using convention routing." );
				}
			} else {
				log.warn( "No Application.bx found — using convention routing only." );
			}
		} catch ( Exception e ) {
			log.warn( "Could not bootstrap router ({}): using convention routing only.", e.getMessage() );
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

		// ── Static file serving ───────────────────────────────────────────────
		// Files under <appPath>/public/ are served directly without going through
		// the MVC dispatcher. The canonical-path check prevents path traversal.
		File publicDir = new File( this.appPath, "public" );
		if ( publicDir.isDirectory() ) {
			String filePath = uri.contains( "?" ) ? uri.substring( 0, uri.indexOf( '?' ) ) : uri;
			try {
				File publicCanon   = publicDir.getCanonicalFile();
				File requestedFile = new File( publicDir, filePath ).getCanonicalFile();
				if ( requestedFile.toPath().startsWith( publicCanon.toPath() ) && requestedFile.isFile() ) {
					serveStaticFile( exchange, requestedFile );
					exchange.close();
					return;
				}
			} catch ( IOException ignored ) {
				// fall through to MVC dispatch
			}
		}

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
				// onRequestStart returned false — the app intercepted and handled this request.
				// In production this means the app wrote its own response (redirect, auth wall, etc.).
				// In the simulator we surface a dev-friendly page so the browser doesn't go blank.
				String body = "<html><body style='font-family:monospace;padding:16px'>"
				    + "<h2>Request Intercepted</h2>"
				    + "<p><code>onRequestStart()</code> returned <code>false</code> for "
				    + "<code>" + escapeHtml( uri ) + "</code>.</p>"
				    + "<p>The application handled (or blocked) this request. "
				    + "If you expected a page here, check your <code>onRequestStart</code> implementation.</p>"
				    + "</body></html>";
				byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
				exchange.getResponseHeaders().set( "Content-Type", "text/html; charset=UTF-8" );
				exchange.sendResponseHeaders( 200, bytes.length );
				try ( OutputStream out = exchange.getResponseBody() ) {
					out.write( bytes );
				}
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

	private static void serveStaticFile( HttpExchange exchange, File file ) throws IOException {
		String	name	= file.getName();
		int		dot		= name.lastIndexOf( '.' );
		String	ext		= dot >= 0 ? name.substring( dot + 1 ).toLowerCase() : "";
		String	mime	= MIME_TYPES.getOrDefault( ext, "application/octet-stream" );
		byte[]	bytes	= Files.readAllBytes( file.toPath() );
		exchange.getResponseHeaders().set( "Content-Type", mime );
		exchange.sendResponseHeaders( 200, bytes.length );
		try ( OutputStream out = exchange.getResponseBody() ) {
			out.write( bytes );
		}
		log.debug( "Static  {} → 200 ({} bytes, {})", file.getName(), bytes.length, mime );
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

	// ── Framework BX files ───────────────────────────────────────────────────

	private static final String[] FRAMEWORK_BX_FILES = {
	    "bxandroid/system/FrameworkSupertype.bx",
	    "bxandroid/system/Handler.bx"
	};

	private static void extractFrameworkFiles( String appPath ) {
		for ( String resource : FRAMEWORK_BX_FILES ) {
			File target = new File( appPath, resource );
			target.getParentFile().mkdirs();
			try ( java.io.InputStream in = BoxLangSimulator.class.getClassLoader().getResourceAsStream( resource ) ) {
				if ( in == null ) {
					log.warn( "Framework resource not found on classpath: {}", resource );
					continue;
				}
				java.nio.file.Files.copy( in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING );
			} catch ( Exception e ) {
				log.warn( "Could not extract framework file {} ({})", resource, e.getMessage() );
			}
		}
	}

	// ── Framework config ─────────────────────────────────────────────────────

	private record FrameworkConfig(
	    String viewsLocation,
	    String layoutsLocation,
	    String viewExtension,
	    String handlersLocation,
	    String defaultEvent,
	    String defaultLayout
	) {

		static FrameworkConfig defaults() {
			return new FrameworkConfig( "views", "layouts", ".bxm", "handlers", "Main.index",
			    ortus.boxlang.runtime.android.mvc.MVCEvent.DEFAULT_LAYOUT );
		}

		static FrameworkConfig from( IStruct coldbox ) {
			return new FrameworkConfig(
			    str( coldbox, "viewsLocation", "views" ),
			    str( coldbox, "layoutsLocation", "layouts" ),
			    str( coldbox, "viewExtension", ".bxm" ),
			    str( coldbox, "handlersLocation", "handlers" ),
			    str( coldbox, "defaultEvent", "Main.index" ),
			    str( coldbox, "defaultLayout", ortus.boxlang.runtime.android.mvc.MVCEvent.DEFAULT_LAYOUT )
			);
		}

		private static String str( IStruct s, String key, String def ) {
			Object v = s.getOrDefault( Key.of( key ), def );
			return v != null && !v.toString().isBlank() ? v.toString() : def;
		}
	}

	private FrameworkConfig readColdboxConfig( BoxRuntime runtime, String appPath ) {
		if ( !new File( appPath, "config/Coldbox.bx" ).exists() ) {
			return FrameworkConfig.defaults();
		}
		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( runtime.getRuntimeContext() );
		try {
			IClassRunnable coldboxClass = ( IClassRunnable ) ctx.invokeFunction(
			    Key.of( "createObject" ),
			    new Object[] { "component", "app.config.Coldbox" }
			);
			Object result = coldboxClass.dereferenceAndInvoke( ctx, Key.of( "configure" ), new LinkedHashMap<>(), false );
			if ( result instanceof IStruct configStruct ) {
				Object coldboxNode = configStruct.getOrDefault( Key.of( "coldbox" ), null );
				if ( coldboxNode instanceof IStruct coldbox ) {
					log.info( "config/Coldbox.bx loaded — framework settings applied." );
					return FrameworkConfig.from( coldbox );
				}
			}
			log.warn( "config/Coldbox.bx configure() did not return a {{coldbox:{{...}}}} struct — using defaults." );
			return FrameworkConfig.defaults();
		} catch ( Exception e ) {
			log.warn( "Could not load config/Coldbox.bx ({}), using defaults.", e.getMessage() );
			return FrameworkConfig.defaults();
		} finally {
			ctx.shutdown();
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
