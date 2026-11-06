package com.greysworkshop.hoardeye;

import com.greysworkshop.hoardeye.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pick what you want out of a shulker box while it's still safe in the chest. It's drawn over the open container, so
 * the container stays open behind it and nothing changes on the server until you press Take.
 */
public final class BoxPicker {
	private static final int COLS = 9, ROWS = 3, SIZE = COLS * ROWS, CELL = 22, PAD = 10;
	private static final int PANEL_W = COLS * CELL + PAD * 2;
	private static final int BUTTON_H = 20;

	private static final int BG = 0xF4101018, EDGE = 0xFF40E0FF, TITLE = 0xFFFFC040, TEXT = 0xFFF0F0F0, MUTED = 0xFF9AA0AC;
	private static final int CELL_BG = 0xFF23232E, CELL_HOVER = 0xFF343446, PICKED_BG = 0xFF0E4A5C;
	private static final int WARN = 0xFFFFC040, BAD = 0xFFFF6060, GOOD = 0xFF60E080;

	private static HandledScreen<?> screen;
	private static int sourceSlot;
	private static boolean sourceIsInventory;
	private static int inventoryIndex;
	private static ItemStack box = ItemStack.EMPTY;
	private static final List<ItemStack> contents = new ArrayList<>();
	private static final int[] want = new int[SIZE];
	private static BlockPos container;
	private static int containerSlots;
	private static boolean fromInventoryScreen;

	private static boolean painting, paintOn;
	private static int lastPainted = -1;
	private static int left, top, height, buttonsY;
	private static int checkTimer;
	private static boolean hasSpot;

	private BoxPicker() {}

	public static boolean isOpen() {
		return screen != null;
	}

	public static boolean isOpenOn(Screen s) {
		return screen == s;
	}

	public static void close() {
		screen = null;
		painting = false;
	}

	/** Opens on the box under the mouse. False if that's not a box with something in it. */
	public static boolean tryOpen(HandledScreen<?> on) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity p = client.player;
		if (!ModConfig.INSTANCE.enabled || !ModConfig.INSTANCE.boxPicker || p == null || BoxGrab.running()) return false;
		Slot slot = ((HandledScreenAccessor) on).hoardeye$getFocusedSlot();
		if (slot == null || !BoxGrab.isBox(slot.getStack()) || !p.currentScreenHandler.getCursorStack().isEmpty()) return false;
		ContainerComponent component = slot.getStack().get(DataComponentTypes.CONTAINER);
		if (component == null || component.streamNonEmpty().findAny().isEmpty()) return false;

		screen = on;
		sourceSlot = slot.id;
		sourceIsInventory = slot.inventory instanceof PlayerInventory;
		inventoryIndex = sourceIsInventory ? slot.getIndex() : -1;
		// The armor, crafting and offhand slots can't be swapped back the same way.
		if (sourceIsInventory && inventoryIndex >= PlayerInventory.MAIN_SIZE) {
			screen = null;
			return false;
		}
		box = slot.getStack().copy();
		DefaultedList<ItemStack> list = DefaultedList.ofSize(SIZE, ItemStack.EMPTY);
		component.copyTo(list);
		contents.clear();
		contents.addAll(list);
		java.util.Arrays.fill(want, 0);
		fromInventoryScreen = on instanceof InventoryScreen;
		container = BoxGrab.openContainer(client);
		containerSlots = fromInventoryScreen ? -1 : countContainerSlots(on, p);
		painting = false;
		checkTimer = 0;
		hasSpot = !BoxGrab.spots(p, client.world).isEmpty();
		return true;
	}

	private static int countContainerSlots(HandledScreen<?> on, ClientPlayerEntity p) {
		int count = 0;
		for (Slot s : on.getScreenHandler().slots) if (s.inventory != p.getInventory()) count++;
		return count;
	}

	// ---- Layout ----

	private static void layout(int width, int screenHeight) {
		height = 34 + ROWS * CELL + 8 + 28 + 8 + 24 + BUTTON_H + PAD;
		left = (width - PANEL_W) / 2;
		top = Math.max(4, (screenHeight - height) / 2);
		buttonsY = top + height - PAD - BUTTON_H;
	}

	private static int cellX(int i) {
		return left + PAD + (i % COLS) * CELL;
	}

	private static int cellY(int i) {
		return top + 34 + (i / COLS) * CELL;
	}

	/** Which slot of the box is at a screen position, or -1. */
	private static int cellAt(double x, double y) {
		for (int i = 0; i < SIZE; i++) {
			if (x >= cellX(i) && x < cellX(i) + CELL - 1 && y >= cellY(i) && y < cellY(i) + CELL - 1) return i;
		}
		return -1;
	}

	private enum Button { ALL, NONE, CANCEL, TAKE }

	private static int[] buttonBox(Button b) {
		int inner = PANEL_W - PAD * 2;
		return switch (b) {
			case ALL -> new int[]{left + PAD, buttonsY, 26, BUTTON_H};
			case NONE -> new int[]{left + PAD + 28, buttonsY, 32, BUTTON_H};
			case CANCEL -> new int[]{left + PAD + 62, buttonsY, 42, BUTTON_H};
			case TAKE -> new int[]{left + PAD + 106, buttonsY, inner - 106, BUTTON_H};
		};
	}

	private static Button buttonAt(double x, double y) {
		for (Button b : Button.values()) {
			int[] r = buttonBox(b);
			if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) return b;
		}
		return null;
	}

	// ---- State ----

	private static int pickedStacks() {
		int n = 0;
		for (int w : want) if (w > 0) n++;
		return n;
	}

	/** Why Take can't be pressed right now, or null. */
	static Text problem() {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity p = client.player;
		if (p == null) return Text.empty();
		if (client.interactionManager != null && (!client.interactionManager.getCurrentGameMode().isSurvivalLike()
				|| client.interactionManager.getCurrentGameMode().isBlockBreakingRestricted())) {
			return Text.translatable("hoardeye.picker.survival_only");
		}
		if (!p.currentScreenHandler.getCursorStack().isEmpty()) return Text.translatable("hoardeye.picker.cursor");
		if (!hasSpot) return Text.translatable("hoardeye.picker.no_spot");
		if (!BoxGrab.fits(p.getInventory(), contents, want, inventoryIndex)) return Text.translatable("hoardeye.picker.no_room");
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

	private static boolean boxStillThere() {
		ClientPlayerEntity p = MinecraftClient.getInstance().player;
		if (p == null || screen == null || p.currentScreenHandler != screen.getScreenHandler()) return false;
		if (sourceSlot >= p.currentScreenHandler.slots.size()) return false;
		return ItemStack.areEqual(p.currentScreenHandler.slots.get(sourceSlot).getStack(), box);
	}

	// ---- Drawing ----

	public static void render(HandledScreen<?> on, DrawContext context, int mouseX, int mouseY) {
		if (screen != on) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			close();
			return;
		}
		// Someone took it, or it changed: what's listed isn't true any more.
		if (!boxStillThere()) {
			close();
			client.player.sendMessage(Text.translatable("hoardeye.picker.box_changed").formatted(Formatting.YELLOW), true);
			return;
		}
		TextRenderer font = client.textRenderer;
		context.createNewRootLayer();
		layout(on.width, on.height);
		context.fill(0, 0, on.width, on.height, 0x90000000);
		if (checkTimer-- <= 0) {
			checkTimer = 10;
			hasSpot = !BoxGrab.spots(client.player, client.world).isEmpty();
		}

		int hovered = cellAt(mouseX, mouseY);
		if (painting && hovered >= 0 && hovered != lastPainted && !contents.get(hovered).isEmpty()) {
			want[hovered] = paintOn ? contents.get(hovered).getCount() : 0;
			lastPainted = hovered;
		}

		context.fill(left, top, left + PANEL_W, top + height, BG);
		context.drawStrokedRectangle(left, top, PANEL_W, height, EDGE);

		context.drawItem(box, left + PAD, top + 7);
		String title = font.trimToWidth(box.getName().getString(), PANEL_W - PAD * 2 - 22);
		context.drawTextWithShadow(font, title, left + PAD + 20, top + 7, TITLE);
		context.drawTextWithShadow(font, Text.translatable("hoardeye.picker.hint"), left + PAD + 20, top + 18, MUTED);

		for (int i = 0; i < SIZE; i++) drawCell(context, font, i, i == hovered);

		drawSummary(context, font, top + 34 + ROWS * CELL + 4);
		drawStatus(client, context, font, buttonsY - 24);
		drawButtons(context, font, mouseX, mouseY);

		if (hovered >= 0 && !contents.get(hovered).isEmpty()) {
			ItemStack stack = contents.get(hovered);
			List<Text> lines = new ArrayList<>(Screen.getTooltipFromItem(client, stack));
			if (want[hovered] > 0) {
				lines.add(Text.translatable("hoardeye.picker.taking", want[hovered], stack.getCount()).formatted(Formatting.AQUA));
			}
			context.drawTooltip(font, lines, stack.getTooltipData(), mouseX, mouseY);
			context.drawDeferredElements();
		}
	}

	private static void drawCell(DrawContext context, TextRenderer font, int i, boolean hovered) {
		int x = cellX(i), y = cellY(i), size = CELL - 1;
		ItemStack stack = contents.get(i);
		boolean picked = want[i] > 0;
		boolean live = !stack.isEmpty();
		context.fill(x, y, x + size, y + size, picked ? PICKED_BG : hovered && live ? CELL_HOVER : CELL_BG);
		if (picked) context.drawStrokedRectangle(x, y, size, size, EDGE);
		else if (hovered && live) context.drawStrokedRectangle(x, y, size, size, 0xFFFFFFFF);
		if (!live) return;
		int ix = x + 2, iy = y + 2;
		context.drawItem(stack, ix, iy);
		if (picked && want[i] != stack.getCount()) {
			// A part of the stack: show how many, in the picked colour.
			context.drawStackOverlay(font, stack, ix, iy, "§b" + want[i]);
		} else {
			context.drawStackOverlay(font, stack, ix, iy);
		}
	}

	private static void drawSummary(DrawContext context, TextRenderer font, int y) {
		Map<String, ItemStack> kinds = new LinkedHashMap<>();
		Map<String, Integer> totals = new LinkedHashMap<>();
		for (int i = 0; i < SIZE; i++) {
			if (want[i] <= 0) continue;
			String key = Fetch.key(contents.get(i));
			kinds.putIfAbsent(key, contents.get(i));
			totals.merge(key, want[i], Integer::sum);
		}
		if (kinds.isEmpty()) {
			context.drawCenteredTextWithShadow(font, Text.translatable("hoardeye.picker.nothing"), left + PANEL_W / 2, y + 8, MUTED);
			return;
		}
		context.drawTextWithShadow(font, Text.translatable("hoardeye.picker.you_get"), left + PAD, y + 8, MUTED);
		int x = left + PAD + font.getWidth(Text.translatable("hoardeye.picker.you_get")) + 4;
		int shown = 0, room = (left + PANEL_W - PAD - x) / 18;
		for (Map.Entry<String, ItemStack> e : kinds.entrySet()) {
			if (shown == room - 1 && kinds.size() > room) {
				context.drawTextWithShadow(font, "+" + (kinds.size() - shown), x + 2, y + 8, MUTED);
				break;
			}
			context.drawItem(e.getValue(), x, y + 3);
			int total = totals.get(e.getKey());
			if (total > 1) context.drawStackOverlay(font, e.getValue(), x, y + 3, String.valueOf(total));
			x += 18;
			shown++;
		}
	}

	private static void drawStatus(MinecraftClient client, DrawContext context, TextRenderer font, int y) {
		Text problem = problem();
		int width = PANEL_W - PAD * 2;
		if (problem != null) {
			context.drawTextWithShadow(font, font.trimToWidth(problem.getString(), width), left + PAD, y, BAD);
		} else if (pickedStacks() > 0) {
			int seconds = BoxGrab.breakTicks(client.player.getInventory());
			Text ok = seconds > 40 ? Text.translatable("hoardeye.picker.no_pick") : Text.translatable("hoardeye.picker.ready");
			context.drawTextWithShadow(font, font.trimToWidth(ok.getString(), width), left + PAD, y, seconds > 40 ? WARN : GOOD);
		} else {
			context.drawTextWithShadow(font, Text.translatable("hoardeye.picker.safe"), left + PAD, y, GOOD);
		}
		List<String> near = nearbyPlayers(client);
		Text players = near.isEmpty() ? Text.translatable("hoardeye.picker.nobody")
				: Text.translatable("hoardeye.picker.players", near.size(), String.join(", ", near));
		context.drawTextWithShadow(font, font.trimToWidth(players.getString(), width), left + PAD, y + 11, near.isEmpty() ? MUTED : WARN);
	}

	private static void drawButtons(DrawContext context, TextRenderer font, int mouseX, int mouseY) {
		int stacks = pickedStacks();
		boolean canTake = stacks > 0 && problem() == null;
		for (Button b : Button.values()) {
			int[] r = buttonBox(b);
			boolean hover = buttonAt(mouseX, mouseY) == b;
			String label = switch (b) {
				case ALL -> Text.translatable("hoardeye.picker.all").getString();
				case NONE -> Text.translatable("hoardeye.picker.none").getString();
				case CANCEL -> Text.translatable("hoardeye.picker.cancel").getString();
				case TAKE -> Text.translatable(stacks == 1 ? "hoardeye.picker.take_one" : "hoardeye.picker.take", stacks).getString();
			};
			int bg, edge, color;
			if (b == Button.TAKE) {
				bg = canTake ? (hover ? 0xFF1E8FB0 : 0xFF14708C) : 0xFF23232E;
				edge = canTake ? EDGE : 0xFF44444F;
				color = canTake ? 0xFFFFFFFF : 0xFF777780;
			} else {
				bg = hover ? 0xFF3C3C50 : 0xFF2A2A38;
				edge = 0xFF55555F;
				color = TEXT;
			}
			context.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], bg);
			context.drawStrokedRectangle(r[0], r[1], r[2], r[3], edge);
			context.drawCenteredTextWithShadow(font, label, r[0] + r[2] / 2, r[1] + 6, color);
		}
	}

	// ---- Input ----

	/** The mouse went down over an open picker. Everything is swallowed, so nothing reaches the chest behind it. */
	public static void mouseDown(Click click) {
		int button = click.button();
		Button b = buttonAt(click.x(), click.y());
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && b != null) {
			switch (b) {
				case ALL -> setAll(true);
				case NONE -> setAll(false);
				case CANCEL -> close();
				case TAKE -> confirm();
			}
			return;
		}
		int cell = cellAt(click.x(), click.y());
		if (cell < 0 || contents.get(cell).isEmpty()) return;
		ItemStack stack = contents.get(cell);
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			paintOn = want[cell] != stack.getCount();
			if (click.hasShift()) {
				// Every stack of this item at once.
				for (int i = 0; i < SIZE; i++) {
					if (ItemStack.areItemsAndComponentsEqual(contents.get(i), stack)) want[i] = paintOn ? contents.get(i).getCount() : 0;
				}
				return;
			}
			want[cell] = paintOn ? stack.getCount() : 0;
			painting = true;
			lastPainted = cell;
		} else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			int half = (stack.getCount() + 1) / 2;
			want[cell] = want[cell] == half ? 0 : half;
		}
	}

	public static void mouseUp() {
		painting = false;
		lastPainted = -1;
	}

	/** Scrolling over a slot sets how many to take from it; hold Shift to move in tens. */
	public static void scroll(double x, double y, double amount, boolean shift) {
		int cell = cellAt(x, y);
		if (cell < 0 || contents.get(cell).isEmpty() || amount == 0) return;
		int step = (shift ? 10 : 1) * (amount > 0 ? 1 : -1);
		want[cell] = Math.max(0, Math.min(contents.get(cell).getCount(), want[cell] + step));
	}

	public static void key(KeyInput key, KeyBinding pickKey, KeyBinding inventoryKey) {
		if (key.isEscape() || pickKey.matchesKey(key) || inventoryKey.matchesKey(key)) {
			close();
		} else if (key.isEnter() || key.key() == GLFW.GLFW_KEY_KP_ENTER) {
			confirm();
		} else if (key.key() == GLFW.GLFW_KEY_A) {
			setAll(true);
		} else if (key.key() == GLFW.GLFW_KEY_N) {
			setAll(false);
		}
	}

	private static void setAll(boolean on) {
		for (int i = 0; i < SIZE; i++) want[i] = on ? contents.get(i).getCount() : 0;
	}

	private static void confirm() {
		if (pickedStacks() == 0 || problem() != null || !boxStillThere()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		BoxGrab.Request request = new BoxGrab.Request(sourceSlot, sourceIsInventory, inventoryIndex, box, new ArrayList<>(contents), want.clone(),
				container, containerSlots, fromInventoryScreen);
		close();
		BoxGrab.start(client, request);
	}

	// ---- For the self-test ----

	static int[] testCell(int i) {
		return new int[]{cellX(i) + CELL / 2, cellY(i) + CELL / 2};
	}

	static int[] testButton(String name) {
		int[] r = buttonBox(Button.valueOf(name));
		return new int[]{r[0] + r[2] / 2, r[1] + r[3] / 2};
	}

	static int testWant(int i) {
		return want[i];
	}

	static boolean testHasSpot() {
		return hasSpot;
	}

	static Optional<String> testProblem() {
		Text problem = problem();
		return problem == null ? Optional.empty() : Optional.of(problem.getString());
	}
}
