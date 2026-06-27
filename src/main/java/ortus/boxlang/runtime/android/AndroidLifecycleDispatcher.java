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

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.application.ApplicationClassListener;
import ortus.boxlang.runtime.application.BaseApplicationListener;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.scopes.Key;

/**
 * Dispatches Android lifecycle callbacks to the optional Android-specific hook methods on
 * the app's {@code Application.bx}, using convention-over-configuration: a hook fires only
 * when the developer has defined a matching function, otherwise it is a clean no-op.
 * <p>
 * Each hook invocation creates its own short-lived {@link ScriptingRequestBoxContext} so
 * lifecycle events (onActivityResume, onActivityPause, etc.) are never coupled to a stale
 * or recycled request context. The application listener — and with it the application scope
 * — is resolved fresh each time via the BoxLang application service.
 * <p>
 * Supported hooks:
 * {@code onActivityCreate}, {@code onActivityStart}, {@code onActivityResume},
 * {@code onActivityPause}, {@code onActivityStop}, {@code onActivityDestroy},
 * {@code onActivityResult}, {@code onPermissionResult}, {@code onBackPressed},
 * {@code onLowMemory}, {@code onConfigurationChanged}.
 */
public class AndroidLifecycleDispatcher {

	private static final Logger	log	= LoggerFactory.getLogger( AndroidLifecycleDispatcher.class );

	private final BoxRuntime	runtime;

	/**
	 * @param runtime The BoxLang runtime (used to create per-hook contexts)
	 */
	public AndroidLifecycleDispatcher( BoxRuntime runtime ) {
		this.runtime = runtime;
	}

	/**
	 * Invoke an optional Android hook on {@code Application.bx} if it is defined.
	 * Creates a fresh request context for the duration of the hook call.
	 *
	 * @param hook The hook name (e.g. {@code "onActivityResume"})
	 * @param args The positional arguments to pass
	 *
	 * @return The hook's return value, or {@code null} if the hook is not defined or an error occurs
	 */
	public Object invokeHook( String hook, Object... args ) {
		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( this.runtime.getRuntimeContext(), true );
		RequestBoxContext.setCurrent( ctx );
		try {
			IClassRunnable listener = resolveListenerClass( ctx );
			if ( listener == null ) {
				return null;
			}
			Key hookKey = Key.of( hook );
			if ( !listener.getThisScope().containsKey( hookKey ) ) {
				return null;
			}
			return listener.dereferenceAndInvoke( ctx, hookKey, args, false );
		} catch ( Exception e ) {
			log.warn( "BoxLang Android: error in lifecycle hook {} — {}", hook, e.getMessage() );
			return null;
		} finally {
			ctx.shutdown();
			RequestBoxContext.removeCurrent();
		}
	}

	/**
	 * @param hook The hook name
	 *
	 * @return {@code true} if {@code Application.bx} defines the hook
	 */
	public boolean hasHook( String hook ) {
		ScriptingRequestBoxContext ctx = new ScriptingRequestBoxContext( this.runtime.getRuntimeContext(), true );
		RequestBoxContext.setCurrent( ctx );
		try {
			IClassRunnable listener = resolveListenerClass( ctx );
			return listener != null && listener.getThisScope().containsKey( Key.of( hook ) );
		} finally {
			ctx.shutdown();
			RequestBoxContext.removeCurrent();
		}
	}

	private IClassRunnable resolveListenerClass( ScriptingRequestBoxContext ctx ) {
		BaseApplicationListener appListener = ctx.getApplicationListener();
		if ( appListener instanceof ApplicationClassListener acl ) {
			return acl.getListenerClass();
		}
		return null;
	}
}
