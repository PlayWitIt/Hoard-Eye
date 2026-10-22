package com.greysworkshop.hoardeye;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

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

	private SelfTest() {}

	public static void start() {
		log("Self-test armed");
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (started || !(screen instanceof TitleScreen)) return;
			started = true;
			client.send(() -> openWorld(client));
		});
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
	}

	private static void openWorld(MinecraftClient client) {
		if (client.getLevelStorage().levelExists(WORLD)) {
			client.createIntegratedServerLoader().start(WORLD, () -> {});
			return;
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.CREATIVE, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		client.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static ItemStack shulkerItem(Item box, Object... contents) {
		List<ItemStack> stacks = new ArrayList<>();
		for (int i = 0; i < contents.length; i += 2) stacks.add(new ItemStack((Item) contents[i], (Integer) contents[i + 1]));
		ItemStack stack = new ItemStack(box);
		stack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(stacks));
		return stack;
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || client.getServer() == null) return;
		tick++;
		switch (tick) {
			case 100 -> {
				java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
				check("files saved as Container Peek are carried over to Hoard Eye", java.nio.file.Files.exists(config.resolve("hoardeye.json"))
						&& !java.nio.file.Files.exists(config.resolve("containerpeek.json")) && !java.nio.file.Files.exists(config.resolve("containerpeek-config.json")));
				origin = client.player.getBlockPos();
				ModConfig.INSTANCE.maxIcons = 27;
				ModConfig.INSTANCE.iconsPerRow = 9;
				ModConfig.INSTANCE.hudPosition = HudPosition.BELOW_CROSSHAIR;
				log("Filling containers around " + origin.toShortString());
				BlockPos mixed = origin.add(3, 0, 0), storage = origin.add(0, 0, 3), barrel = origin.add(-3, 0, 0),
						shulker = origin.add(0, 0, -3), nested = origin.add(3, 0, 3);
				containers.addAll(List.of(mixed, storage, barrel, shulker, nested));
				server(client, w -> {
					// The world is kept between runs: no chest left beside Storage by the double-chest check.
					w.setBlockState(storage.east(), Blocks.AIR.getDefaultState());
					w.setBlockState(storage.west(), Blocks.AIR.getDefaultState());
					fill(w, mixed, Blocks.CHEST, null, new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.IRON_INGOT, 64),
							new ItemStack(Items.GOLD_INGOT, 32), new ItemStack(Items.EMERALD, 12), new ItemStack(Items.REDSTONE, 64),
							new ItemStack(Items.LAPIS_LAZULI, 40), new ItemStack(Items.COAL, 64), new ItemStack(Items.QUARTZ, 20),
							new ItemStack(Items.NETHERITE_INGOT, 2), new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.ELYTRA),
							new ItemStack(Items.TOTEM_OF_UNDYING, 3), new ItemStack(Items.FIREWORK_ROCKET, 64));
					fill(w, storage, Blocks.CHEST, "Storage", new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.COBBLESTONE, 64),
							new ItemStack(Items.COBBLESTONE, 64));
					fill(w, barrel, Blocks.BARREL, null, new ItemStack(Items.FIREWORK_ROCKET, 64), new ItemStack(Items.FIREWORK_ROCKET, 64));
					fill(w, shulker, Blocks.BLUE_SHULKER_BOX, null, new ItemStack(Items.DIAMOND, 20), new ItemStack(Items.OBSIDIAN, 64));
					fill(w, nested, Blocks.CHEST, null, shulkerItem(Items.RED_SHULKER_BOX, Items.DIAMOND, 30, Items.GOLDEN_APPLE, 8));
				});
			}
			case 120, 145, 170, 195, 220 -> {
				BlockPos pos = containers.get((tick - 120) / 25);
				BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false);
				client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
			}
			case 135, 160, 185, 210, 235 -> client.player.closeHandledScreen();
			case 245 -> {
				int remembered = HoardEyeClient.INSTANCE.store()
						.dimension(HoardEyeClient.worldKey(client), HoardEyeClient.dimensionKey(client.world)).size();
				check("all 5 containers remembered (" + remembered + ")", remembered == 5);
				aimAt(client, containers.get(0));
			}
			case 260 -> screenshot(client, "1_hud_wrapped_icons");
			case 265 -> aimAt(client, containers.get(1));
			case 275 -> screenshot(client, "1b_hud_named_chest");
			case 280 -> client.setScreen(new TooltipScreen(shulkerItem(Items.LIME_SHULKER_BOX,
					Items.DIAMOND, 64, Items.TOTEM_OF_UNDYING, 1, Items.ENDER_PEARL, 16, Items.GOLDEN_CARROT, 64, Items.ELYTRA, 1)));
			case 292 -> screenshot(client, "2_shulker_tooltip");
			case 300 -> client.setScreen(new SearchScreen());
			case 315 -> screenshot(client, "3_search_items");
			case 320 -> {
				if (client.currentScreen instanceof SearchScreen s) s.testQuery("diamond");
			}
			case 330 -> {
				if (client.currentScreen instanceof SearchScreen s) {
					int total = s.testTotal("Diamond");
					check("diamond total across containers incl. nested shulker is 119 (" + total + ")", total == 119);
				}
				screenshot(client, "4_search_diamond");
			}
			case 335 -> {
				if (client.currentScreen instanceof SearchScreen s) s.testView(true);
			}
			case 345 -> screenshot(client, "5_search_containers");
			case 350 -> {
				if (client.currentScreen instanceof SearchScreen s) {
					s.testView(false);
				}
			}
			case 322 -> {
				// Step back from the containers, or the locator counts them as already found.
				client.getServer().execute(() -> client.getServer().getPlayerManager().getPlayerList().get(0)
						.requestTeleport(origin.getX() - 14, origin.getY(), origin.getZ() - 14));
			}
			case 340 -> {
				log("Walling the player in so the containers are out of sight");
				BlockPos me = origin.add(-14, 0, -14);
				server(client, w -> {
					for (int dx = -3; dx <= 3; dx++) {
						for (int dz = -3; dz <= 3; dz++) {
							if (Math.abs(dx) != 3 && Math.abs(dz) != 3) continue;
							for (int dy = 0; dy <= 3; dy++) w.setBlockState(me.add(dx, dy, dz), Blocks.STONE.getDefaultState());
						}
					}
				});
			}
			case 358 -> {
				log("Player is " + (int) Math.sqrt(client.player.squaredDistanceTo(Vec3d.ofCenter(origin))) + " blocks from the containers");
				if (client.currentScreen instanceof SearchScreen s) s.testHighlightAll();
				log("Locator targets right after Highlight all: " + Locator.count());
			}
			case 362 -> {
				check("locator guiding to 4 diamond containers (" + Locator.count() + ")", Locator.count() == 4);
				aimAt(client, origin.add(1, 0, 1));
			}
			case 372 -> {
				log("Marker: " + Locator.lastMarker);
				screenshot(client, "6_locator_through_wall");
			}
			case 374 -> aimAt(client, origin.add(-40, 0, -40));
			case 379 -> {
				log("Marker after turning away: " + Locator.lastMarker);
				screenshot(client, "6b_locator_behind_you");
			}
			case 380 -> {
				Locator.stop();
				ModConfig.INSTANCE.labelBackground = true;
				ModConfig.INSTANCE.nameColor = ModConfig.NameColor.GOLD;
				client.getServer().execute(() -> client.getServer().getPlayerManager().getPlayerList().get(0)
						.requestTeleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5));
			}
			case 390 -> aimAt(client, containers.get(1));
			case 400 -> screenshot(client, "7_label_background_gold_name");
			case 405 -> client.setScreen(new ConfigScreen(null));
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
				client.setScreen(new SearchScreen());
			}
			case 485 -> {
				if (client.currentScreen instanceof SearchScreen s) {
					s.testQuery("");
					s.testFetch("Diamond", 2);
					s.testFetch("Firework Rocket", 1);
					s.testShowFetch();
				}
			}
			case 495 -> {
				if (client.currentScreen instanceof SearchScreen s) {
					check("fetch route planned (" + s.testRouteStops() + " stops)", s.testRouteStops() >= 2);
				}
				screenshot(client, "9_fetch_list");
			}
			case 500 -> {
				if (client.currentScreen instanceof SearchScreen s) s.testStartRoute();
			}
			case 505 -> check("route started at stop 1", Locator.onRoute() && Locator.routeIndex() == 0);
			case 510 -> aimAt(client, Locator.currentStop().pos());
			case 518 -> screenshot(client, "9b_fetch_strip");
			case 520 -> {
				BlockPos pos = Locator.currentStop().pos();
				BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false);
				client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
			}
			case 535 -> {
				check("opening the stop's container moves the route on (now at stop " + (Locator.routeIndex() + 1) + ")",
						Locator.routeIndex() == 1 || !Locator.onRoute());
				screenshot(client, "9c_fetch_take_here");
			}
			case 545 -> {
				client.player.closeHandledScreen();
				Locator.stop();
			}
			// ---- Forgetting only what's certainly gone ----
			case 550 -> {
				client.setScreen(null);
				log("The barrel shows as stone, as anti-xray sends hidden containers (no break)");
				client.world.setBlockState(containers.get(2), Blocks.STONE.getDefaultState());
			}
			case 600 -> {
				check("a container hidden by anti-xray is still remembered", known(client, containers.get(2)));
				log("The shulker box is broken while the player is there");
				server(client, w -> w.setBlockState(containers.get(3), Blocks.AIR.getDefaultState()));
				// And the barrel really goes too: the server sends air, which anti-xray never fakes.
				server(client, w -> w.setBlockState(containers.get(2), Blocks.AIR.getDefaultState()));
			}
			case 640 -> {
				check("a container broken in front of you is forgotten", !known(client, containers.get(3)));
				check("one that turns to air is forgotten", !known(client, containers.get(2)));
				// A second chest joins "Storage" on the side the pair is kept under: the name and contents go with it.
				BlockPos storage = containers.get(1), east = storage.east(), west = storage.west();
				boolean eastLower = east.asLong() < storage.asLong();
				BlockPos partner = eastLower ? east : west;
				log("A chest joins Storage on the " + (eastLower ? "east" : "west"));
				var facing = net.minecraft.state.property.Properties.HORIZONTAL_FACING;
				var type = net.minecraft.state.property.Properties.CHEST_TYPE;
				server(client, w -> {
					w.setBlockState(storage, w.getBlockState(storage).with(facing, Direction.NORTH)
							.with(type, eastLower ? net.minecraft.block.enums.ChestType.LEFT : net.minecraft.block.enums.ChestType.RIGHT));
					w.setBlockState(partner, Blocks.CHEST.getDefaultState().with(facing, Direction.NORTH)
							.with(type, eastLower ? net.minecraft.block.enums.ChestType.RIGHT : net.minecraft.block.enums.ChestType.LEFT));
				});
				containers.set(1, partner);
			}
			case 680 -> {
				NameStore.Entry pair = HoardEyeClient.INSTANCE.store().get(HoardEyeClient.worldKey(client),
						HoardEyeClient.dimensionKey(client.world), containers.get(1));
				check("a chest that becomes a double chest keeps its name and contents (" + (pair == null ? "none" : pair.name) + ")",
						pair != null && pair.name.equals("Storage") && pair.items != null && !pair.items.isEmpty());
				BlockPos partner = containers.get(1);
				server(client, w -> w.setBlockState(partner, Blocks.AIR.getDefaultState()));
				check("server addresses are one server however they're typed",
						HoardEyeClient.normalizeAddress("2B2T.org:25565.").equals(HoardEyeClient.normalizeAddress("2b2t.org")));
			}
			case 690 -> {
				log("DONE: " + passed + " passed, " + failed + " failed");
				client.scheduleStop();
			}
			default -> {}
		}
	}

	private static boolean known(MinecraftClient client, BlockPos pos) {
		return HoardEyeClient.INSTANCE.store().get(HoardEyeClient.worldKey(client), HoardEyeClient.dimensionKey(client.world), pos) != null;
	}

	private static void fill(ServerWorld world, BlockPos pos, net.minecraft.block.Block block, String name, ItemStack... stacks) {
		world.setBlockState(pos, block.getDefaultState());
		if (world.getBlockEntity(pos) instanceof LockableContainerBlockEntity be) {
			if (name != null) {
				ItemStack named = new ItemStack(block.asItem());
				named.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
				be.readComponents(named);
			}
			for (int i = 0; i < stacks.length; i++) be.setStack(i, stacks[i]);
			be.markDirty();
		}
	}

	/** Shows one item's tooltip in the middle of the screen. */
	private static final class TooltipScreen extends Screen {
		private final ItemStack stack;

		TooltipScreen(ItemStack stack) {
			super(Text.literal("tooltip"));
			this.stack = stack;
		}

		@Override
		public void render(DrawContext context, int mouseX, int mouseY, float delta) {
			super.render(context, mouseX, mouseY, delta);
			context.drawItemTooltip(textRenderer, stack, width / 2 - 90, height / 2 - 30);
		}
	}

	private static void showSettingsTab(MinecraftClient client, int tab) {
		if (client.currentScreen instanceof OptionsScreen options) options.showTab(tab);
	}

	private static void aimAt(MinecraftClient client, BlockPos target) {
		Vec3d eye = client.player.getEyePos();
		Vec3d d = Vec3d.ofCenter(target).subtract(eye);
		double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
		client.player.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
		client.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, horizontal)));
	}

	private static void server(MinecraftClient client, Consumer<ServerWorld> action) {
		IntegratedServer server = client.getServer();
		server.execute(() -> action.accept(server.getOverworld()));
	}

	private static void screenshot(MinecraftClient client, String name) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, "cp_" + name + ".png", client.getFramebuffer(), 1, message -> {});
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
