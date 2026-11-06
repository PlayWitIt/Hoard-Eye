package com.greysworkshop.hoardeye;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Saved kits for the box manager: an item id and how many you want to be carrying. Saved to config/hoardeye-kits.json. */
public final class BoxKits {
	public static final class Kit {
		public String name = "";
		public Map<String, Integer> items = new LinkedHashMap<>();
	}

	private static final class Store {
		List<Kit> kits = new ArrayList<>();
		Map<String, Integer> last = new LinkedHashMap<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Store store;

	private BoxKits() {}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("hoardeye-kits.json");
	}

	private static Store store() {
		if (store != null) return store;
		store = new Store();
		Path path = file();
		if (Files.exists(path)) {
			try {
				Store loaded = GSON.fromJson(Files.readString(path), Store.class);
				if (loaded != null) {
					if (loaded.kits != null) store.kits = loaded.kits;
					if (loaded.last != null) store.last = loaded.last;
				}
			} catch (IOException | RuntimeException e) {
				HoardEyeClient.LOGGER.error("Could not read {}", path, e);
			}
		}
		return store;
	}

	private static void save() {
		try {
			Files.writeString(file(), GSON.toJson(store()));
		} catch (IOException e) {
			HoardEyeClient.LOGGER.error("Could not save the box kits", e);
		}
	}

	public static List<Kit> kits() {
		return store().kits;
	}

	public static Map<String, Integer> last() {
		return store().last;
	}

	public static void setLast(Map<String, Integer> items) {
		if (items.isEmpty()) return;
		store().last = new LinkedHashMap<>(items);
		save();
	}

	/** A kit of the same name is replaced, so saving again updates it. */
	public static void add(String name, Map<String, Integer> items) {
		store().kits.removeIf(k -> k.name.equalsIgnoreCase(name));
		Kit kit = new Kit();
		kit.name = name;
		kit.items = new LinkedHashMap<>(items);
		store().kits.add(kit);
		save();
	}

	public static void remove(Kit kit) {
		store().kits.remove(kit);
		save();
	}

	/** Self-test: forget everything in memory and read the file again. */
	static void testReload() {
		store = null;
	}
}
