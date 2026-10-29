package com.greysworkshop.hoardeye;

import com.greysworkshop.hoardeye.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fetch list: items you want to gather, a route through the containers that hold them (nearest first,
 * no back-tracking), and help at each stop: what to take, with those slots highlighted.
 */
public final class Fetch {
	/** One item on the list. {@code key} matches the search's item grouping (name and id). */
	public static final class Want {
		public final String key;
		public final ItemStack icon;
		public final String name;
		public int count;

		Want(String key, ItemStack icon, String name, int count) {
			this.key = key;
			this.icon = icon;
			this.name = name;
			this.count = count;
		}
	}

	public record Take(String key, ItemStack icon, String name, int count) {}

	public record Stop(String dimension, BlockPos pos, String label, ItemStack icon, List<Take> takes) {}

	/** A container that could be visited, with how many of each item (by key) it holds. */
	public record Candidate(String dimension, BlockPos pos, String label, ItemStack icon, Map<String, Integer> counts, double distance) {}

	private static final Map<String, Want> wants = new LinkedHashMap<>();
	/** The stop whose container is open right now, for the in-screen panel. */
	private static Stop openStop;

	private Fetch() {}

	public static List<Want> wants() {
		return new ArrayList<>(wants.values());
	}

	public static int size() {
		return wants.size();
	}

	public static String key(ItemStack stack) {
		return stack.getName().getString() + "|" + Registries.ITEM.getId(stack.getItem());
	}

	/** Adds one stack's worth of an item (or more of it, if it's already on the list). */
	public static void addStack(ItemStack stack) {
		String key = key(stack);
		Want want = wants.computeIfAbsent(key, k -> new Want(k, stack.copyWithCount(1), stack.getName().getString(), 0));
		want.count += stack.getMaxCount();
	}

	public static void adjust(String key, int stacks) {
		Want want = wants.get(key);
		if (want == null) return;
		want.count += stacks * want.icon.getMaxCount();
		if (want.count <= 0) wants.remove(key);
	}

	public static void remove(String key) {
		wants.remove(key);
	}

	public static void clear() {
		wants.clear();
	}

	/**
	 * Picks up a list handed over by another mod (Base Insurance's "Fetch with Hoard Eye"), replacing the
	 * current list. Returns who sent it, or null if there was nothing to pick up.
	 */
	public static String importHandOff() {
		java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("hoardeye-fetch.json");
		if (!java.nio.file.Files.exists(file)) return null;
		try {
			com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
			java.nio.file.Files.delete(file);
			wants.clear();
			for (com.google.gson.JsonElement element : root.getAsJsonArray("items")) {
				com.google.gson.JsonObject item = element.getAsJsonObject();
				net.minecraft.util.Identifier id = net.minecraft.util.Identifier.tryParse(item.get("id").getAsString());
				if (id == null || !Registries.ITEM.containsId(id)) continue;
				ItemStack stack = new ItemStack(Registries.ITEM.get(id));
				String key = key(stack);
				wants.put(key, new Want(key, stack, stack.getName().getString(), item.get("count").getAsInt()));
			}
			return root.has("from") ? root.get("from").getAsString() : "another mod";
		} catch (Exception e) {
			HoardEyeClient.LOGGER.warn("Could not read handed-over fetch list", e);
			return null;
		}
	}

	/**
	 * Picks containers for each item, nearest first, until there's enough, then orders the stops so each is
	 * the nearest unvisited one to the last. Anything that can't be found in full is added to {@code shortages}.
	 */
	public static List<Stop> plan(List<Candidate> candidates, Vec3d from, List<String> shortages) {
		List<Candidate> sorted = new ArrayList<>(candidates);
		sorted.sort((a, b) -> Double.compare(a.distance(), b.distance()));
		Map<Candidate, List<Take>> takes = new LinkedHashMap<>();
		for (Want want : wants.values()) {
			int need = want.count;
			for (Candidate candidate : sorted) {
				int have = candidate.counts().getOrDefault(want.key, 0);
				if (have <= 0) continue;
				int take = Math.min(have, need);
				takes.computeIfAbsent(candidate, c -> new ArrayList<>()).add(new Take(want.key, want.icon, want.name, take));
				need -= take;
				if (need == 0) break;
			}
			if (need > 0) shortages.add(want.name + ": " + need + " short");
		}

		List<Candidate> left = new ArrayList<>(takes.keySet());
		List<Stop> stops = new ArrayList<>();
		Vec3d at = from;
		while (!left.isEmpty()) {
			Candidate next = left.get(0);
			for (Candidate candidate : left) {
				if (Vec3d.ofCenter(candidate.pos()).squaredDistanceTo(at) < Vec3d.ofCenter(next.pos()).squaredDistanceTo(at)) next = candidate;
			}
			left.remove(next);
			stops.add(new Stop(next.dimension(), next.pos(), next.label(), next.icon(), takes.get(next)));
			at = Vec3d.ofCenter(next.pos());
		}
		return stops;
	}

	/** A route stop's container was opened: show what to take while it's open. */
	public static void onOpened(Stop stop) {
		openStop = stop;
	}

	public static void onClosed() {
		openStop = null;
	}

	/** A route stop's container is open right now. */
	public static boolean isOpen() {
		return openStop != null;
	}

	/** Draws "take from here" above the container, and outlines the slots holding those items. */
	public static void renderInScreen(HandledScreen<?> screen, DrawContext context) {
		Stop stop = openStop;
		if (stop == null) return;
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer font = client.textRenderer;
		HandledScreenAccessor panel = (HandledScreenAccessor) screen;
		int left = panel.hoardeye$getX(), top = panel.hoardeye$getY();

		Map<String, Integer> wanted = new LinkedHashMap<>();
		for (Take take : stop.takes()) wanted.put(take.key(), take.count());
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory instanceof PlayerInventory || slot.getStack().isEmpty()) continue;
			if (!wanted.containsKey(key(slot.getStack()))) continue;
			int x = left + slot.x, y = top + slot.y;
			context.fill(x, y, x + 16, y + 16, 0x5040E0FF);
			context.drawStrokedRectangle(x - 1, y - 1, 18, 18, 0xFF40E0FF);
		}

		List<String> lines = new ArrayList<>();
		for (Take take : stop.takes()) lines.add(take.count() + " × " + take.name());
		String title = Text.translatable("hoardeye.fetch.take_here").getString();
		int width = font.getWidth(title);
		for (String line : lines) width = Math.max(width, font.getWidth(line) + 20);
		int height = 14 + lines.size() * 18;
		int x = Math.max(4, left - width - 16), y = Math.max(4, top);
		context.fill(x, y, x + width + 8, y + height, 0xE0101018);
		context.drawStrokedRectangle(x, y, width + 8, height, 0xFF40E0FF);
		context.drawTextWithShadow(font, title, x + 4, y + 4, 0xFFFFC040);
		int lineY = y + 14;
		for (int i = 0; i < stop.takes().size(); i++) {
			context.drawItem(stop.takes().get(i).icon(), x + 4, lineY);
			context.drawTextWithShadow(font, lines.get(i), x + 22, lineY + 4, 0xFFF0F0F0);
			lineY += 18;
		}
	}
}
