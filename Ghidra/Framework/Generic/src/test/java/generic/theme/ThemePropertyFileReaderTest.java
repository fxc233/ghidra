/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package generic.theme;

import static ghidra.util.WebColors.*;
import static org.junit.Assert.*;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import javax.swing.Icon;

import org.junit.Test;

import resources.MultiIcon;
import resources.ResourceManager;

public class ThemePropertyFileReaderTest {

	@Test
	public void testDefaults() throws IOException {
		//@formatter:off
		ThemePropertyFileReader reader = new ThemePropertyFileReader("test", new StringReader(String.join("\n", 
			"[Defaults]", 
			"  color.b.1    = white",					// WHITE
			"  color.b.2    = #ff0000",					// RED
			"  color.b.3    = 0x008000",				// GREEN
			"  color.b.4    = 0xff000080",				// half alpha red
			"  color.b.5 	= rgb(0,0,255)",			// BLUE
			"  color.b.6 	= rgba(255,0,0,0.5)",		// half alpha red
			"  color.b.7    = color.b.1",				// ref
			"  font.a.8     = dialog-PLAIN-14",
			"  font.a.9     = font.a.8",
			"  font.a.b     = (font.a.8[20][BOLD])",
			"  icon.a.10     = core.png",
			"  icon.a.11     = icon.a.10",
			"  icon.a.12    = icon.a.10[size(17,21)]",
			"  icon.a.13    = core.png[size(17,21)]",
			"  icon.a.14    = icon.a.10{core.png[size(4,4)][move(8, 8)]",
			"")));
		//@formatter:on

		Color halfAlphaRed = new Color(0x80ff0000, true);
		GThemeValueMap values = reader.getDefaultValues();
		assertEquals(15, values.size());

		assertEquals(WHITE, getColor(values, "color.b.1"));
		assertEquals(RED, getColor(values, "color.b.2"));
		assertEquals(GREEN, getColor(values, "color.b.3"));
		assertEquals(halfAlphaRed, getColor(values, "color.b.4"));
		assertEquals(BLUE, getColor(values, "color.b.5"));
		assertEquals(halfAlphaRed, getColor(values, "color.b.6"));
		assertEquals(WHITE, getColor(values, "color.b.7"));

		assertEquals(new Font("dialog", Font.PLAIN, 14), getFont(values, "font.a.8"));
		assertEquals(new Font("dialog", Font.PLAIN, 14), getFont(values, "font.a.9"));
		assertEquals(new Font("dialog", Font.BOLD, 20), getFont(values, "font.a.b"));

		assertEquals(ResourceManager.loadImage("core.png"), getIcon(values, "icon.a.10"));
		assertEquals(ResourceManager.loadImage("core.png"), getIcon(values, "icon.a.11"));
		Icon icon = getIcon(values, "icon.a.12");
		assertEquals(17, icon.getIconWidth());
		assertEquals(21, icon.getIconHeight());

		icon = getIcon(values, "icon.a.13");
		assertEquals(17, icon.getIconWidth());
		assertEquals(21, icon.getIconHeight());

		icon = getIcon(values, "icon.a.14");
		assertTrue(icon instanceof MultiIcon);

	}

	@Test
	public void testDarkDefaults() throws IOException {
		//@formatter:off
		ThemePropertyFileReader reader = new ThemePropertyFileReader("test", new StringReader(String.join("\n", 
			"[Dark Defaults]", 
			"  color.b.1    = white",					// WHITE
			"  color.b.2    = #ff0000",					// RED
			"  color.b.3    = 0x008000",				// GREEN
			"  color.b.4    = 0xff000080",				// half alpha red
			"  color.b.5 	= rgb(0,0,255)",			// BLUE
			"  color.b.6 	= rgba(255,0,0,0.5)",		// half alpha red
			"  color.b.7    = color.b.1",				// ref
			"")));
		//@formatter:on

		Color halfAlphaRed = new Color(0x80ff0000, true);
		GThemeValueMap values = reader.getDarkDefaultValues();
		assertEquals(7, values.size());

		assertEquals(WHITE, getColor(values, "color.b.1"));
		assertEquals(RED, getColor(values, "color.b.2"));
		assertEquals(GREEN, getColor(values, "color.b.3"));
		assertEquals(halfAlphaRed, getColor(values, "color.b.4"));
		assertEquals(BLUE, getColor(values, "color.b.5"));
		assertEquals(halfAlphaRed, getColor(values, "color.b.6"));
		assertEquals(WHITE, getColor(values, "color.b.7"));
	}

	@Test
	public void testBothDefaultsAndDarkDefaultsInSameFile() throws IOException {
		//@formatter:off
		ThemePropertyFileReader reader = new ThemePropertyFileReader("test", new StringReader(String.join("\n", 
			"[Defaults]", 
			"  color.b.1    = white",					// WHITE
			"  color.b.2    = #ff0000",					// RED
			"[Dark Defaults]", 
			"  color.b.1    = black",					// BLACK
			"  color.b.2    = #0000ff",					// BLUE
			"")));
		//@formatter:on

		GThemeValueMap values = reader.getDefaultValues();
		assertEquals(2, values.size());

		GThemeValueMap darkValues = reader.getDarkDefaultValues();
		assertEquals(2, values.size());

		assertEquals(WHITE, getColor(values, "color.b.1"));
		assertEquals(RED, getColor(values, "color.b.2"));
		assertEquals(BLACK, getColor(darkValues, "color.b.1"));
		assertEquals(BLUE, getColor(darkValues, "color.b.2"));
	}

	@Test
	public void testParseColorError() throws IOException {
		//@formatter:off
		ThemePropertyFileReader reader = new ThemePropertyFileReader("test", new StringReader(String.join("\n", 
			"[Defaults]", 
			"  color.b.1    = white",					// WHITE
			"  color.b.2    = sdfsdf",					// RED
			"")));
		//@formatter:on
		List<String> errors = reader.getErrors();
		assertEquals(1, errors.size());
		assertEquals("Error parsing file \"test\" at line: 3, Could not parse Color value: sdfsdf",
			errors.get(0));
	}

	@Test
	public void testParseFontError() throws IOException {
		//@formatter:off
		ThemePropertyFileReader reader = new ThemePropertyFileReader("test", new StringReader(String.join("\n", 
			"[Defaults]", 
			"  font.b.1    =  Dialog-PLAIN-14",					
			"  font.b.2    = Dialog-PLANE-13",				
			"  font.b.3    = Dialog-BOLD-ITALIC",				
			"")));
		//@formatter:on
		List<String> errors = reader.getErrors();
		assertEquals(2, errors.size());

	}

	@Test
	public void testParseFontModiferError() throws IOException {
		//@formatter:off
		ThemePropertyFileReader reader = new ThemePropertyFileReader("test", new StringReader(String.join("\n", 
			"[Defaults]", 
			"  font.b.1    =  Dialog-PLAIN-14",					
			"  font.b.2    = (font.b.1[)",				
			"")));
		//@formatter:on
		List<String> errors = reader.getErrors();
		assertEquals(1, errors.size());

	}

	private Color getColor(GThemeValueMap values, String id) {
		ColorValue color = values.getColor(id);
		return color.get(values);
	}

	private Font getFont(GThemeValueMap values, String id) {
		FontValue font = values.getFont(id);
		return font.get(values);
	}

	private Icon getIcon(GThemeValueMap values, String id) {
		IconValue icon = values.getIcon(id);
		return icon.get(values);
	}
}
