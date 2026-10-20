package com.greysworkshop.shulkernames;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers shulker boxes by world -> dimension -> block position.
 */
public final class NameStore {
	/** What we know about one placed box. */
	public static final class Entry {
		/** Custom name, or "" if the box is unnamed. */
		public String name = "";
		/** Saved item stacks, or null if the contents were never seen. */
		public List<JsonElement> items;
		/** Decoded {@link #items}, filled lazily for rendering. */
		public transient List<ItemStack> decoded;

		public List<ItemStack> stacks(RegistryWrapper.WrapperLookup registries) {
			if (decoded == null && items != null) {
				DynamicOps<JsonElement> ops = registries.getOps(JsonOps.INSTANCE);
				decoded = new ArrayList<>();
				for (JsonElement json : items) {
					ItemStack.CODEC.parse(ops, json).result().ifPresent(decoded::add);
				}
			}
			return decoded;
		}
	}

	private static final Gson PLAIN_GSON = new Gson();
	// Version 1.0.0 stored just the name as a string, so accept that too.
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.registerTypeAdapter(Entry.class, (JsonDeserializer<Entry>) (json, type, ctx) -> {
				if (json.isJsonPrimitive()) {
					Entry entry = new Entry();
					entry.name = json.getAsString();
					return entry;
				}
				return PLAIN_GSON.fromJson(json, Entry.class);
			})
			.create();
	private static final Type TYPE = new TypeToken<Map<String, Map<String, Map<Long, Entry>>>>() {}.getType();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("shulkernames.json");

	private Map<String, Map<String, Map<Long, Entry>>> data = new HashMap<>();
	private boolean dirty;

	public void load() {
		if (!Files.exists(FILE)) return;
		try (Reader reader = Files.newBufferedReader(FILE)) {
			Map<String, Map<String, Map<Long, Entry>>> loaded = GSON.fromJson(reader, TYPE);
			if (loaded != null) data = loaded;
		} catch (IOException | RuntimeException e) {
			ShulkerNamesClient.LOGGER.error("Could not read {}", FILE, e);
		}
	}

	public void saveIfDirty() {
		if (!dirty) return;
		dirty = false;
		try (Writer writer = Files.newBufferedWriter(FILE)) {
			GSON.toJson(data, TYPE, writer);
		} catch (IOException e) {
			ShulkerNamesClient.LOGGER.error("Could not write {}", FILE, e);
		}
	}

	/** Returns what we know about the box at {@code pos}, or null if never seen. */
	public Entry get(String world, String dimension, BlockPos pos) {
		return dimension(world, dimension).get(pos.asLong());
	}

	/** Records a box's name, and its contents if {@code stacks} is non-null. */
	public void put(String world, String dimension, BlockPos pos, String name, List<ItemStack> stacks,
			RegistryWrapper.WrapperLookup registries) {
		Entry entry = dimension(world, dimension).computeIfAbsent(pos.asLong(), k -> new Entry());
		entry.name = name;
		if (stacks != null) {
			DynamicOps<JsonElement> ops = registries.getOps(JsonOps.INSTANCE);
			entry.items = new ArrayList<>();
			for (ItemStack stack : stacks) {
				if (stack.isEmpty()) continue;
				ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent(entry.items::add);
			}
			entry.decoded = null;
		}
		dirty = true;
	}

	public Map<Long, Entry> dimension(String world, String dimension) {
		return data.computeIfAbsent(world, k -> new HashMap<>()).computeIfAbsent(dimension, k -> new HashMap<>());
	}

	public void markDirty() {
		dirty = true;
	}
}
