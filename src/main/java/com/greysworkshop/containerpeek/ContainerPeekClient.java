package com.greysworkshop.containerpeek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
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

	private final NameStore store = new NameStore();

	private KeyBinding toggleKey;
	private KeyBinding configKey;

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
		migrateOldFiles();
		ModConfig.load();
		store.load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.containerpeek.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.containerpeek.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!world.isClient() || !ModConfig.INSTANCE.enabled) return ActionResult.PASS;
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
			store.put(world, target.dimension(), target.pos(), name, null, client.world.getRegistryManager());
			store.saveIfDirty();

			// Slots fill in after the screen opens, so read the contents when it closes.
			ScreenEvents.remove(screen).register(closed -> {
				if (client.world == null || !world.equals(worldKey(client))) return;
				List<ItemStack> items = containerStacks(handled.getScreenHandler());
				store.put(world, target.dimension(), target.pos(), name, items, client.world.getRegistryManager());
				store.saveIfDirty();
			});
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			pendingOpen = null;
			pendingPlace = null;
			store.saveIfDirty();
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
				Identifier.of(MOD_ID, "name_label"), this::renderHud);
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
		while (toggleKey.wasPressed()) {
			ModConfig.INSTANCE.enabled = !ModConfig.INSTANCE.enabled;
			ModConfig.save();
			if (client.player != null) {
				client.player.sendMessage(Text.translatable(ModConfig.INSTANCE.enabled
						? "containerpeek.toggled_on" : "containerpeek.toggled_off"), true);
			}
		}

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
							client.world.getRegistryManager());
					store.saveIfDirty();
				}
				pendingPlace = null;
			} else if (--pendingPlaceTicks <= 0) {
				pendingPlace = null;
			}
		}

		// Once a second, forget containers that are gone (broken or picked up) in loaded chunks.
		if (++cleanupTimer >= 20) {
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
		String world = worldKey(client);
		if (world == null) return;

		TextRenderer font = client.textRenderer;
		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(context.getScaledWindowWidth() / 2f, context.getScaledWindowHeight() / 2f + config.yOffset);
		float scale = config.scalePercent / 100f;
		matrices.scale(scale, scale);
		// From here on, (0, 0) is the top centre of the label.
		int y = 0;

		NameStore.Entry entry = store.get(world, target.dimension(), target.pos());
		if (entry == null) {
			if (config.showUnknownHint) {
				Text blockName = client.world.getBlockState(hit.getBlockPos()).getBlock().getName();
				drawCentered(context, font, Text.translatable("containerpeek.unknown", blockName), y, 0xFFAAAAAA);
			}
			matrices.popMatrix();
			return;
		}
		if (config.showNames && !entry.name.isEmpty()) {
			drawCentered(context, font, Text.literal(entry.name), y, 0xFFFFFFFF);
			y += 11;
		}

		List<ItemStack> stacks = config.showContents ? entry.stacks(client.world.getRegistryManager()) : null;
		if (stacks != null && stacks.isEmpty()) {
			drawCentered(context, font, Text.translatable("containerpeek.empty"), y, 0xFFAAAAAA);
		} else if (stacks != null) {
			drawItemRow(context, font, stacks, y, config.maxIcons);
		}
		matrices.popMatrix();
	}

	/** Draws the contents as icons, combining identical items so 27 stacks of cobblestone show "1728". */
	private static void drawItemRow(DrawContext context, TextRenderer font, List<ItemStack> stacks, int y, int maxIcons) {
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
		String more = extra > 0 ? "+" + extra : null;
		int rowWidth = shown * 18 + (more != null ? font.getWidth(more) + 2 : 0);
		int x = -rowWidth / 2;
		for (int i = 0; i < shown; i++) {
			ItemStack stack = groups.get(i);
			int total = totals.get(i);
			context.drawItem(stack, x, y);
			context.drawStackOverlay(font, stack, x, y, total > 1 ? String.valueOf(total) : null);
			x += 18;
		}
		if (more != null) {
			context.drawTextWithShadow(font, more, x + 2, y + 4, 0xFFAAAAAA);
		}
	}

	private static void drawCentered(DrawContext context, TextRenderer font, Text text, int y, int color) {
		context.drawTextWithShadow(font, text, -font.getWidth(text) / 2, y, color);
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
	private static String worldKey(MinecraftClient client) {
		ServerInfo server = client.getCurrentServerEntry();
		if (server != null) return "server:" + server.address;
		if (client.getServer() != null) return "local:" + client.getServer().getSaveProperties().getLevelName();
		return null;
	}

	private static String dimensionKey(ClientWorld world) {
		return world.getRegistryKey().getValue().toString();
	}
}
