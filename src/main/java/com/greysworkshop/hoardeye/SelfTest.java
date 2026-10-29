package com.greysworkshop.hoardeye;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Development-only scripted test, run with {@code ./gradlew runSelfTest}. Not included in the release jar.
 * Fills some containers, opens them, then screenshots the HUD, the shulker tooltip, the search and the locator.
 */
public final class SelfTest {
	private static final String WORLD = "cp-selftest";

	private static boolean started;
	private static int tick = -1;
	private static int passed, failed;
	private static BlockPos origin;
	private static final List<BlockPos> containers = new ArrayList<>();
	private static net.minecraft.world.level.block.state.BlockState hiddenState;
	private static int beforeItems;

	private SelfTest() {}

	public static void start() {
		log("Self-test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			// No "move with WASD" pop-up over the screenshots.
			client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
			client.schedule(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(Minecraft client) {
		if (client.getLevelSource().levelExists(WORLD)) {
			client.createWorldOpenFlows().openWorld(WORLD, () -> {});
			return;
		}
		LevelSettings info = new LevelSettings(WORLD, GameType.CREATIVE,
				new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
		client.createWorldOpenFlows().createFreshLevel(WORLD, info, WorldOptions.testWorldWithRandomSeed(),
				WorldPresets::createTestWorldDimensions, new TitleScreen());
	}

	private static ItemStack shulkerItem(Item box, Object... contents) {
		List<ItemStack> stacks = new ArrayList<>();
		for (int i = 0; i < contents.length; i += 2) stacks.add(new ItemStack((Item) contents[i], (Integer) contents[i + 1]));
		ItemStack stack = new ItemStack(box);
		stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(stacks));
		return stack;
	}

	private static void tick(Minecraft client) {
		if (client.player == null || client.level == null || client.getSingleplayerServer() == null) return;
		tick++;
		switch (tick) {
			case 100 -> {
				// In a folder of its own, so it doesn't matter what the test's config folder had in it.
				boolean carried = false;
				try {
					java.nio.file.Path old = java.nio.file.Files.createTempDirectory("hoardeye-migrate");
					java.nio.file.Files.writeString(old.resolve("containerpeek.json"), "{\"saved\":1}");
					java.nio.file.Files.writeString(old.resolve("containerpeek-config.json"), "{}");
					HoardEyeClient.migrateOldFiles(old);
					carried = java.nio.file.Files.readString(old.resolve("hoardeye.json")).contains("saved")
							&& java.nio.file.Files.exists(old.resolve("hoardeye-config.json"))
							&& !java.nio.file.Files.exists(old.resolve("containerpeek.json")) && !java.nio.file.Files.exists(old.resolve("containerpeek-config.json"));
				} catch (java.io.IOException e) {
					log("Migration test failed: " + e);
				}
				check("files saved as Container Peek are carried over to Hoard Eye", carried);
				origin = client.player.blockPosition();
				ModConfig.INSTANCE.maxIcons = 27;
				ModConfig.INSTANCE.iconsPerRow = 9;
				ModConfig.INSTANCE.hudPosition = HudPosition.BELOW_CROSSHAIR;
				log("Filling containers around " + origin.toShortString());
				BlockPos mixed = origin.offset(3, 0, 0), storage = origin.offset(0, 0, 3), barrel = origin.offset(-3, 0, 0),
						shulker = origin.offset(0, 0, -3), nested = origin.offset(3, 0, 3);
				containers.addAll(List.of(mixed, storage, barrel, shulker, nested));
				server(client, w -> {
					// The world is kept between runs: no chest left beside Storage by the double-chest check.
					w.setBlockAndUpdate(storage.east(), Blocks.AIR.defaultBlockState());
					w.setBlockAndUpdate(storage.west(), Blocks.AIR.defaultBlockState());
					fill(w, mixed, Blocks.CHEST, null, new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.IRON_INGOT, 64),
							new ItemStack(Items.GOLD_INGOT, 32), new ItemStack(Items.EMERALD, 12), new ItemStack(Items.REDSTONE, 64),
							new ItemStack(Items.LAPIS_LAZULI, 40), new ItemStack(Items.COAL, 64), new ItemStack(Items.QUARTZ, 20),
							new ItemStack(Items.NETHERITE_INGOT, 2), new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.ELYTRA),
							new ItemStack(Items.TOTEM_OF_UNDYING, 3), new ItemStack(Items.FIREWORK_ROCKET, 64));
					fill(w, storage, Blocks.CHEST, "Storage", new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.COBBLESTONE, 64),
							new ItemStack(Items.COBBLESTONE, 64));
					fill(w, barrel, Blocks.BARREL, null, new ItemStack(Items.FIREWORK_ROCKET, 64), new ItemStack(Items.FIREWORK_ROCKET, 64));
					fill(w, shulker, Blocks.DYED_SHULKER_BOX.blue(), null, new ItemStack(Items.DIAMOND, 20), new ItemStack(Items.OBSIDIAN, 64));
					fill(w, nested, Blocks.CHEST, null, shulkerItem(Items.DYED_SHULKER_BOX.red(), Items.DIAMOND, 30, Items.GOLDEN_APPLE, 8));
				});
			}
			case 120, 145, 170, 195, 220 -> {
				BlockPos pos = containers.get((tick - 120) / 25);
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
				client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
			}
			case 135, 160, 185, 210, 235 -> client.player.closeContainer();
			case 245 -> {
				int remembered = HoardEyeClient.INSTANCE.store()
						.dimension(HoardEyeClient.worldKey(client), HoardEyeClient.dimensionKey(client.level)).size();
				check("all 5 containers remembered (" + remembered + ")", remembered == 5);
				aimAt(client, containers.get(0));
			}
			case 260 -> screenshot(client, "1_hud_wrapped_icons");
			case 265 -> aimAt(client, containers.get(1));
			case 275 -> screenshot(client, "1b_hud_named_chest");
			case 280 -> client.gui.setScreen(new TooltipScreen(shulkerItem(Items.DYED_SHULKER_BOX.lime(),
					Items.DIAMOND, 64, Items.TOTEM_OF_UNDYING, 1, Items.ENDER_PEARL, 16, Items.GOLDEN_CARROT, 64, Items.ELYTRA, 1)));
			case 292 -> screenshot(client, "2_shulker_tooltip");
			case 300 -> client.gui.setScreen(new SearchScreen());
			case 315 -> screenshot(client, "3_search_items");
			case 320 -> {
				if (client.gui.screen() instanceof SearchScreen s) s.testQuery("diamond");
			}
			case 330 -> {
				if (client.gui.screen() instanceof SearchScreen s) {
					int total = s.testTotal("Diamond");
					check("diamond total across containers incl. nested shulker is 119 (" + total + ")", total == 119);
				}
				screenshot(client, "4_search_diamond");
			}
			case 335 -> {
				if (client.gui.screen() instanceof SearchScreen s) s.testView(true);
			}
			case 345 -> screenshot(client, "5_search_containers");
			case 350 -> {
				if (client.gui.screen() instanceof SearchScreen s) {
					s.testView(false);
				}
			}
			case 322 -> {
				// Step back from the containers, or the locator counts them as already found.
				client.getSingleplayerServer().execute(() -> client.getSingleplayerServer().getPlayerList().getPlayers().get(0)
						.teleportTo(origin.getX() - 14, origin.getY(), origin.getZ() - 14));
			}
			case 340 -> {
				log("Walling the player in so the containers are out of sight");
				BlockPos me = origin.offset(-14, 0, -14);
				server(client, w -> {
					for (int dx = -3; dx <= 3; dx++) {
						for (int dz = -3; dz <= 3; dz++) {
							if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue;
							for (int dy = 0; dy <= 3; dy++) w.setBlockAndUpdate(me.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
						}
					}
				});
			}
			case 358 -> {
				log("Player is " + (int) Math.sqrt(client.player.distanceToSqr(Vec3.atCenterOf(origin))) + " blocks from the containers");
				if (client.gui.screen() instanceof SearchScreen s) s.testHighlightAll();
				log("Locator targets right after Highlight all: " + Locator.count());
			}
			case 362 -> {
				check("locator guiding to 4 diamond containers (" + Locator.count() + ")", Locator.count() == 4);
				aimAt(client, origin.offset(1, 0, 1));
			}
			case 372 -> {
				log("Marker: " + Locator.lastMarker);
				check("markers that would overlap merge into one with a count (" + Locator.markersDrawn + " drawn for " + Locator.count() + ")",
						Locator.markersDrawn >= 1 && Locator.markersDrawn < Locator.count());
				screenshot(client, "6_locator_through_wall");
			}
			case 374 -> aimAt(client, origin.offset(-40, 0, -40));
			case 379 -> {
				log("Marker after turning away: " + Locator.lastMarker);
				screenshot(client, "6b_locator_behind_you");
			}
			case 380 -> {
				Locator.stop();
				ModConfig.INSTANCE.labelBackground = true;
				ModConfig.INSTANCE.nameColor = ModConfig.NameColor.GOLD;
				client.getSingleplayerServer().execute(() -> client.getSingleplayerServer().getPlayerList().getPlayers().get(0)
						.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5));
			}
			case 390 -> aimAt(client, containers.get(1));
			case 400 -> screenshot(client, "7_label_background_gold_name");
			case 405 -> client.gui.setScreen(new ConfigScreen(null));
			case 415 -> screenshot(client, "8_settings_general");
			case 420 -> showSettingsTab(client, 1);
			case 430 -> screenshot(client, "8_settings_label");
			case 435 -> showSettingsTab(client, 2);
			case 445 -> screenshot(client, "8_settings_containers");
			case 450 -> showSettingsTab(client, 3);
			case 460 -> screenshot(client, "8_settings_search");
			case 465 -> showSettingsTab(client, 4);
			case 475 -> screenshot(client, "8_settings_data");
			case 480 -> {
				log("Fetch list: 2 stacks of diamonds and 1 of rockets");
				client.gui.setScreen(new SearchScreen());
			}
			case 485 -> {
				if (client.gui.screen() instanceof SearchScreen s) {
					s.testQuery("");
					s.testFetch("Diamond", 2);
					s.testFetch("Firework Rocket", 1);
					s.testShowFetch();
				}
			}
			case 495 -> {
				if (client.gui.screen() instanceof SearchScreen s) {
					check("fetch route planned (" + s.testRouteStops() + " stops)", s.testRouteStops() >= 2);
				}
				screenshot(client, "9_fetch_list");
			}
			case 500 -> {
				if (client.gui.screen() instanceof SearchScreen s) s.testStartRoute();
			}
			case 505 -> check("route started at stop 1", Locator.onRoute() && Locator.routeIndex() == 0);
			case 510 -> aimAt(client, Locator.currentStop().pos());
			case 518 -> screenshot(client, "9b_fetch_strip");
			case 520 -> {
				BlockPos pos = Locator.currentStop().pos();
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
				client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
			}
			case 535 -> {
				check("opening the stop's container moves the route on (now at stop " + (Locator.routeIndex() + 1) + ")",
						Locator.routeIndex() == 1 || !Locator.onRoute());
				screenshot(client, "9c_fetch_take_here");
			}
			case 545 -> {
				client.player.closeContainer();
				Locator.stop();
			}
			// ---- Forgetting only what's certainly gone ----
			case 550 -> {
				client.gui.setScreen(null);
				log("The barrel shows as stone, as anti-xray sends hidden containers (no break)");
				client.level.setBlockAndUpdate(containers.get(2), Blocks.STONE.defaultBlockState());
			}
			case 600 -> {
				check("a container hidden by anti-xray is still remembered", known(client, containers.get(2)));
				log("The shulker box is broken while the player is there");
				server(client, w -> w.setBlockAndUpdate(containers.get(3), Blocks.AIR.defaultBlockState()));
				// And the barrel really goes too: the server sends air, which anti-xray never fakes.
				server(client, w -> w.setBlockAndUpdate(containers.get(2), Blocks.AIR.defaultBlockState()));
			}
			case 640 -> {
				check("a container broken in front of you is forgotten", !known(client, containers.get(3)));
				check("one that turns to air is forgotten", !known(client, containers.get(2)));
				// A second chest joins "Storage" on the side the pair is kept under: the name and contents go with it.
				BlockPos storage = containers.get(1), east = storage.east(), west = storage.west();
				boolean eastLower = east.asLong() < storage.asLong();
				BlockPos partner = eastLower ? east : west;
				log("A chest joins Storage on the " + (eastLower ? "east" : "west"));
				var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
				var type = net.minecraft.world.level.block.state.properties.BlockStateProperties.CHEST_TYPE;
				server(client, w -> {
					w.setBlockAndUpdate(storage, w.getBlockState(storage).setValue(facing, Direction.NORTH)
							.setValue(type, eastLower ? net.minecraft.world.level.block.state.properties.ChestType.LEFT : net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
					w.setBlockAndUpdate(partner, Blocks.CHEST.defaultBlockState().setValue(facing, Direction.NORTH)
							.setValue(type, eastLower ? net.minecraft.world.level.block.state.properties.ChestType.RIGHT : net.minecraft.world.level.block.state.properties.ChestType.LEFT));
				});
				containers.set(1, partner);
			}
			case 680 -> {
				NameStore.Entry pair = HoardEyeClient.INSTANCE.store().get(HoardEyeClient.worldKey(client),
						HoardEyeClient.dimensionKey(client.level), containers.get(1));
				check("a chest that becomes a double chest keeps its name and contents (" + (pair == null ? "none" : pair.name) + ")",
						pair != null && pair.name.equals("Storage") && pair.items != null && !pair.items.isEmpty());
				BlockPos partner = containers.get(1);
				server(client, w -> w.setBlockAndUpdate(partner, Blocks.AIR.defaultBlockState()));
				check("server addresses are one server however they're typed",
						HoardEyeClient.normalizeAddress("2B2T.org:25565.").equals(HoardEyeClient.normalizeAddress("2b2t.org")));
			}
			// ---- Servers that hide chests you can't see (anti-ESP) send air for them ----
			case 690 -> {
				log("Behind the wall, out of sight: the server hides the first chest (sends air)");
				client.getSingleplayerServer().execute(() -> client.getSingleplayerServer().getPlayerList().getPlayers().get(0)
						.teleportTo(origin.getX() - 13.5, origin.getY(), origin.getZ() - 13.5));
			}
			case 720 -> {
				hiddenState = client.level.getBlockState(containers.get(0));
				client.level.setServerVerifiedBlockState(containers.get(0), Blocks.AIR.defaultBlockState(), 19);
			}
			case 770 -> {
				check("a chest a hider turns to air, out of sight, is still remembered", known(client, containers.get(0)));
				client.level.setServerVerifiedBlockState(containers.get(0), hiddenState, 19);
				client.getSingleplayerServer().execute(() -> client.getSingleplayerServer().getPlayerList().getPlayers().get(0)
						.teleportTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5));
			}
			// ---- A server menu opening instead of the chest clicked ----
			case 800 -> {
				log("A block on top of the chest stops it opening; the server opens its own menu instead");
				NameStore.Entry before = HoardEyeClient.INSTANCE.store().get(HoardEyeClient.worldKey(client), HoardEyeClient.dimensionKey(client.level), containers.get(0));
				beforeItems = before == null || before.items == null ? -1 : before.items.size();
				server(client, w -> w.setBlockAndUpdate(containers.get(0).above(), Blocks.STONE.defaultBlockState()));
			}
			case 810 -> {
				BlockPos pos = containers.get(0);
				client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false));
				client.getSingleplayerServer().execute(() -> {
					var player = client.getSingleplayerServer().getPlayerList().getPlayers().get(0);
					var menu = new net.minecraft.world.SimpleContainer(54);
					menu.setItem(0, new ItemStack(Items.DIRT, 1));
					player.openMenu(new net.minecraft.world.SimpleMenuProvider(
							(id, inv, p) -> net.minecraft.world.inventory.ChestMenu.sixRows(id, inv, menu), Component.literal("Server Menu")));
				});
			}
			case 840 -> {
				check("the server's menu opened (" + client.gui.screen() + ")", client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>);
				client.player.closeContainer();
			}
			case 850 -> {
				NameStore.Entry after = HoardEyeClient.INSTANCE.store().get(HoardEyeClient.worldKey(client), HoardEyeClient.dimensionKey(client.level), containers.get(0));
				check("a server menu that opens instead isn't taken for the chest (" + (after == null ? "none" : "\"" + after.name + "\", "
						+ (after.items == null ? -1 : after.items.size()) + " stacks") + ")",
						after != null && after.name.isEmpty() && (after.items == null ? -1 : after.items.size()) == beforeItems);
				server(client, w -> w.setBlockAndUpdate(containers.get(0).above(), Blocks.AIR.defaultBlockState()));
			}
			case 860 -> {
				log("DONE: " + passed + " passed, " + failed + " failed");
				client.stop();
			}
			default -> {}
		}
	}

	private static boolean known(Minecraft client, BlockPos pos) {
		return HoardEyeClient.INSTANCE.store().get(HoardEyeClient.worldKey(client), HoardEyeClient.dimensionKey(client.level), pos) != null;
	}

	private static void fill(ServerLevel world, BlockPos pos, net.minecraft.world.level.block.Block block, String name, ItemStack... stacks) {
		world.setBlockAndUpdate(pos, block.defaultBlockState());
		if (world.getBlockEntity(pos) instanceof BaseContainerBlockEntity be) {
			if (name != null) {
				ItemStack named = new ItemStack(block.asItem());
				named.set(DataComponents.CUSTOM_NAME, Component.literal(name));
				be.applyComponentsFromItemStack(named);
			}
			for (int i = 0; i < stacks.length; i++) be.setItem(i, stacks[i]);
			be.setChanged();
		}
	}

	/** Shows one item's tooltip in the middle of the screen. */
	private static final class TooltipScreen extends Screen {
		private final ItemStack stack;

		TooltipScreen(ItemStack stack) {
			super(Component.literal("tooltip"));
			this.stack = stack;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
			super.extractRenderState(context, mouseX, mouseY, delta);
			context.setTooltipForNextFrame(font, stack, width / 2 - 90, height / 2 - 30);
		}
	}

	private static void showSettingsTab(Minecraft client, int tab) {
		if (client.gui.screen() instanceof OptionsScreen options) options.showTab(tab);
	}

	private static void aimAt(Minecraft client, BlockPos target) {
		Vec3 eye = client.player.getEyePosition();
		Vec3 d = Vec3.atCenterOf(target).subtract(eye);
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		client.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		client.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, horizontal)));
	}

	private static void server(Minecraft client, Consumer<ServerLevel> action) {
		IntegratedServer server = client.getSingleplayerServer();
		server.execute(() -> action.accept(server.overworld()));
	}

	private static void screenshot(Minecraft client, String name) {
		Screenshot.grab(client.gameDirectory, "cp_" + name + ".png", client.gameRenderer.mainRenderTarget(), 1, message -> {});
		log("Screenshot " + name);
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		HoardEyeClient.LOGGER.info("[SELFTEST] {}", message);
	}
}
