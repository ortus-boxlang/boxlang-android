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

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.Context;
import android.content.res.AssetManager;

import ortus.boxlang.runtime.BoxRuntime;
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
 * The singleton entry point for running BoxLang on Android.
 * <p>
 * Boots {@link BoxRuntime} in Ahead-Of-Time mode (the NoOp compiler is selected
 * automatically because only it ships in the slim Android distribution — ART cannot
 * {@code defineClass()} raw JVM bytecode at runtime). It points the runtime home at an
 * app-private directory seeded from the APK's {@code bx/} asset payload, loads the bundled
 * {@code boxlang.json}, and wires up the MVC front controller (router + view renderer +
 * dispatcher) for the WebView track.
 * <p>
 * Tied to the Android application lifecycle: {@link BoxAndroidApplication#onCreate()}
 * calls {@link #boot(Context)} once, and process teardown calls {@link #shutdown()}.
 */
public final class AndroidBoxRuntime {

	private static final Logger			log				= LoggerFactory.getLogger( AndroidBoxRuntime.class );

	/** The directory (under the app's files dir) the runtime home is seeded into. */
	public static final String			HOME_DIR_NAME	= "boxlang";

	/** The asset directory inside the APK holding the BoxLang app payload. */
	public static final String			ASSET_APP_DIR	= "bx";

	/** Stamp file written after asset seeding; contains the APK versionCode. */
	private static final String			VERSION_STAMP	= ".boxlang_version";

	private static AndroidBoxRuntime	instance;

	private final BoxRuntime			runtime;
	private final File					appHome;
	private final RoutingService		routingService;
	private final MVCDispatcher			dispatcher;

	private AndroidBoxRuntime( BoxRuntime runtime, File appHome, RoutingService routingService, MVCDispatcher dispatcher ) {
		this.runtime		= runtime;
		this.appHome		= appHome;
		this.routingService	= routingService;
		this.dispatcher		= dispatcher;
	}

	// ── Boot ─────────────────────────────────────────────────────────────────

	/**
	 * Boot the runtime once for the given Android context.
	 * <p>
	 * Safe to call from {@link android.app.Application#onCreate()} — subsequent calls are no-ops.
	 *
	 * @param androidCtx The Android application context
	 *
	 * @return The booted singleton
	 */
	public static synchronized AndroidBoxRuntime boot( Context androidCtx ) {
		if ( instance != null ) {
			return instance;
		}

		// 1. Seed the runtime home from the APK assets, re-seeding on APK upgrades.
		File appHome = new File( androidCtx.getFilesDir(), HOME_DIR_NAME );
		seedFromAssets( androidCtx, appHome );

		// 2. Point BoxLang home at app-private storage (ART can't write to a global dir).
		System.setProperty( "boxlang.home", appHome.getAbsolutePath() );

		// 3. Install the Android class loader factory BEFORE booting so the runtime
		// never attempts URLClassLoader (absent on ART) and modules use DexClassLoader.
		BoxRuntime.setClassLoaderFactory( new AndroidClassLoaderFactory( androidCtx, appHome ) );

		// 4. Boot the core runtime. The NoOp boxpiler is selected automatically via
		// ServiceLoader — it is the only IBoxpiler on the classpath in the Android dist.
		File		configFile	= new File( appHome, "boxlang.json" );
		BoxRuntime	runtime		= BoxRuntime.getInstance(
		    false,
		    configFile.exists() ? configFile.getAbsolutePath() : null,
		    appHome.getAbsolutePath()
		);

		// 5. Register the /app mapping so createObject("app.handlers.Items") resolves
		// to <appHome>/handlers/Items.bx — the same convention used in tests.
		runtime.getConfiguration().registerMapping( "/app", Struct.of(
		    Key.path, appHome.getAbsolutePath(),
		    Key.external, true
		) );

		// 6. Read config/Coldbox.bx (if present) to get framework settings.
		// This runs in a bare scripting context — it does NOT load Application.bx / fire
		// onApplicationStart; that happens later in bootstrapRouter().
		FrameworkConfig cfg = readColdboxConfig( runtime, appHome );

		// 7. Build the MVC stack using values from config/Coldbox.bx (or defaults).
		// Register the RoutingService as a BoxLang global service so:
		//   (a) BoxLang scripts can call getService('RoutingService') / getRouter()
		//   (b) The runtime fires onShutdown() automatically on process exit.
		// The runtime is already fully started at this point, so we fire the
		// configuration and startup hooks manually before registering.
		RoutingService routingService = new RoutingService();
		routingService.onConfigurationLoad();
		routingService.onStartup();
		runtime.putGlobalService( RoutingService.NAME, routingService );
		// Apply the defaultEvent from config (Router.bx may override it during bootstrapRouter).
		routingService.getRouter().setDefaultEvent( cfg.defaultEvent() );

		ViewRenderer	viewRenderer	= new ViewRenderer(
		    runtime,
		    new File( appHome, cfg.viewsLocation() ).getAbsolutePath(),
		    new File( appHome, cfg.layoutsLocation() ).getAbsolutePath(),
		    cfg.viewExtension()
		);
		MVCDispatcher	dispatcher		= new MVCDispatcher( runtime, routingService, viewRenderer, "app." + cfg.handlersLocation(), cfg.defaultLayout() );

		instance = new AndroidBoxRuntime( runtime, appHome, routingService, dispatcher );

		// 8. Load Application.bx (fires onApplicationStart automatically) and register routes
		// from config/Router.bx or Application.bx configureRouter() (Router.bx may also
		// override the defaultEvent set above).
		instance.bootstrapRouter();

		return instance;
	}

	// ── Accessors ─────────────────────────────────────────────────────────────

	/** @return The booted singleton; throws if {@link #boot(Context)} has not been called. */
	public static AndroidBoxRuntime getInstance() {
		if ( instance == null ) {
			throw new IllegalStateException( "AndroidBoxRuntime has not been booted. Call boot(context) first." );
		}
		return instance;
	}

	/** @return The underlying BoxLang runtime. */
	public BoxRuntime getRuntime() {
		return this.runtime;
	}

	/** @return The app home directory (seeded from assets on first launch / upgrade). */
	public File getAppHome() {
		return this.appHome;
	}

	/** @return The routing service (owns the Router). */
	public RoutingService getRoutingService() {
		return this.routingService;
	}

	/** @return The MVC front-controller dispatcher. */
	public MVCDispatcher getDispatcher() {
		return this.dispatcher;
	}

	// ── Shutdown ──────────────────────────────────────────────────────────────

	/** Shut the runtime down (process teardown). */
	public static synchronized void shutdown() {
		if ( instance != null ) {
			instance.runtime.shutdown();
			instance = null;
		}
	}

	// ── Bootstrap ─────────────────────────────────────────────────────────────

	/**
	 * Load {@code Application.bx} once at boot time so the BoxLang application service
	 * creates the application scope and fires {@code onApplicationStart}. Then wire up
	 * the router using the first convention that matches:
	 * <ol>
	 * <li><b>{@code config/Router.bx}</b> (preferred) — if this file exists in the app home,
	 * it is loaded as a BoxLang class and its {@code configure(router)} method is called.</li>
	 * <li><b>{@code configureRouter(router)}</b> in {@code Application.bx} (inline fallback)
	 * — called when no separate Router file is present.</li>
	 * </ol>
	 * Subsequent per-request contexts will find the application already running and will
	 * NOT re-fire {@code onApplicationStart}.
	 */
	private void bootstrapRouter() {
		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( this.runtime.getRuntimeContext(), true );
		RequestBoxContext.setCurrent( ctx );
		try {
			// Load Application.bx — fires onApplicationStart automatically.
			BaseApplicationListener appListener = ctx.getApplicationListener();

			// Convention 1: config/Router.bx — preferred separation-of-concerns approach.
			if ( new File( this.appHome, "config/Router.bx" ).exists() ) {
				@SuppressWarnings( "unchecked" )
				IClassRunnable routerClass = ( IClassRunnable ) ctx.invokeFunction(
				    Key.of( "createObject" ),
				    new Object[] { "component", "app.config.Router" }
				);
				Map<Key, Object> args = new LinkedHashMap<>();
				args.put( Key.of( "router" ), this.routingService.getRouter() );
				routerClass.dereferenceAndInvoke( ctx, Key.of( "configure" ), args, false );
				log.info( "BoxLang Android: config/Router.bx loaded — routes registered." );
			}
			// Convention 2: configureRouter(router) inline in Application.bx — inline fallback.
			else if ( appListener instanceof ApplicationClassListener acl ) {
				IClassRunnable	listenerClass	= acl.getListenerClass();
				Key				routerKey		= Key.of( "configureRouter" );
				if ( listenerClass.getThisScope().containsKey( routerKey ) ) {
					Map<Key, Object> args = new LinkedHashMap<>();
					args.put( Key.of( "router" ), this.routingService.getRouter() );
					listenerClass.dereferenceAndInvoke( ctx, routerKey, args, false );
					log.info( "BoxLang Android: Application.bx configureRouter() called — routes registered." );
				}
			}
		} catch ( Exception e ) {
			log.warn( "BoxLang Android: Could not bootstrap router ({}). Using convention routing.", e.getMessage() );
		} finally {
			ctx.shutdown();
			RequestBoxContext.removeCurrent();
		}
	}

	// ── Framework config ──────────────────────────────────────────────────────

	/**
	 * Framework settings read from {@code config/Coldbox.bx} at boot time.
	 * All fields fall back to sensible defaults when the file is absent or a key is omitted.
	 */
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

	/**
	 * Load {@code config/Coldbox.bx} and return the framework settings it declares.
	 * Falls back to {@link FrameworkConfig#defaults()} when the file is absent or fails to load.
	 * <p>
	 * Uses a bare scripting context (no application listener) — {@code onApplicationStart} is
	 * fired later in {@link #bootstrapRouter()}.
	 */
	private static FrameworkConfig readColdboxConfig( BoxRuntime runtime, File appHome ) {
		if ( !new File( appHome, "config/Coldbox.bx" ).exists() ) {
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
					log.info( "BoxLang Android: config/Coldbox.bx loaded — framework settings applied." );
					return FrameworkConfig.from( coldbox );
				}
			}
			log.warn( "BoxLang Android: config/Coldbox.bx configure() did not return a {coldbox:{...}} struct — using defaults." );
			return FrameworkConfig.defaults();
		} catch ( Exception e ) {
			log.warn( "BoxLang Android: Could not load config/Coldbox.bx ({}), using defaults.", e.getMessage() );
			return FrameworkConfig.defaults();
		} finally {
			ctx.shutdown();
		}
	}

	// ── Asset seeding ─────────────────────────────────────────────────────────

	/**
	 * Recursively copy the APK's {@code bx/} asset payload into {@code appHome}.
	 * Re-seeds on APK upgrades by comparing the stored versionCode stamp with the
	 * currently installed version.
	 */
	private static void seedFromAssets( Context androidCtx, File appHome ) {
		long currentVersion = getVersionCode( androidCtx );
		File stamp          = new File( appHome, VERSION_STAMP );

		if ( stamp.exists() ) {
			try {
				long stored = Long.parseLong( new String( java.nio.file.Files.readAllBytes( stamp.toPath() ) ).trim() );
				if ( stored == currentVersion ) {
					return;		// already seeded for this APK version
				}
				log.info( "BoxLang Android: APK upgraded ({}→{}), re-seeding app home.", stored, currentVersion );
			} catch ( Exception ignored ) {
			}
		}

		appHome.mkdirs();
		copyAssetDir( androidCtx.getAssets(), ASSET_APP_DIR, appHome );

		try {
			java.nio.file.Files.writeString( stamp.toPath(), String.valueOf( currentVersion ) );
		} catch ( Exception e ) {
			log.warn( "BoxLang Android: Could not write version stamp: {}", e.getMessage() );
		}
	}

	@SuppressWarnings( "deprecation" )
	private static long getVersionCode( Context ctx ) {
		try {
			android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo( ctx.getPackageName(), 0 );
			return android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
		} catch ( Exception e ) {
			return 0;
		}
	}

	private static void copyAssetDir( AssetManager assets, String assetPath, File target ) {
		try {
			String[] children = assets.list( assetPath );
			if ( children == null || children.length == 0 ) {
				copyAssetFile( assets, assetPath, target );
				return;
			}
			target.mkdirs();
			for ( String child : children ) {
				copyAssetDir( assets, assetPath + "/" + child, new File( target, child ) );
			}
		} catch ( Exception e ) {
			throw new RuntimeException( "Failed to seed BoxLang app from assets: " + assetPath, e );
		}
	}

	private static void copyAssetFile( AssetManager assets, String assetPath, File target ) {
		try ( var in = assets.open( assetPath ); var out = new java.io.FileOutputStream( target ) ) {
			in.transferTo( out );
		} catch ( Exception e ) {
			throw new RuntimeException( "Failed to copy asset: " + assetPath, e );
		}
	}
}
