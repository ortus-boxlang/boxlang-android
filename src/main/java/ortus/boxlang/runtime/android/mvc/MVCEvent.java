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
package ortus.boxlang.runtime.android.mvc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * The ColdBox-style request event object passed to every handler action, view, and layout.
 * <p>
 * It carries the single <b>request collection ({@code rc})</b> — a {@link Struct}
 * auto-populated from path/query/form/JSON parameters that handlers add to and views/layouts
 * read from — plus the rendering decisions the action makes: which {@code view} and
 * {@code layout} to render, or a {@code relocate} target to redirect to another route.
 * <p>
 * The front-controller flow is: the handler action runs first, mutates {@code rc} and calls
 * {@link #setView(String)} / {@link #setLayout(String)} (or {@link #relocate(String)}); then
 * the framework renders the chosen view inside the chosen layout with {@code rc} in scope.
 */
public class MVCEvent {

	/**
	 * The default layout name used when an action does not set one.
	 */
	public static final String	DEFAULT_LAYOUT	= "main";

	/**
	 * The request collection: shared across handler, view, and layout.
	 */
	private final IStruct		rc;

	/**
	 * The current event in {@code Handler.action} form, e.g. {@code Items.show}.
	 */
	private String				currentEvent;

	/**
	 * The view to render (relative path under {@code views/}, without extension), or {@code null}.
	 */
	private String				view;

	/**
	 * The layout to wrap the view in (relative path under {@code layouts/}, without extension).
	 */
	private String				layout;

	/**
	 * When {@code true}, the view is rendered without a layout.
	 */
	private boolean				noLayout		= false;

	/**
	 * The relocate target (a URI or named route), or {@code null} when not relocating.
	 */
	private String				relocateTarget;

	/**
	 * The HTTP method of the current request (upper-cased), or {@code null}.
	 */
	private final String		httpMethod;

	/**
	 * The router, injected by the dispatcher so templates can call {@link #buildLink}.
	 * {@code null} when the event is created outside the dispatch cycle (e.g. in tests).
	 */
	private Router				router;

	/**
	 * Construct an event with a fresh, empty request collection and the framework default layout.
	 *
	 * @param httpMethod The HTTP method of the request (may be {@code null})
	 */
	public MVCEvent( String httpMethod ) {
		this( new Struct(), httpMethod, DEFAULT_LAYOUT );
	}

	/**
	 * Construct an event with a pre-populated request collection and the framework default layout.
	 *
	 * @param rc         The request collection (must not be {@code null})
	 * @param httpMethod The HTTP method of the request (may be {@code null})
	 */
	public MVCEvent( IStruct rc, String httpMethod ) {
		this( rc, httpMethod, DEFAULT_LAYOUT );
	}

	/**
	 * Construct an event with a pre-populated request collection and an explicit default layout.
	 * Used by the dispatcher to honour the {@code defaultLayout} setting from {@code config/Coldbox.bx}.
	 *
	 * @param rc            The request collection (must not be {@code null})
	 * @param httpMethod    The HTTP method of the request (may be {@code null})
	 * @param defaultLayout The layout name to use when the action does not call {@link #setLayout}
	 */
	public MVCEvent( IStruct rc, String httpMethod, String defaultLayout ) {
		this.rc			= rc == null ? new Struct() : rc;
		this.httpMethod	= httpMethod == null ? null : httpMethod.toUpperCase();
		this.layout		= defaultLayout != null ? defaultLayout : DEFAULT_LAYOUT;
	}

	/**
	 * @return The request collection ({@code rc})
	 */
	public IStruct getCollection() {
		return this.rc;
	}

	/**
	 * Alias for {@link #getCollection()} matching the ColdBox {@code rc} name.
	 *
	 * @return The request collection
	 */
	public IStruct getRC() {
		return this.rc;
	}

	/**
	 * Get a value from the request collection.
	 *
	 * @param key The key
	 *
	 * @return The value, or {@code null} if absent
	 */
	public Object getValue( String key ) {
		return this.rc.get( Key.of( key ) );
	}

	/**
	 * Get a value from the request collection with a default fallback.
	 *
	 * @param key          The key
	 * @param defaultValue The fallback value
	 *
	 * @return The value, or {@code defaultValue} if absent
	 */
	public Object getValue( String key, Object defaultValue ) {
		Object value = this.rc.get( Key.of( key ) );
		return value == null ? defaultValue : value;
	}

	/**
	 * Put a value into the request collection.
	 *
	 * @param key   The key
	 * @param value The value
	 *
	 * @return This event for chaining
	 */
	public MVCEvent setValue( String key, Object value ) {
		this.rc.put( Key.of( key ), value );
		return this;
	}

	/**
	 * @param key The key
	 *
	 * @return {@code true} if the request collection contains the key
	 */
	public boolean valueExists( String key ) {
		return this.rc.containsKey( Key.of( key ) );
	}

	/**
	 * Set the view to render.
	 *
	 * @param view The view path relative to {@code views/} (without extension)
	 *
	 * @return This event for chaining
	 */
	public MVCEvent setView( String view ) {
		this.view = view;
		return this;
	}

	/**
	 * @return The view to render, or {@code null} if none set
	 */
	public String getView() {
		return this.view;
	}

	/**
	 * Set the layout that wraps the view.
	 *
	 * @param layout The layout path relative to {@code layouts/} (without extension)
	 *
	 * @return This event for chaining
	 */
	public MVCEvent setLayout( String layout ) {
		this.layout		= layout;
		this.noLayout	= false;
		return this;
	}

	/**
	 * @return The layout name (defaults to {@value #DEFAULT_LAYOUT})
	 */
	public String getLayout() {
		return this.layout;
	}

	/**
	 * Render the view without any layout.
	 *
	 * @return This event for chaining
	 */
	public MVCEvent noLayout() {
		this.noLayout = true;
		return this;
	}

	/**
	 * @return {@code true} if the view should render without a layout
	 */
	public boolean isNoLayout() {
		return this.noLayout;
	}

	/**
	 * Relocate (redirect) to another URI or named route. Short-circuits rendering.
	 *
	 * @param target The destination URI or named route
	 *
	 * @return This event for chaining
	 */
	public MVCEvent relocate( String target ) {
		this.relocateTarget = target;
		return this;
	}

	/**
	 * @return {@code true} if the action requested a relocate
	 */
	public boolean isRelocating() {
		return this.relocateTarget != null;
	}

	/**
	 * @return The relocate target, or {@code null} if not relocating
	 */
	public String getRelocateTarget() {
		return this.relocateTarget;
	}

	/**
	 * @return The HTTP method of the request (upper-cased), or {@code null}
	 */
	public String getHTTPMethod() {
		return this.httpMethod;
	}

	/**
	 * Set the current event string.
	 *
	 * @param event The event in {@code Handler.action} form
	 *
	 * @return This event for chaining
	 */
	public MVCEvent setCurrentEvent( String event ) {
		this.currentEvent = event;
		return this;
	}

	/**
	 * @return The current event string, e.g. {@code Items.show}
	 */
	public String getCurrentEvent() {
		return this.currentEvent;
	}

	// ── buildLink ─────────────────────────────────────────────────────────────

	/**
	 * Inject the router so templates can call {@link #buildLink}.
	 * Called by the dispatcher immediately after constructing the event.
	 *
	 * @param router The active router
	 *
	 * @return This event for chaining
	 */
	MVCEvent setRouter( Router router ) {
		this.router = router;
		return this;
	}

	/**
	 * Build a URL for a named route or an event/path string.
	 *
	 * <p>Usage in a {@code .bxm} view:
	 * <pre>
	 * &lt;a href="#event.buildLink('items')#"&gt;All items&lt;/a&gt;
	 * &lt;a href="#event.buildLink('item.show', {id: 42})#"&gt;Show item 42&lt;/a&gt;
	 * </pre>
	 *
	 * <p>Resolution:
	 * <ol>
	 * <li>If {@code name} matches a named route (registered via {@code .withName(…)}),
	 *     fill its {@code :placeholder} segments and append remaining params as a query
	 *     string.</li>
	 * <li>Otherwise treat {@code name} as a raw path ({@code /items}) or a
	 *     {@code Handler.action} event string ({@code Items.show} → {@code /items/show}),
	 *     with params appended as a query string.</li>
	 * </ol>
	 *
	 * @param name   A named route, a raw path, or a {@code Handler.action} string
	 * @param params Optional parameters (path placeholders consumed first, rest as query string)
	 *
	 * @return The built URL
	 */
	public String buildLink( String name, IStruct params ) {
		Map<String, String> paramsMap = structToStringMap( params );

		// 1. Try a named route first.
		if ( this.router != null ) {
			Route route = this.router.findByName( name );
			if ( route != null ) {
				return route.buildUrl( paramsMap );
			}
		}

		// 2. Fallback: raw path or Handler.action event string.
		String path;
		if ( name == null || name.isBlank() || name.startsWith( "/" ) ) {
			path = name == null || name.isBlank() ? "/" : name;
		} else {
			int dot = name.lastIndexOf( '.' );
			if ( dot > 0 ) {
				path = "/" + name.substring( 0, dot ).toLowerCase() + "/" + name.substring( dot + 1 );
			} else {
				path = "/" + name.toLowerCase();
			}
		}

		if ( paramsMap.isEmpty() ) {
			return path;
		}

		StringBuilder sb    = new StringBuilder( path ).append( '?' );
		boolean       first = true;
		for ( Map.Entry<String, String> entry : paramsMap.entrySet() ) {
			if ( !first ) sb.append( '&' );
			sb.append( URLEncoder.encode( entry.getKey(), StandardCharsets.UTF_8 ) );
			sb.append( '=' );
			sb.append( URLEncoder.encode( entry.getValue(), StandardCharsets.UTF_8 ) );
			first = false;
		}
		return sb.toString();
	}

	/**
	 * Convenience overload of {@link #buildLink(String, IStruct)} with no extra params.
	 *
	 * @param name A named route, a raw path, or a {@code Handler.action} string
	 *
	 * @return The built URL
	 */
	public String buildLink( String name ) {
		return buildLink( name, null );
	}

	private static Map<String, String> structToStringMap( IStruct struct ) {
		if ( struct == null ) {
			return new LinkedHashMap<>();
		}
		Map<String, String> result = new LinkedHashMap<>();
		struct.forEach( ( k, v ) -> result.put( k.getName(), v != null ? v.toString() : "" ) );
		return result;
	}
}
