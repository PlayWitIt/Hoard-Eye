package com.greysworkshop.shulkernames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** User settings, saved to config/shulkernames-config.json. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("shulkernames-config.json");

	public static ModConfig INSTANCE = new ModConfig();

	public boolean enabled = true;
	public boolean showNames = true;
	public boolean showContents = true;
	public boolean showUnknownHint = true;

	public boolean shulkerBoxes = true;
	public boolean chests = true;
	public boolean barrels = true;
	public boolean enderChest = true;
	public boolean otherContainers = true;

	public int maxIcons = 9;
	public int scalePercent = 100;
	public int yOffset = 10;

	public boolean isShown(ContainerKind kind) {
		return switch (kind) {
			case SHULKER_BOX -> shulkerBoxes;
			case CHEST -> chests;
			case BARREL -> barrels;
			case ENDER_CHEST -> enderChest;
			case OTHER -> otherContainers;
		};
	}

	public static void load() {
		if (!Files.exists(FILE)) return;
		try (Reader reader = Files.newBufferedReader(FILE)) {
			ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
			if (loaded != null) INSTANCE = loaded;
		} catch (IOException | RuntimeException e) {
			ShulkerNamesClient.LOGGER.error("Could not read {}", FILE, e);
		}
	}

	public static void save() {
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			GSON.toJson(INSTANCE, writer);
		} catch (IOException e) {
			ShulkerNamesClient.LOGGER.error("Could not write {}", FILE, e);
		}
	}
}
