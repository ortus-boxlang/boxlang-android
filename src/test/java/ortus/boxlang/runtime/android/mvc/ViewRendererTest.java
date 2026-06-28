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

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Struct;

/**
 * Unit tests for {@link ViewRenderer}: path helpers and rendering contract.
 * End-to-end rendering through the full dispatcher is covered in {@link MVCDispatcherTest}.
 */
class ViewRendererTest {

	static BoxRuntime		runtime;
	static String			viewsRoot;
	static String			layoutsRoot;
	static ViewRenderer		renderer;

	@BeforeAll
	static void setUpClass() {
		runtime = BoxRuntime.getInstance( true );

		Path appDir = Paths.get( "src/test/resources/app" ).toAbsolutePath();
		viewsRoot	= appDir.resolve( "views" ).toString();
		layoutsRoot	= appDir.resolve( "layouts" ).toString();

		runtime.getConfiguration().registerMapping( "/app", Struct.of(
		    Key.path, appDir.toString(),
		    Key.external, true
		) );

		renderer = new ViewRenderer( runtime, viewsRoot, layoutsRoot );
	}

	// ── Path helpers ──────────────────────────────────────────────────────────

	@DisplayName( "viewPath() builds the correct absolute path with .bxm extension" )
	@Test
	void testViewPath() {
		assertThat( renderer.viewPath( "items/list" ) )
		    .isEqualTo( viewsRoot + "/items/list.bxm" );
	}

	@DisplayName( "layoutPath() builds the correct absolute path with .bxm extension" )
	@Test
	void testLayoutPath() {
		assertThat( renderer.layoutPath( "main" ) )
		    .isEqualTo( layoutsRoot + "/main.bxm" );
	}

	@DisplayName( "A custom extension is used by both viewPath and layoutPath" )
	@Test
	void testCustomExtension() {
		ViewRenderer cfmRenderer = new ViewRenderer( runtime, viewsRoot, layoutsRoot, ".cfm" );
		assertThat( cfmRenderer.viewPath( "items/list" ) ).endsWith( ".cfm" );
		assertThat( cfmRenderer.layoutPath( "main" ) ).endsWith( ".cfm" );
	}

	@DisplayName( "viewsRoot trailing slash is stripped (paths don't double-slash)" )
	@Test
	void testTrailingSlashStripped() {
		ViewRenderer r = new ViewRenderer( runtime, viewsRoot + "/", layoutsRoot + "/" );
		assertThat( r.viewPath( "items/list" ) ).doesNotContain( "//" );
		assertThat( r.layoutPath( "main" ) ).doesNotContain( "//" );
	}

	// ── Rendering ─────────────────────────────────────────────────────────────

	@DisplayName( "renderView() renders just the view, without layout chrome" )
	@Test
	void testRenderViewOnly() {
		IBoxContext ctx = new ScriptingRequestBoxContext();
		Struct rc = new Struct();
		rc.put( Key.of( "items" ), new Object[] { "Solo" } );

		MVCEvent event = new MVCEvent( rc, "GET" );
		event.setView( "items/list" );

		String html = renderer.renderView( ctx, event );
		assertThat( html ).contains( "[Solo]" );
		assertThat( html ).doesNotContain( "<h1>My App</h1>" );
	}

	@DisplayName( "render() wraps the view in the layout and exposes renderedView" )
	@Test
	void testRenderWrapped() {
		IBoxContext ctx = new ScriptingRequestBoxContext();
		Struct rc = new Struct();
		rc.put( Key.of( "items" ), new Object[] { "Mango" } );

		MVCEvent event = new MVCEvent( rc, "GET" );
		event.setView( "items/list" );

		String html = renderer.render( ctx, event );
		assertThat( html ).contains( "<h1>My App</h1>" );
		assertThat( html ).contains( "[Mango]" );
	}

	@DisplayName( "render() with noLayout() skips the layout wrapper" )
	@Test
	void testRenderNoLayout() {
		IBoxContext ctx = new ScriptingRequestBoxContext();
		Struct rc = new Struct();
		rc.put( Key.of( "items" ), new Object[] { "Papaya" } );

		MVCEvent event = new MVCEvent( rc, "GET" );
		event.setView( "items/list" ).noLayout();

		String html = renderer.render( ctx, event );
		assertThat( html ).contains( "[Papaya]" );
		assertThat( html ).doesNotContain( "<h1>My App</h1>" );
	}
}
