package com.greysworkshop.hoardeye;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the mod up to date by itself. Once per game session it asks GitHub for the latest release; if it's newer and
 * has a jar for this Minecraft version, the jar is downloaded next to the one in use (named ".jar.update", which the
 * game ignores), checked against the SHA-256 GitHub publishes and against its own fabric.mod.json, and swapped in by
 * {@link UpdateSwap} once the game has closed. The next start runs the new version and says so once in chat.
 * <p>
 * Nothing is asked. If updating by itself can't work here (the mod isn't a plain jar in a folder, e.g. in development,
 * or the folder can't be written), it falls back to a note in chat with a download link. Off in the settings.
 */
public final class UpdateCheck {
	private UpdateCheck() {}

	private static final String REPO = "PlayWitIt/Hoard-Eye";
	private static final String NAME = "Hoard Eye";
	private static final String MOD_ID = HoardEyeClient.MOD_ID;
	/** The start of every release jar's name ("hoard-eye-3.3.0+1.21.11.jar", "hoard-eye-3.3.0+26.2.jar"). */
	private static final String FILE_PREFIX = "hoard-eye-";
	private static final String UPDATE_SUFFIX = ".update";
	/** Wait this long in a world before saying anything, so it doesn't land on top of join messages. */
	private static final int DELAY_TICKS = 300;
	private static final long MAX_JAR_BYTES = 32L << 20;

	record Release(String version, String page, String jarName, String jarUrl, String sha256) {}

	private static boolean started;
	private static boolean told;
	private static boolean armed;
	/** A newer release that couldn't be installed by itself: shown as a note. */
	private static volatile Release noteFor;
	private static int ticksInWorld;

	/** The version running, e.g. "1.5.0" (without the "+1.21.11"). */
	public static String current() {
		String v = FabricLoader.getInstance().getModContainer(MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("0");
		int plus = v.indexOf('+');
		return plus > 0 ? v.substring(0, plus) : v;
	}

	private static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
	}

	/** The jar this mod was loaded from, if it's a plain jar in a folder (it isn't in development). */
	static Path ownJar() {
		try {
			ModOrigin origin = FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getOrigin()).orElse(null);
			if (origin == null || origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) return null;
			Path jar = origin.getPaths().get(0).toAbsolutePath().normalize();
			return Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar") ? jar : null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * At game start: clears away half-downloads, and updates that are no longer newer than what's running (or that
	 * aren't wanted any more). An update downloaded last time that's still newer is kept: the check checks it against
	 * GitHub again before it's set to go in.
	 */
	public static void init() {
		Path jar = ownJar();
		if (jar == null) return;
		try (Stream<Path> files = Files.list(jar.getParent())) {
			for (Path f : (Iterable<Path>) files::iterator) {
				String n = f.getFileName().toString();
				if (!n.startsWith(FILE_PREFIX) || !(n.endsWith(".jar" + UPDATE_SUFFIX) || n.endsWith(".jar.part"))) continue;
				String version = versionOf(n);
				boolean keep = n.endsWith(UPDATE_SUFFIX) && ModConfig.INSTANCE.checkUpdates && version != null && newer(version, current());
				if (!keep) Files.deleteIfExists(f);
			}
		} catch (IOException e) {
			HoardEyeClient.LOGGER.debug("Couldn't look for a downloaded update: {}", e.toString());
		}
	}

	/** "3.3.0" from "hoard-eye-3.3.0+1.21.11.jar.update". */
	static String versionOf(String fileName) {
		if (!fileName.startsWith(FILE_PREFIX)) return null;
		String rest = fileName.substring(FILE_PREFIX.length());
		int plus = rest.indexOf('+');
		return plus > 0 ? rest.substring(0, plus) : null;
	}

	/** Every tick: starts the check once; says once when it updated itself, or when it couldn't. */
	public static void tick(MinecraftClient client) {
		if (told) return;
		if (!started && ModConfig.INSTANCE.checkUpdates) {
			started = true;
			CompletableFuture.runAsync(() -> {
				try {
					check();
				} catch (Exception e) {
					HoardEyeClient.LOGGER.warn("Update check failed", e);
				}
			});
		}
		if (client.player == null) {
			ticksInWorld = 0;
			return;
		}
		if (++ticksInWorld < DELAY_TICKS) return;
		String now = current();
		String before = ModConfig.INSTANCE.lastVersion;
		if (!now.equals(before)) {
			ModConfig.INSTANCE.lastVersion = now;
			ModConfig.INSTANCE.updateTries = 0;
			ModConfig.save();
			if (!before.isEmpty() && newer(now, before)) {
				told = true;
				client.inGameHud.getChatHud().addMessage(Text.literal(NAME + " updated to " + now + ". ").formatted(Formatting.GRAY)
						.append(Text.literal("[What's new]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
								.withClickEvent(new ClickEvent.OpenUrl(URI.create("https://github.com/" + REPO + "/releases"))))));
				return;
			}
		}
		Release release = noteFor;
		if (release == null || !started) return;
		told = true;
		client.inGameHud.getChatHud().addMessage(Text.literal(NAME + " " + release.version + " is out (you have " + now + "). ").formatted(Formatting.YELLOW)
				.append(Text.literal("[Download]").styled(s -> s.withColor(Formatting.AQUA).withUnderline(true)
						.withClickEvent(new ClickEvent.OpenUrl(URI.create(release.page))))));
	}

	private static void check() {
		Release release = latest(REPO, minecraftVersion());
		if (release == null || !newer(release.version, current())) return;
		Path jar = ownJar();
		if (jar == null) {
			noteFor = release;
			return;
		}
		Path target = jar.resolveSibling(release.jarName);
		Path update = jar.resolveSibling(release.jarName + UPDATE_SUFFIX);
		if (armed) return;
		try {
			if (Files.isRegularFile(update) && verify(update, release.sha256, MOD_ID, release.version)) {
				// Downloaded in an earlier game but never put in (the game was killed, or the helper couldn't run).
				// Twice in a row means it can't on this computer: say so, with a download link, rather than keep quiet.
				ModConfig.INSTANCE.updateTries++;
				ModConfig.save();
				if (ModConfig.INSTANCE.updateTries >= 2) noteFor = release;
			} else {
				download(release, update, MOD_ID);
			}
			arm(jar, update, target);
			HoardEyeClient.LOGGER.info("{} {} is downloaded and goes in when the game closes", NAME, release.version);
		} catch (Exception e) {
			HoardEyeClient.LOGGER.warn("Couldn't update {} by itself: {}", NAME, e.toString());
			noteFor = release;
		}
	}

	private static HttpClient http() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	/**
	 * The newest release with a jar for this Minecraft version, or null (no internet, no release, no jar for it).
	 * Hoard Eye has separate releases for each Minecraft version ("v3.3.0" for 1.21.11, "v3.3.0-mc26.2" for 26.2), so
	 * GitHub's "latest" may be the other one: the recent releases are looked through instead.
	 */
	static Release latest(String repo, String minecraft) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases?per_page=20"))
					.timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
					.header("User-Agent", NAME + "/" + current() + " (Minecraft mod by PlayWit Creations)").GET().build();
			HttpResponse<String> response = http().send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return null;
			return newest(JsonParser.parseString(response.body()).getAsJsonArray(), repo, minecraft);
		} catch (Exception e) {
			// No internet, or GitHub is down: try again next time the game starts.
			HoardEyeClient.LOGGER.debug("Update check failed: {}", e.toString());
			return null;
		}
	}

	/** From GitHub's list of releases: the newest finished one that has a jar for {@code minecraft}. */
	static Release newest(com.google.gson.JsonArray releases, String repo, String minecraft) {
		Release best = null;
		for (JsonElement r : releases) {
			JsonObject json = r.getAsJsonObject();
			if (bool(json, "draft") || bool(json, "prerelease")) continue;
			// "v3.3.0-mc26.2" is 3.3.0.
			String tag = json.has("tag_name") ? json.get("tag_name").getAsString().replaceFirst("^[vV]", "").replaceFirst("-mc.*$", "") : "";
			String page = json.has("html_url") ? json.get("html_url").getAsString() : "";
			if (!tag.matches("[0-9]+(\\.[0-9]+)*") || !page.startsWith("https://github.com/" + repo + "/releases/") || !json.has("assets")) continue;
			if (best != null && !newer(tag, best.version)) continue;
			for (JsonElement e : json.getAsJsonArray("assets")) {
				JsonObject asset = e.getAsJsonObject();
				String name = asset.get("name").getAsString();
				String url = asset.has("browser_download_url") ? asset.get("browser_download_url").getAsString() : "";
				String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
				if (name.equals(FILE_PREFIX + tag + "+" + minecraft + ".jar")
						&& url.startsWith("https://github.com/" + repo + "/releases/download/") && digest.startsWith("sha256:")) {
					best = new Release(tag, page, name, url, digest.substring("sha256:".length()).toLowerCase(Locale.ROOT));
					break;
				}
			}
		}
		return best;
	}

	private static boolean bool(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull() && json.get(key).getAsBoolean();
	}

	/** Downloads the release's jar to {@code to}, checked, by way of a ".part" file so a half-download is never left as the update. */
	static void download(Release release, Path to, String modId) throws Exception {
		Path part = to.resolveSibling(to.getFileName() + ".part");
		HttpRequest request = HttpRequest.newBuilder(URI.create(release.jarUrl)).timeout(Duration.ofMinutes(2))
				.header("User-Agent", NAME + "/" + current() + " (Minecraft mod by PlayWit Creations)").GET().build();
		HttpResponse<InputStream> response = http().send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			response.body().close();
			throw new IOException("GitHub said " + response.statusCode());
		}
		try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			for (int n; (n = in.read(buffer)) > 0; ) {
				total += n;
				if (total > MAX_JAR_BYTES) throw new IOException("the download is too big");
				out.write(buffer, 0, n);
			}
		} catch (Exception e) {
			Files.deleteIfExists(part);
			throw e;
		}
		if (!verify(part, release.sha256, modId, release.version)) {
			Files.deleteIfExists(part);
			throw new IOException("the download didn't match the release");
		}
		Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Whether the file is exactly the release's jar (SHA-256) and is this mod at that version. */
	static boolean verify(Path file, String sha256, String modId, String version) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buffer = new byte[64 * 1024];
				for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
			}
			if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha256)) return false;
			try (ZipFile zip = new ZipFile(file.toFile())) {
				ZipEntry entry = zip.getEntry("fabric.mod.json");
				if (entry == null) return false;
				try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
					JsonObject meta = JsonParser.parseReader(reader).getAsJsonObject();
					String id = meta.get("id").getAsString(), v = meta.get("version").getAsString();
					return id.equals(modId) && (v.equals(version) || v.startsWith(version + "+"));
				}
			}
		} catch (Exception e) {
			return false;
		}
	}

	/** Sets the update to be swapped in once this game has closed. */
	private static synchronized void arm(Path jar, Path update, Path target) {
		if (armed) return;
		armed = true;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> startSwap(ProcessHandle.current().pid(), jar, update, target), NAME + " update"));
	}

	/** Starts {@link UpdateSwap} as its own program, which outlives the game and does the swap after it. */
	static Process startSwap(long waitFor, Path jar, Path update, Path target) {
		try {
			Path dir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("updater");
			String cls = UpdateSwap.class.getName();
			Path classFile = dir.resolve(cls.replace('.', '/') + ".class");
			Files.createDirectories(classFile.getParent());
			try (InputStream in = UpdateSwap.class.getResourceAsStream(UpdateSwap.class.getSimpleName() + ".class")) {
				if (in == null) throw new IOException("the updater is missing from the jar");
				Files.copy(in, classFile, StandardCopyOption.REPLACE_EXISTING);
			}
			ProcessBuilder pb = new ProcessBuilder(javaCommand(), "-Xmx32m", "-XX:+UseSerialGC", "-cp", dir.toString(), cls,
					String.valueOf(waitFor), jar.toString(), update.toString(), target.toString());
			pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("updater.log").toFile()));
			return pb.start();
		} catch (Exception e) {
			HoardEyeClient.LOGGER.warn("Couldn't start the updater: {}", e.toString());
			return null;
		}
	}

	/** The Java the game runs on (javaw on Windows, so no window flashes up). */
	private static String javaCommand() {
		String command = ProcessHandle.current().info().command().orElse("");
		Path java = command.isEmpty() ? Path.of(System.getProperty("java.home"), "bin", "java") : Path.of(command);
		String name = java.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.equals("java.exe")) {
			Path javaw = java.resolveSibling("javaw.exe");
			if (Files.isRegularFile(javaw)) return javaw.toString();
		}
		if (!Files.isRegularFile(java)) {
			Path home = Path.of(System.getProperty("java.home"), "bin");
			Path w = home.resolve("javaw.exe"), j = home.resolve("java.exe"), u = home.resolve("java");
			java = Files.isRegularFile(w) ? w : Files.isRegularFile(j) ? j : u;
		}
		return java.toString();
	}

	/** Whether version a is newer than b, comparing the numbers in order ("1.10.0" is newer than "1.9.2"). */
	static boolean newer(String a, String b) {
		String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
			int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
			if (p != q) return p > q;
		}
		return false;
	}
}
