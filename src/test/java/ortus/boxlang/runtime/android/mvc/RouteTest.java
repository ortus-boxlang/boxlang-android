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

import static com.google.common.truth.Truth.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Route}: normalization, path matching, and {@code buildUrl}.
 * Router-level resolution is covered in {@link RouterTest}; this class focuses on
 * {@link Route}-specific behaviour that is easier to verify with a single route in isolation.
 */
class RouteTest {

	// ── normalize ─────────────────────────────────────────────────────────────

	@DisplayName( "normalize() keeps the root path as-is" )
	@Test
	void testNormalizeRoot() {
		assertThat( Route.normalize( "/" ) ).isEqualTo( "/" );
	}

	@DisplayName( "normalize() adds a leading slash if missing" )
	@Test
	void testNormalizeAddLeadingSlash() {
		assertThat( Route.normalize( "items" ) ).isEqualTo( "/items" );
	}

	@DisplayName( "normalize() strips a trailing slash (non-root)" )
	@Test
	void testNormalizeStripTrailingSlash() {
		assertThat( Route.normalize( "/items/" ) ).isEqualTo( "/items" );
	}

	@DisplayName( "normalize() handles null and empty as root" )
	@Test
	void testNormalizeNullEmpty() {
		assertThat( Route.normalize( null ) ).isEqualTo( "/" );
		assertThat( Route.normalize( "" ) ).isEqualTo( "/" );
	}

	// ── match ─────────────────────────────────────────────────────────────────

	@DisplayName( "match() extracts a single path placeholder" )
	@Test
	void testMatchSinglePlaceholder() {
		Route		route	= new Route( null, "/items/:id", "Items", "show", null );
		RouteMatch	match	= route.match( "/items/42", "GET" );

		assertThat( match ).isNotNull();
		assertThat( match.getParams() ).containsEntry( "id", "42" );
	}

	@DisplayName( "match() returns null when the method constraint is not met" )
	@Test
	void testMatchWrongMethod() {
		Route route = new Route( null, "/items", "Items", "list",
		    java.util.Set.of( "GET" ) );

		assertThat( route.match( "/items", "POST" ) ).isNull();
		assertThat( route.match( "/items", "GET" ) ).isNotNull();
	}

	@DisplayName( "match() accepts any method when unconstrained" )
	@Test
	void testMatchAnyMethod() {
		Route route = new Route( null, "/items", "Items", "list", null );
		assertThat( route.match( "/items", "GET" ) ).isNotNull();
		assertThat( route.match( "/items", "POST" ) ).isNotNull();
	}

	// ── buildUrl ──────────────────────────────────────────────────────────────

	@DisplayName( "buildUrl() returns the pattern as-is when params are empty" )
	@Test
	void testBuildUrlNoParams() {
		Route route = new Route( null, "/items", "Items", "list", null );
		assertThat( route.buildUrl( Map.of() ) ).isEqualTo( "/items" );
	}

	@DisplayName( "buildUrl() fills a single :placeholder from the params map" )
	@Test
	void testBuildUrlSinglePlaceholder() {
		Route route = new Route( null, "/items/:id", "Items", "show", null );

		Map<String, String> params = new LinkedHashMap<>();
		params.put( "id", "42" );

		assertThat( route.buildUrl( params ) ).isEqualTo( "/items/42" );
	}

	@DisplayName( "buildUrl() fills multiple placeholders in order" )
	@Test
	void testBuildUrlMultiplePlaceholders() {
		Route route = new Route( null, "/blog/:year/:slug", "Blog", "entry", null );

		Map<String, String> params = new LinkedHashMap<>();
		params.put( "year", "2026" );
		params.put( "slug", "hello-world" );

		assertThat( route.buildUrl( params ) ).isEqualTo( "/blog/2026/hello-world" );
	}

	@DisplayName( "buildUrl() appends unused params as a query string" )
	@Test
	void testBuildUrlQueryString() {
		Route route = new Route( null, "/items/:id", "Items", "show", null );

		Map<String, String> params = new LinkedHashMap<>();
		params.put( "id", "42" );
		params.put( "tab", "2" );

		assertThat( route.buildUrl( params ) ).isEqualTo( "/items/42?tab=2" );
	}

	@DisplayName( "buildUrl() URL-encodes placeholder values with %20 (not +)" )
	@Test
	void testBuildUrlEncodesSpaceAsPercent20() {
		Route route = new Route( null, "/search/:term", "Search", "results", null );

		Map<String, String> params = new LinkedHashMap<>();
		params.put( "term", "hello world" );

		assertThat( route.buildUrl( params ) ).isEqualTo( "/search/hello%20world" );
	}

	@DisplayName( "buildUrl() leaves unfilled placeholders in place when no matching param" )
	@Test
	void testBuildUrlMissingParam() {
		Route route = new Route( null, "/items/:id", "Items", "show", null );
		assertThat( route.buildUrl( Map.of() ) ).isEqualTo( "/items/:id" );
	}
}
