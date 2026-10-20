package com.greysworkshop.containerpeek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoubleBlockProperties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ContainerPeekClient implements ClientModInitializer {
	public static final String MOD_ID = "containerpeek";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** How long (in ticks) after a right-click we still accept the resulting screen or placement. */
	private static final int PENDING_TICKS = 40;
	/** Slot count of a shulker box item's contents. */
	private static final int BOX_SLOTS = 27;
	/** Ender chest contents are the same everywhere, so they're stored under one fixed key. */
	private static final String ENDER_DIMENSION = "#ender";

	/** Where a container's data is stored: a dimension key plus a position within it. */
	private record Target(ContainerKind kind, String dimension, BlockPos pos) {}

	public static ContainerPeekClient INSTANCE;

	private final NameStore store = new NameStore();

	private KeyBinding toggleKey;
	private KeyBinding configKey;
	private KeyBinding searchKey;

	private Target pendingOpen;
	private int pendingOpenTicks;
	private BlockPos pendingPlace;
	private Block pendingPlaceBlock;
	private String pendingPlaceName;
	private List<ItemStack> pendingPlaceItems;
	private int pendingPlaceTicks;
	private int cleanupTimer;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		migrateOldFiles();
		ModConfig.load();
		store.load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.containerpeek.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.containerpeek.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		searchKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.containerpeek.search", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Y, category));

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!world.isClient() || !ModConfig.INSTANCE.enabled || !ModConfig.INSTANCE.rememberContainers) return ActionResult.PASS;
			ClientWorld clientWorld = (ClientWorld) world;

			// Right-clicking a container might open it; remember which, so we can match the screen.
			Target target = resolve(clientWorld, hit.getBlockPos());
			if (target != null) {
				pendingOpen = target;
				pendingOpenTicks = PENDING_TICKS;
			}

			// Right-clicking with a block item might place a container; remember where and what it holds.
			ItemStack stack = player.getStackInHand(hand);
			Block block = Block.getBlockFromItem(stack.getItem());
			if (block != null && block.getDefaultState().hasBlockEntity()) {
				Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
				DefaultedList<ItemStack> items = DefaultedList.ofSize(BOX_SLOTS, ItemStack.EMPTY);
				stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).copyTo(items);
				pendingPlace = new ItemPlacementContext(player, hand, stack, hit).getBlockPos();
				pendingPlaceBlock = block;
				pendingPlaceName = name == null ? "" : name.getString();
				pendingPlaceItems = items;
				pendingPlaceTicks = PENDING_TICKS;
			}
			return ActionResult.PASS;
		});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof HandledScreen<?> handled) || pendingOpen == null) return;
			if (screen instanceof InventoryScreen || screen instanceof CreativeInventoryScreen) return;
			String world = worldKey(client);
			if (world == null || client.world == null) return;
			Target target = pendingOpen;
			String name = titleToName(screen.getTitle());
			pendingOpen = null;
			String block = blockId(client.world, target);
			store.put(world, target.dimension(), target.pos(), name, null, client.world.getRegistryManager(), block, target.kind());
			store.saveIfDirty();

			// Slots fill in after the screen opens, so read the contents when it closes.
			ScreenEvents.remove(screen).register(closed -> {
				if (client.world == null || !world.equals(worldKey(client))) return;
				List<ItemStack> items = containerStacks(handled.getScreenHandler());
				store.put(world, target.dimension(), target.pos(), name, items, client.world.getRegistryManager(), block, target.kind());
				store.saveIfDirty();
			});
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);

		// Disconnect fires on the network thread; do the work on the game thread.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			pendingOpen = null;
			pendingPlace = null;
			store.saveIfDirty();
		}));

		TooltipComponentCallback.EVENT.register(data ->
				data instanceof ShulkerPreview.Data preview ? new ShulkerPreview.Component(preview) : null);

		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
				Identifier.of(MOD_ID, "name_label"), this::renderHud);
		// After chat, so markers aren't hidden behind recent messages.
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of(MOD_ID, "locator"), Locator::renderHud);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(Locator::renderWorld);

		// Scripted development test (./gradlew runSelfTest); the class isn't shipped in the release jar.
		if (Boolean.getBoolean("containerpeek.selftest")) {
			try {
				Class.forName("com.greysworkshop.containerpeek.SelfTest").getMethod("start").invoke(null);
			} catch (ReflectiveOperationException e) {
				LOGGER.error("Self-test not available", e);
			}
		}
	}

	public NameStore store() {
		return store;
	}

	/** Versions before 1.3.0 were called "Shulker Names"; carry their saved files over. */
	private static void migrateOldFiles() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		String[][] renames = {
				{"shulkernames.json", "containerpeek.json"},
				{"shulkernames-config.json", "containerpeek-config.json"},
		};
		for (String[] rename : renames) {
			Path from = dir.resolve(rename[0]);
			Path to = dir.resolve(rename[1]);
			if (Files.exists(from) && !Files.exists(to)) {
				try {
					Files.move(from, to);
				} catch (IOException e) {
					LOGGER.error("Could not move {} to {}", from, to, e);
				}
			}
		}
	}

	private void tick(MinecraftClient client) {
		while (configKey.wasPressed()) {
			client.setScreen(new ConfigScreen(client.currentScreen));
		}
		while (searchKey.wasPressed()) {
			if (client.world != null && worldKey(client) != null) client.setScreen(new SearchScreen());
		}
		while (toggleKey.wasPressed()) {
			ModConfig.INSTANCE.enabled = !ModConfig.INSTANCE.enabled;
			ModConfig.save();
			if (client.player != null) {
				client.player.sendMessage(Text.translatable(ModConfig.INSTANCE.enabled
						? "containerpeek.toggled_on" : "containerpeek.toggled_off"), true);
			}
		}

		Locator.tick(client);
		if (client.world == null || !ModConfig.INSTANCE.enabled) return;
		String world = worldKey(client);
		if (world == null) return;
		String dimension = dimensionKey(client.world);

		if (pendingOpen != null && --pendingOpenTicks <= 0) pendingOpen = null;

		if (pendingPlace != null) {
			BlockState state = client.world.getBlockState(pendingPlace);
			Target target = state.isOf(pendingPlaceBlock) ? resolve(client.world, pendingPlace) : null;
			if (target != null) {
				// A chest that joined another into a double chest keeps the other half's contents, so skip it.
				boolean joinedDouble = state.getBlock() instanceof ChestBlock
						&& ChestBlock.getDoubleBlockType(state) != DoubleBlockProperties.Type.SINGLE;
				if (target.kind() != ContainerKind.ENDER_CHEST && !joinedDouble) {
					store.put(world, target.dimension(), target.pos(), pendingPlaceName, pendingPlaceItems,
							client.world.getRegistryManager(), blockId(client.world, target), target.kind());
					store.saveIfDirty();
				}
				pendingPlace = null;
			} else if (--pendingPlaceTicks <= 0) {
				pendingPlace = null;
			}
		}

		// Once a second, forget containers that are gone (broken or picked up) in loaded chunks.
		if (ModConfig.INSTANCE.forgetBroken && ++cleanupTimer >= 20) {
			cleanupTimer = 0;
			Map<Long, NameStore.Entry> entries = store.dimension(world, dimension);
			Iterator<Map.Entry<Long, NameStore.Entry>> it = entries.entrySet().iterator();
			while (it.hasNext()) {
				BlockPos pos = BlockPos.fromLong(it.next().getKey());
				if (pos.equals(pendingPlace)) continue;
				if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
				if (resolve(client.world, pos) == null) {
					it.remove();
					store.markDirty();
				}
			}
			store.saveIfDirty();
		}
	}

	private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
		ModConfig config = ModConfig.INSTANCE;
		MinecraftClient client = MinecraftClient.getInstance();
		if (!config.enabled || client.world == null || client.options.hudHidden || client.currentScreen != null) return;
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		Target target = resolve(client.world, hit.getBlockPos());
		if (target == null || !config.isShown(target.kind())) return;
		if (config.hideWhileSneaking && client.player.isSneaking()) return;
		if (client.player.getEyePos().distanceTo(hit.getBlockPos().toCenterPos()) > config.maxDistance) return;
		String world = worldKey(client);
		if (world == null) return;

		TextRenderer font = client.textRenderer;
		Text line = null;
		int lineColor = 0xFFFFFFFF;
		ItemGrid grid = null;

		NameStore.Entry entry = store.get(world, target.dimension(), target.pos());
		if (entry == null) {
			if (!config.showUnknownHint) return;
			Text blockName = client.world.getBlockState(hit.getBlockPos()).getBlock().getName();
			line = Text.translatable("containerpeek.unknown", blockName);
			lineColor = 0xFFAAAAAA;
		} else {
			if (config.showNames && !entry.name.isEmpty()) {
				line = Text.literal(entry.name);
				lineColor = 0xFF000000 | config.nameColor.rgb;
			}
			List<ItemStack> stacks = config.showContents ? entry.stacks(client.world.getRegistryManager()) : null;
			if (stacks != null && !stacks.isEmpty()) {
				grid = ItemGrid.of(stacks, config.maxIcons, Math.max(1, config.iconsPerRow), config.showMoreCount, font);
			} else if (stacks != null && line == null) {
				line = Text.translatable("containerpeek.empty");
				lineColor = 0xFFAAAAAA;
			} else if (stacks != null) {
				line = Text.empty().append(line).append(" ")
						.append(Text.translatable("containerpeek.empty").formatted(Formatting.GRAY));
			}
		}
		if (line == null && grid == null) return;

		// Measure the label, then place it according to the chosen position.
		int lineWidth = line != null ? font.getWidth(line) : 0;
		int gridWidth = grid != null ? grid.width() : 0;
		int width = Math.max(lineWidth, gridWidth);
		int height = (line != null ? 9 : 0) + (line != null && grid != null ? 2 : 0) + (grid != null ? grid.height() : 0);

		HudPosition position = config.hudPosition != null ? config.hudPosition : HudPosition.BELOW_CROSSHAIR;
		float scale = config.scalePercent / 100f;
		float screenW = context.getScaledWindowWidth();
		float screenH = context.getScaledWindowHeight();
		float w = width * scale;
		float h = height * scale;
		int off = config.yOffset;
		float x = switch (position) {
			case BELOW_CROSSHAIR, ABOVE_CROSSHAIR, TOP_CENTER -> (screenW - w) / 2;
			case RIGHT_OF_CROSSHAIR -> screenW / 2 + off;
			case TOP_LEFT, BOTTOM_LEFT -> off;
			case TOP_RIGHT, BOTTOM_RIGHT -> screenW - off - w;
		};
		float y = switch (position) {
			case BELOW_CROSSHAIR -> screenH / 2 + off;
			case ABOVE_CROSSHAIR -> screenH / 2 - off - h;
			case RIGHT_OF_CROSSHAIR -> (screenH - h) / 2;
			case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> off;
			case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - off - h;
		};

		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(Math.round(x), Math.round(y));
		matrices.scale(scale, scale);
		if (config.labelBackground) {
			int alpha = Math.round(config.backgroundOpacity * 2.55f) << 24;
			context.fill(-4, -4, width + 4, height + 4, alpha | 0x101018);
		}
		int lineY = 0;
		if (line != null) {
			context.drawTextWithShadow(font, line, position.align(width, lineWidth), lineY, lineColor);
			lineY += 11;
		}
		if (grid != null) {
			grid.draw(context, font, position, width, lineY);
		}
		matrices.popMatrix();
	}

	/**
	 * The contents as rows of icons, combining identical items so 27 stacks of cobblestone show "1728".
	 * Wraps after {@code perRow} icons; a "+N" for anything past {@code maxIcons} goes at the end of the last row.
	 */
	private record ItemGrid(List<ItemStack> groups, List<Integer> totals, int perRow, String more, int moreWidth) {
		/** A little wider than an item, so 3-digit counts don't run into their neighbours. */
		private static final int CELL = 20;

		static ItemGrid of(List<ItemStack> stacks, int maxIcons, int perRow, boolean showMore, TextRenderer font) {
			List<ItemStack> groups = new ArrayList<>();
			List<Integer> totals = new ArrayList<>();
			outer:
			for (ItemStack stack : stacks) {
				for (int i = 0; i < groups.size(); i++) {
					if (ItemStack.areItemsAndComponentsEqual(groups.get(i), stack)) {
						totals.set(i, totals.get(i) + stack.getCount());
						continue outer;
					}
				}
				groups.add(stack);
				totals.add(stack.getCount());
			}

			int shown = Math.min(groups.size(), maxIcons);
			int extra = groups.size() - shown;
			String more = extra > 0 && showMore ? "+" + extra : null;
			int moreWidth = more != null ? font.getWidth(more) + 2 : 0;
			return new ItemGrid(groups.subList(0, shown), totals.subList(0, shown), perRow, more, moreWidth);
		}

		int rows() {
			return (groups.size() + perRow - 1) / perRow;
		}

		/** "64", "999", "1.7k", "17k": short enough to stay under its icon. */
		static String count(int total) {
			if (total < 1000 || ModConfig.INSTANCE.countFormat == ModConfig.CountFormat.EXACT) return String.valueOf(total);
			if (total < 10_000) return String.format(java.util.Locale.ROOT, "%.1fk", total / 1000.0);
			return total / 1000 + "k";
		}

		int rowWidth(int row) {
			int count = Math.min(perRow, groups.size() - row * perRow);
			return count * CELL + (row == rows() - 1 ? moreWidth : 0);
		}

		int width() {
			int width = 0;
			for (int row = 0; row < rows(); row++) width = Math.max(width, rowWidth(row));
			return width;
		}

		int height() {
			return rows() * CELL - 2;
		}

		void draw(DrawContext context, TextRenderer font, HudPosition position, int labelWidth, int y) {
			for (int row = 0; row < rows(); row++) {
				int x = position.align(labelWidth, rowWidth(row));
				int end = Math.min(groups.size(), (row + 1) * perRow);
				for (int i = row * perRow; i < end; i++) {
					ItemStack stack = groups.get(i);
					int total = totals.get(i);
					context.drawItem(stack, x, y);
					context.drawStackOverlay(font, stack, x, y, total > 1 ? count(total) : null);
					x += CELL;
				}
				if (row == rows() - 1 && more != null) {
					context.drawTextWithShadow(font, more, x + 2, y + 4, 0xFFAAAAAA);
				}
				y += CELL;
			}
		}
	}


	/**
	 * Works out where the container at {@code pos} is stored, or returns null if it isn't a container.
	 * Both halves of a double chest share one entry, and all ender chests share one entry.
	 */
	private static Target resolve(ClientWorld world, BlockPos pos) {
		ContainerKind kind = ContainerKind.of(world.getBlockEntity(pos));
		if (kind == null) return null;
		if (kind == ContainerKind.ENDER_CHEST) return new Target(kind, ENDER_DIMENSION, BlockPos.ORIGIN);

		BlockState state = world.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock && ChestBlock.getDoubleBlockType(state) != DoubleBlockProperties.Type.SINGLE) {
			BlockPos other = pos.offset(ChestBlock.getFacing(state));
			if (other.asLong() < pos.asLong()) pos = other;
		}
		return new Target(kind, dimensionKey(world), pos.toImmutable());
	}

	/** The container's own slots from an open screen, leaving out the player's inventory and crafter output. */
	private static List<ItemStack> containerStacks(ScreenHandler handler) {
		List<ItemStack> items = new ArrayList<>();
		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof PlayerInventory || slot.inventory instanceof CraftingResultInventory) continue;
			items.add(slot.getStack().copy());
		}
		return items;
	}

	/** A default title (like "Chest" or "Furnace") means the container is unnamed; anything else is a custom name. */
	private static String titleToName(Text title) {
		if (title.getContent() instanceof TranslatableTextContent t
				&& (t.getKey().startsWith("container.") || t.getKey().startsWith("block."))) {
			return "";
		}
		return title.getString();
	}

	/** Identifies the server (by address) or singleplayer world (by folder name). */
	public static String worldKey(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) return "server:" + server.address;
		if (client.getServer() != null) return "local:" + client.getServer().getSaveProperties().getLevelName();
		return null;
	}

	private static String blockId(ClientWorld world, Target target) {
		if (target.kind() == ContainerKind.ENDER_CHEST) return "minecraft:ender_chest";
		return Registries.BLOCK.getId(world.getBlockState(target.pos()).getBlock()).toString();
	}

	public static String dimensionKey(ClientWorld world) {
		return world.getRegistryKey().getValue().toString();
	}
}
