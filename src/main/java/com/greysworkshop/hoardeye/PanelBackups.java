package com.greysworkshop.hoardeye;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Backups that leave the computer, for the mods panel: a copy in a folder you pick (one that Google Drive, OneDrive or
 * Dropbox syncs puts it in the cloud by itself), and in Google Drive or OneDrive themselves when they're connected.
 * One goes every day you play, and to the folder when you quit; the last {@link #KEEP} automatic ones are kept in each
 * place. Settings are in config/playwit/backup-settings.json, which is about this computer and so isn't backed up.
 */
final class PanelBackups {
	private PanelBackups() {}

	/** Ends the name of an automatic backup (only those are cleared out). */
	static final String AUTO = " auto";
	static final int KEEP = 10;
	private static final long DAY = 24L * 60 * 60 * 1000;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static final class Settings {
		/** The folder a copy of each backup goes to; empty for none. */
		String folder = "";
		/** When the last automatic backup was made. */
		long lastAuto;
	}

	private static Settings settings;

	/** What the last run did or why it failed, for the panel; null when there's nothing to say. */
	static volatile String status;
	static volatile boolean failed;
	private static volatile boolean busy;

	static boolean busy() {
		return busy;
	}

	private static Path file() {
		return PanelData.dir().resolve("backup-settings.json");
	}

	static synchronized Settings settings() {
		if (settings == null) {
			settings = new Settings();
			try {
				if (Files.exists(file())) {
					Settings read = GSON.fromJson(Files.readString(file()), Settings.class);
					if (read != null) settings = read;
				}
			} catch (IOException | RuntimeException e) {
				LoggerFactory.getLogger("playwit").warn("Could not read {}", file(), e);
			}
			if (settings.folder == null) settings.folder = "";
		}
		return settings;
	}

	static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(settings()));
		} catch (IOException e) {
			LoggerFactory.getLogger("playwit").warn("Could not save {}", file(), e);
		}
	}

	/** The folder backups are copied to, or null if none is set. */
	static Path folder() {
		String f = settings().folder;
		return f.isEmpty() ? null : Path.of(f);
	}

	static void setFolder(Path folder) {
		settings().folder = folder == null ? "" : folder.toAbsolutePath().normalize().toString();
		save();
	}

	/** Whether an automatic backup is due: a day since the last, and somewhere off this computer to put it. */
	static boolean due() {
		boolean somewhere = folder() != null || connectedServices().size() > 0;
		return somewhere && !busy && System.currentTimeMillis() - settings().lastAuto >= DAY;
	}

	static List<PanelCloud.Service> connectedServices() {
		List<PanelCloud.Service> list = new ArrayList<>();
		for (PanelCloud.Service s : PanelCloud.Service.values()) {
			if (PanelCloud.configured(s) && PanelCloud.connected(s)) list.add(s);
		}
		return list;
	}

	/**
	 * Makes a backup of {@code paths} and sends it everywhere it should go. {@code auto}: named as automatic, so the
	 * oldest are cleared out. {@code quitting}: the game is closing, so only the quick part (this computer and the
	 * folder). Blocks: call it off the game's thread, except when quitting.
	 */
	static void run(List<String> paths, boolean auto, boolean quitting) {
		if (busy) return;
		busy = true;
		failed = false;
		List<String> done = new ArrayList<>(), problems = new ArrayList<>();
		try {
			status = "Backing up…";
			Path zip = PanelData.newBackupFile(auto ? AUTO.strip() : null);
			int files = PanelData.backUp(paths, zip);
			if (auto) pruneLocal();
			done.add(files + (files == 1 ? " file" : " files"));
			Path folder = folder();
			if (folder != null) {
				try {
					Files.createDirectories(folder);
					Path copy = folder.resolve(zip.getFileName().toString() + ".part");
					Files.copy(zip, copy, StandardCopyOption.REPLACE_EXISTING);
					Files.move(copy, folder.resolve(zip.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
					if (auto) pruneFolder(folder);
					done.add("copied to the folder");
				} catch (IOException e) {
					problems.add("the folder: " + e.getMessage());
				}
			}
			if (!quitting) {
				for (PanelCloud.Service s : connectedServices()) {
					try {
						status = "Uploading to " + s.label + "…";
						PanelCloud.upload(s, zip);
						if (auto) PanelCloud.prune(s, KEEP);
						done.add("uploaded to " + s.label);
					} catch (IOException | RuntimeException e) {
						problems.add(s.label + ": " + e.getMessage());
					}
				}
			}
			if (auto) {
				settings().lastAuto = System.currentTimeMillis();
				save();
			}
		} catch (IOException | RuntimeException e) {
			problems.add(e.getMessage());
		} finally {
			failed = !problems.isEmpty();
			status = (problems.isEmpty() ? "Backed up: " + String.join(", ", done) + "."
					: "Backup problem with " + String.join("; ", problems) + (done.isEmpty() ? "" : " (done: " + String.join(", ", done) + ")"));
			if (failed) LoggerFactory.getLogger("playwit").warn("Backup: {}", status);
			busy = false;
		}
	}

	/** Clears out all but the newest automatic backups on this computer. */
	private static void pruneLocal() throws IOException {
		List<Path> autos = PanelData.list().stream().filter(PanelBackups::isAuto).toList();
		for (int i = KEEP; i < autos.size(); i++) Files.deleteIfExists(autos.get(i));
	}

	private static void pruneFolder(Path folder) throws IOException {
		List<Path> autos;
		try (Stream<Path> files = Files.list(folder)) {
			autos = files.filter(PanelBackups::isAuto).sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
		}
		for (int i = KEEP; i < autos.size(); i++) Files.deleteIfExists(autos.get(i));
	}

	private static boolean isAuto(Path p) {
		return p.getFileName().toString().endsWith(AUTO + ".zip");
	}

	/** Whether {@code zip} is a PlayWit backup: a zip with at least one of {@code paths} in it. */
	static boolean isBackup(Path zip, List<String> paths) {
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
				for (String rel : paths) {
					if (e.getName().equals(rel) || e.getName().startsWith(rel + "/")) return true;
				}
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
		return false;
	}

	/** Puts a backup from elsewhere (a file picked, a download) with the others, and returns where it is now. */
	static Path bringIn(Path zip) throws IOException {
		Path backups = PanelData.backups();
		if (zip.toAbsolutePath().normalize().startsWith(backups.toAbsolutePath().normalize())) return zip;
		Files.createDirectories(backups);
		String name = zip.getFileName().toString();
		Path to = backups.resolve(name.endsWith(".zip") ? name : name + ".zip");
		Files.copy(zip, to, StandardCopyOption.REPLACE_EXISTING);
		return to;
	}

	/** Folders on this computer that a cloud app keeps in sync, by name, that exist. */
	static Map<String, Path> syncedFolders() {
		Map<String, Path> found = new LinkedHashMap<>();
		Path home = Path.of(System.getProperty("user.home"));
		List<Map.Entry<String, Path>> candidates = new ArrayList<>();
		candidates.add(Map.entry("Google Drive", home.resolve("Google Drive").resolve("My Drive")));
		candidates.add(Map.entry("Google Drive", home.resolve("My Drive")));
		candidates.add(Map.entry("Google Drive", home.resolve("Google Drive")));
		// Google Drive for desktop on Windows shows as a drive letter (G: by default).
		for (char letter = 'D'; letter <= 'Z'; letter++) candidates.add(Map.entry("Google Drive", Path.of(letter + ":\\My Drive")));
		String oneDrive = System.getenv("OneDrive");
		if (oneDrive != null && !oneDrive.isBlank()) candidates.add(Map.entry("OneDrive", Path.of(oneDrive)));
		candidates.add(Map.entry("OneDrive", home.resolve("OneDrive")));
		candidates.add(Map.entry("Dropbox", home.resolve("Dropbox")));
		candidates.add(Map.entry("iCloud Drive", home.resolve("Library").resolve("Mobile Documents").resolve("com~apple~CloudDocs")));
		for (Map.Entry<String, Path> c : candidates) {
			try {
				if (!found.containsKey(c.getKey()) && Files.isDirectory(c.getValue())) found.put(c.getKey(), c.getValue().resolve("PlayWit backups"));
			} catch (RuntimeException ignored) {
				// A path this system can't have (a drive letter on Linux).
			}
		}
		return found;
	}
}
