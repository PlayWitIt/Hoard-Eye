package com.greysworkshop.hoardeye;

import com.greysworkshop.hoardeye.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The box manager. It's drawn over the open container, which stays open behind it, and nothing changes on the server
 * until you press the big button: it's a plan, which {@link BoxGrab} then carries out. One tab for each shulker box in
 * reach and one for the loose items in the container; take any amount from any of them, put things in, sort them.
 */
public final class BoxPicker {
	private static final int COLS = 9, ROWS = 3, SIZE = COLS * ROWS, CELL = 22, PAD = 10, INV_CELL = 18, TAB = 22;
	private static final int LEFT_W = COLS * CELL + PAD * 2, RIGHT_W = COLS * INV_CELL + 16, GAP = 4;
	private static final int PANEL_H = 230, BUTTON_H = 20, GUARD_RADIUS = 8;
	private static final long TYPE_WINDOW_MS = 1500;
	/** Moved this far with the button down, it's a drag rather than a click. */
	private static final int DRAG_START = 5;

	private static final int BG = 0xF4101018, EDGE = 0xFF40E0FF, TITLE = 0xFFFFC040, TEXT = 0xFFF0F0F0, MUTED = 0xFF9AA0AC;
	private static final int CELL_BG = 0xFF23232E, CELL_HOVER = 0xFF343446, PICKED_BG = 0xFF0E4A5C, STORE_BG = 0xFF4A3A0E, STORE_EDGE = 0xFFFFC040;
	private static final int WARN = 0xFFFFC040, BAD = 0xFFFF6060, GOOD = 0xFF60E080, DIM = 0xC0101018, SECTION = 0xFF1A1A24;

	/** One thing that can be managed: a shulker box, or the loose items in the open container. */
	private static final class Entry {
		boolean loose;
		int slotId, invIndex = -1;
		boolean inInventory, tidy;
		ItemStack box = ItemStack.EMPTY;
		List<ItemStack> contents = new ArrayList<>();
		int[] want;
		final int[] store = new int[PlayerInventory.MAIN_SIZE];
		/** What's wanted from inside the bundles here, by the slot the bundle is in. */
		final Map<Integer, Entry> bundles = new LinkedHashMap<>();

		int picked() {
			int n = 0;
			for (int w : want) if (w > 0) n++;
			for (Entry b : bundles.values()) n += b.picked();
			return n;
		}

		int stored() {
			int n = 0;
			for (int s : store) if (s > 0) n++;
			return n;
		}

		boolean planned() {
			return picked() > 0 || stored() > 0 || tidy;
		}
	}

	private record Hit(String id, int x, int y, int w, int h) {
		boolean over(double px, double py) {
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	private enum Focus { NONE, SEARCH, KIT_NAME }

	private static HandledScreen<?> screen, lastScreen;
	private static final List<Entry> entries = new ArrayList<>();
	private static List<Entry> remembered = new ArrayList<>();
	private static int current, page, tabOffset, kitOffset;
	/** Looking inside a bundle: the grid shows what's in it, and the slot it sits in. */
	private static Entry bundleView;
	private static int bundleSlot;
	private static BlockPos container;
	private static int containerSlots;
	private static boolean fromInventoryScreen, wide;

	private static int left, top, rightX, bodyH;
	/** Shrinks the whole panel to fit a small window or a big GUI scale: every button stays on screen. */
	private static float fit = 1;
	private static final List<Hit> hits = new ArrayList<>();
	/** A stack being carried from one side to the other. With {@code dragBack}, a planned one going back where it came from. */
	private static boolean dragging, dragFromInventory, dragMoved, dragBack;
	private static int dragIndex, dragBefore, dragAmount;
	private static ItemStack dragStack = ItemStack.EMPTY;
	private static double pressX, pressY;
	/** How both sides will look with the plan done, redrawn every frame. */
	private static ItemStack[] previewInventory = new ItemStack[0], previewBox = new ItemStack[0];
	/** The last look before anything happens. */
	private static boolean confirming;
	private static boolean painting, paintOn, paintInventory, draggingSpeed;
	private static int lastPainted = -1;

	/** The cell the amount bar and typed numbers act on: a box cell, or one of yours. */
	private static int selected = -1;
	private static boolean selectedIsInventory;
	private static String typed = "";
	private static long typedAt;
	private static int[][] typedWantBase, typedStoreBase;

	private static Focus focus = Focus.NONE;
	private static String search = "", kitName = "", note = "";
	private static final Map<String, String> searchText = new HashMap<>();
	private static String armedDelete;
	private static int checkTimer;
	private static boolean hasSpot;
	/** Self-test: somebody this close, as nobody else is in the test world. */
	static int testNearby = -1;

	private BoxPicker() {}

	public static boolean isOpen() {
		return screen != null;
	}

	public static boolean isOpenOn(Screen s) {
		return screen == s;
	}

	/** Closes, keeping the plan: open it again on the same screen and your picks are still there. */
	public static void close() {
		if (screen != null) {
			lastScreen = screen;
			remembered = new ArrayList<>(entries);
		}
		screen = null;
		painting = draggingSpeed = dragging = confirming = false;
		focus = Focus.NONE;
		ModConfig.save();
	}

	private static void cancel() {
		close();
		remembered.clear();
		search = "";
	}

	private static boolean loosePossible(HandledScreen<?> on, ScreenHandler h) {
		return !(on instanceof InventoryScreen) && container != null
				&& (h instanceof GenericContainerScreenHandler || h instanceof HopperScreenHandler || h instanceof Generic3x3ContainerScreenHandler);
	}

	/** Whether there's anything to manage on this screen: for showing the button that opens it. */
	public static boolean hasBoxes(HandledScreen<?> on) {
		ClientPlayerEntity p = MinecraftClient.getInstance().player;
		if (p == null) return false;
		for (Slot s : on.getScreenHandler().slots) if (usable(s, p) && BoxGrab.isBox(s.getStack())) return true;
		return false;
	}

	/** Where the mouse is now. The screen's own idea is from the last frame drawn, so on a slow frame it can be out of date. */
	private static Slot slotUnderMouse(MinecraftClient client, HandledScreen<?> on) {
		HandledScreenAccessor a = (HandledScreenAccessor) on;
		double x = client.mouse.getScaledX(client.getWindow()) - a.hoardeye$getX(), y = client.mouse.getScaledY(client.getWindow()) - a.hoardeye$getY();
		for (Slot s : on.getScreenHandler().slots) {
			if (s.isEnabled() && x >= s.x - 1 && x < s.x + 17 && y >= s.y - 1 && y < s.y + 17) return s;
		}
		return null;
	}

	private static boolean usable(Slot s, ClientPlayerEntity p) {
		// The armor, crafting and offhand slots can't be swapped back the same way.
		return s.inventory != p.getInventory() || s.getIndex() < PlayerInventory.MAIN_SIZE;
	}

	/** Opens on the box under the mouse. With {@code anyBox}, on the first box when the mouse isn't on one. */
	public static boolean tryOpen(HandledScreen<?> on, boolean anyBox) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity p = client.player;
		if (!ModConfig.INSTANCE.enabled || !ModConfig.INSTANCE.boxPicker || p == null || BoxGrab.running()) return false;
		if (!p.currentScreenHandler.getCursorStack().isEmpty()) return false;
		Slot hover = slotUnderMouse(client, on);
		boolean onBox = hover != null && BoxGrab.isBox(hover.getStack()) && usable(hover, p);
		if (!onBox && (!anyBox || !hasBoxes(on))) return false;

		ScreenHandler h = on.getScreenHandler();
		fromInventoryScreen = on instanceof InventoryScreen;
		container = BoxGrab.openContainer(client);
		containerSlots = fromInventoryScreen ? -1 : countContainerSlots(on, p);
		entries.clear();
		if (loosePossible(on, h)) entries.add(looseEntry(h, p));
		for (Slot s : h.slots) {
			if (!usable(s, p) || !BoxGrab.isBox(s.getStack())) continue;
			entries.add(boxEntry(s, p));
		}
		// Back on the same screen: the plan is as you left it, for whatever is still there.
		if (on == lastScreen) {
			for (Entry old : remembered) {
				for (Entry e : entries) {
					if (old.loose ? e.loose : !e.loose && e.slotId == old.slotId && ItemStack.areEqual(e.box, old.box)) {
						e.bundles.putAll(old.bundles);
						e.want = Arrays.copyOf(old.want, e.want.length);
						System.arraycopy(old.store, 0, e.store, 0, e.store.length);
						e.tidy = old.tidy;
					}
				}
			}
		} else {
			search = "";
		}
		current = 0;
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			if (onBox && !e.loose && e.slotId == hover.id) current = i;
			if (!onBox && !e.loose && entries.get(current).loose) current = i;
		}
		screen = on;
		page = 0;
		bundleView = null;
		painting = dragging = confirming = false;
		selected = -1;
		typed = "";
		note = "";
		armedDelete = null;
		focus = Focus.NONE;
		checkTimer = 0;
		hasSpot = !BoxGrab.spots(p, client.world).isEmpty();
		searchText.clear();
		return true;
	}

	private static Entry looseEntry(ScreenHandler h, ClientPlayerEntity p) {
		Entry e = new Entry();
		e.loose = true;
		int n = 0;
		for (Slot s : h.slots) if (s.inventory != p.getInventory()) n++;
		e.want = new int[n];
		for (int i = 0; i < n; i++) e.contents.add(h.slots.get(i).getStack().copy());
		return e;
	}

	private static Entry boxEntry(Slot s, ClientPlayerEntity p) {
		Entry e = new Entry();
		e.slotId = s.id;
		e.inInventory = s.inventory == p.getInventory();
		e.invIndex = e.inInventory ? s.getIndex() : -1;
		e.box = s.getStack().copy();
		DefaultedList<ItemStack> list = DefaultedList.ofSize(SIZE, ItemStack.EMPTY);
		ContainerComponent component = e.box.get(DataComponentTypes.CONTAINER);
		if (component != null) component.copyTo(list);
		e.contents.addAll(list);
		e.want = new int[SIZE];
		return e;
	}

	private static int countContainerSlots(HandledScreen<?> on, ClientPlayerEntity p) {
		int count = 0;
		for (Slot s : on.getScreenHandler().slots) if (s.inventory != p.getInventory()) count++;
		return count;
	}

	private static Entry entry() {
		return bundleView != null ? bundleView : entries.get(current);
	}

	/** The box or container itself, even when looking inside a bundle in it. */
	private static Entry top() {
		return entries.get(current);
	}

	private static boolean isFullBundle(ItemStack stack) {
		BundleContentsComponent c = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
		return c != null && !c.isEmpty();
	}

	private static Entry bundleEntry(Entry parent, int slot) {
		return parent.bundles.computeIfAbsent(slot, s -> {
			Entry b = new Entry();
			BundleContentsComponent c = parent.contents.get(s).get(DataComponentTypes.BUNDLE_CONTENTS);
			if (c != null) for (ItemStack item : c.iterate()) b.contents.add(item.copy());
			b.want = new int[b.contents.size()];
			return b;
		});
	}

	private static void openBundle(int slot) {
		top().want[slot] = 0;
		bundleView = bundleEntry(top(), slot);
		bundleSlot = slot;
		page = 0;
		select(-1, false);
	}

	private static void closeBundle() {
		if (bundleView != null && bundleView.picked() == 0) top().bundles.remove(bundleSlot);
		bundleView = null;
		page = 0;
		select(-1, false);
	}

	// ---- Layout ----

	private static void layout(int screenWidth, int screenHeight) {
		int total = LEFT_W + GAP + RIGHT_W;
		fit = Math.min(1f, Math.min(screenWidth / (total + 4f), screenHeight / (PANEL_H + 4f)));
		int width = (int) (screenWidth / fit), height = (int) (screenHeight / fit);
		wide = true;
		left = (width - total) / 2;
		rightX = left + LEFT_W + GAP;
		bodyH = PANEL_H;
		top = Math.max(2, (height - bodyH) / 2);
	}

	private static int cellX(int i) {
		return left + PAD + (i % COLS) * CELL;
	}

	private static int cellY(int i) {
		return top + 40 + (i / COLS) * CELL;
	}

	private static int cellAt(double x, double y) {
		for (int i = 0; i < SIZE; i++) {
			if (x >= cellX(i) && x < cellX(i) + CELL - 1 && y >= cellY(i) && y < cellY(i) + CELL - 1) return i;
		}
		return -1;
	}

	/** Slot in the entry for a cell on screen: the loose items can run to more pages than one. */
	private static int indexOf(int cell) {
		int index = cell + page * SIZE;
		return index < entry().contents.size() ? index : -1;
	}

	/** Inventory rows are drawn as in the game: main rows, then the hotbar. */
	private static int invX(int index) {
		return rightX + 8 + (index % 9) * INV_CELL;
	}

	private static int invY(int index) {
		return index < 9 ? top + 92 : top + 36 + (index / 9 - 1) * INV_CELL;
	}

	private static int invAt(double x, double y) {
		if (!wide) return -1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			if (x >= invX(i) && x < invX(i) + INV_CELL - 1 && y >= invY(i) && y < invY(i) + INV_CELL - 1) return i;
		}
		return -1;
	}

	private static Hit hitAt(double x, double y) {
		for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).over(x, y)) return hits.get(i);
		return null;
	}

	// ---- The plan ----

	private static PlayerInventory inventory() {
		return MinecraftClient.getInstance().player.getInventory();
	}

	private static boolean same(ItemStack a, ItemStack b) {
		return !a.isEmpty() && !b.isEmpty() && ItemStack.areItemsAndComponentsEqual(a, b);
	}

	private static String id(Item item) {
		return Registries.ITEM.getId(item).toString();
	}

	private static boolean lockedInBox(Entry e, int index) {
		return e.loose && BoxGrab.isBox(e.contents.get(index));
	}

	/** How many of an inventory stack aren't already going into some other box. */
	private static int storable(int index) {
		ItemStack s = inventory().getStack(index);
		if (s.isEmpty() || BoxGrab.isBox(s)) return 0;
		int n = s.getCount();
		for (Entry e : entries) if (e != entry()) n -= e.store[index];
		return Math.max(0, n);
	}

	private static List<BoxGrab.Request> buildRequests() {
		List<BoxGrab.Request> list = new ArrayList<>();
		BoxGrab.Request looseLast = null;
		for (Entry e : entries) {
			if (!e.planned()) continue;
			Map<String, ItemStack> kinds = new LinkedHashMap<>();
			for (int i = 0; i < e.store.length; i++) {
				if (e.store[i] <= 0) continue;
				ItemStack s = inventory().getStack(i);
				if (s.isEmpty()) continue;
				String key = s.getItem() + "|" + s.getComponentChanges().hashCode();
				ItemStack have = kinds.get(key);
				if (have == null) kinds.put(key, s.copyWithCount(e.store[i]));
				else have.setCount(have.getCount() + e.store[i]);
			}
			BoxGrab.Request r = new BoxGrab.Request(e.loose ? -1 : e.slotId, e.inInventory, e.invIndex, e.box, new ArrayList<>(e.contents), e.want.clone(),
					container, containerSlots, fromInventoryScreen, new ArrayList<>(kinds.values()), e.tidy, e.loose, bundlePicks(e));
			// Sorting the container moves the boxes in it, so that goes last. Otherwise loose items first: they risk nothing.
			if (e.loose && e.tidy) looseLast = r;
			else if (e.loose) list.add(0, r);
			else list.add(r);
		}
		if (looseLast != null) list.add(looseLast);
		return list;
	}

	private static List<BoxGrab.BundlePick> bundlePicks(Entry e) {
		List<BoxGrab.BundlePick> picks = new ArrayList<>();
		for (Map.Entry<Integer, Entry> b : e.bundles.entrySet()) {
			Entry inner = b.getValue();
			for (int i = 0; i < inner.want.length; i++) {
				if (inner.want[i] <= 0) continue;
				// Two stacks of the same thing in a bundle count as one pick for that much.
				BoxGrab.BundlePick merged = null;
				for (BoxGrab.BundlePick p : picks) {
					if (p.slot() == b.getKey() && same(p.item(), inner.contents.get(i))) merged = p;
				}
				if (merged != null) picks.remove(merged);
				picks.add(new BoxGrab.BundlePick(b.getKey(), inner.contents.get(i).copyWithCount(1), inner.want[i] + (merged == null ? 0 : merged.amount())));
			}
		}
		return picks;
	}

	private static int nearestPlayerDistance(MinecraftClient client) {
		if (testNearby >= 0) return testNearby;
		ClientPlayerEntity me = client.player;
		double best = Double.MAX_VALUE;
		for (AbstractClientPlayerEntity other : client.world.getPlayers()) {
			if (other != me) best = Math.min(best, other.squaredDistanceTo(me));
		}
		return best == Double.MAX_VALUE ? -1 : (int) Math.round(Math.sqrt(best));
	}

	/** Why the plan can't go right now, or null. */
	static Text problem() {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity p = client.player;
		if (p == null) return Text.empty();
		List<BoxGrab.Request> plan = buildRequests();
		boolean anyBox = plan.stream().anyMatch(r -> !r.loose());
		if (client.interactionManager != null && (!client.interactionManager.getCurrentGameMode().isSurvivalLike()
				|| client.interactionManager.getCurrentGameMode().isBlockBreakingRestricted()) && anyBox) {
			return Text.translatable("hoardeye.picker.survival_only");
		}
		if (!p.currentScreenHandler.getCursorStack().isEmpty()) return Text.translatable("hoardeye.picker.cursor");
		if (anyBox && !hasSpot) return Text.translatable("hoardeye.picker.no_spot");
		String sim = BoxGrab.simulate(p.getInventory(), plan);
		if (sim != null) return Text.translatable(sim);
		if (anyBox && ModConfig.INSTANCE.boxPickerGuard) {
			int near = nearestPlayerDistance(client);
			if (near >= 0 && near <= GUARD_RADIUS) return Text.translatable("hoardeye.picker.guard", near);
		}
		return null;
	}

	/** Everyone close enough to see, nearest first. */
	private static List<String> nearbyPlayers(MinecraftClient client) {
		ClientPlayerEntity me = client.player;
		List<AbstractClientPlayerEntity> others = new ArrayList<>(client.world.getPlayers());
		others.remove(me);
		others.sort(Comparator.comparingDouble(o -> o.squaredDistanceTo(me)));
		List<String> out = new ArrayList<>();
		for (AbstractClientPlayerEntity other : others) {
			out.add(other.getName().getString() + " " + Math.round(Math.sqrt(other.squaredDistanceTo(me))) + "m");
		}
		return out;
	}

	/** The world moves on while you decide: drop what's gone, and keep the numbers true. */
	private static boolean refresh(ClientPlayerEntity p) {
		if (screen == null || p.currentScreenHandler != screen.getScreenHandler()) return false;
		ScreenHandler h = p.currentScreenHandler;
		boolean currentGone = false;
		for (int i = entries.size() - 1; i >= 0; i--) {
			Entry e = entries.get(i);
			if (e.loose) {
				for (int s = 0; s < e.contents.size(); s++) {
					ItemStack live = h.slots.get(s).getStack();
					if (!ItemStack.areEqual(live, e.contents.get(s))) {
						e.want[s] = same(live, e.contents.get(s)) ? Math.min(e.want[s], live.getCount()) : 0;
						e.contents.set(s, live.copy());
						// What was planned from inside a bundle isn't true any more.
						Entry dropped = e.bundles.remove(s);
						if (dropped != null && dropped == bundleView) bundleView = null;
					}
				}
				continue;
			}
			if (e.slotId >= h.slots.size() || !ItemStack.areEqual(h.slots.get(e.slotId).getStack(), e.box)) {
				if (i == current) currentGone = true;
				if (i == current) bundleView = null;
				entries.remove(i);
				if (i < current) current--;
			}
		}
		if (currentGone) {
			p.sendMessage(Text.translatable("hoardeye.picker.box_changed").formatted(Formatting.YELLOW), true);
			current = Math.min(current, entries.size() - 1);
			page = 0;
			selected = -1;
		}
		if (entries.stream().noneMatch(e -> !e.loose)) return false;
		for (Entry e : entries) {
			for (int i = 0; i < e.store.length; i++) e.store[i] = Math.min(e.store[i], p.getInventory().getStack(i).getCount());
		}
		return true;
	}

	// ---- Drawing ----

	public static void render(HandledScreen<?> on, DrawContext context, int mouseX, int mouseY) {
		if (screen != on) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			close();
			return;
		}
		if (!refresh(client.player)) {
			boolean wasOpen = client.player.currentScreenHandler == on.getScreenHandler();
			cancel();
			if (wasOpen) client.player.sendMessage(Text.translatable("hoardeye.picker.box_changed").formatted(Formatting.YELLOW), true);
			return;
		}
		TextRenderer font = client.textRenderer;
		context.createNewRootLayer();
		layout(on.width, on.height);
		context.fill(0, 0, on.width, on.height, 0x90000000);
		context.getMatrices().pushMatrix();
		context.getMatrices().scale(fit, fit);
		mouseX = (int) (mouseX / fit);
		mouseY = (int) (mouseY / fit);
		if (checkTimer-- <= 0) {
			checkTimer = 10;
			hasSpot = !BoxGrab.spots(client.player, client.world).isEmpty();
		}
		if (draggingSpeed) setSpeedFromMouse(mouseX);
		if (!typed.isEmpty() && System.currentTimeMillis() - typedAt > TYPE_WINDOW_MS) typed = "";

		hits.clear();
		updatePreview();
		// Everything behind the confirm box is only there to be seen.
		int realX = mouseX, realY = mouseY;
		if (confirming) mouseX = mouseY = -9999;
		if (dragging && Math.hypot(realX - pressX, realY - pressY) > DRAG_START) dragMoved = true;
		int hovered = cellAt(mouseX, mouseY), hoveredInv = invAt(mouseX, mouseY);
		int hoveredIndex = hovered >= 0 ? indexOf(hovered) : -1;
		paintOver(hoveredIndex, hoveredInv);

		panel(context, left, top, LEFT_W, bodyH);
		drawTabs(context, font, mouseX, mouseY);
		drawNameRow(context, font, mouseX, mouseY);
		for (int cell = 0; cell < SIZE; cell++) drawCell(context, font, cell, cell == hovered);
		drawAmountBar(context, font, mouseX, mouseY);
		drawYouGet(context, font);
		drawHint(context, font, hovered, hoveredInv, hitAt(mouseX, mouseY));
		drawStatus(client, context, font);
		drawMainButtons(context, font, mouseX, mouseY);
		if (wide) {
			panel(context, rightX, top, RIGHT_W, bodyH);
			drawRight(client, context, font, mouseX, mouseY, hoveredInv);
		}

		if (confirming) {
			hits.clear();
			drawConfirm(client, context, font, realX, realY);
		} else if (dragging && dragMoved) {
			drawDrag(context, font, realX, realY);
		} else {
			drawTooltips(client, context, font, mouseX, mouseY, hoveredIndex, hoveredInv);
		}
		context.getMatrices().popMatrix();
	}

	private static void panel(DrawContext context, int x, int y, int w, int h) {
		context.fill(x, y, x + w, y + h, BG);
		context.drawStrokedRectangle(x, y, w, h, EDGE);
	}

	private static void button(DrawContext context, TextRenderer font, String id, int x, int y, int w, int h, String label, boolean enabled, boolean primary, int mouseX, int mouseY) {
		hits.add(new Hit(id, x, y, w, h));
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		int bg, edge, color;
		if (primary) {
			bg = enabled ? (hover ? 0xFF1E8FB0 : 0xFF14708C) : 0xFF23232E;
			edge = enabled ? EDGE : 0xFF44444F;
			color = enabled ? 0xFFFFFFFF : 0xFF777780;
		} else {
			bg = hover && enabled ? 0xFF3C3C50 : 0xFF2A2A38;
			edge = 0xFF55555F;
			color = enabled ? TEXT : 0xFF777780;
		}
		context.fill(x, y, x + w, y + h, bg);
		context.drawStrokedRectangle(x, y, w, h, edge);
		String fitted = font.trimToWidth(label, w - 2);
		context.drawCenteredTextWithShadow(font, fitted, x + w / 2, y + (h - 8) / 2, color);
	}

	private static String tr(String key, Object... args) {
		return Text.translatable("hoardeye.picker." + key, args).getString();
	}

	private static void drawTabs(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
		int y = top + 6, x = left + PAD, inner = LEFT_W - PAD * 2;
		boolean overflow = entries.size() * TAB > inner;
		int visible = overflow ? (inner - 24) / TAB : entries.size();
		tabOffset = Math.max(0, Math.min(tabOffset, entries.size() - visible));
		if (current < tabOffset) tabOffset = current;
		if (current >= tabOffset + visible) tabOffset = current - visible + 1;
		if (overflow) {
			button(context, font, "TABL", x, y, 11, 20, "<", tabOffset > 0, false, mouseX, mouseY);
			x += 12;
		}
		for (int n = tabOffset; n < Math.min(entries.size(), tabOffset + visible); n++) {
			Entry e = entries.get(n);
			hits.add(new Hit("TAB" + n, x, y, TAB - 1, 20));
			boolean on = n == current;
			boolean hover = mouseX >= x && mouseX < x + TAB - 1 && mouseY >= y && mouseY < y + 20;
			context.fill(x, y, x + TAB - 1, y + 20, on ? 0xFF2F3A4A : hover ? CELL_HOVER : SECTION);
			if (on) context.fill(x, y + 18, x + TAB - 1, y + 20, EDGE);
			context.drawItem(e.loose ? new ItemStack(Items.CHEST) : e.box, x + 2, y + 1);
			int matches = search.isEmpty() ? 0 : matchCount(e);
			if (!search.isEmpty() && matches == 0) context.fill(x, y, x + TAB - 1, y + 18, DIM);
			int planned = e.picked() + e.stored() + (e.tidy ? 1 : 0);
			String badge = planned > 0 ? "§b" + planned : matches > 0 ? "§a" + matches : null;
			if (badge != null) {
				context.createNewRootLayer();
				context.drawTextWithShadow(font, badge, x + TAB - 2 - font.getWidth(badge) + 2, y + 10, 0xFFFFFFFF);
			}
			x += TAB;
		}
		if (overflow) {
			button(context, font, "TABR", left + LEFT_W - PAD - 11, y, 11, 20, ">", tabOffset + visible < entries.size(), false, mouseX, mouseY);
		}
	}

	private static void drawNameRow(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
		Entry e = entry();
		int y = top + 29, x = left + PAD, room = LEFT_W - PAD * 2;
		int pages = (e.contents.size() + SIZE - 1) / SIZE;
		if (pages > 1) {
			button(context, font, "PAGEU", left + LEFT_W - PAD - 34, y - 2, 11, 11, "<", page > 0, false, mouseX, mouseY);
			button(context, font, "PAGED", left + LEFT_W - PAD - 11, y - 2, 11, 11, ">", page < pages - 1, false, mouseX, mouseY);
			context.drawCenteredTextWithShadow(font, (page + 1) + "/" + pages, left + LEFT_W - PAD - 17, y, MUTED);
			room -= 38;
		}
		if (bundleView != null) {
			button(context, font, "BACK", x, y - 2, 34, 11, tr("back"), true, false, mouseX, mouseY);
			button(context, font, "WHOLE", x + 36, y - 2, 56, 11, tr("whole_bundle"), true, false, mouseX, mouseY);
			String inside = top().contents.get(bundleSlot).getName().getString();
			context.drawTextWithShadow(font, font.trimToWidth(inside, room - 96), x + 96, y, TITLE);
			return;
		}
		String name = e.loose ? tr("loose") : e.box.getName().getString();
		context.drawTextWithShadow(font, font.trimToWidth(name, room), x, y, TITLE);
	}

	private static void drawCell(DrawContext context, TextRenderer font, int cell, boolean hovered) {
		int x = cellX(cell), y = cellY(cell), size = CELL - 1;
		int index = indexOf(cell);
		if (index < 0) {
			context.fill(x, y, x + size, y + size, 0xFF16161E);
			return;
		}
		Entry e = entry();
		ItemStack stack = e.contents.get(index);
		Entry inner = e.bundles.get(index);
		boolean fromInside = inner != null && inner.picked() > 0;
		boolean picked = e.want[index] > 0 || fromInside, live = !stack.isEmpty();
		context.fill(x, y, x + size, y + size, picked ? PICKED_BG : hovered && live ? CELL_HOVER : CELL_BG);
		if (picked) context.drawStrokedRectangle(x, y, size, size, EDGE);
		else if (hovered && live) context.drawStrokedRectangle(x, y, size, size, 0xFFFFFFFF);
		if (index == selected && !selectedIsInventory && live) context.drawStrokedRectangle(x - 1, y - 1, size + 2, size + 2, TITLE);
		if (!live) {
			if (index < previewBox.length && !previewBox[index].isEmpty()) drawGhost(context, font, previewBox[index], x, y, x + 2, y + 2, size, STORE_EDGE);
			return;
		}
		int ix = x + 2, iy = y + 2;
		context.drawItem(stack, ix, iy);
		if (fromInside) context.drawStackOverlay(font, stack, ix, iy, "§b" + inner.picked());
		else if (picked && e.want[index] != stack.getCount()) context.drawStackOverlay(font, stack, ix, iy, "§b" + e.want[index]);
		else context.drawStackOverlay(font, stack, ix, iy);
		if (!search.isEmpty() && !matches(stack)) {
			context.createNewRootLayer();
			context.fill(x, y, x + size, y + size, DIM);
		}
	}

	private static void drawAmountBar(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
		int y = top + 108, x = left + PAD;
		ItemStack stack = selectedStack();
		if (stack.isEmpty()) {
			context.drawCenteredTextWithShadow(font, tr("amount_hint"), left + LEFT_W / 2, y + 5, MUTED);
			return;
		}
		context.drawItem(stack, x, y);
		x += 18;
		int value = selectedAmount(), max = selectedMax();
		button(context, font, "M10", x, y, 20, 16, "-10", value > 0, false, mouseX, mouseY);
		button(context, font, "M1", x + 21, y, 16, 16, "-1", value > 0, false, mouseX, mouseY);
		int boxX = x + 38, boxW = 36;
		context.fill(boxX, y, boxX + boxW, y + 16, typed.isEmpty() ? SECTION : 0xFF0E4A5C);
		context.drawStrokedRectangle(boxX, y, boxW, 16, typed.isEmpty() ? 0xFF55555F : EDGE);
		context.drawCenteredTextWithShadow(font, String.valueOf(value), boxX + boxW / 2, y + 4, selectedIsInventory ? WARN : 0xFF80E8FF);
		button(context, font, "P1", boxX + boxW + 1, y, 16, 16, "+1", value < max, false, mouseX, mouseY);
		button(context, font, "P10", boxX + boxW + 18, y, 20, 16, "+10", value < max, false, mouseX, mouseY);
		button(context, font, "HALF", boxX + boxW + 39, y, 14, 16, "½", true, false, mouseX, mouseY);
		button(context, font, "MAX", boxX + boxW + 54, y, 22, 16, tr("max"), true, false, mouseX, mouseY);
	}

	private static ItemStack selectedStack() {
		if (selected < 0) return ItemStack.EMPTY;
		if (selectedIsInventory) return inventory().getStack(selected);
		return selected < entry().contents.size() ? entry().contents.get(selected) : ItemStack.EMPTY;
	}

	private static int selectedAmount() {
		return selectedIsInventory ? top().store[selected] : entry().want[selected];
	}

	private static int selectedMax() {
		return selectedIsInventory ? storable(selected) : selectedStack().getCount();
	}

	private static void drawYouGet(DrawContext context, TextRenderer font) {
		Map<String, ItemStack> got = new LinkedHashMap<>(), put = new LinkedHashMap<>();
		Map<String, Integer> gotN = new LinkedHashMap<>(), putN = new LinkedHashMap<>();
		for (Entry e : entries) {
			addPicks(e, got, gotN);
			for (Entry b : e.bundles.values()) addPicks(b, got, gotN);
			for (int i = 0; i < e.store.length; i++) {
				if (e.store[i] <= 0) continue;
				ItemStack s = inventory().getStack(i);
				String key = Fetch.key(s);
				put.putIfAbsent(key, s);
				putN.merge(key, e.store[i], Integer::sum);
			}
		}
		drawIconRow(context, font, tr("you_get"), got, gotN, top + 127, GOOD);
		drawIconRow(context, font, tr("you_store"), put, putN, top + 145, WARN);
	}

	private static void addPicks(Entry e, Map<String, ItemStack> got, Map<String, Integer> gotN) {
		for (int i = 0; i < e.want.length; i++) {
			if (e.want[i] <= 0) continue;
			String key = Fetch.key(e.contents.get(i));
			got.putIfAbsent(key, e.contents.get(i));
			gotN.merge(key, e.want[i], Integer::sum);
		}
	}

	private static void drawIconRow(DrawContext context, TextRenderer font, String label, Map<String, ItemStack> kinds, Map<String, Integer> totals, int y, int color) {
		context.drawTextWithShadow(font, label, left + PAD, y + 5, kinds.isEmpty() ? MUTED : color);
		int x = left + PAD + 46;
		int room = (left + LEFT_W - PAD - x) / 18, shown = 0;
		if (kinds.isEmpty()) {
			context.drawTextWithShadow(font, "-", x, y + 5, MUTED);
			return;
		}
		for (Map.Entry<String, ItemStack> e : kinds.entrySet()) {
			if (shown == room - 1 && kinds.size() > room) {
				context.drawTextWithShadow(font, "+" + (kinds.size() - shown), x + 2, y + 5, MUTED);
				break;
			}
			context.drawItem(e.getValue(), x, y);
			int total = totals.get(e.getKey());
			if (total > 1) context.drawStackOverlay(font, e.getValue(), x, y, String.valueOf(total));
			x += 18;
			shown++;
		}
	}

	private static void drawHint(DrawContext context, TextRenderer font, int hovered, int hoveredInv, Hit hit) {
		String text;
		if (focus != Focus.NONE) text = tr("hint_typing");
		else if (hit != null && hasHint(hit.id())) text = tr("hint." + hintKey(hit.id()));
		else if (bundleView != null && hit == null) text = tr("hint_bundle");
		else if (hovered >= 0 && indexOf(hovered) >= 0 && isFullBundle(entry().contents.get(indexOf(hovered)))) text = tr("hint_bundle_cell");
		else if (hovered >= 0 && indexOf(hovered) >= 0 && indexOf(hovered) < previewBox.length && entry().contents.get(indexOf(hovered)).isEmpty() && !previewBox[indexOf(hovered)].isEmpty()) text = tr("hint_ghost");
		else if (hoveredInv >= 0 && inventory().getStack(hoveredInv).isEmpty() && hoveredInv < previewInventory.length && !previewInventory[hoveredInv].isEmpty()) text = tr("hint_ghost");
		else if (hovered >= 0) text = tr("hint_box");
		else if (hoveredInv >= 0) text = tr("hint_inv");
		else if (!note.isEmpty()) text = note;
		else text = tr("hint");
		context.drawTextWithShadow(font, font.trimToWidth(text, LEFT_W - PAD * 2), left + PAD, top + 165, note.equals(text) ? WARN : MUTED);
	}

	private static String hintKey(String id) {
		if (id.startsWith("TAB") && id.length() > 3 && Character.isDigit(id.charAt(3))) return "tab";
		if (id.startsWith("KIT")) return "kit";
		return id.toLowerCase(Locale.ROOT);
	}

	private static boolean hasHint(String id) {
		String key = hintKey(id);
		return List.of("tab", "kit", "all", "none", "tidy", "instant", "guard", "topup", "match", "storeall", "save", "last", "speed", "take", "cancel").contains(key);
	}

	private static void drawStatus(MinecraftClient client, DrawContext context, TextRenderer font) {
		Text problem = problem();
		int width = LEFT_W - PAD * 2, y = top + 177;
		List<BoxGrab.Request> plan = buildRequests();
		if (problem != null) {
			context.drawTextWithShadow(font, font.trimToWidth(problem.getString(), width), left + PAD, y, BAD);
		} else if (!plan.isEmpty()) {
			boolean slow = plan.stream().anyMatch(r -> !r.loose()) && BoxGrab.breakTicks(client.player.getInventory()) > 40;
			String time = ModConfig.INSTANCE.boxPickerInstant && plan.stream().allMatch(r -> !r.loose() && r.deposits().isEmpty() && !r.tidy()) ? tr("eta_instant")
					: tr("eta", String.format(Locale.ROOT, "%.1f", BoxGrab.estimateTicks(client.player, plan) / 20.0));
			String ok = slow ? tr("no_pick") : time;
			context.drawTextWithShadow(font, font.trimToWidth(ok, width), left + PAD, y, slow ? WARN : GOOD);
		} else {
			context.drawTextWithShadow(font, tr("safe"), left + PAD, y, GOOD);
		}
		List<String> near = nearbyPlayers(client);
		Text players = near.isEmpty() ? Text.translatable("hoardeye.picker.nobody")
				: Text.translatable("hoardeye.picker.players", near.size(), String.join(", ", near));
		context.drawTextWithShadow(font, font.trimToWidth(players.getString(), width), left + PAD, y + 11, near.isEmpty() ? MUTED : WARN);
	}

	private static String goLabel() {
		int take = 0, store = 0, sort = 0;
		for (Entry e : entries) {
			take += e.picked();
			store += e.stored();
			sort += e.tidy ? 1 : 0;
		}
		if (take > 0 && store > 0) return tr("go_both", take, store);
		if (take > 0) return take == 1 ? tr("take_one") : tr("take", take);
		if (store > 0) return tr("go_store", store);
		if (sort > 0) return tr("go_sort");
		return tr("nothing");
	}

	private static boolean canGo() {
		return !buildRequests().isEmpty() && problem() == null;
	}

	private static void drawMainButtons(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
		int y = top + bodyH - PAD + 4 - BUTTON_H, x = left + PAD;
		button(context, font, "ALL", x, y, 26, BUTTON_H, tr("all"), true, false, mouseX, mouseY);
		button(context, font, "NONE", x + 28, y, 32, BUTTON_H, tr("none"), true, false, mouseX, mouseY);
		button(context, font, "CANCEL", x + 62, y, 42, BUTTON_H, tr("cancel"), true, false, mouseX, mouseY);
		button(context, font, "TAKE", x + 106, y, LEFT_W - PAD * 2 - 106, BUTTON_H, goLabel(), canGo(), true, mouseX, mouseY);
	}

	private static void drawRight(MinecraftClient client, DrawContext context, TextRenderer font, int mouseX, int mouseY, int hoveredInv) {
		int x = rightX + 8, w = RIGHT_W - 16;
		drawField(context, font, "SEARCH", x, top + 6, w, focus == Focus.SEARCH, search, tr("search"));

		int free = freeNow(), after = BoxGrab.freeAfter(inventory(), buildRequests());
		context.drawTextWithShadow(font, tr("your_inv"), x, top + 24, MUTED);
		String room = free == after ? tr("free", free) : tr("free_change", free, after);
		context.drawTextWithShadow(font, room, x + w - font.getWidth(room), top + 24, after <= 0 ? BAD : MUTED);
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) drawInvCell(context, font, i, i == hoveredInv);

		int y = top + 112;
		int third = (w - 4) / 3;
		button(context, font, "TOPUP", x, y, third, 14, tr("topup"), true, false, mouseX, mouseY);
		button(context, font, "MATCH", x + third + 2, y, third, 14, tr("match"), bundleView == null, false, mouseX, mouseY);
		button(context, font, "STOREALL", x + (third + 2) * 2, y, third, 14, tr("storeall"), true, false, mouseX, mouseY);

		y = top + 129;
		context.drawTextWithShadow(font, tr("kits"), x, y + 2, MUTED);
		int saveW = 34;
		if (focus == Focus.KIT_NAME) {
			drawField(context, font, "KITFIELD", x + 24, y - 1, w - 24, true, kitName, tr("kit_name"));
		} else {
			button(context, font, "SAVE", x + w - saveW, y - 1, saveW, 12, tr("save"), planHasPicks(), false, mouseX, mouseY);
		}
		drawKitChips(context, font, x, y + 13, w, mouseX, mouseY);

		y = top + 172;
		ModConfig c = ModConfig.INSTANCE;
		boolean instant = c.boxPickerInstant;
		String speed = instant ? tr("speed_instant") : tr("speed", String.format(Locale.ROOT, "%.2g", c.boxPickerSpeed / 100.0));
		context.drawTextWithShadow(font, speed, x, y, instant ? WARN : TEXT);
		int sy = y + 10;
		hits.add(new Hit("SPEED", x, sy - 1, w, 8));
		context.fill(x, sy + 2, x + w, sy + 4, 0xFF44444F);
		int knob = x + (int) Math.round((c.boxPickerSpeed - 50) / 450.0 * (w - 4));
		context.fill(knob, sy, knob + 4, sy + 6, instant ? 0xFF777780 : EDGE);

		y = top + bodyH - PAD + 4 - BUTTON_H;
		button(context, font, "TIDY", x, y, third, BUTTON_H, top().tidy ? "§b" + tr("tidy") : tr("tidy"), true, false, mouseX, mouseY);
		button(context, font, "INSTANT", x + third + 2, y, third, BUTTON_H, (instant ? "§e" : "") + tr("instant"), true, false, mouseX, mouseY);
		button(context, font, "GUARD", x + (third + 2) * 2, y, third, BUTTON_H, (c.boxPickerGuard ? "§a" : "") + tr("guard_btn"), true, false, mouseX, mouseY);
	}

	private static void drawField(DrawContext context, TextRenderer font, String id, int x, int y, int w, boolean focused, String value, String placeholder) {
		hits.add(new Hit(id, x, y, w, 13));
		context.fill(x, y, x + w, y + 13, SECTION);
		context.drawStrokedRectangle(x, y, w, 13, focused ? EDGE : 0xFF55555F);
		if (value.isEmpty() && !focused) context.drawTextWithShadow(font, font.trimToWidth(placeholder, w - 6), x + 3, y + 3, MUTED);
		else {
			String shown = font.trimToWidth(value, w - 10, true);
			context.drawTextWithShadow(font, shown + (focused && System.currentTimeMillis() / 500 % 2 == 0 ? "_" : ""), x + 3, y + 3, TEXT);
		}
	}

	private static void drawInvCell(DrawContext context, TextRenderer font, int i, boolean hovered) {
		int x = invX(i), y = invY(i), size = INV_CELL - 1;
		ItemStack stack = inventory().getStack(i);
		if (stack.isEmpty() && i < previewInventory.length && !previewInventory[i].isEmpty()) {
			drawGhost(context, font, previewInventory[i], x, y, x, y, size, EDGE);
			return;
		}
		Entry e = top();
		boolean going = e.store[i] > 0, others = storable(i) < stack.getCount() && !BoxGrab.isBox(stack);
		context.fill(x, y, x + size, y + size, going ? STORE_BG : others ? 0xFF2A2412 : hovered && !stack.isEmpty() ? CELL_HOVER : CELL_BG);
		if (going) context.drawStrokedRectangle(x, y, size, size, STORE_EDGE);
		else if (hovered && !stack.isEmpty()) context.drawStrokedRectangle(x, y, size, size, 0xFFFFFFFF);
		if (i == selected && selectedIsInventory) context.drawStrokedRectangle(x - 1, y - 1, size + 2, size + 2, TITLE);
		if (stack.isEmpty()) return;
		context.drawItem(stack, x, y);
		int later = i < previewInventory.length && ItemStack.areItemsAndComponentsEqual(previewInventory[i], stack) ? previewInventory[i].getCount() : stack.getCount();
		if (going && e.store[i] != stack.getCount()) context.drawStackOverlay(font, stack, x, y, "§e" + e.store[i]);
		else if (later > stack.getCount()) context.drawStackOverlay(font, stack, x, y, "§b" + later);
		else context.drawStackOverlay(font, stack, x, y);
		if (BoxGrab.isBox(stack) || (!search.isEmpty() && !matches(stack))) {
			context.createNewRootLayer();
			context.fill(x, y, x + size, y + size, 0x90101018);
		}
	}

	/** Something that isn't there yet, only planned: faint, outlined in the colour of where it's from. */
	private static void drawGhost(DrawContext context, TextRenderer font, ItemStack stack, int x, int y, int ix, int iy, int size, int edge) {
		context.fill(x, y, x + size, y + size, CELL_BG);
		context.drawItem(stack, ix, iy);
		context.drawStackOverlay(font, stack, ix, iy);
		context.createNewRootLayer();
		context.fill(x, y, x + size, y + size, 0x70101018);
		context.drawStrokedRectangle(x, y, size, size, edge);
	}

	private static void updatePreview() {
		List<BoxGrab.Request> plan = buildRequests();
		previewInventory = BoxGrab.inventoryAfter(inventory(), plan);
		previewBox = new ItemStack[0];
		if (bundleView != null) return;
		for (BoxGrab.Request r : plan) {
			Entry e = top();
			boolean mine = e.loose ? r.loose() : !r.loose() && r.sourceSlot() == e.slotId;
			if (mine) previewBox = BoxGrab.boxAfter(r.contents(), r.want(), r.deposits());
		}
	}

	private static int cellGhost(double x, double y) {
		int cell = cellAt(x, y), index = cell >= 0 ? indexOf(cell) : -1;
		return index >= 0 && index < previewBox.length && entry().contents.get(index).isEmpty() && !previewBox[index].isEmpty() ? index : -1;
	}

	private static int invGhost(double x, double y) {
		int inv = invAt(x, y);
		return inv >= 0 && inv < previewInventory.length && inventory().getStack(inv).isEmpty() && !previewInventory[inv].isEmpty() ? inv : -1;
	}

	private static boolean overLeft(double x, double y) {
		return x >= left && x < left + LEFT_W && y >= top && y < top + bodyH;
	}

	private static boolean overRight(double x, double y) {
		return wide && x >= rightX && x < rightX + RIGHT_W && y >= top && y < top + bodyH;
	}

	/** The tab under the mouse, if a box can be put into there: not a box's own, or anything but a real tab. */
	private static int tabAt(double x, double y) {
		Hit hit = hitAt(x, y);
		return hit != null && hit.id().startsWith("TAB") && hit.id().length() > 3 && Character.isDigit(hit.id().charAt(3)) ? Integer.parseInt(hit.id().substring(3)) : -1;
	}

	private static void drawDrag(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
		context.createNewRootLayer();
		boolean left = overLeft(mouseX, mouseY), right = overRight(mouseX, mouseY);
		String label;
		int color;
		if (dragBack) {
			boolean ok = dragFromInventory ? left : right;
			label = ok ? tr("drop_undo") : "";
			color = WARN;
			if (ok) outline(context, left, WARN);
		} else if (dragFromInventory) {
			int tab = tabAt(mouseX, mouseY);
			Entry into = tab >= 0 ? entries.get(tab) : top();
			if (tab >= 0 || left) {
				label = tr("drop_store", dragAmount);
				color = WARN;
				outline(context, true, WARN);
				if (tab >= 0 && into != top()) label += " " + (into.loose ? tr("loose") : into.box.getName().getString());
			} else {
				label = right ? "" : tr("drop_cancel");
				color = MUTED;
			}
		} else if (right) {
			label = tr("drop_take", dragAmount);
			color = GOOD;
			outline(context, false, GOOD);
		} else {
			label = left ? "" : tr("drop_cancel");
			color = MUTED;
		}
		context.createNewRootLayer();
		context.drawItem(dragStack, mouseX - 8, mouseY - 8);
		if (!dragBack) context.drawStackOverlay(font, dragStack, mouseX - 8, mouseY - 8, String.valueOf(dragAmount));
		if (!label.isEmpty()) context.drawTextWithShadow(font, label, mouseX + 10, mouseY - 14, color);
	}

	private static void outline(DrawContext context, boolean leftSide, int color) {
		context.drawStrokedRectangle(leftSide ? left : rightX, top, leftSide ? LEFT_W : RIGHT_W, bodyH, color);
	}

	private static String listOf(Map<String, ItemStack> kinds, Map<String, Integer> totals) {
		List<String> parts = new ArrayList<>();
		kinds.forEach((key, stack) -> parts.add(totals.get(key) + " " + stack.getName().getString()));
		return String.join(", ", parts);
	}

	/** What's about to happen, box by box, in the order it'll happen. */
	private static void drawConfirm(MinecraftClient client, DrawContext context, TextRenderer font, int mouseX, int mouseY) {
		context.createNewRootLayer();
		int total = LEFT_W + GAP + RIGHT_W, w = Math.min(total - 40, 300), h = Math.min(bodyH - 20, 190);
		int x = left + (total - w) / 2, y = top + (bodyH - h) / 2, textW = w - 20;
		context.fill(0, 0, (int) (context.getScaledWindowWidth() / fit) + 1, (int) (context.getScaledWindowHeight() / fit) + 1, 0x80000000);
		panel(context, x, y, w, h);
		context.drawTextWithShadow(font, tr("confirm_title"), x + 10, y + 8, TITLE);

		List<Text> lines = new ArrayList<>();
		for (Entry e : entriesInPlanOrder()) {
			lines.add(e.loose ? Text.literal(tr("loose")).formatted(Formatting.WHITE, Formatting.BOLD) : e.box.getName().copy().formatted(Formatting.WHITE, Formatting.BOLD));
			Map<String, ItemStack> got = new LinkedHashMap<>(), put = new LinkedHashMap<>();
			Map<String, Integer> gotN = new LinkedHashMap<>(), putN = new LinkedHashMap<>();
			addPicks(e, got, gotN);
			for (Entry b : e.bundles.values()) addPicks(b, got, gotN);
			for (int i = 0; i < e.store.length; i++) {
				if (e.store[i] <= 0) continue;
				ItemStack s = inventory().getStack(i);
				put.putIfAbsent(Fetch.key(s), s);
				putN.merge(Fetch.key(s), e.store[i], Integer::sum);
			}
			if (!got.isEmpty()) lines.add(Text.translatable("hoardeye.picker.confirm_take", listOf(got, gotN)).formatted(Formatting.GREEN));
			if (!put.isEmpty()) lines.add(Text.translatable("hoardeye.picker.confirm_put", listOf(put, putN)).formatted(Formatting.GOLD));
			if (e.tidy) lines.add(Text.translatable("hoardeye.picker.confirm_sort").formatted(Formatting.AQUA));
		}
		int buttonY = y + h - BUTTON_H - 8, footY = buttonY - 24, room = (footY - (y + 22)) / 10, line = 0, drawn = 0;
		List<net.minecraft.text.OrderedText> wrapped = new ArrayList<>();
		for (Text t : lines) wrapped.addAll(font.wrapLines(t, textW));
		for (net.minecraft.text.OrderedText t : wrapped) {
			if (line == room - 1 && wrapped.size() > room) {
				context.drawTextWithShadow(font, tr("confirm_more", wrapped.size() - drawn), x + 10, y + 22 + line * 10, MUTED);
				break;
			}
			context.drawTextWithShadow(font, t, x + 10, y + 22 + line * 10, TEXT);
			line++;
			drawn++;
		}
		Text problem = problem();
		List<BoxGrab.Request> plan = buildRequests();
		String time = problem != null ? font.trimToWidth(problem.getString(), textW)
				: ModConfig.INSTANCE.boxPickerInstant && plan.stream().allMatch(r -> !r.loose() && r.deposits().isEmpty() && !r.tidy()) ? tr("eta_instant")
				: tr("eta", String.format(Locale.ROOT, "%.1f", BoxGrab.estimateTicks(client.player, plan) / 20.0));
		context.drawTextWithShadow(font, time, x + 10, footY, problem != null ? BAD : GOOD);
		context.drawTextWithShadow(font, font.trimToWidth(tr("confirm_keys"), textW), x + 10, footY + 11, MUTED);
		int half = (w - 24) / 2;
		button(context, font, "CONFIRM_BACK", x + 10, buttonY, half, BUTTON_H, tr("confirm_back"), true, false, mouseX, mouseY);
		button(context, font, "CONFIRM_GO", x + 14 + half, buttonY, half, BUTTON_H, tr("confirm_go"), canGo(), true, mouseX, mouseY);
	}

	/** The entries in the plan, in the order it runs them. */
	private static List<Entry> entriesInPlanOrder() {
		List<Entry> order = new ArrayList<>();
		for (BoxGrab.Request r : buildRequests()) {
			for (Entry e : entries) {
				if (e.planned() && (r.loose() ? e.loose : !e.loose && e.slotId == r.sourceSlot())) order.add(e);
			}
		}
		return order;
	}

	private static void drawKitChips(DrawContext context, TextRenderer font, int x, int y, int w, int mouseX, int mouseY) {
		List<BoxKits.Kit> kits = BoxKits.kits();
		int cx = x, row = 0, skipped = 0;
		if (!BoxKits.last().isEmpty()) {
			int cw = font.getWidth(tr("last")) + 8;
			button(context, font, "LAST", cx, y, cw, 12, tr("last"), true, false, mouseX, mouseY);
			cx += cw + 2;
		}
		if (kits.isEmpty() && BoxKits.last().isEmpty()) {
			context.drawTextWithShadow(font, tr("no_kits"), x, y + 2, MUTED);
			return;
		}
		for (int n = 0; n < kits.size(); n++) {
			if (skipped < kitOffset) {
				skipped++;
				continue;
			}
			String name = font.trimToWidth(kits.get(n).name, 60);
			boolean armed = kits.get(n).name.equals(armedDelete);
			String label = armed ? tr("kit_sure") : name;
			int cw = font.getWidth(label) + 8;
			if (cx + cw > x + w) {
				if (++row > 1) break;
				cx = x;
			}
			button(context, font, "KIT" + n, cx, y + row * 13, cw, 12, (armed ? "§c" : "") + label, true, false, mouseX, mouseY);
			cx += cw + 2;
		}
	}

	private static void drawTooltips(MinecraftClient client, DrawContext context, TextRenderer font, int mouseX, int mouseY, int index, int inv) {
		ItemStack stack = ItemStack.EMPTY;
		List<Text> extra = new ArrayList<>();
		if (index >= 0 && !entry().contents.get(index).isEmpty()) {
			stack = entry().contents.get(index);
			if (entry().want[index] > 0) extra.add(Text.translatable("hoardeye.picker.taking", entry().want[index], stack.getCount()).formatted(Formatting.AQUA));
			if (isFullBundle(stack)) extra.add(Text.translatable("hoardeye.picker.hint_bundle_cell").formatted(Formatting.GRAY));
			if (lockedInBox(entry(), index)) extra.add(Text.translatable("hoardeye.picker.open_tab").formatted(Formatting.GRAY));
			addTotals(extra, stack);
		} else if (inv >= 0 && !inventory().getStack(inv).isEmpty()) {
			stack = inventory().getStack(inv);
			if (top().store[inv] > 0) extra.add(Text.translatable("hoardeye.picker.storing", top().store[inv], stack.getCount()).formatted(Formatting.GOLD));
			if (BoxGrab.isBox(stack)) extra.add(Text.translatable("hoardeye.picker.no_box_in_box").formatted(Formatting.GRAY));
			addTotals(extra, stack);
		}
		if (!stack.isEmpty()) {
			List<Text> lines = new ArrayList<>(Screen.getTooltipFromItem(client, stack));
			lines.addAll(extra);
			context.drawTooltip(font, lines, stack.getTooltipData(), mouseX, mouseY);
			context.drawDeferredElements();
			return;
		}
		Hit hit = hitAt(mouseX, mouseY);
		if (hit != null && hit.id().startsWith("TAB") && hit.id().length() > 3 && Character.isDigit(hit.id().charAt(3))) {
			Entry e = entries.get(Integer.parseInt(hit.id().substring(3)));
			List<Text> lines = new ArrayList<>();
			lines.add(e.loose ? Text.literal(tr("loose")) : e.box.getName());
			lines.add(Text.translatable("hoardeye.picker.tab_stacks", (int) e.contents.stream().filter(s -> !s.isEmpty()).count(), e.contents.size()).formatted(Formatting.GRAY));
			context.drawTooltip(font, lines, Optional.empty(), mouseX, mouseY);
			context.drawDeferredElements();
		}
	}

	/** How much of this you've got, in the boxes and on you: you can't choose an amount well without knowing. */
	private static void addTotals(List<Text> out, ItemStack stack) {
		int inBoxes = 0, onYou = 0;
		for (Entry e : entries) {
			for (ItemStack s : e.contents) {
				if (same(s, stack)) inBoxes += s.getCount();
				BundleContentsComponent c = s.get(DataComponentTypes.BUNDLE_CONTENTS);
				if (c != null) for (ItemStack inside : c.iterate()) if (same(inside, stack)) inBoxes += inside.getCount();
			}
		}
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (same(inventory().getStack(i), stack)) onYou += inventory().getStack(i).getCount();
		out.add(Text.translatable("hoardeye.picker.totals", inBoxes, onYou).formatted(Formatting.DARK_GRAY));
	}

	private static int freeNow() {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inventory().getStack(i).isEmpty()) n++;
		return n;
	}

	// ---- Search ----

	private static boolean matches(ItemStack stack) {
		if (search.isEmpty()) return true;
		if (stack.isEmpty()) return false;
		String key = stack.getItem() + "|" + stack.getComponentChanges().hashCode();
		String text = searchText.computeIfAbsent(key, k -> {
			StringBuilder all = new StringBuilder(id(stack.getItem()));
			for (Text line : Screen.getTooltipFromItem(MinecraftClient.getInstance(), stack)) all.append(' ').append(line.getString());
			// A bundle matches on what's inside it.
			BundleContentsComponent c = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
			if (c != null) for (ItemStack inside : c.iterate()) all.append(' ').append(id(inside.getItem())).append(' ').append(inside.getName().getString());
			return all.toString().toLowerCase(Locale.ROOT);
		});
		for (String word : search.toLowerCase(Locale.ROOT).trim().split("\\s+")) if (!text.contains(word)) return false;
		return true;
	}

	private static int matchCount(Entry e) {
		int n = 0;
		for (ItemStack s : e.contents) if (!s.isEmpty() && matches(s)) n++;
		return n;
	}

	// ---- Changing the plan ----

	private static void paintOver(int index, int inv) {
		if (!painting) return;
		if (paintInventory) {
			if (inv >= 0 && inv != lastPainted && storable(inv) > 0) {
				top().store[inv] = paintOn ? storable(inv) : 0;
				lastPainted = inv;
			}
		} else if (index >= 0 && index != lastPainted && !entry().contents.get(index).isEmpty() && !lockedInBox(entry(), index) && matches(entry().contents.get(index))) {
			entry().want[index] = paintOn ? entry().contents.get(index).getCount() : 0;
			lastPainted = index;
		}
	}

	static int testBundleWant(int i) {
		return bundleView == null ? -1 : bundleView.want[i];
	}

	static boolean testInBundle() {
		return bundleView != null;
	}

	private static void select(int index, boolean inventorySide) {
		selected = index;
		selectedIsInventory = inventorySide;
		typed = "";
		note = "";
	}

	private static void setAll(boolean on) {
		Entry e = entry();
		for (int i = 0; i < e.want.length; i++) {
			ItemStack s = e.contents.get(i);
			if (s.isEmpty() || lockedInBox(e, i)) continue;
			// With a search on, "all" means all of what you can see.
			if (!matches(s)) continue;
			e.want[i] = on ? s.getCount() : 0;
			e.bundles.remove(i);
		}
		if (!on) Arrays.fill(e.store, 0);
		note = "";
	}

	private static void changeAmount(int delta) {
		if (selected < 0 || selectedStack().isEmpty()) return;
		int value = Math.max(0, Math.min(selectedMax(), selectedAmount() + delta));
		if (selectedIsInventory) top().store[selected] = value;
		else entry().want[selected] = value;
	}

	private static void setSelected(int value) {
		if (selected < 0 || selectedStack().isEmpty()) return;
		value = Math.max(0, Math.min(selectedMax(), value));
		if (selectedIsInventory) top().store[selected] = value;
		else entry().want[selected] = value;
	}

	/** A typed number is how many you want, whatever the stacks: it fills this one, then others of the same item. */
	private static void applyTyped() {
		if (selected < 0 || selectedStack().isEmpty()) return;
		// Put back what the digits so far did, so 100 then Backspace is exactly as if you'd typed 10.
		for (int n = 0; n < entries.size(); n++) {
			System.arraycopy(typedWantBase[n], 0, entries.get(n).want, 0, typedWantBase[n].length);
			System.arraycopy(typedStoreBase[n], 0, entries.get(n).store, 0, typedStoreBase[n].length);
		}
		long parsed = typed.isEmpty() ? 0 : Math.min(1_000_000, Long.parseLong(typed));
		if (bundleView != null) for (int i = 0; i < bundleView.want.length; i++) if (same(bundleView.contents.get(i), selectedStack())) bundleView.want[i] = 0;
		int left = (int) parsed;
		ItemStack kind = selectedStack();
		if (selectedIsInventory) {
			int take = Math.min(left, storable(selected));
			top().store[selected] = take;
			left -= take;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE && left > 0; i++) {
				if (i == selected || !same(inventory().getStack(i), kind)) continue;
				take = Math.min(left, storable(i));
				top().store[i] = take;
				left -= take;
			}
		} else {
			int take = Math.min(left, kind.getCount());
			entry().want[selected] = take;
			left -= take;
			left = fillFrom(entry(), kind, left, selected);
			for (Entry e : entries) if (e != entry()) left = fillFrom(e, kind, left, -1);
		}
		note = left > 0 && parsed > 0 ? tr("only_have", parsed - left) : "";
	}

	private static int fillFrom(Entry e, ItemStack kind, int left, int skip) {
		for (int i = 0; i < e.want.length && left > 0; i++) {
			if (i == skip || !same(e.contents.get(i), kind)) continue;
			int take = Math.min(left, e.contents.get(i).getCount());
			e.want[i] = take;
			left -= take;
		}
		return left;
	}

	private static void typeDigit(int digit) {
		if (selected < 0 || selectedStack().isEmpty()) return;
		if (typed.isEmpty()) {
			typedWantBase = new int[entries.size()][];
			typedStoreBase = new int[entries.size()][];
			for (int n = 0; n < entries.size(); n++) {
				typedWantBase[n] = entries.get(n).want.clone();
				typedStoreBase[n] = entries.get(n).store.clone();
			}
		}
		if (typed.length() >= 6) return;
		typed += digit;
		typedAt = System.currentTimeMillis();
		applyTyped();
	}

	private static Map<String, Integer> pickTotals() {
		Map<String, Integer> totals = new LinkedHashMap<>();
		for (Entry e : entries) {
			addTotals(e, totals);
			for (Entry b : e.bundles.values()) addTotals(b, totals);
		}
		return totals;
	}

	private static void addTotals(Entry e, Map<String, Integer> totals) {
		for (int i = 0; i < e.want.length; i++) {
			if (e.want[i] > 0) totals.merge(id(e.contents.get(i).getItem()), e.want[i], Integer::sum);
		}
	}

	private static boolean planHasPicks() {
		return !pickTotals().isEmpty();
	}

	/** Sets the plan so you end up carrying these amounts: only what you're short of, loose items first as they risk nothing. */
	private static void applyKit(Map<String, Integer> kit) {
		for (Entry e : entries) {
			Arrays.fill(e.want, 0);
			e.bundles.clear();
		}
		bundleView = null;
		List<String> shortOf = new ArrayList<>();
		for (Map.Entry<String, Integer> item : kit.entrySet()) {
			Identifier itemId = Identifier.tryParse(item.getKey());
			if (itemId == null || !Registries.ITEM.containsId(itemId)) continue;
			Item type = Registries.ITEM.get(itemId);
			int have = 0;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inventory().getStack(i).isOf(type)) have += inventory().getStack(i).getCount();
			int need = item.getValue() - have;
			for (Entry e : entries) {
				for (int i = 0; i < e.want.length && need > 0; i++) {
					if (!e.contents.get(i).isOf(type) || lockedInBox(e, i)) continue;
					int take = Math.min(need, e.contents.get(i).getCount());
					e.want[i] = take;
					need -= take;
				}
			}
			// Then whatever is tucked away inside bundles.
			for (Entry e : entries) {
				for (int i = 0; i < e.want.length && need > 0; i++) {
					if (!isFullBundle(e.contents.get(i))) continue;
					Entry inner = bundleEntry(e, i);
					for (int c = 0; c < inner.want.length && need > 0; c++) {
						if (!inner.contents.get(c).isOf(type)) continue;
						int take = Math.min(need, inner.contents.get(c).getCount());
						inner.want[c] = take;
						need -= take;
					}
					if (inner.picked() == 0) e.bundles.remove(i);
				}
			}
			if (need > 0) shortOf.add(need + " " + new ItemStack(type).getName().getString());
		}
		note = shortOf.isEmpty() ? "" : tr("short", String.join(", ", shortOf));
	}

	/** For each partial stack you carry, whatever would fill it. */
	private static void topUp() {
		List<String> shortOf = new ArrayList<>();
		int added = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inventory().getStack(i);
			if (s.isEmpty() || s.getCount() >= s.getMaxCount()) continue;
			int need = s.getMaxCount() - s.getCount();
			for (Entry e : entries) {
				for (int c = 0; c < e.want.length && need > 0; c++) {
					if (!same(e.contents.get(c), s)) continue;
					int room = e.contents.get(c).getCount() - e.want[c];
					int take = Math.min(room, need);
					e.want[c] += take;
					need -= take;
					added += take;
				}
			}
		}
		note = added == 0 ? tr("nothing_to_top_up") : "";
	}

	/** Everything you carry outside the hotbar (or including it with Shift) that this box already has some of, or all of it. */
	private static void storeInto(boolean onlyMatching, boolean hotbarToo) {
		Entry e = top();
		for (int i = hotbarToo ? 0 : 9; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inventory().getStack(i);
			if (storable(i) <= 0) continue;
			if (onlyMatching) {
				boolean has = false;
				for (ItemStack c : e.contents) has |= same(c, s);
				if (!has) continue;
			}
			e.store[i] = storable(i);
		}
		note = "";
	}

	private static void setSpeedFromMouse(double mouseX) {
		int x = rightX + 8, w = RIGHT_W - 16;
		double f = Math.max(0, Math.min(1, (mouseX - x) / w));
		ModConfig.INSTANCE.boxPickerSpeed = 50 + (int) Math.round(f * 18) * 25;
	}

	private static void go() {
		if (!canGo()) return;
		if (ModConfig.INSTANCE.boxPickerConfirm && !confirming) {
			typed = "";
			focus = Focus.NONE;
			confirming = true;
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		List<BoxGrab.Request> plan = buildRequests();
		BoxKits.setLast(pickTotals());
		ModConfig.save();
		cancel();
		BoxGrab.startQueue(client, plan);
	}

	// ---- Input ----

	private static boolean ours(long window) {
		MinecraftClient client = MinecraftClient.getInstance();
		return screen != null && client.currentScreen == screen && window == client.getWindow().getHandle();
	}

	/** True when the manager took it: everything is, while it's open, so nothing reaches what's behind it. */
	public static boolean rawMouseButton(long window, MouseInput input, int action) {
		if (!ours(window)) return false;
		MinecraftClient client = MinecraftClient.getInstance();
		double x = client.mouse.getScaledX(client.getWindow()) / fit, y = client.mouse.getScaledY(client.getWindow()) / fit;
		if (action == GLFW.GLFW_PRESS) mouseDown(x, y, input.button(), (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0, (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0);
		else if (action == GLFW.GLFW_RELEASE) mouseUp(x, y);
		return true;
	}

	public static boolean rawMouseScroll(long window, double vertical) {
		if (!ours(window)) return false;
		MinecraftClient client = MinecraftClient.getInstance();
		scroll(client.mouse.getScaledX(client.getWindow()) / fit, client.mouse.getScaledY(client.getWindow()) / fit, vertical, client.isShiftPressed());
		return true;
	}

	public static boolean rawKey(long window, int action, KeyInput input) {
		if (!ours(window)) return false;
		if (action != GLFW.GLFW_RELEASE) key(input, HoardEyeClient.boxKey(), MinecraftClient.getInstance().options.inventoryKey);
		return true;
	}

	private static void mouseDown(double mouseX, double mouseY, int button, boolean shift, boolean ctrl) {
		boolean primary = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
		Hit hit = hitAt(mouseX, mouseY);
		if (confirming) {
			if (primary && hit != null && hit.id().equals("CONFIRM_GO")) go();
			else if (primary && hit != null && hit.id().equals("CONFIRM_BACK")) confirming = false;
			return;
		}
		if (focus != Focus.NONE && (hit == null || !hit.id().equals(focus == Focus.SEARCH ? "SEARCH" : "KITFIELD"))) {
			if (focus == Focus.KIT_NAME) focus = Focus.NONE;
			else focus = Focus.NONE;
		}
		if (hit != null) {
			act(hit.id(), primary, shift, button, mouseX);
			return;
		}
		armedDelete = null;
		int cell = cellAt(mouseX, mouseY);
		int index = cell >= 0 ? indexOf(cell) : -1;
		if (index >= 0 && !entry().contents.get(index).isEmpty()) {
			Entry e = entry();
			ItemStack stack = e.contents.get(index);
			// Those two open something when clicked, rather than being picked up.
			boolean carry = !shift && !ctrl && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE && !lockedInBox(e, index) && !(bundleView == null && isFullBundle(stack));
			int before = e.want[index];
			clickBoxCell(index, button, shift, ctrl);
			if (carry) startDrag(false, index, stack, before, amountFor(before, stack.getCount(), button), mouseX, mouseY);
			return;
		}
		int inv = invAt(mouseX, mouseY);
		if (inv >= 0 && !inventory().getStack(inv).isEmpty()) {
			ItemStack stack = inventory().getStack(inv);
			boolean carry = !shift && !ctrl && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE && !BoxGrab.isBox(stack) && storable(inv) > 0;
			int before = top().store[inv], max = storable(inv);
			clickInvCell(inv, button, ctrl);
			if (carry) startDrag(true, inv, stack, before, amountFor(before, max, button), mouseX, mouseY);
			return;
		}
		if (primary) {
			int ghost = invGhost(mouseX, mouseY), boxGhost = cellGhost(mouseX, mouseY);
			if (ghost >= 0) startBack(true, previewInventory[ghost], mouseX, mouseY);
			else if (boxGhost >= 0) startBack(false, previewBox[boxGhost], mouseX, mouseY);
		}
	}

	/** A partly chosen amount carries over; otherwise the whole stack, or half with the right button, as in the game. */
	private static int amountFor(int before, int max, int button) {
		if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) return (max + 1) / 2;
		return before > 0 && before < max ? before : max;
	}

	private static void startDrag(boolean fromInventory, int index, ItemStack stack, int before, int amount, double x, double y) {
		dragging = true;
		dragBack = dragMoved = false;
		dragFromInventory = fromInventory;
		dragIndex = index;
		dragStack = stack.copy();
		dragBefore = before;
		dragAmount = amount;
		pressX = x;
		pressY = y;
	}

	private static void startBack(boolean fromInventory, ItemStack stack, double x, double y) {
		startDrag(fromInventory, -1, stack, 0, stack.getCount(), x, y);
		dragBack = true;
	}

	/** Where a carried stack comes down decides what it means. Anywhere that means nothing, and it's as if you'd never picked it up. */
	private static void endDrag(double x, double y) {
		dragging = false;
		if (Math.hypot(x - pressX, y - pressY) > DRAG_START) dragMoved = true;
		if (dragBack) {
			if (!dragMoved || (dragFromInventory ? overLeft(x, y) : overRight(x, y))) unplan(dragStack, dragFromInventory);
			return;
		}
		if (!dragMoved) return;
		if (dragFromInventory) {
			Entry e = top();
			int tab = tabAt(x, y);
			if (tab >= 0) {
				e.store[dragIndex] = dragBefore;
				switchTab(tab);
				top().store[dragIndex] = Math.min(dragAmount, storable(dragIndex));
			} else if (overLeft(x, y)) {
				e.store[dragIndex] = dragAmount;
			} else if (!overRight(x, y)) {
				e.store[dragIndex] = dragBefore;
			}
		} else {
			Entry e = entry();
			if (overRight(x, y)) e.want[dragIndex] = dragAmount;
			else if (!overLeft(x, y)) e.want[dragIndex] = dragBefore;
		}
	}

	/** Forgets a planned stack: taking it, if it was on its way to you, or putting it in, if it was on its way to the box. */
	private static void unplan(ItemStack kind, boolean wasComing) {
		if (wasComing) {
			for (Entry e : entries) {
				for (int i = 0; i < e.want.length; i++) if (same(e.contents.get(i), kind)) e.want[i] = 0;
				for (Entry b : e.bundles.values()) {
					for (int i = 0; i < b.want.length; i++) if (same(b.contents.get(i), kind)) b.want[i] = 0;
				}
			}
		} else {
			Entry e = top();
			for (int i = 0; i < e.store.length; i++) if (same(inventory().getStack(i), kind)) e.store[i] = 0;
		}
	}

	private static void clickBoxCell(int index, int button, boolean shift, boolean every) {
		Entry e = entry();
		ItemStack stack = e.contents.get(index);
		if (lockedInBox(e, index)) {
			// A box inside the container: click it to go to it.
			for (int n = 0; n < entries.size(); n++) {
				if (!entries.get(n).loose && entries.get(n).slotId == index) switchTab(n);
			}
			return;
		}
		// A bundle: click goes inside it, as a box's tab would. Right-click takes it whole.
		if (bundleView == null && isFullBundle(stack)) {
			if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !shift) {
				openBundle(index);
			} else {
				e.bundles.remove(index);
				e.want[index] = e.want[index] > 0 ? 0 : stack.getCount();
			}
			return;
		}
		select(index, false);
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			// Shift-click sends just this stack across, as in any chest. Ctrl-click sends every stack of the item.
			paintOn = e.want[index] != stack.getCount();
			if (every) {
				for (int i = 0; i < e.want.length; i++) {
					if (same(e.contents.get(i), stack)) e.want[i] = paintOn ? e.contents.get(i).getCount() : 0;
				}
				return;
			}
			e.want[index] = paintOn ? stack.getCount() : 0;
			painting = true;
			paintInventory = false;
			lastPainted = index;
		} else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			int half = (stack.getCount() + 1) / 2;
			e.want[index] = e.want[index] == half ? 0 : half;
		} else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			e.want[index] = Math.min(stack.getCount(), e.want[index] + 1);
		}
	}

	private static void clickInvCell(int inv, int button, boolean every) {
		Entry e = top();
		if (bundleView != null) closeBundle();
		if (BoxGrab.isBox(inventory().getStack(inv))) {
			for (int n = 0; n < entries.size(); n++) {
				if (!entries.get(n).loose && entries.get(n).invIndex == inv) switchTab(n);
			}
			return;
		}
		select(inv, true);
		int max = storable(inv);
		ItemStack stack = inventory().getStack(inv);
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			paintOn = e.store[inv] != max;
			if (every) {
				for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
					if (same(inventory().getStack(i), stack)) e.store[i] = paintOn ? storable(i) : 0;
				}
				return;
			}
			e.store[inv] = paintOn ? max : 0;
			painting = true;
			paintInventory = true;
			lastPainted = inv;
		} else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			int half = (max + 1) / 2;
			e.store[inv] = e.store[inv] == half ? 0 : half;
		} else if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			e.store[inv] = Math.min(max, e.store[inv] + 1);
		}
	}

	private static void switchTab(int n) {
		if (bundleView != null) closeBundle();
		current = Math.max(0, Math.min(entries.size() - 1, n));
		page = 0;
		selected = -1;
		typed = "";
		note = "";
	}

	private static void act(String id, boolean primary, boolean shift, int button, double mouseX) {
		ModConfig c = ModConfig.INSTANCE;
		if (id.startsWith("KIT") && !id.equals("KITFIELD")) {
			BoxKits.Kit kit = BoxKits.kits().get(Integer.parseInt(id.substring(3)));
			if (primary && kit.name.equals(armedDelete)) {
				BoxKits.remove(kit);
				armedDelete = null;
			} else if (!primary) {
				armedDelete = kit.name;
			} else {
				armedDelete = null;
				applyKit(kit.items);
			}
			return;
		}
		armedDelete = null;
		if (id.startsWith("TAB") && id.length() > 3 && Character.isDigit(id.charAt(3))) {
			switchTab(Integer.parseInt(id.substring(3)));
			return;
		}
		if (!primary) return;
		switch (id) {
			case "ALL" -> setAll(true);
			case "NONE" -> setAll(false);
			case "CANCEL" -> cancel();
			case "TAKE" -> go();
			case "TABL" -> tabOffset = Math.max(0, tabOffset - 1);
			case "TABR" -> tabOffset++;
			case "PAGEU" -> movePage(-1);
			case "PAGED" -> movePage(1);
			case "M10" -> changeAmount(-10);
			case "M1" -> changeAmount(-1);
			case "P1" -> changeAmount(1);
			case "P10" -> changeAmount(10);
			case "HALF" -> setSelected((selectedStack().getCount() + 1) / 2);
			case "MAX" -> setSelected(selectedMax());
			case "SEARCH" -> focus = Focus.SEARCH;
			case "KITFIELD" -> focus = Focus.KIT_NAME;
			case "SAVE" -> {
				if (planHasPicks()) {
					kitName = "";
					focus = Focus.KIT_NAME;
				}
			}
			case "LAST" -> applyKit(BoxKits.last());
			case "TOPUP" -> topUp();
			case "MATCH" -> storeInto(true, shift);
			case "STOREALL" -> storeInto(false, shift);
			case "TIDY" -> top().tidy = !top().tidy;
			case "BACK" -> closeBundle();
			case "WHOLE" -> {
				int slot = bundleSlot;
				top().bundles.remove(slot);
				bundleView = null;
				top().want[slot] = top().contents.get(slot).getCount();
				page = 0;
				select(-1, false);
			}
			case "INSTANT" -> c.boxPickerInstant = !c.boxPickerInstant;
			case "GUARD" -> c.boxPickerGuard = !c.boxPickerGuard;
			case "SPEED" -> {
				draggingSpeed = true;
				setSpeedFromMouse(mouseX);
			}
			default -> {}
		}
	}

	private static void movePage(int delta) {
		int pages = (entry().contents.size() + SIZE - 1) / SIZE;
		page = Math.max(0, Math.min(pages - 1, page + delta));
	}

	public static void mouseUp(double x, double y) {
		if (dragging) endDrag(x, y);
		painting = false;
		draggingSpeed = false;
		lastPainted = -1;
	}

	/** Scrolling over a slot changes how many; hold Shift to move in tens. Over the tabs or kits, it moves along them. */
	public static void scroll(double x, double y, double amount, boolean shift) {
		if (amount == 0 || confirming) return;
		int direction = amount > 0 ? 1 : -1, step = (shift ? 10 : 1) * direction;
		if (y >= top + 6 && y < top + 26 && x >= left && x < left + LEFT_W) {
			switchTab(current - direction);
			return;
		}
		if (wide && x >= rightX && y >= top + 141 && y < top + 170) {
			kitOffset = Math.max(0, Math.min(Math.max(0, BoxKits.kits().size() - 1), kitOffset - direction));
			return;
		}
		int cell = cellAt(x, y), index = cell >= 0 ? indexOf(cell) : -1;
		if (index >= 0 && !entry().contents.get(index).isEmpty() && !lockedInBox(entry(), index) && !(bundleView == null && isFullBundle(entry().contents.get(index)))) {
			select(index, false);
			changeAmount(step);
			return;
		}
		int inv = invAt(x, y);
		if (inv >= 0 && storable(inv) > 0) {
			select(inv, true);
			changeAmount(step);
		}
	}

	public static void key(KeyInput key, KeyBinding pickKey, KeyBinding inventoryKey) {
		int code = key.key();
		boolean ctrl = (key.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0, shift = (key.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
		if (confirming) {
			if (key.isEnter() || code == GLFW.GLFW_KEY_KP_ENTER) go();
			else if (key.isEscape() || pickKey.matchesKey(key) || inventoryKey.matchesKey(key)) confirming = false;
			return;
		}
		if (focus != Focus.NONE) {
			keyInField(key, code, ctrl);
			return;
		}
		if (key.isEscape()) {
			if (bundleView != null) {
				closeBundle();
				return;
			}
			if (!search.isEmpty()) {
				search = "";
				return;
			}
			close();
			return;
		}
		if (pickKey.matchesKey(key) || inventoryKey.matchesKey(key)) {
			close();
		} else if (key.isEnter() || code == GLFW.GLFW_KEY_KP_ENTER) {
			// The first Enter finishes the number you were typing; it doesn't set anything off by surprise.
			if (!typed.isEmpty()) typed = "";
			else go();
		} else if ((ctrl && code == GLFW.GLFW_KEY_F) || code == GLFW.GLFW_KEY_SLASH) {
			if (wide) focus = Focus.SEARCH;
		} else if (code == GLFW.GLFW_KEY_TAB || code == GLFW.GLFW_KEY_RIGHT) {
			switchTab((current + (shift || code == GLFW.GLFW_KEY_LEFT ? entries.size() - 1 : 1)) % entries.size());
		} else if (code == GLFW.GLFW_KEY_LEFT) {
			switchTab((current + entries.size() - 1) % entries.size());
		} else if (code == GLFW.GLFW_KEY_PAGE_UP) {
			movePage(-1);
		} else if (code == GLFW.GLFW_KEY_PAGE_DOWN) {
			movePage(1);
		} else if (code == GLFW.GLFW_KEY_A) {
			setAll(true);
		} else if (code == GLFW.GLFW_KEY_N) {
			setAll(false);
		} else if (code == GLFW.GLFW_KEY_T) {
			top().tidy = !top().tidy;
		} else if (code >= GLFW.GLFW_KEY_0 && code <= GLFW.GLFW_KEY_9) {
			hoverSelect();
			typeDigit(code - GLFW.GLFW_KEY_0);
		} else if (code >= GLFW.GLFW_KEY_KP_0 && code <= GLFW.GLFW_KEY_KP_9) {
			hoverSelect();
			typeDigit(code - GLFW.GLFW_KEY_KP_0);
		} else if (code == GLFW.GLFW_KEY_BACKSPACE && !typed.isEmpty()) {
			typed = typed.substring(0, typed.length() - 1);
			typedAt = System.currentTimeMillis();
			applyTyped();
		}
	}

	/** Typing a number goes to whatever's under the mouse, unless you're already typing one. */
	private static void hoverSelect() {
		if (!typed.isEmpty()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		double x = client.mouse.getScaledX(client.getWindow()) / fit, y = client.mouse.getScaledY(client.getWindow()) / fit;
		int cell = cellAt(x, y), index = cell >= 0 ? indexOf(cell) : -1, inv = invAt(x, y);
		if (index >= 0 && !entry().contents.get(index).isEmpty() && !lockedInBox(entry(), index)) select(index, false);
		else if (inv >= 0 && storable(inv) > 0) select(inv, true);
	}

	private static void keyInField(KeyInput key, int code, boolean ctrl) {
		String value = focus == Focus.SEARCH ? search : kitName;
		if (key.isEscape()) {
			if (focus == Focus.SEARCH) search = "";
			focus = Focus.NONE;
			return;
		}
		if (key.isEnter() || code == GLFW.GLFW_KEY_KP_ENTER) {
			if (focus == Focus.KIT_NAME && !kitName.isBlank()) BoxKits.add(kitName.trim(), pickTotals());
			focus = Focus.NONE;
			return;
		}
		if (code == GLFW.GLFW_KEY_BACKSPACE && !value.isEmpty()) {
			value = ctrl ? "" : value.substring(0, value.length() - 1);
		}
		if (focus == Focus.SEARCH) search = value;
		else kitName = value;
	}

	/** A character typed. True if one of the text boxes here took it. */
	public static boolean typeChar(String text) {
		if (screen == null || focus == Focus.NONE) return screen != null;
		if (focus == Focus.SEARCH && search.length() < 40) search += text;
		if (focus == Focus.KIT_NAME && kitName.length() < 24) kitName += text;
		return true;
	}

	// ---- For the self-test ----

	static boolean testConfirming() {
		return confirming;
	}

	static ItemStack testPreviewInv(int i) {
		return i < previewInventory.length ? previewInventory[i] : ItemStack.EMPTY;
	}

	static ItemStack testPreviewBox(int i) {
		return i < previewBox.length ? previewBox[i] : ItemStack.EMPTY;
	}

	static int[] testCell(int i) {
		return new int[]{(int) ((cellX(i) + CELL / 2) * fit), (int) ((cellY(i) + CELL / 2) * fit)};
	}

	static int[] testInv(int i) {
		return new int[]{(int) ((invX(i) + INV_CELL / 2) * fit), (int) ((invY(i) + INV_CELL / 2) * fit)};
	}

	/** The whole panel, in real screen pixels. */
	static int[] testPanel() {
		return new int[]{(int) (left * fit), (int) (top * fit), (int) ((rightX + RIGHT_W) * fit), (int) ((top + bodyH) * fit)};
	}

	static int[] testButton(String name) {
		for (Hit h : hits) if (h.id().equals(name)) return new int[]{(int) ((h.x() + h.w() / 2) * fit), (int) ((h.y() + h.h() / 2) * fit)};
		throw new IllegalStateException("no button " + name + " on screen");
	}

	static boolean testHasButton(String name) {
		return hits.stream().anyMatch(h -> h.id().equals(name));
	}

	static int testWant(int i) {
		return entry().want[i];
	}

	static int testWantIn(int entryIndex, int i) {
		return entries.get(entryIndex).want[i];
	}

	static int testStore(int i) {
		return top().store[i];
	}

	static int testTabs() {
		return entries.size();
	}

	static int testCurrent() {
		return current;
	}

	static boolean testIsLoose() {
		return entry().loose;
	}

	static boolean testMatches(int i) {
		return matches(entry().contents.get(i));
	}

	static String testGoLabel() {
		return goLabel();
	}

	static String testNote() {
		return note;
	}

	static boolean testWide() {
		return wide;
	}

	static boolean testHasSpot() {
		return hasSpot;
	}

	static Optional<String> testProblem() {
		Text problem = problem();
		return problem == null ? Optional.empty() : Optional.of(problem.getString());
	}
}
