package com.greysworkshop.hoardeye;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Puts a downloaded update in place once the game has closed. It runs as its own small program (plain Java, nothing
 * from Minecraft), because a running game can't replace its own jar on Windows.
 * <p>
 * Arguments: the game's process id, the jar in use now, the downloaded update, and the name the update should have.
 * It waits for the game to close, deletes the old jar, then renames the update into place. If the old jar can't be
 * deleted, nothing else is touched (two copies of the mod would stop the game from starting), and the game tries
 * again the next time it closes.
 */
public final class UpdateSwap {
	private UpdateSwap() {}

	public static void main(String[] args) {
		if (args.length < 4) {
			System.out.println("usage: UpdateSwap <pid> <old jar> <downloaded update> <new jar>");
			return;
		}
		long pid = Long.parseLong(args[0]);
		Path old = Path.of(args[1]), update = Path.of(args[2]), jar = Path.of(args[3]);
		ProcessHandle.of(pid).ifPresent(game -> {
			try {
				game.onExit().get(12, TimeUnit.HOURS);
			} catch (Exception ignored) {
				// Waited long enough; trying anyway does no harm (a jar in use can't be deleted, so nothing changes).
			}
		});
		boolean done = swap(old, update, jar, 120_000);
		System.out.println(LocalDateTime.now() + (done ? " updated: " + jar.getFileName() : " couldn't update to " + jar.getFileName() + " (will try again next time)"));
	}

	/**
	 * Deletes old and renames update to jar, trying again for up to timeoutMs while a file is still in use (Windows
	 * keeps it locked for a moment after the game closes). True when the update is in place.
	 */
	public static boolean swap(Path old, Path update, Path jar, long timeoutMs) {
		long giveUp = System.currentTimeMillis() + timeoutMs;
		boolean oldGone = false;
		while (true) {
			try {
				if (!Files.isRegularFile(update)) return false;
				if (!oldGone) {
					if (!old.equals(jar)) Files.deleteIfExists(old);
					oldGone = true;
				}
				try {
					Files.move(update, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(update, jar, StandardCopyOption.REPLACE_EXISTING);
				}
				return true;
			} catch (IOException e) {
				if (System.currentTimeMillis() > giveUp) {
					System.out.println(LocalDateTime.now() + " " + e);
					return false;
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException ie) {
					return false;
				}
			}
		}
	}
}
