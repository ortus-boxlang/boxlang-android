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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DispatchResult} — no runtime required.
 */
class DispatchResultTest {

	@DisplayName( "rendered() carries the HTML and is not a relocate" )
	@Test
	void testRendered() {
		DispatchResult result = DispatchResult.rendered( "<html>ok</html>" );

		assertThat( result.isRelocate() ).isFalse();
		assertThat( result.getHtml() ).isEqualTo( "<html>ok</html>" );
		assertThat( result.getRelocateTarget() ).isNull();
	}

	@DisplayName( "relocate() carries the target and has no HTML" )
	@Test
	void testRelocate() {
		DispatchResult result = DispatchResult.relocate( "/items?notice=Done" );

		assertThat( result.isRelocate() ).isTrue();
		assertThat( result.getRelocateTarget() ).isEqualTo( "/items?notice=Done" );
		assertThat( result.getHtml() ).isNull();
	}

	@DisplayName( "rendered() with empty HTML is not a relocate" )
	@Test
	void testRenderedEmptyHtml() {
		DispatchResult result = DispatchResult.rendered( "" );
		assertThat( result.isRelocate() ).isFalse();
		assertThat( result.getHtml() ).isEmpty();
	}
}
