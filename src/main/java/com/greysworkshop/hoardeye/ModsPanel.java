package com.greysworkshop.hoardeye;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
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
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The mods panel: Right Shift (or the button at the top left of the title screen) on the title screen, the server and
 * world lists and the pause menu opens one screen with the main switches of every PlayWit mod installed, and a way
 * into each one's full settings, so something can be turned off before joining a world. Also: profiles (switches saved
 * under a name, set by themselves when you join the servers you pick), backups of everything the mods keep, and
 * stream-safe mode (a key that hides coordinates in all the mods while you record or stream).
 * <p>
 * Each of the mods carries this same class and adds itself through Fabric's object share; the newest copy draws the
 * panel, so it's there once however many of the mods are installed. Only Java and Minecraft types are shared: the list
 * under {@link #MODS} holds a map per mod with "id", "name", "version" (strings), "icon" (Identifier), "settings"
 * (Function&lt;Screen, Screen&gt;: the full settings, given the screen to go back to), "save" (Runnable), "data" (a List of
 * the files and folders it keeps, relative to the config folder) and "switches" (a List of maps: "section" alone for
 * a heading, or "label" and "tip" (String or Text), "get" (BooleanSupplier), "set" (Consumer&lt;Boolean&gt;) and, for the
 * one that turns the whole mod off, "master" true). Stream-safe mode is a Boolean under {@link #STREAM_SAFE}.
 */
public final class ModsPanel extends Screen {
	/** Raised when the panel changes, so the newest copy among the installed mods is the one that draws it. */
	private static final int VERSION = 2;
	private static final String MODS = "playwit:mods_panel_entries";
	private static final String OWNER = "playwit:mods_panel_owner";
	private static final String STREAM_SAFE = "playwit:stream_safe";
	private static final String KEY_OWNER = "playwit:stream_safe_key_owner";
	private static final String ME = VERSION + ":" + HoardEyeClient.MOD_ID;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final int PANEL = 0xF2141920, EDGE = 0xFF2C343E, SIDE = 0xFF1A2028, PICKED = 0xFF27313C, HOVER = 0xFF1F2730,
			ACCENT = 0xFFFFA41C, TEXT = 0xFFE7E9EC, GREY = 0xFF9AA4AE, DIM = 0xFF5E6873, ON = 0xFF2EA043, OFF = 0xFF4A525C,
			RED = 0xFFFF7A6E, BUTTON = 0xFF2A333E, BUTTON_HOVER = 0xFF36414E;
	private static final int HEADER = 28, SIDEBAR = 150, FOOTER = 30;

	/** The page last looked at (a mod's id, "profiles" or "backups"), so the panel opens where it was left. */
	private static String lastPage;

	private final Screen parent;
	private final List<Map<String, Object>> mods;
	/** 0.. for the mods, then the profiles page, then the backups page. */
	private int picked;
	private double scroll;
	private int maxScroll;
	private final List<Hit> hits = new ArrayList<>();
	private TextFieldWidget nameField;
	private String status;
	private int statusColor = GREY;
	/** The profile whose servers are being picked. */
	private String openProfile;
	/** Something waiting for a second click to confirm ("delete:Name", "restore:file"). */
	private String confirm;
	private List<Path> backupList;
	private volatile boolean backingUp;

	private record Hit(int x0, int y0, int x1, int y1, Runnable run) {
		boolean has(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	/** A named set of switch positions, and the servers it's for. */
	static final class Profile {
		String name = "";
		List<String> servers = new ArrayList<>();
		/** "modid/switch" to on or off. */
		Map<String, Boolean> switches = new LinkedHashMap<>();
	}

	private ModsPanel(Screen parent) {
		super(Text.literal("Mods panel"));
		this.parent = parent;
		this.mods = entries();
		for (int i = 0; i < pages(); i++) {
			if (pageId(i).equals(lastPage)) picked = i;
		}
	}

	// ---- For the development test ----

	static ModsPanel open(Screen parent) {
		return new ModsPanel(parent);
	}

	int testMods() {
		return mods.size();
	}

	String testPicked() {
		return pageId(picked);
	}

	void testPick(String id) {
		for (int i = 0; i < pages(); i++) {
			if (pageId(i).equals(id)) pick(i);
		}
	}

	/** Flips the switch labelled {@code label} (or with that translation key) in the picked mod. */
	boolean testFlip(String label) {
		Map<String, Object> mod = mods.get(picked);
		for (Object o : list(mod.get("switches"))) {
			if (o instanceof Map<?, ?> row && row.get("label") != null && (label.equals(String.valueOf(row.get("label")))
					|| row.get("label") instanceof Text t && t.getContent() instanceof TranslatableTextContent tr && tr.getKey().endsWith("." + label))) {
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
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (!owned()) return;
			try {
				applyForServer(client);
			} catch (RuntimeException e) {
				HoardEyeClient.LOGGER.error("Could not apply the mod profile", e);
			}
		}));
		// Stream-safe mode: one key for all the mods, set up by whichever of them starts first.
		share.putIfAbsent(KEY_OWNER, ME);
		if (ME.equals(share.get(KEY_OWNER))) {
			share.put(STREAM_SAFE, Files.exists(streamSafeFile()));
			KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.playwit.stream_safe", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7,
					KeyBinding.Category.create(Identifier.of("playwit", "mods"))));
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				while (key.wasPressed()) {
					setStreamSafe(!streamSafe());
					if (client.player != null) client.inGameHud.setOverlayMessage(Text.literal(streamSafe() ? "Stream-safe on: coordinates hidden"
							: "Stream-safe off").formatted(streamSafe() ? Formatting.GREEN : Formatting.YELLOW), false);
				}
			});
			HudElementRegistry.addLast(Identifier.of("playwit", "stream_safe"), (context, tickCounter) -> {
				MinecraftClient client = MinecraftClient.getInstance();
				if (!streamSafe() || client.options.hudHidden) return;
				String label = "● stream-safe";
				context.drawText(client.textRenderer, label, (context.getScaledWindowWidth() - client.textRenderer.getWidth(label)) / 2, 2, 0x8866DD88, false);
			});
		}
	}

	/** A heading in a mod's list of switches. */
	public static Map<String, Object> section(String title) {
		return Map.of("section", title);
	}

	public static Map<String, Object> toggle(Object label, Object tip, BooleanSupplier get, Consumer<Boolean> set) {
		return Map.of("label", label, "tip", tip, "get", get, "set", set);
	}

	// ---- Stream-safe mode ----

	/** Whether coordinates should be hidden right now (stream-safe mode is on). */
	public static boolean streamSafe() {
		return Boolean.TRUE.equals(FabricLoader.getInstance().getObjectShare().get(STREAM_SAFE));
	}

	/** {@code coords} as they are, or "hidden" while stream-safe mode is on. */
	public static String hide(String coords) {
		return streamSafe() ? "hidden" : coords;
	}

	private static Path streamSafeFile() {
		return PanelData.dir().resolve("stream-safe");
	}

	private static Boolean reducedBefore;

	static void setStreamSafe(boolean on) {
		FabricLoader.getInstance().getObjectShare().put(STREAM_SAFE, on);
		try {
			if (on) {
				Files.createDirectories(PanelData.dir());
				Files.writeString(streamSafeFile(), "Stream-safe mode is on (delete this file or press the key to turn it off).");
			} else {
				Files.deleteIfExists(streamSafeFile());
			}
		} catch (IOException e) {
			HoardEyeClient.LOGGER.warn("Could not save stream-safe mode", e);
		}
		// The debug screen (F3) shows coordinates too: the game's own "reduced debug info" hides them.
		var reduced = MinecraftClient.getInstance().options.getReducedDebugInfo();
		if (on && reducedBefore == null) {
			reducedBefore = reduced.getValue();
			reduced.setValue(true);
		} else if (!on && reducedBefore != null) {
			reduced.setValue(reducedBefore);
			reducedBefore = null;
		}
	}

	// ---- Profiles ----

	private static Path profilesFile() {
		return PanelData.dir().resolve("profiles.json");
	}

	static List<Profile> loadProfiles() {
		try {
			if (!Files.exists(profilesFile())) return new ArrayList<>();
			List<Profile> list = GSON.fromJson(Files.readString(profilesFile()), new TypeToken<List<Profile>>() {}.getType());
			return list == null ? new ArrayList<>() : new ArrayList<>(list);
		} catch (IOException | RuntimeException e) {
			HoardEyeClient.LOGGER.warn("Could not read the mod profiles", e);
			return new ArrayList<>();
		}
	}

	static void saveProfiles(List<Profile> profiles) {
		try {
			Files.createDirectories(PanelData.dir());
			Files.writeString(profilesFile(), GSON.toJson(profiles));
		} catch (IOException e) {
			HoardEyeClient.LOGGER.warn("Could not save the mod profiles", e);
		}
	}

	/** A switch's lasting name: its translation key, else its label. */
	private static String key(Object modId, Map<?, ?> row) {
		Object label = row.get("label");
		String k = label instanceof Text t && t.getContent() instanceof TranslatableTextContent tr ? tr.getKey()
				: label instanceof Text t ? t.getString() : String.valueOf(label);
		return modId + "/" + k;
	}

	/** Every switch of every mod, as it is now. */
	static Map<String, Boolean> snapshot() {
		Map<String, Boolean> out = new LinkedHashMap<>();
		for (Map<String, Object> mod : entries()) {
			for (Object o : list(mod.get("switches"))) {
				if (o instanceof Map<?, ?> row && row.get("get") instanceof BooleanSupplier get) out.put(key(mod.get("id"), row), get.getAsBoolean());
			}
		}
		return out;
	}

	/** Sets every switch the profile has; returns how many changed. */
	@SuppressWarnings("unchecked")
	static int apply(Profile profile) {
		int changed = 0;
		for (Map<String, Object> mod : entries()) {
			boolean any = false;
			for (Object o : list(mod.get("switches"))) {
				if (!(o instanceof Map<?, ?> row) || !(row.get("get") instanceof BooleanSupplier get) || !(row.get("set") instanceof Consumer<?> set)) continue;
				Boolean want = profile.switches.get(key(mod.get("id"), row));
				if (want == null || want == get.getAsBoolean()) continue;
				((Consumer<Boolean>) set).accept(want);
				any = true;
				changed++;
			}
			if (any && mod.get("save") instanceof Runnable save) save.run();
		}
		return changed;
	}

	/** "Play.Example.net:25565" and "play.example.net" are the same server. */
	static String server(String address) {
		String a = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
		return a.endsWith(":25565") ? a.substring(0, a.length() - 6) : a;
	}

	private static void applyForServer(MinecraftClient client) {
		ServerInfo entry = client.getCurrentServerEntry();
		if (entry == null || entry.isLocal()) return;
		String here = server(entry.address);
		for (Profile p : loadProfiles()) {
			if (p.servers.stream().map(ModsPanel::server).noneMatch(here::equals)) continue;
			int changed = apply(p);
			client.inGameHud.getChatHud().addMessage(Text.literal("[Mods panel] ").formatted(Formatting.GOLD)
					.append(Text.literal("Profile \"" + p.name + "\" for this server: "
							+ (changed == 0 ? "everything was already set." : changed + (changed == 1 ? " switch" : " switches") + " changed."))
							.formatted(Formatting.GRAY)));
			return;
		}
	}

	/** The servers in your multiplayer list: name and address. */
	private List<String[]> savedServers() {
		List<String[]> out = new ArrayList<>();
		try {
			ServerList list = new ServerList(client);
			list.loadFile();
			for (int i = 0; i < list.size(); i++) out.add(new String[] {list.get(i).name, list.get(i).address});
		} catch (RuntimeException e) {
			HoardEyeClient.LOGGER.warn("Could not read the server list", e);
		}
		ServerInfo current = client.getCurrentServerEntry();
		if (current != null && !current.isLocal() && out.stream().noneMatch(s -> server(s[1]).equals(server(current.address)))) {
			out.add(0, new String[] {current.name, current.address});
		}
		return out;
	}

	// ---- Pages ----

	private int pages() {
		return mods.size() + 2;
	}

	private int profilesPage() {
		return mods.size();
	}

	private int backupsPage() {
		return mods.size() + 1;
	}

	private String pageId(int i) {
		return i < mods.size() ? String.valueOf(mods.get(i).get("id")) : i == profilesPage() ? "profiles" : "backups";
	}

	@Override
	protected void init() {
		String typed = nameField == null ? "" : nameField.getText();
		nameField = new TextFieldWidget(textRenderer, 0, 0, 120, 18, Text.literal("Profile name"));
		nameField.setMaxLength(32);
		nameField.setPlaceholder(Text.literal("Name, e.g. Anarchy").formatted(Formatting.DARK_GRAY));
		nameField.setText(typed);
		addSelectableChild(nameField);
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

	/** A small button; returns its width. */
	private int button(DrawContext context, String label, int x, int y, int mouseX, int mouseY, int top, int bottom, boolean primary, Runnable run) {
		int w = textRenderer.getWidth(label) + 12;
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 16 && mouseY >= top && mouseY < bottom;
		context.fill(x, y, x + w, y + 16, primary ? (over ? 0xFFFFB84D : ACCENT) : over ? BUTTON_HOVER : BUTTON);
		context.drawText(textRenderer, label, x + 6, y + 4, primary ? 0xFF0F1111 : TEXT, false);
		if (y + 16 > top && y < bottom) hits.add(new Hit(x, Math.max(y, top), x + w, Math.min(y + 16, bottom), run));
		return w;
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
		if (textRenderer.getWidth(hint) + 120 < w) context.drawText(textRenderer, hint, x + w - 28 - textRenderer.getWidth(hint), y + 10, GREY, false);
		context.drawText(textRenderer, "×", x + w - 16, y + 10, overClose ? TEXT : GREY, false);
		hits.add(new Hit(x + w - 22, y + 5, x + w - 4, y + 23, this::close));
		context.fill(x, y + HEADER, x + w, y + HEADER + 1, EDGE);

		// The pages down the side: each mod, then profiles and backups; stream-safe mode at the bottom.
		int top = y + HEADER + 1, streamRow = 26;
		context.fill(x, top, x + SIDEBAR, y + h, SIDE);
		context.fill(x + SIDEBAR, top, x + SIDEBAR + 1, y + h, EDGE);
		int rowH = Math.max(20, Math.min(34, (h - HEADER - 1 - streamRow - 10) / pages()));
		for (int i = 0; i < pages(); i++) {
			int ry = top + 4 + i * rowH + (i >= mods.size() ? 4 : 0);
			if (i == mods.size()) context.fill(x + 8, ry - 4, x + SIDEBAR - 8, ry - 3, EDGE);
			boolean on = i == picked, over = mouseX >= x && mouseX < x + SIDEBAR && mouseY >= ry && mouseY < ry + rowH;
			if (on || over) context.fill(x, ry, x + SIDEBAR, ry + rowH, on ? PICKED : HOVER);
			if (on) context.fill(x, ry, x + 2, ry + rowH, ACCENT);
			int icon = Math.min(24, rowH - 6), iy = ry + (rowH - icon) / 2;
			if (i < mods.size()) {
				Map<String, Object> mod = mods.get(i);
				if (mod.get("icon") instanceof Identifier id) context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x + 8, iy, 0, 0, icon, icon, icon, icon);
				boolean off = !masterOn(mod);
				String name = textRenderer.trimToWidth(String.valueOf(mod.get("name")), SIDEBAR - 44);
				if (rowH >= 28) {
					context.drawText(textRenderer, name, x + 38, ry + rowH / 2 - 10, off ? GREY : TEXT, false);
					context.drawText(textRenderer, off ? "Turned off" : "v" + mod.get("version"), x + 38, ry + rowH / 2 + 1, off ? RED : DIM, false);
				} else {
					context.drawText(textRenderer, name, x + 38, ry + rowH / 2 - 4, off ? RED : TEXT, false);
				}
			} else {
				boolean profiles = i == profilesPage();
				context.fill(x + 8, iy, x + 8 + icon, iy + icon, BUTTON);
				context.drawCenteredTextWithShadow(textRenderer, profiles ? "☰" : "⟲", x + 8 + icon / 2, iy + icon / 2 - 4, ACCENT);
				context.drawText(textRenderer, profiles ? "Profiles" : "Backups", x + 38, ry + rowH / 2 - 4, TEXT, false);
			}
			int index = i;
			hits.add(new Hit(x, ry, x + SIDEBAR, ry + rowH, () -> pick(index)));
		}
		int sy = y + h - streamRow;
		context.fill(x, sy, x + SIDEBAR, sy + 1, EDGE);
		boolean safe = streamSafe(), overSafe = mouseX >= x && mouseX < x + SIDEBAR && mouseY >= sy && mouseY < y + h;
		if (overSafe) context.fill(x, sy + 1, x + SIDEBAR, y + h, HOVER);
		context.drawText(textRenderer, "Stream-safe", x + 10, sy + 9, safe ? TEXT : GREY, false);
		drawSwitch(context, x + SIDEBAR - 36, sy + 7, safe);
		hits.add(new Hit(x, sy, x + SIDEBAR, y + h, () -> {
			setStreamSafe(!streamSafe());
			click();
		}));

		int lx = x + SIDEBAR + 1, lw = w - SIDEBAR - 1;
		nameField.visible = picked == profilesPage();
		if (picked < mods.size()) renderMod(context, mods.get(picked), lx, top, lw, y + h, mouseX, mouseY);
		else if (picked == profilesPage()) renderProfiles(context, lx, top, lw, y + h, mouseX, mouseY, delta);
		else renderBackups(context, lx, top, lw, y + h, mouseX, mouseY);

		if (mouseX >= x && mouseX < x + SIDEBAR && mouseY >= sy && mouseY < y + h) {
			context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(Text.literal("Hides coordinates in all your PlayWit mods (and F3) while you record or stream. "
					+ "Key: " + streamKeyName() + " in game."), 200), mouseX, mouseY);
		}
	}

	private static String streamKeyName() {
		return KeyBinding.byId("key.playwit.stream_safe") instanceof KeyBinding k ? k.getBoundKeyLocalizedText().getString() : "F7";
	}

	private void scrollbar(DrawContext context, int lx, int lw, int listTop, int listBottom) {
		if (maxScroll <= 0) return;
		int track = listBottom - listTop, bar = Math.max(20, track * track / (track + maxScroll));
		int by = listTop + (int) ((track - bar) * (scroll / maxScroll));
		context.fill(lx + lw - 3, by, lx + lw - 1, by + bar, EDGE);
	}

	private void renderMod(DrawContext context, Map<String, Object> mod, int lx, int listTop, int lw, int bottom, int mouseX, int mouseY) {
		int listBottom = bottom - FOOTER, textW = lw - 20 - 40;
		context.enableScissor(lx, listTop, lx + lw, listBottom);
		int ry = listTop + 6 - (int) scroll;
		boolean masterOff = false;
		for (Object o : list(mod.get("switches"))) {
			if (!(o instanceof Map<?, ?> row)) continue;
			if (row.get("section") != null) {
				ry += 6;
				context.drawText(textRenderer, String.valueOf(row.get("section")).toUpperCase(Locale.ROOT), lx + 10, ry, ACCENT, false);
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
		scrollbar(context, lx, lw, listTop, listBottom);

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

	/** Wrapped grey text; returns the y below it. */
	private int para(DrawContext context, String s, int x, int y, int w, int color) {
		for (OrderedText line : textRenderer.wrapLines(Text.literal(s), w)) {
			context.drawText(textRenderer, line, x, y, color, false);
			y += 10;
		}
		return y;
	}

	private void renderProfiles(DrawContext context, int lx, int listTop, int lw, int bottom, int mouseX, int mouseY, float delta) {
		List<Profile> profiles = loadProfiles();
		int listBottom = bottom - FOOTER, cw = lw - 20;
		context.enableScissor(lx, listTop, lx + lw, listBottom);
		int ry = listTop + 8 - (int) scroll;
		ry = para(context, "Save how your switches are set as a profile, and pick the servers it's for. When you join one of them, "
				+ "its switches are set for you.", lx + 10, ry, cw, GREY) + 6;
		if (profiles.isEmpty()) ry = para(context, "No profiles yet: set the switches the way you want them, name it below and save.", lx + 10, ry, cw, DIM) + 4;
		for (Profile p : profiles) {
			boolean open = p.name.equals(openProfile);
			int cardTop = ry;
			context.drawText(textRenderer, Text.literal(p.name).styled(s -> s.withBold(true)), lx + 10, ry + 6, TEXT, false);
			String used = p.servers.isEmpty() ? "Not used on any server yet" : "Used on " + String.join(", ", p.servers);
			ry = para(context, used, lx + 10, ry + 18, cw, p.servers.isEmpty() ? DIM : GREY);
			int bx = lx + 10;
			bx += button(context, open ? "Done" : "Servers…", bx, ry + 2, mouseX, mouseY, listTop, listBottom, false, () -> {
				openProfile = p.name.equals(openProfile) ? null : p.name;
				click();
			}) + 4;
			bx += button(context, "Apply now", bx, ry + 2, mouseX, mouseY, listTop, listBottom, false, () -> {
				int n = apply(p);
				say("\"" + p.name + "\" applied: " + n + (n == 1 ? " switch" : " switches") + " changed.", ON);
			}) + 4;
			bx += button(context, "Update", bx, ry + 2, mouseX, mouseY, listTop, listBottom, false, () -> {
				List<Profile> all = loadProfiles();
				all.stream().filter(q -> q.name.equals(p.name)).forEach(q -> q.switches = snapshot());
				saveProfiles(all);
				say("\"" + p.name + "\" now has your switches as they are.", ON);
			}) + 4;
			String del = "delete:" + p.name;
			button(context, del.equals(confirm) ? "Sure? Delete" : "Delete", bx, ry + 2, mouseX, mouseY, listTop, listBottom, false, () -> {
				if (!del.equals(confirm)) {
					confirm = del;
					return;
				}
				List<Profile> all = loadProfiles();
				all.removeIf(q -> q.name.equals(p.name));
				saveProfiles(all);
				confirm = null;
				say("Deleted \"" + p.name + "\".", GREY);
			});
			ry += 22;
			if (open) {
				List<String[]> servers = savedServers();
				if (servers.isEmpty()) ry = para(context, "Add servers in Multiplayer first, then pick them here.", lx + 18, ry + 2, cw - 8, DIM) + 2;
				for (String[] s : servers) {
					boolean in = p.servers.stream().map(ModsPanel::server).anyMatch(server(s[1])::equals);
					boolean over = mouseX >= lx + 10 && mouseX < lx + lw - 10 && mouseY >= ry && mouseY < ry + 14 && mouseY >= listTop && mouseY < listBottom;
					if (over) context.fill(lx + 10, ry, lx + lw - 10, ry + 14, HOVER);
					context.fill(lx + 18, ry + 3, lx + 26, ry + 11, in ? ON : OFF);
					context.drawText(textRenderer, textRenderer.trimToWidth(s[0] + "  ·  " + s[1], cw - 30), lx + 32, ry + 3, in ? TEXT : GREY, false);
					if (ry + 14 > listTop && ry < listBottom) hits.add(new Hit(lx + 10, Math.max(ry, listTop), lx + lw - 10, Math.min(ry + 14, listBottom), () -> {
						// A server uses one profile: picking it here takes it off any other.
						List<Profile> all = loadProfiles();
						for (Profile q : all) {
							q.servers.removeIf(a -> server(a).equals(server(s[1])));
							if (q.name.equals(p.name) && !in) q.servers.add(s[1]);
						}
						saveProfiles(all);
						click();
					}));
					ry += 15;
				}
				ry += 4;
			}
			context.fill(lx + 10, ry + 2, lx + lw - 10, ry + 3, EDGE);
			ry += 6;
			if (cardTop > listBottom) break;
		}
		context.disableScissor();
		maxScroll = Math.max(0, ry + (int) scroll - listBottom + 6);
		scrollbar(context, lx, lw, listTop, listBottom);

		// Footer: save the switches as a new profile.
		context.fill(lx, listBottom, lx + lw, listBottom + 1, EDGE);
		int by = listBottom + 6;
		String save = "Save current switches";
		int sw = textRenderer.getWidth(save) + 12;
		nameField.setX(lx + 10);
		nameField.setY(by);
		nameField.setWidth(Math.max(60, lw - 30 - sw));
		nameField.render(context, mouseX, mouseY, delta);
		button(context, save, lx + lw - 10 - sw, by + 1, mouseX, mouseY, by, by + 18, true, this::saveNewProfile);
		drawStatus(context, lx, lw, listBottom);
	}

	private void saveNewProfile() {
		String name = nameField.getText().strip();
		if (name.isEmpty()) {
			say("Type a name for the profile first.", RED);
			setFocused(nameField);
			return;
		}
		List<Profile> all = loadProfiles();
		Profile p = all.stream().filter(q -> q.name.equalsIgnoreCase(name)).findFirst().orElse(null);
		if (p == null) {
			p = new Profile();
			p.name = name;
			all.add(p);
		}
		p.switches = snapshot();
		saveProfiles(all);
		nameField.setText("");
		openProfile = p.name;
		say("Saved \"" + p.name + "\". Pick the servers it's for.", ON);
	}

	private void renderBackups(DrawContext context, int lx, int listTop, int lw, int bottom, int mouseX, int mouseY) {
		if (backupList == null) backupList = PanelData.list();
		int listBottom = bottom - FOOTER, cw = lw - 20;
		context.enableScissor(lx, listTop, lx + lw, listBottom);
		int ry = listTop + 8 - (int) scroll;
		ry = para(context, "A backup holds everything your PlayWit mods keep: lists, notes, chat logs, recordings, bases, maps, "
				+ "containers, settings and profiles. Restoring one happens when you next start the game; what you have now is backed up first.",
				lx + 10, ry, cw, GREY) + 8;
		if (backupList.isEmpty()) ry = para(context, "No backups yet.", lx + 10, ry, cw, DIM) + 4;
		for (Path file : backupList) {
			String name = file.getFileName().toString().replaceFirst("\\.zip$", "");
			long size;
			try {
				size = Files.size(file);
			} catch (IOException e) {
				size = 0;
			}
			boolean pending = PanelData.restorePendingFor(file);
			context.drawText(textRenderer, textRenderer.trimToWidth(name, cw - 150), lx + 10, ry + 4, TEXT, false);
			context.drawText(textRenderer, pending ? "Restores when you restart" : String.format(Locale.ROOT, "%.1f MB", size / 1048576.0),
					lx + 10, ry + 15, pending ? ACCENT : DIM, false);
			String restore = "restore:" + file, delete = "delete:" + file;
			int bx = lx + lw - 10;
			String dl = delete.equals(confirm) ? "Sure? Delete" : "Delete";
			bx -= textRenderer.getWidth(dl) + 12;
			button(context, dl, bx, ry + 4, mouseX, mouseY, listTop, listBottom, false, () -> {
				if (!delete.equals(confirm)) {
					confirm = delete;
					return;
				}
				try {
					if (PanelData.restorePendingFor(file)) PanelData.cancelRestore();
					Files.deleteIfExists(file);
					say("Deleted the backup.", GREY);
				} catch (IOException e) {
					say("Couldn't delete it: " + e.getMessage(), RED);
				}
				confirm = null;
				backupList = null;
			});
			String rl = pending ? "Cancel restore" : restore.equals(confirm) ? "Sure? Restore" : "Restore";
			bx -= textRenderer.getWidth(rl) + 16;
			button(context, rl, bx, ry + 4, mouseX, mouseY, listTop, listBottom, !pending && restore.equals(confirm), () -> {
				try {
					if (pending) {
						PanelData.cancelRestore();
						say("Restore cancelled.", GREY);
					} else if (!restore.equals(confirm)) {
						confirm = restore;
						return;
					} else {
						PanelData.restoreAtNextStart(file, dataPaths());
						say("Restart the game to finish restoring. What you have now is backed up first.", ACCENT);
					}
				} catch (IOException e) {
					say("Couldn't set up the restore: " + e.getMessage(), RED);
				}
				confirm = null;
			});
			ry += 28;
			context.fill(lx + 10, ry, lx + lw - 10, ry + 1, EDGE);
			ry += 3;
		}
		context.disableScissor();
		maxScroll = Math.max(0, ry + (int) scroll - listBottom + 6);
		scrollbar(context, lx, lw, listTop, listBottom);

		context.fill(lx, listBottom, lx + lw, listBottom + 1, EDGE);
		int by = listBottom + 6, bx = lx + lw - 10;
		String now = backingUp ? "Backing up…" : "Back up now", folder = "Open folder";
		bx -= textRenderer.getWidth(now) + 12;
		button(context, now, bx, by + 1, mouseX, mouseY, by, by + 18, true, this::backUpNow);
		bx -= textRenderer.getWidth(folder) + 16;
		button(context, folder, bx, by + 1, mouseX, mouseY, by, by + 18, false, () -> {
			try {
				Files.createDirectories(PanelData.backups());
			} catch (IOException ignored) {
				// Opened anyway.
			}
			Util.getOperatingSystem().open(PanelData.backups());
		});
		drawStatus(context, lx, lw, listBottom);
	}

	/** What every installed mod keeps, plus the panel's own profiles. */
	private List<String> dataPaths() {
		List<String> paths = new ArrayList<>();
		for (Map<String, Object> mod : mods) {
			for (Object o : list(mod.get("data"))) paths.add(String.valueOf(o));
		}
		paths.add("playwit/profiles.json");
		return paths;
	}

	private void backUpNow() {
		if (backingUp) return;
		backingUp = true;
		List<String> paths = dataPaths();
		say("Backing up…", GREY);
		Util.getIoWorkerExecutor().execute(() -> {
			try {
				int n = PanelData.backUp(paths, PanelData.newBackupFile(null));
				client.execute(() -> say("Backed up " + n + (n == 1 ? " file." : " files."), ON));
			} catch (IOException e) {
				client.execute(() -> say("Couldn't back up: " + e.getMessage(), RED));
			} finally {
				backingUp = false;
				backupList = null;
			}
		});
	}

	private void say(String message, int color) {
		status = message;
		statusColor = color;
		click();
	}

	/** The latest message, just above the footer. */
	private void drawStatus(DrawContext context, int lx, int lw, int listBottom) {
		if (status == null) return;
		String s = textRenderer.trimToWidth(status, lw - 20);
		context.fill(lx, listBottom - 14, lx + lw, listBottom, PANEL);
		context.drawText(textRenderer, s, lx + 10, listBottom - 11, statusColor, false);
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
		status = null;
		confirm = null;
		backupList = null;
		lastPage = pageId(index);
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
					setFocused(null);
					hit.run.run();
					return true;
				}
			}
			confirm = null;
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
		boolean typing = getFocused() == nameField && nameField.visible;
		if (typing && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			saveNewProfile();
			return true;
		}
		if (!typing && input.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
			close();
			return true;
		}
		if (!typing && (input.key() == GLFW.GLFW_KEY_UP || input.key() == GLFW.GLFW_KEY_DOWN)) {
			pick(Math.floorMod(picked + (input.key() == GLFW.GLFW_KEY_UP ? -1 : 1), pages()));
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
