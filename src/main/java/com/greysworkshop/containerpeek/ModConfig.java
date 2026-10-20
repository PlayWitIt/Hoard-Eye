package com.greysworkshop.containerpeek;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** User settings, saved to config/containerpeek-config.json. */
public final class ModConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("containerpeek-config.json");

	public static ModConfig INSTANCE = new ModConfig();

	public boolean enabled = true;
	public boolean showNames = true;
	public boolean showContents = true;
	public boolean showUnknownHint = true;
	/** Replace the text list in shulker box item tooltips with a grid of icons. */
	public boolean shulkerTooltip = true;

	/** Per-type visibility; types missing from the map are shown. */
	public Map<ContainerKind, Boolean> containers = new EnumMap<>(ContainerKind.class);

	public int maxIcons = 9;
	public int iconsPerRow = 9;
	public int scalePercent = 100;
	public int yOffset = 10;
	public HudPosition hudPosition = HudPosition.BELOW_CROSSHAIR;

	// Group switches from 1.2.0, read once and converted into `containers`.
	private Boolean shulkerBoxes;
	private Boolean chests;
	private Boolean barrels;
	private Boolean enderChest;
	private Boolean otherContainers;

	public boolean isShown(ContainerKind kind) {
		return containers.getOrDefault(kind, true);
	}

	public void setShown(ContainerKind kind, boolean shown) {
		containers.put(kind, shown);
	}

	private void migrateGroups() {
		migrateGroup(shulkerBoxes, ContainerKind.SHULKER_BOX);
		migrateGroup(chests, ContainerKind.CHEST, ContainerKind.TRAPPED_CHEST, ContainerKind.COPPER_CHEST);
		migrateGroup(barrels, ContainerKind.BARREL);
		migrateGroup(enderChest, ContainerKind.ENDER_CHEST);
		migrateGroup(otherContainers, ContainerKind.HOPPER, ContainerKind.DROPPER, ContainerKind.DISPENSER,
				ContainerKind.FURNACE, ContainerKind.SMOKER, ContainerKind.BLAST_FURNACE,
				ContainerKind.BREWING_STAND, ContainerKind.CRAFTER, ContainerKind.OTHER);
		shulkerBoxes = chests = barrels = enderChest = otherContainers = null;
	}

	private void migrateGroup(Boolean value, ContainerKind... kinds) {
		if (value == null) return;
		for (ContainerKind kind : kinds) containers.putIfAbsent(kind, value);
	}

	public static void load() {
		if (!Files.exists(FILE)) return;
		try (Reader reader = Files.newBufferedReader(FILE)) {
			ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
			if (loaded != null) {
				if (loaded.containers == null) loaded.containers = new EnumMap<>(ContainerKind.class);
				loaded.migrateGroups();
				INSTANCE = loaded;
			}
		} catch (IOException | RuntimeException e) {
			ContainerPeekClient.LOGGER.error("Could not read {}", FILE, e);
		}
	}

	public static void save() {
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			GSON.toJson(INSTANCE, writer);
		} catch (IOException e) {
			ContainerPeekClient.LOGGER.error("Could not write {}", FILE, e);
		}
	}
}
