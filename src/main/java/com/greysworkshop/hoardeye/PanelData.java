package com.greysworkshop.hoardeye;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backups for the mods panel: everything the PlayWit mods have saved, zipped into config/playwit/backups. A restore is
 * done at the next start, before any mod has loaded (as a pre-launch entrypoint), because the mods would otherwise save
 * what they have in memory over it. What's there now is backed up first. Plain Java only: Minecraft's classes mustn't
 * be loaded before launch.
 */
public final class PanelData implements PreLaunchEntrypoint {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.ROOT);

	static Path config() {
		return FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
	}

	static Path dir() {
		return config().resolve("playwit");
	}

	static Path backups() {
		return dir().resolve("backups");
	}

	private static Path marker() {
		return dir().resolve("restore-pending.txt");
	}

	@Override
	public void onPreLaunch() {
		try {
			restorePending();
		} catch (IOException | RuntimeException e) {
			LoggerFactory.getLogger("playwit").error("Could not restore the backup", e);
		}
	}

	/** Zips {@code paths} (files or folders, relative to the config folder) into {@code zip}. Returns the files written. */
	static int backUp(List<String> paths, Path zip) throws IOException {
		Path config = config();
		Files.createDirectories(zip.getParent());
		Path temp = zip.resolveSibling(zip.getFileName() + ".part");
		int count = 0;
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temp))) {
			for (String rel : paths) {
				Path root = inside(config, rel);
				if (root == null || !Files.exists(root)) continue;
				List<Path> files;
				try (Stream<Path> walk = Files.walk(root)) {
					files = walk.filter(Files::isRegularFile).filter(f -> !f.startsWith(backups())).sorted().toList();
				}
				for (Path f : files) {
					out.putNextEntry(new ZipEntry(config.relativize(f).toString().replace('\\', '/')));
					Files.copy(f, out);
					out.closeEntry();
					count++;
				}
			}
		}
		Files.move(temp, zip, StandardCopyOption.REPLACE_EXISTING);
		return count;
	}

	static Path newBackupFile(String note) {
		return backups().resolve(LocalDateTime.now().format(STAMP) + (note == null ? "" : " " + note) + ".zip");
	}

	/** Saved backups, newest first. */
	static List<Path> list() {
		List<Path> list = new ArrayList<>();
		if (!Files.isDirectory(backups())) return list;
		try (Stream<Path> files = Files.list(backups())) {
			files.filter(f -> f.getFileName().toString().endsWith(".zip")).forEach(list::add);
		} catch (IOException e) {
			return list;
		}
		list.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
		return list;
	}

	/** Restores {@code zip} at the next start; {@code paths} are what the mods keep (only those in the backup are replaced). */
	static void restoreAtNextStart(Path zip, List<String> paths) throws IOException {
		Files.createDirectories(dir());
		List<String> lines = new ArrayList<>();
		lines.add(zip.toAbsolutePath().toString());
		lines.addAll(paths);
		Files.write(marker(), lines);
	}

	static boolean restorePendingFor(Path zip) {
		try {
			return Files.exists(marker()) && Files.readAllLines(marker()).get(0).equals(zip.toAbsolutePath().toString());
		} catch (IOException | IndexOutOfBoundsException e) {
			return false;
		}
	}

	static void cancelRestore() throws IOException {
		Files.deleteIfExists(marker());
	}

	private static void restorePending() throws IOException {
		Path marker = marker();
		if (!Files.exists(marker)) return;
		List<String> lines = Files.readAllLines(marker);
		// Gone first, so a backup that can't be read doesn't stop the game starting every time.
		Files.delete(marker);
		if (lines.isEmpty()) return;
		Path zip = Path.of(lines.get(0));
		List<String> paths = lines.subList(1, lines.size());
		if (!Files.isRegularFile(zip)) return;
		Path config = config();

		// Which of the mods' folders and files the backup has: only those are replaced.
		Set<String> inBackup = new HashSet<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
				for (String rel : paths) {
					if (e.getName().equals(rel) || e.getName().startsWith(rel + "/")) inBackup.add(rel);
				}
			}
		}
		backUp(paths, newBackupFile("before restore"));
		for (String rel : inBackup) {
			Path target = inside(config, rel);
			if (target == null || !Files.exists(target)) continue;
			try (Stream<Path> walk = Files.walk(target)) {
				for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
			}
		}
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
				Path out = inside(config, e.getName());
				if (out == null || e.isDirectory()) continue;
				Files.createDirectories(out.getParent());
				try (OutputStream o = Files.newOutputStream(out)) {
					copy(in, o);
				}
			}
		}
		LoggerFactory.getLogger("playwit").info("Restored the backup {}", zip.getFileName());
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buf = new byte[65536];
		for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
	}

	/** {@code rel} under the config folder, or null if it would lead out of it. */
	private static Path inside(Path config, String rel) {
		if (rel == null || rel.isBlank()) return null;
		Path p = config.resolve(rel).normalize();
		return p.startsWith(config) && !p.equals(config) ? p : null;
	}
}
