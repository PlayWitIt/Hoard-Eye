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

	public enum CountFormat { COMPACT, EXACT }

	public enum NameColor {
		WHITE(0xFFFFFF), GOLD(0xFFAA00), AQUA(0x55FFFF), GREEN(0x55FF55), YELLOW(0xFFFF55), PINK(0xFF77FF);

		public final int rgb;

		NameColor(int rgb) {
			this.rgb = rgb;
		}
	}

	public enum StripPosition { TOP, BOTTOM }

	public boolean enabled = true;
	/** Record containers as you open and place them. Off: nothing new is remembered. */
	public boolean rememberContainers = true;
	/** Forget containers that are broken or picked up. */
	public boolean forgetBroken = true;
	/** Only show the shulker tooltip grid while Shift is held. */
	public boolean shulkerTooltipShiftOnly = false;

	// Label.
	public boolean labelBackground = false;
	public int backgroundOpacity = 50;
	public CountFormat countFormat = CountFormat.COMPACT;
	/** Show "+N" when there are more item kinds than fit. */
	public boolean showMoreCount = true;
	public NameColor nameColor = NameColor.WHITE;
	/** Only show the label for containers within this many blocks. */
	public int maxDistance = 64;
	public boolean hideWhileSneaking = false;

	// Search and locator.
	/** On-screen markers over located containers, visible through walls. */
	public boolean locatorMarkers = true;
	public boolean locatorBeam = true;
	public int beamHeight = 96;
	/** How close you have to get for a located container to count as found. */
	public int locatorReach = 4;
	public StripPosition stripPosition = StripPosition.TOP;
	public boolean foundSound = true;

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
				if (loaded.countFormat == null) loaded.countFormat = CountFormat.COMPACT;
				if (loaded.nameColor == null) loaded.nameColor = NameColor.WHITE;
				if (loaded.stripPosition == null) loaded.stripPosition = StripPosition.TOP;
				if (loaded.hudPosition == null) loaded.hudPosition = HudPosition.BELOW_CROSSHAIR;
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
