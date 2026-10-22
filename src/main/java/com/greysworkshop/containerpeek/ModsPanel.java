package com.greysworkshop.containerpeek;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ObjectShare;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The mods panel: Right Shift (or the button at the top left of the title screen) on the title screen, the server and
 * world lists and the pause menu opens one screen with the main switches of every PlayWit mod installed, and a way
 * into each one's full settings, so something can be turned off before joining a world.
 * <p>
 * Each of the mods carries this same class and adds itself through Fabric's object share; the newest copy draws the
 * panel, so it's there once however many of the mods are installed. Only Java and Minecraft types are shared: the list
 * under {@link #MODS} holds a map per mod with "id", "name", "version" (strings), "icon" (Identifier), "settings"
 * (Function&lt;Screen, Screen&gt;: the full settings, given the screen to go back to), "save" (Runnable) and "switches"
 * (a List of maps: "section" alone for a heading, or "label" and "tip" (String or Text), "get" (BooleanSupplier), "set"
 * (Consumer&lt;Boolean&gt;) and, for the one that turns the whole mod off, "master" true).
 */
public final class ModsPanel extends Screen {
	/** Raised when the panel changes, so the newest copy among the installed mods is the one that draws it. */
	private static final int VERSION = 1;
	private static final String MODS = "playwit:mods_panel_entries";
	private static final String OWNER = "playwit:mods_panel_owner";
	private static final String ME = VERSION + ":" + ContainerPeekClient.MOD_ID;

	private static final int PANEL = 0xF2141920, EDGE = 0xFF2C343E, SIDE = 0xFF1A2028, PICKED = 0xFF27313C, HOVER = 0xFF1F2730,
			ACCENT = 0xFFFFA41C, TEXT = 0xFFE7E9EC, GREY = 0xFF9AA4AE, DIM = 0xFF5E6873, ON = 0xFF2EA043, OFF = 0xFF4A525C;
	private static final int HEADER = 28, SIDEBAR = 150, MOD_ROW = 34, FOOTER = 30;

	/** The mod last looked at, so the panel opens where it was left. */
	private static String lastId;

	private final Screen parent;
	private final List<Map<String, Object>> mods;
	private int picked;
	private double scroll;
	private int maxScroll;
	private final List<Hit> hits = new ArrayList<>();

	private record Hit(int x0, int y0, int x1, int y1, Runnable run) {
		boolean has(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	private ModsPanel(Screen parent) {
		super(Text.literal("Mods panel"));
		this.parent = parent;
		this.mods = entries();
		for (int i = 0; i < mods.size(); i++) {
			if (mods.get(i).get("id").equals(lastId)) picked = i;
		}
	}

	/** For the development test. */
	static ModsPanel open(Screen parent) {
		return new ModsPanel(parent);
	}

	int testMods() {
		return mods.size();
	}

	String testPicked() {
		return String.valueOf(mods.get(picked).get("id"));
	}

	void testPick(String id) {
		for (int i = 0; i < mods.size(); i++) {
			if (mods.get(i).get("id").equals(id)) pick(i);
		}
	}

	/** Flips the switch labelled {@code label} (or with that translation key) in the picked mod. */
	boolean testFlip(String label) {
		Map<String, Object> mod = mods.get(picked);
		for (Object o : list(mod.get("switches"))) {
			if (o instanceof Map<?, ?> row && row.get("label") != null && (label.equals(String.valueOf(row.get("label")))
					|| row.get("label") instanceof Text t && t.getContent() instanceof net.minecraft.text.TranslatableTextContent tr && tr.getKey().endsWith("." + label))) {
				@SuppressWarnings("unchecked") Consumer<Boolean> set = (Consumer<Boolean>) row.get("set");
				flip(mod, (BooleanSupplier) row.get("get"), set);
				return true;
			}
		}
		return false;
	}

	void testOpenSettings() {
		openSettings(mods.get(picked));
	}

	// ---- Sharing between the mods ----

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> entries() {
		ObjectShare share = FabricLoader.getInstance().getObjectShare();
		share.putIfAbsent(MODS, new CopyOnWriteArrayList<Map<String, Object>>());
		return (List<Map<String, Object>>) share.get(MODS);
	}

	private static int version(Object owner) {
		if (!(owner instanceof String s) || s.indexOf(':') < 0) return -1;
		try {
			return Integer.parseInt(s.substring(0, s.indexOf(':')));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static boolean owned() {
		return ME.equals(FabricLoader.getInstance().getObjectShare().get(OWNER));
	}

	/** Adds this mod to the panel, and offers to draw the panel (the newest copy does). */
	public static void install(Map<String, Object> entry) {
		ObjectShare share = FabricLoader.getInstance().getObjectShare();
		entries().add(entry);
		if (version(share.get(OWNER)) < VERSION) share.put(OWNER, ME);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!owned() || !(screen instanceof TitleScreen || screen instanceof MultiplayerScreen || screen instanceof SelectWorldScreen
					|| screen instanceof GameMenuScreen)) return;
			if (screen instanceof TitleScreen) {
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Mods panel"), b -> client.setScreen(new ModsPanel(screen)))
						.dimensions(4, 4, 80, 20).tooltip(Tooltip.of(Text.literal("Turn your mods' features on or off before you join a world. Right Shift opens it too.")))
						.build());
			}
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> {
				// Not while typing (Right Shift makes capitals in the world search box).
				if (key.key() != GLFW.GLFW_KEY_RIGHT_SHIFT || s.getFocused() instanceof TextFieldWidget) return true;
				client.setScreen(new ModsPanel(s));
				return false;
			});
		});
	}

	/** A heading in a mod's list of switches. */
	public static Map<String, Object> section(String title) {
		return Map.of("section", title);
	}

	public static Map<String, Object> toggle(Object label, Object tip, BooleanSupplier get, Consumer<Boolean> set) {
		return Map.of("label", label, "tip", tip, "get", get, "set", set);
	}

	// ---- Drawing ----

	private static Text text(Object o) {
		return o instanceof Text t ? t : Text.literal(String.valueOf(o));
	}

	private int panelW() {
		return Math.min(560, width - 32);
	}

	private int panelH() {
		return Math.min(360, height - 32);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		int w = panelW(), h = panelH(), x = (width - w) / 2, y = (height - h) / 2;
		context.fill(x - 1, y - 1, x + w + 1, y + h + 1, EDGE);
		context.fill(x, y, x + w, y + h, PANEL);

		// Header.
		context.drawText(textRenderer, Text.literal("Mods panel").styled(s -> s.withBold(true)), x + 10, y + 10, TEXT, false);
		String hint = "Right Shift or Esc to close";
		boolean overClose = mouseX >= x + w - 22 && mouseX < x + w - 4 && mouseY >= y + 5 && mouseY < y + 23;
		context.drawText(textRenderer, hint, x + w - 28 - textRenderer.getWidth(hint), y + 10, GREY, false);
		context.drawText(textRenderer, "×", x + w - 16, y + 10, overClose ? TEXT : GREY, false);
		hits.add(new Hit(x + w - 22, y + 5, x + w - 4, y + 23, this::close));
		context.fill(x, y + HEADER, x + w, y + HEADER + 1, EDGE);

		if (mods.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, "No PlayWit mods to show.", x + w / 2, y + h / 2, GREY);
			return;
		}

		// The mods.
		int top = y + HEADER + 1;
		context.fill(x, top, x + SIDEBAR, y + h, SIDE);
		context.fill(x + SIDEBAR, top, x + SIDEBAR + 1, y + h, EDGE);
		for (int i = 0; i < mods.size(); i++) {
			Map<String, Object> mod = mods.get(i);
			int ry = top + 4 + i * MOD_ROW;
			if (ry + MOD_ROW > y + h) break;
			boolean on = i == picked, over = mouseX >= x && mouseX < x + SIDEBAR && mouseY >= ry && mouseY < ry + MOD_ROW;
			if (on || over) context.fill(x, ry, x + SIDEBAR, ry + MOD_ROW, on ? PICKED : HOVER);
			if (on) context.fill(x, ry, x + 2, ry + MOD_ROW, ACCENT);
			if (mod.get("icon") instanceof Identifier icon) context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, x + 8, ry + 5, 0, 0, 24, 24, 24, 24);
			boolean off = !masterOn(mod);
			context.drawText(textRenderer, textRenderer.trimToWidth(String.valueOf(mod.get("name")), SIDEBAR - 44), x + 38, ry + 7, off ? GREY : TEXT, false);
			context.drawText(textRenderer, off ? "Turned off" : "v" + mod.get("version"), x + 38, ry + 18, off ? 0xFFFF7A6E : DIM, false);
			int index = i;
			hits.add(new Hit(x, ry, x + SIDEBAR, ry + MOD_ROW, () -> pick(index)));
		}

		// Its switches.
		Map<String, Object> mod = mods.get(picked);
		int lx = x + SIDEBAR + 1, lw = w - SIDEBAR - 1, listTop = top, listBottom = y + h - FOOTER;
		int textW = lw - 20 - 40;
		context.enableScissor(lx, listTop, lx + lw, listBottom);
		int ry = listTop + 6 - (int) scroll;
		boolean masterOff = false;
		for (Object o : list(mod.get("switches"))) {
			if (!(o instanceof Map<?, ?> row)) continue;
			if (row.get("section") != null) {
				ry += 6;
				context.drawText(textRenderer, String.valueOf(row.get("section")).toUpperCase(java.util.Locale.ROOT), lx + 10, ry, ACCENT, false);
				ry += 13;
				continue;
			}
			if (!(row.get("get") instanceof BooleanSupplier get) || !(row.get("set") instanceof Consumer<?> set)) continue;
			List<OrderedText> tip = row.get("tip") == null ? List.of() : textRenderer.wrapLines(text(row.get("tip")), textW);
			int rh = 8 + 10 + tip.size() * 10 + 6;
			boolean value = get.getAsBoolean(), dim = masterOff && !Boolean.TRUE.equals(row.get("master"));
			boolean over = mouseX >= lx && mouseX < lx + lw && mouseY >= Math.max(ry, listTop) && mouseY < Math.min(ry + rh, listBottom);
			if (over) context.fill(lx + 4, ry, lx + lw - 4, ry + rh, HOVER);
			context.drawText(textRenderer, text(row.get("label")), lx + 10, ry + 8, dim ? GREY : TEXT, false);
			int ty = ry + 19;
			for (OrderedText line : tip) {
				context.drawText(textRenderer, line, lx + 10, ty, dim ? DIM : GREY, false);
				ty += 10;
			}
			drawSwitch(context, lx + lw - 38, ry + 8, value);
			if (ry + rh > listTop && ry < listBottom) {
				@SuppressWarnings("unchecked") Consumer<Boolean> setter = (Consumer<Boolean>) set;
				hits.add(new Hit(lx, Math.max(ry, listTop), lx + lw, Math.min(ry + rh, listBottom), () -> flip(mod, get, setter)));
			}
			if (Boolean.TRUE.equals(row.get("master")) && !value) masterOff = true;
			ry += rh;
		}
		context.disableScissor();
		maxScroll = Math.max(0, ry + (int) scroll - listBottom + 6);
		if (maxScroll > 0) {
			int track = listBottom - listTop, bar = Math.max(20, track * track / (track + maxScroll));
			int by = listTop + (int) ((track - bar) * (scroll / maxScroll));
			context.fill(lx + lw - 3, by, lx + lw - 1, by + bar, EDGE);
		}

		// Footer: the full settings.
		context.fill(lx, listBottom, lx + lw, listBottom + 1, EDGE);
		String all = "All " + mod.get("name") + " settings  ›", saved = "Saved as you click";
		if (textRenderer.getWidth(all) + 16 > lw - 16) all = "All settings  ›";
		int bw = textRenderer.getWidth(all) + 16, bx = lx + lw - bw - 8, by = listBottom + 6;
		boolean overAll = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 18;
		context.fill(bx, by, bx + bw, by + 18, overAll ? 0xFFFFB84D : ACCENT);
		context.drawText(textRenderer, all, bx + 8, by + 5, 0xFF0F1111, false);
		hits.add(new Hit(bx, by, bx + bw, by + 18, () -> openSettings(mod)));
		if (lx + 10 + textRenderer.getWidth(saved) + 8 <= bx) context.drawText(textRenderer, saved, lx + 10, by + 5, DIM, false);
	}

	private static void drawSwitch(DrawContext context, int x, int y, boolean on) {
		context.fill(x, y, x + 26, y + 12, on ? ON : OFF);
		int k = on ? x + 15 : x + 1;
		context.fill(k, y + 1, k + 10, y + 11, 0xFFF4F6F8);
	}

	private static List<?> list(Object o) {
		return o instanceof List<?> l ? l : List.of();
	}

	private static boolean masterOn(Map<String, Object> mod) {
		for (Object o : list(mod.get("switches"))) {
			if (o instanceof Map<?, ?> row && Boolean.TRUE.equals(row.get("master")) && row.get("get") instanceof BooleanSupplier get) return get.getAsBoolean();
		}
		return true;
	}

	// ---- Input ----

	private void click() {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1f));
	}

	private void pick(int index) {
		if (index == picked) return;
		picked = index;
		scroll = 0;
		lastId = String.valueOf(mods.get(index).get("id"));
		click();
	}

	private void flip(Map<String, Object> mod, BooleanSupplier get, Consumer<Boolean> set) {
		set.accept(!get.getAsBoolean());
		if (mod.get("save") instanceof Runnable save) save.run();
		click();
	}

	@SuppressWarnings("unchecked")
	private void openSettings(Map<String, Object> mod) {
		if (!(mod.get("settings") instanceof Function<?, ?> f)) return;
		click();
		client.setScreen(((Function<Screen, Screen>) f).apply(this));
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 0) {
			for (Hit hit : List.copyOf(hits)) {
				if (hit.has(click.x(), click.y())) {
					hit.run.run();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, Math.min(maxScroll, scroll - verticalAmount * 20));
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			close();
			return true;
		}
		if (!mods.isEmpty() && (input.key() == GLFW.GLFW_KEY_UP || input.key() == GLFW.GLFW_KEY_DOWN)) {
			pick(Math.floorMod(picked + (input.key() == GLFW.GLFW_KEY_UP ? -1 : 1), mods.size()));
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return parent instanceof GameMenuScreen;
	}
}
