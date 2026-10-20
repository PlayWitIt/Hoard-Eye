package com.greysworkshop.shulkernames;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class ShulkerNamesClient implements ClientModInitializer {
	public static final String MOD_ID = "shulkernames";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** How long (in ticks) after a right-click we still accept the resulting screen or placement. */
	private static final int PENDING_TICKS = 40;
	private static final int BOX_SLOTS = 27;
	/** Most distinct item types drawn in the HUD row before showing "+N". */
	private static final int MAX_ICONS = 9;

	private final NameStore store = new NameStore();

	private BlockPos pendingOpen;
	private int pendingOpenTicks;
	private BlockPos pendingPlace;
	private String pendingPlaceName;
	private List<ItemStack> pendingPlaceItems;
	private int pendingPlaceTicks;
	private int cleanupTimer;

	@Override
	public void onInitializeClient() {
		store.load();

		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!world.isClient()) return ActionResult.PASS;

			// Right-clicking a shulker box might open it; remember where, so we can match the screen.
			if (world.getBlockState(hit.getBlockPos()).getBlock() instanceof ShulkerBoxBlock) {
				pendingOpen = hit.getBlockPos();
				pendingOpenTicks = PENDING_TICKS;
			}

			// Right-clicking with a shulker box item might place it; remember where and what it holds.
			ItemStack stack = player.getStackInHand(hand);
			if (Block.getBlockFromItem(stack.getItem()) instanceof ShulkerBoxBlock) {
				Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
				DefaultedList<ItemStack> items = DefaultedList.ofSize(BOX_SLOTS, ItemStack.EMPTY);
				stack.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).copyTo(items);
				pendingPlace = new ItemPlacementContext(player, hand, stack, hit).getBlockPos();
				pendingPlaceName = name == null ? "" : name.getString();
				pendingPlaceItems = items;
				pendingPlaceTicks = PENDING_TICKS;
			}
			return ActionResult.PASS;
		});

		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof ShulkerBoxScreen boxScreen) || pendingOpen == null) return;
			String world = worldKey(client);
			if (world == null || client.world == null) return;
			String dimension = dimensionKey(client.world);
			BlockPos pos = pendingOpen;
			String name = titleToName(screen.getTitle());
			pendingOpen = null;
			store.put(world, dimension, pos, name, null, client.world.getRegistryManager());
			store.saveIfDirty();

			// Slots fill in after the screen opens, so read the contents when it closes.
			ScreenEvents.remove(screen).register(closed -> {
				if (client.world == null || !world.equals(worldKey(client))) return;
				ShulkerBoxScreenHandler handler = boxScreen.getScreenHandler();
				List<ItemStack> items = new ArrayList<>();
				for (int i = 0; i < BOX_SLOTS; i++) {
					items.add(handler.getSlot(i).getStack().copy());
				}
				store.put(world, dimension, pos, name, items, client.world.getRegistryManager());
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

	private void tick(MinecraftClient client) {
		if (client.world == null) return;
		String world = worldKey(client);
		if (world == null) return;
		String dimension = dimensionKey(client.world);

		if (pendingOpen != null && --pendingOpenTicks <= 0) pendingOpen = null;

		if (pendingPlace != null) {
			if (client.world.getBlockState(pendingPlace).getBlock() instanceof ShulkerBoxBlock) {
				store.put(world, dimension, pendingPlace, pendingPlaceName, pendingPlaceItems,
						client.world.getRegistryManager());
				pendingPlace = null;
				store.saveIfDirty();
			} else if (--pendingPlaceTicks <= 0) {
				pendingPlace = null;
			}
		}

		// Once a second, forget boxes that are gone (broken or picked up) in loaded chunks.
		if (++cleanupTimer >= 20) {
			cleanupTimer = 0;
			Map<Long, NameStore.Entry> entries = store.dimension(world, dimension);
			Iterator<Map.Entry<Long, NameStore.Entry>> it = entries.entrySet().iterator();
			while (it.hasNext()) {
				BlockPos pos = BlockPos.fromLong(it.next().getKey());
				if (pos.equals(pendingPlace)) continue;
				if (!client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
				if (!(client.world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock)) {
					it.remove();
					store.markDirty();
				}
			}
			store.saveIfDirty();
		}
	}

	private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.options.hudHidden || client.currentScreen != null) return;
		if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		if (!(client.world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock)) return;
		String world = worldKey(client);
		if (world == null) return;

		TextRenderer font = client.textRenderer;
		int centerX = context.getScaledWindowWidth() / 2;
		int y = context.getScaledWindowHeight() / 2 + 10;

		NameStore.Entry entry = store.get(world, dimensionKey(client.world), pos);
		if (entry == null) {
			drawCentered(context, font, Text.translatable("shulkernames.unknown"), centerX, y, 0xFFAAAAAA);
			return;
		}
		if (!entry.name.isEmpty()) {
			drawCentered(context, font, Text.literal(entry.name), centerX, y, 0xFFFFFFFF);
			y += 11;
		}

		List<ItemStack> stacks = entry.stacks(client.world.getRegistryManager());
		if (stacks == null) return;
		if (stacks.isEmpty()) {
			drawCentered(context, font, Text.translatable("shulkernames.empty"), centerX, y, 0xFFAAAAAA);
			return;
		}

		// Combine identical items so a box of 27 stacks of cobblestone shows one icon with "1728".
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

		int shown = Math.min(groups.size(), MAX_ICONS);
		int extra = groups.size() - shown;
		String more = extra > 0 ? "+" + extra : null;
		int rowWidth = shown * 18 + (more != null ? font.getWidth(more) + 2 : 0);
		int x = centerX - rowWidth / 2;
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

	private static void drawCentered(DrawContext context, TextRenderer font, Text text, int centerX, int y, int color) {
		context.drawTextWithShadow(font, text, centerX - font.getWidth(text) / 2, y, color);
	}

	/** An unnamed box's screen uses the default "Shulker Box" title; anything else is a custom name. */
	private static String titleToName(Text title) {
		if (title.getContent() instanceof TranslatableTextContent t && t.getKey().equals("container.shulkerBox")) {
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
