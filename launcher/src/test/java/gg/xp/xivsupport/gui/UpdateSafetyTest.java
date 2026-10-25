package gg.xp.xivsupport.gui;

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;

public class UpdateSafetyTest {

	private static final String overrideProperty = "triggevent-update-override-dir";
	private static final Object propertyLock = new Object();

	@DataProvider
	public Object[][] updaterClasses() {
		return new Object[][]{{Update.class}, {UpdateCopyForLegacyMigration.class}};
	}

	@DataProvider
	public Object[][] updateLocations() {
		return new Object[][]{
				{Update.class, false}, {Update.class, true},
				{UpdateCopyForLegacyMigration.class, false}, {UpdateCopyForLegacyMigration.class, true}
		};
	}

	private static Object invoke(Object receiver, String name, Object... args) throws Exception {
		Method method = List.of(receiver.getClass().getDeclaredMethods()).stream()
				.filter(candidate -> candidate.getName().equals(name))
				.findFirst().orElseThrow();
		method.setAccessible(true);
		try {
			return method.invoke(receiver, args);
		}
		catch (InvocationTargetException error) {
			if (error.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw error;
		}
	}

	private static Object updater(Class<?> type, Path installDir, List<String> messages) throws Exception {
		synchronized (propertyLock) {
			String previous = System.getProperty(overrideProperty);
			try {
				System.setProperty(overrideProperty, installDir.toString());
				Constructor<?> constructor = type.getDeclaredConstructor(Consumer.class, boolean.class, boolean.class);
				constructor.setAccessible(true);
				return constructor.newInstance((Consumer<String>) messages::add, false, false);
			}
			finally {
				if (previous == null) {
					System.clearProperty(overrideProperty);
				}
				else {
					System.setProperty(overrideProperty, previous);
				}
			}
		}
	}

	private static void configure(Path installDir, String template, String addons) throws Exception {
		Properties properties = new Properties();
		properties.setProperty("url_template", template);
		properties.setProperty("addons", addons);
		try (OutputStream output = Files.newOutputStream(installDir.resolve("update.properties"))) {
			properties.store(output, "Test configuration");
		}
	}

	private static void removeDirectory(Path directory) throws Exception {
		try (var files = Files.walk(directory)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}

	private static void assertNoStagedFiles(Path installDir) throws Exception {
		try (var files = Files.list(installDir)) {
			Assert.assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".triggevent-update-")));
		}
	}

	@Test(dataProvider = "updaterClasses")
	public void rejectsInvalidAddonDirectoryNames(Class<?> type) throws Exception {
		Path installDir = Files.createTempDirectory("triggevent-addon-test-");
		try {
			for (String name : List.of("Example/child", "Example\\child", "")) {
				configure(installDir, "https://example.invalid/%2$s", name + ":https://example.invalid/%s");
				Object updater = updater(type, installDir, new ArrayList<>());
				Assert.expectThrows(IllegalArgumentException.class, () -> invoke(updater, "getAllManifests"));
			}
		}
		finally {
			removeDirectory(installDir);
		}
	}

	private static void downloadTest(Class<?> type, boolean addon, int status) throws Exception {
		Path installDir = Files.createTempDirectory("triggevent-download-test-");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String oldContent = "Previously installed file";
		String newContent = "Updated file";
		String prefix = addon ? "" : "deps/";
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			boolean manifest = path.equals("/manifest");
			String body = manifest
					? "00000000000000000000000000000000 " + prefix + "current.jar\n00000000000000000000000000000000 " + prefix + "new.jar"
					: newContent;
			int responseStatus = manifest || path.equals("/" + prefix + "new.jar") ? 200 : status;
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(responseStatus, bytes.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(bytes);
			}
			finally {
				exchange.close();
			}
		});
		server.start();
		try {
			Path depsDir = Files.createDirectories(installDir.resolve(addon ? "addon/Example-addon_2" : "deps"));
			Path currentFile = depsDir.resolve("current.jar");
			Path obsoleteFile = depsDir.resolve("obsolete.jar");
			Files.writeString(currentFile, oldContent);
			Files.writeString(obsoleteFile, oldContent);
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			configure(installDir, baseUrl + "%2$s", addon ? "Example-addon_2:" + baseUrl + "%s" : "");
			List<String> messages = new ArrayList<>();
			Object updater = updater(type, installDir, messages);
			List<?> manifests = (List<?>) invoke(updater, "getAllManifests");
			Object manifest = manifests.get(addon ? 1 : 0);
			if (status == 200) {
				Assert.assertEquals(invoke(updater, "updateCheckSingleManifest", manifest), true);
				Assert.assertEquals(Files.readString(currentFile), newContent);
				Assert.assertEquals(Files.readString(depsDir.resolve("new.jar")), newContent);
				Assert.assertFalse(Files.exists(obsoleteFile));
			}
			else {
				RuntimeException failure = Assert.expectThrows(RuntimeException.class,
						() -> invoke(updater, "updateCheckSingleManifest", manifest));
				Assert.assertTrue(failure.getCause().getMessage().contains("HTTP " + status));
				Assert.assertEquals(Files.readString(currentFile), oldContent);
				Assert.assertEquals(Files.readString(obsoleteFile), oldContent);
				Assert.assertFalse(Files.exists(depsDir.resolve("new.jar")));
				Assert.assertFalse(messages.stream().anyMatch(message -> message.startsWith("Update finished!")));
			}
			assertNoStagedFiles(addon ? depsDir : installDir);
		}
		finally {
			server.stop(0);
			removeDirectory(installDir);
		}
	}

	@Test(dataProvider = "updateLocations")
	public void failedArtifactDownloadsLeaveInstalledFilesIntact(Class<?> type, boolean addon) throws Exception {
		downloadTest(type, addon, 404);
	}

	@Test(dataProvider = "updateLocations")
	public void successfulArtifactDownloadsReplaceInstalledFiles(Class<?> type, boolean addon) throws Exception {
		downloadTest(type, addon, 200);
	}

	@Test(dataProvider = "updaterClasses")
	public void restrictsUrlTemplatesToPathsOnTheConfiguredServer(Class<?> type) throws Exception {
		Path installDir = Files.createTempDirectory("triggevent-template-test-");
		try {
			for (String template : List.of("https://example.invalid/%s/v2/%s", "https://example.invalid/%1$s/v2/%2$s")) {
				configure(installDir, template, "");
				Object updater = updater(type, installDir, new ArrayList<>());
				Assert.assertEquals(invoke(updater, "makeUrl", "deps/file.jar"), URI.create("https://example.invalid/stable/v2/deps/file.jar"));
			}
			for (String template : List.of("https://example.invalid/a%20path/%2$s", "https://example.invalid/a%%20path/%2$s")) {
				configure(installDir, template, "");
				Assert.assertEquals(invoke(updater(type, installDir, new ArrayList<>()), "makeUrl", "deps/file.jar"),
						URI.create("https://example.invalid/a%20path/deps/file.jar"));
			}
			for (String template : List.of("https://example.invalid/%1000000000s", "https://example.invalid/%n",
					"file:///tmp/%2$s", "http://example.invalid/%2$s", "https://user:password@example.invalid/%2$s",
					"https://example.invalid/%2$s#fragment", "https://%s.example.invalid/%2$s")) {
				configure(installDir, template, "");
				Object updater = updater(type, installDir, new ArrayList<>());
				Assert.expectThrows(IllegalArgumentException.class, () -> invoke(updater, "makeUrl", "deps/file.jar"));
			}
			configure(installDir, "https://example.invalid/%2$s", "");
			Object updater = updater(type, installDir, new ArrayList<>());
			for (String file : List.of("../outside.jar", "/absolute.jar", "deps/../../outside.jar", "deps\\outside.jar",
					"%2e%2e/outside.jar", "deps/file.jar?query", "deps/file.jar#fragment")) {
				Assert.expectThrows(IllegalArgumentException.class, () -> invoke(updater, "makeUrl", file));
			}
		}
		finally {
			removeDirectory(installDir);
		}
	}

	@Test(dataProvider = "updateLocations")
	public void rejectsMalformedManifestBeforeChangingFiles(Class<?> type, boolean addon) throws Exception {
		Path installDir = Files.createTempDirectory("triggevent-manifest-test-");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String[] manifest = {""};
		AtomicInteger artifactRequests = new AtomicInteger();
		server.createContext("/", exchange -> {
			boolean isManifest = exchange.getRequestURI().getPath().equals("/manifest");
			if (!isManifest) {
				artifactRequests.incrementAndGet();
			}
			byte[] bytes = (isManifest ? manifest[0] : "unexpected download").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(bytes);
			}
			exchange.close();
		});
		server.start();
		try {
			Path files = Files.createDirectories(installDir.resolve(addon ? "addon/Example-addon_2" : "deps"));
			Path current = Files.writeString(files.resolve("current.jar"), "installed content");
			String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			configure(installDir, base + "%2$s", addon ? "Example-addon_2:" + base + "%s" : "");
			for (String path : List.of("../outside.jar", "deps/../../outside.jar", "/absolute.jar", "C:\\outside.jar",
					"deps\\outside.jar", "%2e%2e/outside.jar", "deps/./file.jar", "deps//file.jar", "deps/file.jar?query",
					"deps/.. /outside.exe", "deps/.. . /outside.exe", "deps/NUL.jar", "deps/CON", "deps/LPT1.txt")) {
				manifest[0] = "00000000000000000000000000000000 " + path;
				Object updater = updater(type, installDir, new ArrayList<>());
				List<?> manifests = (List<?>) invoke(updater, "getAllManifests");
				Assert.expectThrows(IllegalArgumentException.class,
						() -> invoke(updater, "updateCheckSingleManifest", manifests.get(addon ? 1 : 0)));
				Assert.assertEquals(Files.readString(current), "installed content");
				assertNoStagedFiles(files);
			}
			Assert.assertEquals(artifactRequests.get(), 0);
		}
		finally {
			server.stop(0);
			removeDirectory(installDir);
		}
	}

	@Test(dataProvider = "updateLocations")
	public void refusesRedirectsToAnotherServer(Class<?> type, boolean addon) throws Exception {
		Path installDir = Files.createTempDirectory("triggevent-redirect-test-");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		HttpServer otherServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger otherRequests = new AtomicInteger();
		otherServer.createContext("/", exchange -> {
			otherRequests.incrementAndGet();
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		otherServer.start();
		server.createContext("/", exchange -> {
			boolean isManifest = exchange.getRequestURI().getPath().equals("/manifest");
			if (isManifest) {
				byte[] body = ("00000000000000000000000000000000 " + (addon ? "" : "deps/") + "current.jar").getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				try (OutputStream output = exchange.getResponseBody()) {
					output.write(body);
				}
			}
			else {
				exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + otherServer.getAddress().getPort() + "/private");
				exchange.sendResponseHeaders(302, -1);
			}
			exchange.close();
		});
		server.start();
		try {
			Path files = Files.createDirectories(installDir.resolve(addon ? "addon/Example-addon_2" : "deps"));
			Path current = Files.writeString(files.resolve("current.jar"), "installed content");
			String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			configure(installDir, base + "%2$s", addon ? "Example-addon_2:" + base + "%s" : "");
			Object updater = updater(type, installDir, new ArrayList<>());
			List<?> manifests = (List<?>) invoke(updater, "getAllManifests");
			RuntimeException failure = Assert.expectThrows(RuntimeException.class,
					() -> invoke(updater, "updateCheckSingleManifest", manifests.get(addon ? 1 : 0)));
			Assert.assertTrue(failure.getCause().getMessage().contains("changed the server"));
			Assert.assertEquals(Files.readString(current), "installed content");
			Assert.assertEquals(otherRequests.get(), 0);
			assertNoStagedFiles(files);
		}
		finally {
			server.stop(0);
			otherServer.stop(0);
			removeDirectory(installDir);
		}
	}

	@Test(dataProvider = "updateLocations")
	public void followsRedirectsOnTheConfiguredServer(Class<?> type, boolean addon) throws Exception {
		Path installDir = Files.createTempDirectory("triggevent-local-redirect-test-");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (!path.startsWith("/content/")) {
				exchange.getResponseHeaders().add("Location", "/content" + path);
				exchange.sendResponseHeaders(302, -1);
			}
			else {
				String body = path.equals("/content/manifest")
						? "00000000000000000000000000000000 " + (addon ? "" : "deps/") + "current.jar"
						: "updated content";
				byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream output = exchange.getResponseBody()) {
					output.write(bytes);
				}
			}
			exchange.close();
		});
		server.start();
		try {
			Path files = Files.createDirectories(installDir.resolve(addon ? "addon/Example-addon_2" : "deps"));
			Path current = Files.writeString(files.resolve("current.jar"), "installed content");
			String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			configure(installDir, base + "%2$s", addon ? "Example-addon_2:" + base + "%s" : "");
			Object updater = updater(type, installDir, new ArrayList<>());
			List<?> manifests = (List<?>) invoke(updater, "getAllManifests");
			Assert.assertEquals(invoke(updater, "updateCheckSingleManifest", manifests.get(addon ? 1 : 0)), true);
			Assert.assertEquals(Files.readString(current), "updated content");
			assertNoStagedFiles(files);
		}
		finally {
			server.stop(0);
			removeDirectory(installDir);
		}
	}

	@Test(dataProvider = "updaterClasses")
	public void rejectsSymlinkedUpdateDirectories(Class<?> type) throws Exception {
		Path installDir = Files.createTempDirectory("triggevent-symlink-test-");
		Path outside = Files.createTempDirectory("triggevent-outside-test-");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] bytes = "00000000000000000000000000000000 deps/current.jar".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(bytes);
			}
			exchange.close();
		});
		server.start();
		try {
			Path current = Files.writeString(outside.resolve("current.jar"), "outside content");
			String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			configure(installDir, base + "%2$s", "");
			Object updater = updater(type, installDir, new ArrayList<>());
			List<?> manifests = (List<?>) invoke(updater, "getAllManifests");
			Files.createSymbolicLink(installDir.resolve("deps"), outside);
			Assert.expectThrows(IllegalArgumentException.class,
					() -> invoke(updater, "updateCheckSingleManifest", manifests.get(0)));
			Assert.assertEquals(Files.readString(current), "outside content");
			Files.delete(installDir.resolve("deps"));
			Files.createDirectories(installDir.resolve("addon"));
			Files.createSymbolicLink(installDir.resolve("addon/Example-addon_2"), outside);
			configure(installDir, base + "%2$s", "Example-addon_2:" + base + "%s");
			Object addonUpdater = updater(type, installDir, new ArrayList<>());
			Assert.expectThrows(IllegalArgumentException.class, () -> invoke(addonUpdater, "getAllManifests"));
			Assert.assertEquals(Files.readString(current), "outside content");
			Files.delete(installDir.resolve("addon/Example-addon_2"));
			Files.delete(installDir.resolve("addon"));
			Files.createDirectories(outside.resolve("removed-addon"));
			Path nested = Files.writeString(outside.resolve("removed-addon/sentinel.jar"), "outside nested content");
			Files.createSymbolicLink(installDir.resolve("addon"), outside);
			Assert.expectThrows(IllegalArgumentException.class, () -> invoke(addonUpdater, "deleteRemovedAddons", List.of()));
			Assert.assertEquals(Files.readString(nested), "outside nested content");
		}
		finally {
			server.stop(0);
			removeDirectory(installDir);
			removeDirectory(outside);
		}
	}
}
