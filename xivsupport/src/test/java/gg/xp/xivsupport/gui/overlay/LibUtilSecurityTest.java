package gg.xp.xivsupport.gui.overlay;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class LibUtilSecurityTest {

	@DataProvider
	public Object[][] invalidResourceNames() {
		return new Object[][]{{"/.."}, {"/lib\\outside.so"}, {"/lib:stream.so"}};
	}

	@Test(dataProvider = "invalidResourceNames")
	public void rejectsFilesystemSyntaxInResourceNames(String resource) {
		assertThrows(IllegalArgumentException.class, () -> LibUtil.loadLibraryFromJar(resource));
	}

	@Test
	public void nativeDirectoriesAreUniqueAndPrivate() throws IOException {
		Set<Path> paths = new HashSet<>();
		try {
			for (int i = 0; i < 16; i++) {
				File directory = LibUtil.createTempDirectory("nativeutils");
				Path path = directory.toPath();
				assertTrue(paths.add(path));
				assertTrue(Files.isDirectory(path));
				try (var files = Files.list(path)) {
					assertEquals(files.count(), 0L);
				}
				if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
					Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
					for (PosixFilePermission permission : PosixFilePermission.values()) {
						if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
						assertFalse(permissions.contains(permission));
						}
					}
				}
			}
		}
		finally {
			for (Path path : paths) {
				Files.deleteIfExists(path);
			}
		}
	}
}
