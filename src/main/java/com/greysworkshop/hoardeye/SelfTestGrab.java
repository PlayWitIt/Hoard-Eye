package com.greysworkshop.hoardeye;

import com.greysworkshop.hoardeye.mixin.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Development-only: drives the box picker through the game's real mouse and keyboard handlers, in Survival, on an
 * integrated world or on a real server (./gradlew runGrabTest -PgrabServer=host:port). Not in the release jar.
 */
public final class SelfTestGrab {
	private record Step(String label, BooleanSupplier ready, int timeout, Runnable action) {}

	private static final String WORLD = "grab-test";
	private static final String KIT = "red_shulker_box[custom_name=\"Kit\",container=["
			+ "{slot:0,item:{id:\"totem_of_undying\",count:1}},{slot:3,item:{id:\"totem_of_undying\",count:1}},"
			+ "{slot:9,item:{id:\"ender_pearl\",count:16}},{slot:10,item:{id:\"golden_apple\",count:64}},"
			+ "{slot:20,item:{id:\"netherite_sword\",count:1,components:{\"enchantments\":{\"sharpness\":5}}}},"
			+ "{slot:26,item:{id:\"firework_rocket\",count:64}}]]";

	private static final Deque<Step> steps = new ArrayDeque<>();
	private static boolean started, stopping;
	private static int waited, passed, failed, sleep;
	private static BlockPos origin, chest;
	private static boolean remote;
	private static List<ItemStack> savedHotbar;
	private static ItemStack savedTool;
	private static BlockPos usedSpot;
	private static float yaw, pitch;
	/** In a local world: how many server ticks the box really stood as a block, seen from the server itself. */
	private static volatile int serverBlockTicks;
	private static volatile boolean refusePlacing;
	private static final MinecraftClient mc = MinecraftClient.getInstance();

	private SelfTestGrab() {}

	public static void start() {
		remote = Boolean.getBoolean("hoardeye.grabtest.server");
		log("armed, " + (remote ? "on a real server" : "on an integrated world"));
		if (!remote) {
			ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
				if (started || !(screen instanceof TitleScreen)) return;
				started = true;
				client.getTutorialManager().setStep(net.minecraft.client.tutorial.TutorialStep.NONE);
				client.send(SelfTestGrab::openWorld);
			});
		}
		ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
		script();
	}

	private static void openWorld() {
		if (mc.getLevelStorage().levelExists(WORLD)) {
			mc.createIntegratedServerLoader().start(WORLD, () -> {});
			return;
		}
		LevelInfo info = new LevelInfo(WORLD, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true,
				new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
		mc.createIntegratedServerLoader().createAndStart(WORLD, info, GeneratorOptions.createTestWorld(),
				WorldPresets::createTestOptions, new TitleScreen());
	}

	private static void tick() {
		if (stopping || mc.player == null || mc.world == null) return;
		if (sleep > 0) {
			sleep--;
			return;
		}
		Step step = steps.peek();
		if (step == null) {
			stopping = true;
			log("RESULT passed=" + passed + " failed=" + failed);
			mc.scheduleStop();
			return;
		}
		if (!step.ready.getAsBoolean()) {
			if (++waited > step.timeout) {
				check("timed out waiting for: " + step.label + " (bot step " + BoxGrab.step() + ", last: " + BoxGrab.lastMessage + ")", false);
				steps.poll();
				waited = 0;
			}
			return;
		}
		waited = 0;
		steps.poll();
		try {
			step.action.run();
		} catch (Exception e) {
			check("step '" + step.label + "' threw " + e, false);
			log(java.util.Arrays.toString(e.getStackTrace()));
		}
	}

	// ---- Script helpers ----

	private static void then(String label, Runnable action) {
		steps.add(new Step(label, () -> true, 0, action));
	}

	private static void waitFor(String label, int timeout, BooleanSupplier ready) {
		steps.add(new Step(label, ready, timeout, () -> {}));
	}

	private static void pause(int ticks) {
		steps.add(new Step("pause", () -> true, 0, () -> sleep = ticks));
	}

	private static void cmd(String command) {
		if (mc.getServer() != null) {
			var server = mc.getServer();
			server.execute(() -> {
				var player = server.getPlayerManager().getPlayerList().get(0);
				server.getCommandManager().parseAndExecute(player.getCommandSource().withSilent(), command);
			});
		} else {
			mc.player.networkHandler.sendChatCommand(command);
		}
	}

	/** A clean floor, a chest to the side, an empty player in Survival. */
	private static void reset() {
		then("close", () -> {
			BoxPicker.close();
			if (mc.player.currentScreenHandler != mc.player.playerScreenHandler || mc.currentScreen != null) mc.player.closeHandledScreen();
			mc.player.getInventory().setSelectedSlot(0);
		});
		// Let the server see the close first, or /clear reports to a screen the client has already left.
		pause(5);
		then("reset", () -> {
			if (origin == null) origin = mc.player.getBlockPos();
			chest = origin.add(2, 0, 0);
			int x = origin.getX(), y = origin.getY(), z = origin.getZ();
			cmd("gamemode survival");
			cmd("difficulty peaceful");
			cmd("time set day");
			cmd("kill @e[type=item]");
			cmd("clear @s");
			cmd("item replace entity @s weapon.offhand with air");
			for (int i = 0; i < 27; i++) cmd("item replace entity @s enderchest." + i + " with air");
			cmd("fill " + (x - 6) + " " + y + " " + (z - 6) + " " + (x + 6) + " " + (y + 3) + " " + (z + 6) + " air");
			cmd("fill " + (x - 6) + " " + (y - 1) + " " + (z - 6) + " " + (x + 6) + " " + (y - 1) + " " + (z + 6) + " stone");
			cmd("tp @s " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 30 10");
			cmd("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " chest");
		});
		waitFor("clean start", 100, () -> mc.world.getBlockState(chest).getBlock() == net.minecraft.block.Blocks.CHEST
				&& mc.player.getInventory().isEmpty() && mc.player.squaredDistanceTo(Vec3d.ofBottomCenter(origin)) < 0.1);
		then("state", () -> {
			if (!mc.player.getInventory().isEmpty() || mc.player.squaredDistanceTo(Vec3d.ofBottomCenter(origin)) >= 0.1) {
				log("not clean: origin " + origin + " player " + mc.player.getEntityPos() + " inv empty " + mc.player.getInventory().isEmpty() + " screen " + mc.currentScreen + " handler " + mc.player.currentScreenHandler);
				for (int i = 0; i < mc.player.getInventory().size(); i++) if (!mc.player.getInventory().getStack(i).isEmpty()) log(" slot " + i + " " + mc.player.getInventory().getStack(i));
			}
		});
		pause(5);
		then("remember inventory", () -> {
			yaw = mc.player.getYaw();
			pitch = mc.player.getPitch();
		});
	}

	private static void boxInChest(int slot) {
		then("box into chest", () -> cmd("item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " container." + slot + " with " + KIT));
		pause(10);
	}

	private static void openBlock(BlockPos pos) {
		Vec3d hit = Vec3d.ofCenter(pos).add(0, 0.45, 0);
		mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
	}

	private static void openChest() {
		then("open chest", () -> openBlock(chest));
		waitFor("chest screen", 100, () -> mc.currentScreen instanceof GenericContainerScreen && mc.player.currentScreenHandler.getRevision() != 0);
		pause(2);
	}

	// ---- Real input ----

	private static void mouseTo(double guiX, double guiY) {
		try {
			double scale = mc.getWindow().getScaleFactor();
			Field x = Mouse.class.getDeclaredField("x"), y = Mouse.class.getDeclaredField("y");
			x.setAccessible(true);
			y.setAccessible(true);
			x.setDouble(mc.mouse, guiX * scale);
			y.setDouble(mc.mouse, guiY * scale);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void button(int button, int action, int modifiers) {
		try {
			Method m = Mouse.class.getDeclaredMethod("onMouseButton", long.class, MouseInput.class, int.class);
			m.setAccessible(true);
			m.invoke(mc.mouse, mc.getWindow().getHandle(), new MouseInput(button, modifiers), action);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void clickButton(int button, int modifiers) {
		button(button, GLFW.GLFW_PRESS, modifiers);
		button(button, GLFW.GLFW_RELEASE, modifiers);
	}

	private static void scroll(double amount) {
		try {
			Method m = Mouse.class.getDeclaredMethod("onMouseScroll", long.class, double.class, double.class);
			m.setAccessible(true);
			m.invoke(mc.mouse, mc.getWindow().getHandle(), 0d, amount);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void key(int code) {
		try {
			Method m = Keyboard.class.getDeclaredMethod("onKey", long.class, int.class, KeyInput.class);
			m.setAccessible(true);
			m.invoke(mc.keyboard, mc.getWindow().getHandle(), GLFW.GLFW_PRESS, new KeyInput(code, 0, 0));
			m.invoke(mc.keyboard, mc.getWindow().getHandle(), GLFW.GLFW_RELEASE, new KeyInput(code, 0, 0));
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static void hoverSlot(int slotId) {
		then("hover slot " + slotId, () -> {
			HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
			Slot slot = screen.getScreenHandler().slots.get(slotId);
			HandledScreenAccessor a = (HandledScreenAccessor) screen;
			mouseTo(a.hoardeye$getX() + slot.x + 8, a.hoardeye$getY() + slot.y + 8);
		});
		pause(3);
	}

	private static void openPickerOn(int slotId) {
		hoverSlot(slotId);
		then("middle-click", () -> clickButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0));
		waitFor("picker open", 20, BoxPicker::isOpen);
		pause(2);
	}

	private static void clickCell(int cell, int button, int modifiers) {
		then("click cell " + cell, () -> {
			int[] at = BoxPicker.testCell(cell);
			mouseTo(at[0], at[1]);
			clickButton(button, modifiers);
		});
	}

	private static void pressEnterAndWait() {
		then("enter", () -> {
			serverBlockTicks = 0;
			key(GLFW.GLFW_KEY_ENTER);
		});
		then("started", () -> check("the bot started", BoxGrab.running() || !BoxGrab.lastMessage.isEmpty()));
		then("note spot", () -> usedSpot = BoxGrab.spot());
		waitFor("the bot to finish", 1200, () -> !BoxGrab.running());
		pause(5);
		then("log", () -> log("took " + BoxGrab.lastTicks + " ticks, box out " + BoxGrab.lastBoxOutTicks + ", on the ground " + BoxGrab.lastGroundTicks
				+ (BoxGrab.lastBurst ? " (burst)" : " (steps)") + ", block stood " + serverBlockTicks + " server ticks: " + BoxGrab.lastMessage));
	}

	private static void shot(String name) {
		then("screenshot", () -> {
			ScreenshotRecorder.saveScreenshot(mc.runDirectory, "grab_" + name + ".png", mc.getFramebuffer(), 1, m -> {});
			log("screenshot " + name);
		});
		pause(3);
	}

	// ---- Assertions ----

	private static int count(Item item) {
		PlayerInventory inv = mc.player.getInventory();
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(item)) n += inv.getStack(i).getCount();
		return n;
	}

	private static int groundItems() {
		return mc.world.getEntitiesByClass(ItemEntity.class, new Box(origin).expand(16), e -> true).size();
	}

	private static DefaultedList<ItemStack> contentsOf(ItemStack box) {
		DefaultedList<ItemStack> list = DefaultedList.ofSize(27, ItemStack.EMPTY);
		ContainerComponent c = box.get(DataComponentTypes.CONTAINER);
		if (c != null) c.copyTo(list);
		return list;
	}

	private static void checkWorldClean(String where) {
		then("world clean", () -> {
			check(where + ": no box left in the world", usedSpot == null || !(mc.world.getBlockState(usedSpot).getBlock() instanceof ShulkerBoxBlock));
			check(where + ": nothing left on the ground (" + groundItems() + ")", groundItems() == 0);
			check(where + ": camera back as it was (" + mc.player.getYaw() + "," + mc.player.getPitch() + " vs " + yaw + "," + pitch + ")", Math.abs(mc.player.getYaw() - yaw) < 0.01 && Math.abs(mc.player.getPitch() - pitch) < 0.01);
		});
	}

	// ---- The tests ----

	private static void script() {
		waitFor("in the world", 2400, () -> mc.player != null);
		// The desktop's own pointer events would overwrite where the test puts the mouse, and spin the camera.
		then("no desktop pointer", () -> GLFW.glfwSetCursorPosCallback(mc.getWindow().getHandle(), null));
		then("server watch", () -> {
			if (remote) return;
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_WORLD_TICK.register(world -> {
				BlockPos at = BoxGrab.spot();
				if (at != null && BoxGrab.running() && world.getBlockState(at).getBlock() instanceof ShulkerBoxBlock) {
					serverBlockTicks++;
				}
			});
			// A real refusal from the server's side, as a protection plugin would give.
			net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) ->
					!world.isClient() && refusePlacing && !(world.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.block.ChestBlock) ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS);
		});
		pause(60);

		// Meteor's Better Tooltips also wants middle-click on a shulker box. With it on, the picker must still win.
		then("meteor", () -> {
			try {
				Class<?> modules = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
				Class<?> tips = Class.forName("meteordevelopment.meteorclient.systems.modules.render.BetterTooltips");
				Object module = modules.getMethod("get", Class.class).invoke(modules.getMethod("get").invoke(null), tips);
				if (!(boolean) module.getClass().getMethod("isActive").invoke(module)) module.getClass().getMethod("toggle").invoke(module);
				check("Meteor's Better Tooltips is on for this run", (boolean) module.getClass().getMethod("isActive").invoke(module));
			} catch (ClassNotFoundException e) {
				log("Meteor isn't loaded in this run");
			} catch (ReflectiveOperationException e) {
				check("could not switch on Better Tooltips: " + e, false);
			}
		});

		// A: the main path, no tool, real input for every way of picking. By hand it takes 3s, proving the break isn't cancelled.
		reset();
		boxInChest(5);
		openChest();
		then("tooltip", () -> {
			ItemStack box = mc.player.currentScreenHandler.slots.get(5).getStack();
			check("A: box is in the chest before anything happens", box.isOf(Items.RED_SHULKER_BOX));
			boolean hint = false;
			for (var line : box.getTooltip(net.minecraft.item.Item.TooltipContext.DEFAULT, mc.player, net.minecraft.item.tooltip.TooltipType.BASIC)) {
				if (line.getString().contains("pick what to take")) hint = true;
			}
			check("A: the box's tooltip says how to open the picker", hint);
		});
		hoverSlot(5);
		shot("0_tooltip_in_chest");
		then("middle", () -> clickButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0));
		waitFor("picker open", 20, BoxPicker::isOpen);
		pause(3);
		then("nothing moved", () -> check("A: opening the picker changes nothing: box still in the chest", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX)));
		shot("1_picker_empty");
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		clickCell(20, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		clickCell(9, GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);
		then("scroll", () -> {
			int[] at = BoxPicker.testCell(10);
			mouseTo(at[0], at[1]);
		});
		pause(2);
		then("scroll", () -> {
			for (int i = 0; i < 6; i++) scroll(1);
			scroll(-1);
		});
		pause(3);
		then("picks", () -> {
			check("A: left-click picks the whole stack", BoxPicker.testWant(0) == 1 && BoxPicker.testWant(20) == 1);
			check("A: right-click picks half (" + BoxPicker.testWant(9) + ")", BoxPicker.testWant(9) == 8);
			check("A: scrolling sets the amount (" + BoxPicker.testWant(10) + ")", BoxPicker.testWant(10) == 5);
			check("A: empty cells can't be picked", BoxPicker.testWant(1) == 0);
			check("A: clicks don't reach the chest behind the picker", mc.player.currentScreenHandler.getCursorStack().isEmpty()
					&& mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
		});
		shot("2_picker_picked");
		pressEnterAndWait();
		then("A results", () -> {
			check("A: reported success: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			check("A: got exactly 1 totem (" + count(Items.TOTEM_OF_UNDYING) + ")", count(Items.TOTEM_OF_UNDYING) == 1);
			check("A: got the sword", count(Items.NETHERITE_SWORD) == 1);
			check("A: got 8 pearls (" + count(Items.ENDER_PEARL) + ")", count(Items.ENDER_PEARL) == 8);
			check("A: got 5 apples (" + count(Items.GOLDEN_APPLE) + ")", count(Items.GOLDEN_APPLE) == 5);
			check("A: rockets stayed behind", count(Items.FIREWORK_ROCKET) == 0);
			check("A: the chest is open again", mc.currentScreen instanceof GenericContainerScreen);
			check("A: no box in the inventory", count(Items.RED_SHULKER_BOX) == 0);
			check("A: hand slot as it was", mc.player.getInventory().getSelectedSlot() == 0);
		});
		checkWorldClean("A");
		shot("3_back_in_chest");
		// Ask the server for the truth: close and open again.
		then("close", () -> mc.player.closeHandledScreen());
		pause(5);
		openChest();
		then("A box", () -> {
			ItemStack box = mc.player.currentScreenHandler.slots.get(5).getStack();
			check("A: box is back in the very same slot, on the server", box.isOf(Items.RED_SHULKER_BOX));
			check("A: it kept its name", "Kit".equals(box.getName().getString()));
			DefaultedList<ItemStack> c = contentsOf(box);
			check("A: what wasn't taken stayed in the same slots", c.get(0).isEmpty() && c.get(3).isOf(Items.TOTEM_OF_UNDYING) && c.get(9).getCount() == 8
					&& c.get(10).getCount() == 59 && c.get(20).isEmpty() && c.get(26).getCount() == 64);
			ItemStack sword = ItemStack.EMPTY;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (mc.player.getInventory().getStack(i).isOf(Items.NETHERITE_SWORD)) sword = mc.player.getInventory().getStack(i);
			check("A: the sword kept its enchantment", sword.hasEnchantments());
		});

		// B: instant: full hotbar, pickaxe in the main inventory: fast, and everything goes back where it was.
		reset();
		then("instant on", () -> ModConfig.INSTANCE.boxPickerInstant = true);
		boxInChest(13);
		then("give", () -> {
			String[] bar = {"stone", "dirt", "oak_log", "cobblestone", "sand", "gravel", "torch", "bread", "arrow"};
			for (int i = 0; i < 9; i++) cmd("item replace entity @s hotbar." + i + " with " + bar[i] + " 7");
			cmd("item replace entity @s inventory.11 with netherite_pickaxe[enchantments={efficiency:5}]");
		});
		pause(10);
		then("select", () -> mc.player.getInventory().setSelectedSlot(4));
		pause(3);
		then("save", () -> {
			savedHotbar = new ArrayList<>();
			for (int i = 0; i < 9; i++) savedHotbar.add(mc.player.getInventory().getStack(i).copy());
			savedTool = mc.player.getInventory().getStack(20).copy();
		});
		openChest();
		openPickerOn(13);
		clickCell(3, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		pressEnterAndWait();
		then("B results", () -> {
			check("B: success: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			check("B: fast with a good pickaxe (" + BoxGrab.lastBoxOutTicks + " ticks out of the chest)", BoxGrab.lastBoxOutTicks < (remote ? 32 : 24));
			check("B: went in one burst", BoxGrab.lastBurst);
			if (!remote) check("B: the box stood as a block for at most 1 server tick (" + serverBlockTicks + ")", serverBlockTicks <= 1);
			check("B: on the ground barely longer than the server's pickup delay (" + BoxGrab.lastGroundTicks + ")", BoxGrab.lastGroundTicks <= (remote ? 26 : 20));
			PlayerInventory inv = mc.player.getInventory();
			boolean same = true;
			for (int i = 0; i < 9; i++) same &= ItemStack.areEqual(inv.getStack(i), savedHotbar.get(i));
			check("B: hotbar exactly as it was", same);
			check("B: pickaxe back in its slot", ItemStack.areItemsEqual(inv.getStack(20), savedTool));
			check("B: selected slot as it was", inv.getSelectedSlot() == 4);
			check("B: got the totem", count(Items.TOTEM_OF_UNDYING) == 1);
			check("B: box in slot 13", mc.player.currentScreenHandler.slots.get(13).getStack().isOf(Items.RED_SHULKER_BOX));
		});
		checkWorldClean("B");

		// B2: straight away from the chest that's still open, as you would for a second box or a second item.
		openPickerOn(13);
		clickCell(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		pressEnterAndWait();
		then("B2 results", () -> {
			check("B2: a second pick from the same open chest: " + BoxGrab.lastMessage, BoxGrab.lastOk && BoxGrab.lastBurst);
			check("B2: got the pearls", count(Items.ENDER_PEARL) == 16);
			check("B2: box in slot 13", mc.player.currentScreenHandler.slots.get(13).getStack().isOf(Items.RED_SHULKER_BOX));
		});
		checkWorldClean("B2");

		// C: the ender chest, everything out, using the All button.
		reset();
		BlockPos[] ender = new BlockPos[1];
		then("ender", () -> {
			ender[0] = origin.add(0, 0, 2);
			cmd("setblock " + ender[0].getX() + " " + ender[0].getY() + " " + ender[0].getZ() + " ender_chest");
			cmd("item replace entity @s enderchest.3 with " + KIT);
			cmd("item replace entity @s hotbar.0 with diamond_pickaxe");
		});
		pause(10);
		then("open", () -> openBlock(ender[0]));
		waitFor("ender chest screen", 100, () -> mc.currentScreen instanceof GenericContainerScreen && mc.player.currentScreenHandler.getRevision() != 0);
		pause(3);
		openPickerOn(3);
		then("all", () -> {
			int[] at = BoxPicker.testButton("ALL");
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		});
		pause(2);
		shot("4_picker_all");
		pressEnterAndWait();
		then("C results", () -> {
			check("C: success: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			check("C: everything came out", count(Items.TOTEM_OF_UNDYING) == 2 && count(Items.ENDER_PEARL) == 16 && count(Items.GOLDEN_APPLE) == 64
					&& count(Items.NETHERITE_SWORD) == 1 && count(Items.FIREWORK_ROCKET) == 64);
			ItemStack box = mc.player.currentScreenHandler.slots.get(3).getStack();
			check("C: the now empty box is back in the ender chest", box.isOf(Items.RED_SHULKER_BOX) && contentsOf(box).stream().allMatch(ItemStack::isEmpty));
		});
		checkWorldClean("C");

		// C2: a pick that takes more clicks than go out in one tick, so the take runs on after the burst.
		reset();
		boxInChest(5);
		then("hand", () -> cmd("item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]"));
		pause(5);
		openChest();
		openPickerOn(5);
		for (int cell : new int[]{10, 26}) {
			then("hover", () -> {
				int[] at = BoxPicker.testCell(cell);
				mouseTo(at[0], at[1]);
			});
			pause(2);
			then("scroll", () -> {
				for (int i = 0; i < 30; i++) scroll(1);
			});
			pause(2);
		}
		pressEnterAndWait();
		then("C2 results", () -> {
			check("C2: a long pick in a burst: " + BoxGrab.lastMessage, BoxGrab.lastOk && BoxGrab.lastBurst);
			check("C2: exact amounts (" + count(Items.GOLDEN_APPLE) + "," + count(Items.FIREWORK_ROCKET) + ")", count(Items.GOLDEN_APPLE) == 30 && count(Items.FIREWORK_ROCKET) == 30);
			ItemStack box = mc.player.currentScreenHandler.slots.get(5).getStack();
			DefaultedList<ItemStack> c = contentsOf(box);
			check("C2: the rest stayed in the box", box.isOf(Items.RED_SHULKER_BOX) && c.get(10).getCount() == 34 && c.get(26).getCount() == 34);
		});
		checkWorldClean("C2");
		then("instant off", () -> ModConfig.INSTANCE.boxPickerInstant = false);

		// D: a box in your own inventory, from the inventory screen.
		reset();
		then("give", () -> {
			cmd("item replace entity @s inventory.6 with " + KIT);
			cmd("item replace entity @s hotbar.0 with diamond_pickaxe");
		});
		pause(10);
		then("inv", () -> mc.setScreen(new InventoryScreen(mc.player)));
		pause(5);
		then("D hover", () -> {
			HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
			for (Slot s : screen.getScreenHandler().slots) {
				if (s.inventory == mc.player.getInventory() && s.getIndex() == 15) {
					HandledScreenAccessor a = (HandledScreenAccessor) screen;
					mouseTo(a.hoardeye$getX() + s.x + 8, a.hoardeye$getY() + s.y + 8);
				}
			}
		});
		pause(3);
		then("key G", () -> {
			HandledScreenAccessor a = (HandledScreenAccessor) mc.currentScreen;
			log("D: screen " + mc.currentScreen + " focused " + a.hoardeye$getFocusedSlot() + " stack " + (a.hoardeye$getFocusedSlot() == null ? null : a.hoardeye$getFocusedSlot().getStack())
					+ " slot15 " + mc.player.getInventory().getStack(15) + " mouse " + mc.mouse.getX() + "," + mc.mouse.getY() + " panel " + a.hoardeye$getX() + "," + a.hoardeye$getY());
			key(GLFW.GLFW_KEY_G);
		});
		waitFor("picker open by key", 20, BoxPicker::isOpen);
		clickCell(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(2);
		pressEnterAndWait();
		then("D results", () -> {
			check("D: success: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			check("D: box back in the same inventory slot", mc.player.getInventory().getStack(15).isOf(Items.RED_SHULKER_BOX));
			check("D: got the pearls", count(Items.ENDER_PEARL) == 16);
			check("D: back on the inventory screen", mc.currentScreen instanceof InventoryScreen);
		});
		checkWorldClean("D");

		// E: no room: refused before anything happens.
		reset();
		boxInChest(5);
		then("fill", () -> {
			for (int i = 0; i < 9; i++) cmd("item replace entity @s hotbar." + i + " with stone 64");
			for (int i = 0; i < 27; i++) cmd("item replace entity @s inventory." + i + " with dirt 64");
		});
		pause(15);
		openChest();
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(3);
		then("E", () -> {
			check("E: full inventory is refused: " + BoxPicker.testProblem().orElse("(none)"), BoxPicker.testProblem().isPresent());
			key(GLFW.GLFW_KEY_ENTER);
			check("E: Enter does nothing", !BoxGrab.running() && BoxPicker.isOpen());
			check("E: box not touched", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
		});
		shot("5_no_room");

		// F: nowhere to set it down.
		reset();
		boxInChest(5);
		then("wall in", () -> {
			int x = origin.getX(), y = origin.getY(), z = origin.getZ();
			cmd("fill " + (x - 2) + " " + y + " " + (z - 2) + " " + (x + 2) + " " + (y + 1) + " " + (z + 2) + " glass");
			cmd("setblock " + x + " " + y + " " + z + " air");
			cmd("setblock " + x + " " + (y + 1) + " " + z + " air");
			cmd("setblock " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " chest");
			cmd("setblock " + chest.getX() + " " + (chest.getY() + 1) + " " + chest.getZ() + " air");
			cmd("item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " container.5 with " + KIT);
		});
		pause(15);
		openChest();
		then("F spots", () -> {
			List<BlockPos> spots = BoxGrab.spots(mc.player, mc.world);
			log("F spots: " + spots);
		});
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(3);
		then("F", () -> {
			check("F: no spot is refused: " + BoxPicker.testProblem().orElse("(none)"), !BoxPicker.testHasSpot() && BoxPicker.testProblem().isPresent());
			key(GLFW.GLFW_KEY_ENTER);
			check("F: Enter does nothing", !BoxGrab.running());
		});
		shot("6_no_spot");

		// G: the box is taken from the chest while the picker is open.
		reset();
		boxInChest(5);
		openChest();
		openPickerOn(5);
		then("steal", () -> cmd("item replace block " + chest.getX() + " " + chest.getY() + " " + chest.getZ() + " container.5 with air"));
		waitFor("picker to close", 60, () -> !BoxPicker.isOpen());
		then("G", () -> {
			check("G: picker closes when the box is gone", !BoxPicker.isOpen());
			check("G: chest stays open, bot idle", mc.currentScreen instanceof GenericContainerScreen && !BoxGrab.running());
		});

		// H: crouching with something in the offhand, which would stop a box opening.
		reset();
		boxInChest(5);
		then("offhand", () -> {
			cmd("item replace entity @s weapon.offhand with totem_of_undying");
			cmd("item replace entity @s hotbar.0 with diamond_pickaxe");
		});
		pause(10);
		openChest();
		openPickerOn(5);
		clickCell(10, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		then("crouch", () -> mc.options.sneakKey.setPressed(true));
		pause(3);
		pressEnterAndWait();
		then("H", () -> {
			check("H: works while crouching: " + BoxGrab.lastMessage, BoxGrab.lastOk && count(Items.GOLDEN_APPLE) == 64);
			mc.options.sneakKey.setPressed(false);
		});
		checkWorldClean("H");

		// I: Escape leaves the picker only; the chest stays open. Then shift-click and drag-painting.
		reset();
		boxInChest(5);
		openChest();
		openPickerOn(5);
		then("esc", () -> key(GLFW.GLFW_KEY_ESCAPE));
		pause(3);
		then("I", () -> check("I: Escape closes the picker, not the chest", !BoxPicker.isOpen() && mc.currentScreen instanceof GenericContainerScreen));
		then("no box", () -> {
			Slot empty = mc.player.currentScreenHandler.slots.get(6);
			HandledScreenAccessor a = (HandledScreenAccessor) mc.currentScreen;
			mouseTo(a.hoardeye$getX() + empty.x + 8, a.hoardeye$getY() + empty.y + 8);
		});
		pause(3);
		then("I2", () -> {
			clickButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0);
			check("I: middle-click on nothing doesn't open it", !BoxPicker.isOpen());
		});
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT);
		pause(2);
		then("I3", () -> check("I: shift-click picks every stack of that item", BoxPicker.testWant(0) == 1 && BoxPicker.testWant(3) == 1));
		then("drag", () -> {
			int[] at = BoxPicker.testCell(9);
			mouseTo(at[0], at[1]);
			button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
		});
		pause(2);
		then("drag2", () -> {
			int[] at = BoxPicker.testCell(10);
			mouseTo(at[0], at[1]);
		});
		pause(3);
		then("drag3", () -> {
			button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
			check("I: dragging across slots picks them all", BoxPicker.testWant(9) == 16 && BoxPicker.testWant(10) == 64);
		});
		then("cancel", () -> {
			int[] at = BoxPicker.testButton("CANCEL");
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			check("I: Cancel closes it and nothing moved", !BoxPicker.isOpen() && mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
		});

		// J: the box can't be set down after all: it goes straight back.
		reset();
		boxInChest(5);
		then("hand", () -> cmd("item replace entity @s hotbar.0 with diamond_pickaxe"));
		pause(5);
		openChest();
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		then("fail", () -> BoxGrab.testFailPlaces = 99);
		pause(2);
		pressEnterAndWait();
		then("J", () -> {
			BoxGrab.testFailPlaces = 0;
			check("J: says it couldn't: " + BoxGrab.lastMessage, !BoxGrab.lastOk && BoxGrab.lastMessage.contains("set down"));
			check("J: box back in the chest, untouched", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX)
					&& contentsOf(mc.player.currentScreenHandler.slots.get(5).getStack()).get(0).isOf(Items.TOTEM_OF_UNDYING));
		});

		// K: pulled away while the box is out: it stops and says where the box is.
		reset();
		boxInChest(5);
		openChest();
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		then("enter", () -> key(GLFW.GLFW_KEY_ENTER));
		waitFor("breaking", 400, () -> BoxGrab.step() == BoxGrab.Step.BREAK);
		then("tp", () -> {
			usedSpot = BoxGrab.spot();
			cmd("tp @s " + (origin.getX() + 12) + " " + origin.getY() + " " + origin.getZ());
		});
		waitFor("bot to stop", 200, () -> !BoxGrab.running());
		then("K", () -> {
			check("K: says where the box is: " + BoxGrab.lastMessage, !BoxGrab.lastOk && BoxGrab.lastMessage.contains(usedSpot.toShortString()));
			check("K: the box is there", mc.world.getBlockState(usedSpot).getBlock() instanceof ShulkerBoxBlock);
			check("K: keys let go", !mc.options.forwardKey.isPressed());
		});

		// L: someone grabs the dropped box: it says so rather than hanging.
		reset();
		boxInChest(5);
		then("hand", () -> cmd("item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]"));
		pause(5);
		openChest();
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		then("enter", () -> key(GLFW.GLFW_KEY_ENTER));
		waitFor("collecting", 400, () -> BoxGrab.step() == BoxGrab.Step.COLLECT);
		then("gone", () -> cmd("kill @e[type=item]"));
		waitFor("bot to stop", 300, () -> !BoxGrab.running());
		then("L", () -> check("L: reports the box gone: " + BoxGrab.lastMessage, !BoxGrab.lastOk && BoxGrab.lastMessage.contains("gone")));

		// N: the server refuses the placement outright, after the burst has gone: nothing lost, and step by step from then on.
		if (remote) log("N: skipped on a real server");
		else {
			reset();
			boxInChest(5);
			then("hand", () -> cmd("item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]"));
			pause(5);
			openChest();
			openPickerOn(5);
			clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			pause(1);
			then("refuse", () -> {
				refusePlacing = true;
				ModConfig.INSTANCE.boxPickerInstant = true;
			});
			pressEnterAndWait();
			then("N", () -> {
				refusePlacing = false;
				check("N: says so: " + BoxGrab.lastMessage, !BoxGrab.lastOk && BoxGrab.lastMessage.contains("step by step"));
				check("N: nothing guessed stays in the inventory", count(Items.TOTEM_OF_UNDYING) == 0);
				check("N: the box is back in the chest with everything in it", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX)
						&& contentsOf(mc.player.currentScreenHandler.slots.get(5).getStack()).get(0).isOf(Items.TOTEM_OF_UNDYING));
				check("N: no more bursts on this server", !BoxGrab.testBurstWorks());
			});
			// Same again: now it goes step by step and works.
			openPickerOn(5);
			clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			pause(1);
			pressEnterAndWait();
			then("N2", () -> {
				check("N: the retry went step by step: " + BoxGrab.lastMessage, BoxGrab.lastOk && !BoxGrab.lastBurst && count(Items.TOTEM_OF_UNDYING) == 1);
				BoxGrab.testAllowBurst();
				ModConfig.INSTANCE.boxPickerInstant = false;
			});
		}

		// O: the speed setting, slowest to fastest: all correct, and it really changes how long it takes.
		int[] timed = new int[3];
		int[] speeds = {50, 200, 500};
		for (int n = 0; n < speeds.length; n++) {
			int which = n;
			reset();
			boxInChest(5);
			then("hand", () -> {
				ModConfig.INSTANCE.boxPickerSpeed = speeds[which];
				cmd("item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]");
			});
			pause(5);
			openChest();
			openPickerOn(5);
			clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			clickCell(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			clickCell(26, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			pause(1);
			pressEnterAndWait();
			then("O", () -> {
				timed[which] = BoxGrab.lastTicks;
				log("O: speed " + speeds[which] + "% took " + BoxGrab.lastTicks + " ticks, box out " + BoxGrab.lastBoxOutTicks);
				check("O: right at speed " + speeds[which] + "%: " + BoxGrab.lastMessage, BoxGrab.lastOk && !BoxGrab.lastBurst
						&& count(Items.TOTEM_OF_UNDYING) == 1 && count(Items.ENDER_PEARL) == 16 && count(Items.FIREWORK_ROCKET) == 64);
				check("O: box back at speed " + speeds[which] + "%", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
			});
			checkWorldClean("O");
		}
		then("O2", () -> {
			ModConfig.INSTANCE.boxPickerSpeed = 200;
			check("O: slower setting takes longer (" + timed[0] + " > " + timed[1] + " > " + timed[2] + ")", timed[0] > timed[1] && timed[1] > timed[2]);
		});

		// P: the clicks get lost on the way (a server not keeping up): the box isn't broken until the items are really out.
		reset();
		boxInChest(5);
		then("hand", () -> {
			cmd("item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]");
			BoxGrab.testLoseClicks = 2;
		});
		pause(5);
		openChest();
		openPickerOn(5);
		clickCell(10, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		pressEnterAndWait();
		then("P", () -> {
			check("P: it noticed and took them again (" + BoxGrab.lastTakeTries + " passes): " + BoxGrab.lastMessage, BoxGrab.lastOk && BoxGrab.lastTakeTries == 3);
			check("P: got all the apples", count(Items.GOLDEN_APPLE) == 64);
			check("P: box back without them", contentsOf(mc.player.currentScreenHandler.slots.get(5).getStack()).get(10).isEmpty());
		});
		checkWorldClean("P");

		// Every message the picker can show has to fit its panel.
		then("texts", () -> {
			for (String key : List.of("hint", "nothing", "safe", "ready", "no_pick", "no_spot", "no_room", "cursor", "survival_only", "nobody")) {
				int width = mc.textRenderer.getWidth(net.minecraft.text.Text.translatable("hoardeye.picker." + key));
				int room = key.equals("hint") ? 178 : 198;
				check("picker text '" + key + "' fits (" + width + " of " + room + ")", width <= room);
			}
		});

		// M: switched off in settings.
		reset();
		boxInChest(5);
		openChest();
		then("off", () -> ModConfig.INSTANCE.boxPicker = false);
		hoverSlot(5);
		then("M", () -> {
			clickButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0);
			check("M: off in settings, middle-click does nothing", !BoxPicker.isOpen());
			ModConfig.INSTANCE.boxPicker = true;
		});
		then("close", () -> mc.player.closeHandledScreen());
	}

	private static void check(String what, boolean ok) {
		if (ok) passed++;
		else failed++;
		log((ok ? "PASS " : "FAIL ") + what);
	}

	private static void log(String message) {
		HoardEyeClient.LOGGER.info("[GRABTEST] {}", message);
	}
}
