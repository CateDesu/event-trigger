package gg.xp.xivsupport.gui.tabs;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertThrows;

public class UpdaterConfigValidationTest {

	private static AddonDef definition() {
		AddonDef addon = new AddonDef();
		addon.name = "Example addon";
		addon.dirName = "Example-addon_2";
		addon.urlPattern = "https://example.invalid/%s";
		return addon;
	}

	@Test
	public void acceptsOrdinaryAddonMetadata() {
		UpdaterConfig.validateAddonDef(definition());
	}

	@DataProvider
	public Object[][] lineBreaks() {
		return new Object[][]{{"\n"}, {"\r"}, {"\r\n"}};
	}

	@Test(dataProvider = "lineBreaks")
	public void rejectsLineBreaksInDownloadTemplates(String lineBreak) {
		AddonDef addon = definition();
		addon.urlPattern += lineBreak;
		assertThrows(AddonValidationException.class, () -> UpdaterConfig.validateAddonDef(addon));
	}

	@DataProvider
	public Object[][] directoryNames() {
		return new Object[][]{{"Example/child"}, {"Example\\child"}, {"Example:child"}, {""}};
	}

	@Test(dataProvider = "directoryNames")
	public void rejectsDirectoryNamesWithPathSeparators(String name) {
		AddonDef addon = definition();
		addon.dirName = name;
		assertThrows(AddonValidationException.class, () -> UpdaterConfig.validateAddonDef(addon));
	}
}
