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
	private static final int[] dirtSlot = new int[2];
	/** Self-test: a server as fussy as a real one about opening an ender chest. */
	private static volatile boolean strictEnder;
	private static volatile int refuseNext, refusedHolding, refusedAim, refusedCount, openedStrict;
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
			BoxGrab.testKeepOpen = true;
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

	/** The way a person clicks it: on whichever face is really in view. */
	private static void openBlockInView(BlockPos pos) {
		Vec3d eye = mc.player.getEyePos();
		BlockHitResult ray = mc.world.raycast(new net.minecraft.world.RaycastContext(eye, eye.add(Vec3d.ofCenter(pos).subtract(eye).multiply(1.2)),
				net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
		mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, ray);
	}

	/** A box at slot 3 of an ender chest; clicks it open, takes everything, and it has to end up back in that slot. */
	private static void enderRun(String name, BlockPos[] ender, Runnable beforePicker) {
		pause(10);
		then(name + " open", () -> openBlockInView(ender[0]));
		waitFor(name + " screen", 100, () -> mc.currentScreen instanceof GenericContainerScreen && mc.player.currentScreenHandler.getRevision() != 0);
		pause(3);
		if (beforePicker != null) then(name + " before", beforePicker);
		openPickerOn(3);
		hit("ALL");
		pressEnterAndWait();
		then(name + " results", () -> {
			check(name + ": success: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			ItemStack box = mc.player.currentScreenHandler.slots.get(3).getStack();
			check(name + ": the box is back in the ender chest", box.isOf(Items.RED_SHULKER_BOX));
			check(name + ": clicked from within a player's reach (" + BoxGrab.lastReopenDistance + ")", BoxGrab.lastReopenDistance <= 4.5);
		});
		shot("ender_" + name);
	}

	/** Where these angles point: the server's own getRotationVec goes by the head, which it only updates on its tick. */
	private static Vec3d lookOf(float yaw, float pitch) {
		double y = Math.toRadians(yaw), x = Math.toRadians(pitch);
		return new Vec3d(-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x));
	}

	/** Press here, move there a frame or two, let go: through the game's real mouse handler. */
	private static void drag(java.util.function.Supplier<int[]> from, java.util.function.Supplier<int[]> to, int mouseButton) {
		then("drag start", () -> {
			int[] at = from.get();
			mouseTo(at[0], at[1]);
		});
		pause(2);
		then("drag press", () -> button(mouseButton, GLFW.GLFW_PRESS, 0));
		pause(2);
		then("drag move", () -> {
			int[] at = to.get();
			mouseTo(at[0], at[1]);
		});
		pause(3);
	}

	private static void drop(int mouseButton) {
		then("drop", () -> button(mouseButton, GLFW.GLFW_RELEASE, 0));
		pause(2);
	}

	private static void pressEnterAndWait2() {
		then("enter", () -> key(GLFW.GLFW_KEY_ENTER));
		waitFor("the bot to start", 40, BoxGrab::running);
		waitFor("the bot to finish", 1600, () -> !BoxGrab.running());
		pause(5);
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
		then("why not", () -> {
			if (!BoxPicker.isOpen()) log("picker shut: screen " + mc.currentScreen + ", cursor " + mc.player.currentScreenHandler.getCursorStack() + ", slot "
					+ (slotId < mc.player.currentScreenHandler.slots.size() ? mc.player.currentScreenHandler.slots.get(slotId).getStack() : null) + ", running " + BoxGrab.running());
		});
		pause(2);
	}

	private static void hit(String name, int button, int modifiers) {
		// Some live behind More: open it, press, and shut it again so what comes next sees the grids.
		then("more for " + name, () -> BoxPicker.testSetMore(BoxPicker.testBehindMore(name)));
		pause(2);
		then("button " + name, () -> {
			int[] at = BoxPicker.testButton(name);
			mouseTo(at[0], at[1]);
			clickButton(button, modifiers);
		});
		pause(2);
		if (!name.equals("SEARCH") && !name.equals("SAVE")) {
			then("more off", () -> BoxPicker.testSetMore(false));
			pause(1);
		}
	}

	private static void hit(String name) {
		hit(name, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
	}

	private static void clickInv(int index, int button, int modifiers) {
		then("click inventory " + index, () -> {
			int[] at = BoxPicker.testInv(index);
			mouseTo(at[0], at[1]);
			clickButton(button, button == GLFW.GLFW_MOUSE_BUTTON_LEFT && modifiers == 0 ? GLFW.GLFW_MOD_SHIFT : modifiers);
			if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !BoxPicker.testCursor().isEmpty()) putDownIn(true);
		});
		pause(2);
	}

	private static void hoverCell(int cell) {
		then("hover cell " + cell, () -> {
			int[] at = BoxPicker.testCell(cell);
			mouseTo(at[0], at[1]);
		});
		pause(2);
	}

	private static void type(String text) {
		then("type " + text, () -> {
			try {
				Method m = Keyboard.class.getDeclaredMethod("onChar", long.class, net.minecraft.client.input.CharInput.class);
				m.setAccessible(true);
				for (int c : text.codePoints().toArray()) m.invoke(mc.keyboard, mc.getWindow().getHandle(), new net.minecraft.client.input.CharInput(c, 0));
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		pause(2);
	}

	private static void keys(int... codes) {
		then("keys", () -> {
			for (int c : codes) key(c);
		});
		pause(2);
	}

	private static void pick() {
		then("pickaxe", () -> cmd("item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]"));
		pause(5);
	}

	private static String chestAt() {
		return chest.getX() + " " + chest.getY() + " " + chest.getZ();
	}

	private static void boxOf(int slot, String contents) {
		then("box", () -> cmd("item replace block " + chestAt() + " container." + slot + " with blue_shulker_box[container=[" + contents + "]]"));
		pause(10);
	}

	/** A plain left-click in these checks means "move this stack across", which is what Shift-click does now. */
	private static void clickCell(int cell, int button, int modifiers) {
		then("click cell " + cell, () -> {
			int[] at = BoxPicker.testCell(cell);
			mouseTo(at[0], at[1]);
			clickButton(button, button == GLFW.GLFW_MOUSE_BUTTON_LEFT && modifiers == 0 ? GLFW.GLFW_MOD_SHIFT : modifiers);
			// Right-click holds half: put it down on your side, which is what "take half" comes to.
			if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !BoxPicker.testCursor().isEmpty()) putDownIn(false);
		});
	}

	private static void putDownIn(boolean box) {
		for (int i = box ? 26 : 35; i >= 0; i--) {
			if (!(box ? BoxPicker.testBoxSlot(i) : BoxPicker.testInvSlot(i)).isEmpty()) continue;
			int[] at = box ? BoxPicker.testCell(i) : BoxPicker.testInv(i);
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			return;
		}
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
		then("no desktop pointer", () -> {
			GLFW.glfwSetCursorPosCallback(mc.getWindow().getHandle(), null);
			// Everything but AK presses Enter once to start.
			ModConfig.INSTANCE.boxPickerConfirm = false;
		});
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
				if (line.getString().contains("manage this box")) hint = true;
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
			check("B: fast with a good pickaxe (" + BoxGrab.lastBoxOutTicks + " ticks out of the chest)", BoxGrab.lastBoxOutTicks < (remote ? 45 : 24));
			check("B: went in one burst", BoxGrab.lastBurst);
			if (!remote) check("B: the box stood as a block for at most 1 server tick (" + serverBlockTicks + ")", serverBlockTicks <= 1);
			check("B: on the ground barely longer than the server's pickup delay (" + BoxGrab.lastGroundTicks + ")", BoxGrab.lastGroundTicks <= (remote ? 35 : 20));
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
		hit("ALL");
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
			check("E: full inventory: it stays put and says why: " + BoxPicker.testNote(), BoxPicker.testWant(0) == 0 && !BoxPicker.testNote().isEmpty());
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

		// F2: surrounded by slabs. Their tops are half a block down, and a box sits on them as well as on anything.
		reset();
		boxInChest(5);
		then("slabs", () -> {
			int x = origin.getX(), y = origin.getY(), z = origin.getZ();
			cmd("fill " + (x - 2) + " " + y + " " + (z - 2) + " " + (x + 2) + " " + y + " " + (z + 2) + " smooth_stone_slab[type=bottom] replace air");
			cmd("setblock " + x + " " + y + " " + z + " air");
			cmd("item replace entity @s hotbar.0 with netherite_pickaxe[enchantments={efficiency:5}]");
		});
		pause(15);
		openChest();
		then("F2 spots", () -> {
			List<BlockPos> spots = BoxGrab.spots(mc.player, mc.world);
			log("F2 spots: " + spots);
			check("F2: room on top of the slabs (" + spots.size() + " spots)", !spots.isEmpty()
					&& mc.world.getBlockState(spots.get(0).down()).isOf(net.minecraft.block.Blocks.SMOOTH_STONE_SLAB));
		});
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		pressEnterAndWait();
		then("F2 results", () -> {
			check("F2: worked on the slabs: " + BoxGrab.lastMessage, BoxGrab.lastOk && count(Items.TOTEM_OF_UNDYING) == 1);
			check("F2: box back in the chest", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
		});
		checkWorldClean("F2");

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
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_CONTROL);
		pause(2);
		then("I3", () -> check("I: ctrl-click picks every stack of that item", BoxPicker.testWant(0) == 1 && BoxPicker.testWant(3) == 1));
		then("drag", () -> {
			int[] at = BoxPicker.testCell(9);
			mouseTo(at[0], at[1]);
			button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
		});
		pause(2);
		then("drag2", () -> {
			int[] at = BoxPicker.testCell(10);
			mouseTo(at[0], at[1]);
		});
		pause(3);
		then("drag3", () -> {
			button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
			check("I: Shift-dragging across slots moves them all", BoxPicker.testWant(9) == 16 && BoxPicker.testWant(10) == 64);
		});
		hit("CANCEL");
		then("cancel", () -> {
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
			// Only the far ends: picking up the drop varies by a few ticks, more than 2x and 5x differ by here.
			check("O: the slowest setting takes clearly longer (" + timed[0] + " vs " + timed[1] + ", " + timed[2] + ")", timed[0] > timed[1] + 15 && timed[0] > timed[2] + 15);
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

		// Q: several boxes in one go. Tabs, a plan across them, each one back where it was.
		reset();
		boxInChest(5);
		boxOf(6, "{slot:2,item:{id:\"diamond\",count:40}}");
		pick();
		openChest();
		openPickerOn(5);
		then("Q0", () -> check("Q: a tab for the loose items and each box (" + BoxPicker.testTabs() + ")", BoxPicker.testTabs() == 3 && !BoxPicker.testIsLoose()));
		shot("7_manager");
		clickCell(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		hit("TAB2");
		clickCell(2, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(2);
		shot("8_two_boxes");
		then("Q1", () -> check("Q: the button says what it'll do: " + BoxPicker.testGoLabel(), BoxPicker.testGoLabel().contains("2")));
		pressEnterAndWait();
		then("Q results", () -> {
			check("Q: both done: " + BoxGrab.lastMessage, BoxGrab.lastOk && BoxGrab.lastMessage.contains("2 boxes"));
			check("Q: got from each", count(Items.ENDER_PEARL) == 16 && count(Items.DIAMOND) == 40);
			check("Q: both boxes in their slots", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX)
					&& mc.player.currentScreenHandler.slots.get(6).getStack().isOf(Items.BLUE_SHULKER_BOX));
		});
		checkWorldClean("Q");

		// R: putting things in, whole stacks and any amount, and taking at the same time.
		reset();
		boxInChest(5);
		pick();
		then("give", () -> {
			cmd("item replace entity @s inventory.0 with stone 64");
			cmd("item replace entity @s inventory.1 with oak_log 32");
			cmd("item replace entity @s inventory.2 with blue_shulker_box");
		});
		pause(10);
		openChest();
		openPickerOn(5);
		clickInv(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		clickInv(10, GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);
		then("R0", () -> check("R: click puts in a whole stack, right-click half (" + BoxPicker.testStore(9) + "," + BoxPicker.testStore(10) + ")", BoxPicker.testStore(9) == 64 && BoxPicker.testStore(10) == 16));
		then("R box", () -> {
			int tab = BoxPicker.testCurrent();
			int[] at = BoxPicker.testInv(11);
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			check("R: a box can't be put in a box", BoxPicker.testStore(11) == 0);
		});
		pause(2);
		then("R tab", () -> {
			if (BoxPicker.testTabs() > 2) {
				int[] at = BoxPicker.testButton("TAB1");
				mouseTo(at[0], at[1]);
				clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			}
		});
		pause(2);
		clickCell(10, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		shot("9_store");
		pressEnterAndWait();
		then("R results", () -> {
			check("R: done: " + BoxGrab.lastMessage, BoxGrab.lastOk && BoxGrab.lastMessage.contains("Put in"));
			check("R: 16 logs and the stone left you", count(Items.OAK_LOG) == 16 && count(Items.STONE) == 0);
			check("R: took the apples too", count(Items.GOLDEN_APPLE) == 64);
			ItemStack box = mc.player.currentScreenHandler.slots.get(5).getStack();
			int stone = 0, logs = 0;
			for (ItemStack s : contentsOf(box)) {
				if (s.isOf(Items.STONE)) stone += s.getCount();
				if (s.isOf(Items.OAK_LOG)) logs += s.getCount();
			}
			check("R: and they're really in the box (" + stone + "," + logs + ")", stone == 64 && logs == 16);
		});
		checkWorldClean("R");

		// R2: a full box takes things out first; a full inventory puts things in first.
		reset();
		StringBuilder fullBox = new StringBuilder();
		for (int i = 0; i < 27; i++) fullBox.append(i == 0 ? "" : ",").append("{slot:").append(i).append(",item:{id:\"diamond\",count:1}}");
		boxOf(5, fullBox.toString());
		pick();
		then("give", () -> cmd("item replace entity @s inventory.0 with stone 8"));
		pause(5);
		openChest();
		openPickerOn(5);
		clickInv(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		then("R2a", () -> check("R2: a full box says so: " + BoxPicker.testNote(), !BoxPicker.testNote().isEmpty() && BoxPicker.testStore(9) == 0));
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(2);
		clickInv(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		then("R2b", () -> check("R2: taking one out makes room: " + BoxPicker.testProblem().orElse("(none)"), BoxPicker.testProblem().isEmpty()));
		pressEnterAndWait();
		then("R2", () -> {
			check("R2: swap into a full box: " + BoxGrab.lastMessage, BoxGrab.lastOk && count(Items.STONE) == 0 && count(Items.DIAMOND) == 1);
		});
		checkWorldClean("R2");

		// S: search across boxes, by real typing; All means all of what's showing.
		reset();
		boxInChest(5);
		boxOf(6, "{slot:4,item:{id:\"ender_pearl\",count:3}},{slot:5,item:{id:\"dirt\",count:9}}");
		openChest();
		openPickerOn(5);
		hit("SEARCH");
		type("pearl");
		then("S", () -> {
			check("S: the pearls show", BoxPicker.testMatches(9));
			check("S: the apples don't", !BoxPicker.testMatches(10));
		});
		shot("10_search");
		keys(GLFW.GLFW_KEY_ENTER);
		then("S2", () -> check("S: Enter leaves the search and starts nothing", !BoxGrab.running() && BoxPicker.isOpen()));
		keys(GLFW.GLFW_KEY_A);
		then("S3", () -> check("S: All takes only what the search shows", BoxPicker.testWant(9) == 16 && BoxPicker.testWant(10) == 0));
		then("S4", () -> {
			check("S: letters typed in the search never reached the chest", mc.currentScreen instanceof GenericContainerScreen);
		});
		hit("SEARCH");
		type(" ");
		keys(GLFW.GLFW_KEY_ESCAPE);
		keys(GLFW.GLFW_KEY_ESCAPE);
		then("S5", () -> check("S: Esc clears the search first, then closes", !BoxPicker.isOpen()));

		// T: typed amounts of any size, spreading over stacks and between boxes.
		reset();
		boxOf(5, "{slot:0,item:{id:\"ender_pearl\",count:16}},{slot:1,item:{id:\"ender_pearl\",count:16}},{slot:8,item:{id:\"golden_apple\",count:64}}");
		boxOf(6, "{slot:0,item:{id:\"ender_pearl\",count:16}}");
		pick();
		openChest();
		openPickerOn(5);
		hoverCell(0);
		keys(GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_0);
		then("T1", () -> check("T: 30 pearls comes from two stacks (" + BoxPicker.testWant(0) + "+" + BoxPicker.testWant(1) + ")", BoxPicker.testWant(0) == 16 && BoxPicker.testWant(1) == 14));
		keys(GLFW.GLFW_KEY_BACKSPACE);
		then("T2", () -> check("T: Backspace corrects it: 3", BoxPicker.testWant(0) == 3 && BoxPicker.testWant(1) == 0));
		keys(GLFW.GLFW_KEY_ENTER);
		then("T3", () -> check("T: Enter finishes the number and doesn't set it off", !BoxGrab.running()));
		pause(2);
		hoverCell(0);
		keys(GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_0);
		then("T4", () -> check("T: 40 reaches into the next box (" + BoxPicker.testWantIn(2, 0) + ")", BoxPicker.testWantIn(1, 0) == 16 && BoxPicker.testWantIn(1, 1) == 16 && BoxPicker.testWantIn(2, 0) == 8));
		keys(GLFW.GLFW_KEY_ENTER);
		hoverCell(8);
		then("scroll", () -> scroll(1));
		pause(2);
		then("T5", () -> check("T: scrolling on a stack changes it (" + BoxPicker.testWant(8) + ")", BoxPicker.testWant(8) == 1));
		shot("11_amount");
		pressEnterAndWait();
		then("T6", () -> check("T: exactly those amounts: " + BoxGrab.lastMessage, BoxGrab.lastOk && count(Items.ENDER_PEARL) == 40 && count(Items.GOLDEN_APPLE) == 1));
		checkWorldClean("T");

		// U: sort: merged and in order, nothing lost.
		reset();
		boxOf(5, "{slot:20,item:{id:\"stone\",count:10}},{slot:3,item:{id:\"apple\",count:30}},{slot:9,item:{id:\"stone\",count:20}},{slot:15,item:{id:\"apple\",count:50}},{slot:1,item:{id:\"bread\",count:5}}");
		pick();
		openChest();
		openPickerOn(5);
		hit("TIDY");
		then("U0", () -> check("U: the button says Sort: " + BoxPicker.testGoLabel(), BoxPicker.testGoLabel().contains("Sort")));
		pressEnterAndWait();
		then("U", () -> {
			check("U: done: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			DefaultedList<ItemStack> c = contentsOf(mc.player.currentScreenHandler.slots.get(5).getStack());
			log("U sorted: " + c.subList(0, 6));
			check("U: apples merged first", c.get(0).isOf(Items.APPLE) && c.get(0).getCount() == 64 && c.get(1).isOf(Items.APPLE) && c.get(1).getCount() == 16);
			check("U: then bread, then stone", c.get(2).isOf(Items.BREAD) && c.get(2).getCount() == 5 && c.get(3).isOf(Items.STONE) && c.get(3).getCount() == 30);
			check("U: the rest empty", c.subList(4, 27).stream().allMatch(ItemStack::isEmpty));
		});
		checkWorldClean("U");

		// V: kits: save by name, top up to it, only what you're short of, remembered after a reload; then delete.
		reset();
		then("no kits", () -> {
			for (BoxKits.Kit k : new ArrayList<>(BoxKits.kits())) BoxKits.remove(k);
		});
		boxInChest(5);
		pick();
		openChest();
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		clickCell(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(2);
		hit("SAVE");
		type("PvP");
		keys(GLFW.GLFW_KEY_ENTER);
		then("V0", () -> {
			BoxKits.testReload();
			check("V: saved to disk under its name", BoxKits.kits().size() == 1 && BoxKits.kits().get(0).name.equals("PvP") && BoxKits.kits().get(0).items.get("minecraft:ender_pearl") == 16);
			check("V: saving didn't start anything", !BoxGrab.running());
		});
		shot("12_kit");
		hit("CANCEL");
		then("have", () -> cmd("give @s ender_pearl 10"));
		pause(5);
		openPickerOn(5);
		hit("KIT0");
		then("V1", () -> check("V: tops up: 6 pearls, not 16 (" + BoxPicker.testWant(9) + ")", BoxPicker.testWant(9) == 6 && BoxPicker.testWant(0) == 1));
		pressEnterAndWait();
		then("V2", () -> check("V: ends with exactly the kit", BoxGrab.lastOk && count(Items.ENDER_PEARL) == 16 && count(Items.TOTEM_OF_UNDYING) == 1));
		openPickerOn(5);
		hit("LAST");
		then("V3", () -> check("V: Last brings back the last pick: nothing needed now", BoxPicker.testWant(9) == 0 && BoxPicker.testWant(0) == 0));
		hit("KIT0", GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);
		then("V4", () -> check("V: right-click asks first", BoxKits.kits().size() == 1));
		hit("KIT0");
		then("V5", () -> check("V: click again deletes", BoxKits.kits().isEmpty()));
		hit("CANCEL");

		// W: top up, match and put all.
		reset();
		boxInChest(5);
		pick();
		then("give", () -> {
			cmd("item replace entity @s inventory.0 with ender_pearl 10");
			cmd("item replace entity @s inventory.1 with stone 10");
			cmd("item replace entity @s hotbar.3 with dirt 5");
		});
		pause(8);
		openChest();
		openPickerOn(5);
		hit("TOPUP");
		then("W0", () -> check("W: Top up fills the partial stack (" + BoxPicker.testWant(9) + ")", BoxPicker.testWant(9) == 6));
		hit("MATCH");
		then("W1", () -> check("W: Match puts in what's already in the box, not the rest", BoxPicker.testStore(9) == 10 && BoxPicker.testStore(10) == 0));
		hit("STOREALL");
		then("W2", () -> check("W: Put all leaves the hotbar", BoxPicker.testStore(10) == 10 && BoxPicker.testStore(3) == 0));
		hit("STOREALL", GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT);
		then("W3", () -> check("W: Shift includes it", BoxPicker.testStore(3) == 5));
		hit("CANCEL");

		// X: the speed slider, Instant and Guard, right there in the panel.
		reset();
		boxInChest(5);
		openChest();
		openPickerOn(5);
		then("more", () -> BoxPicker.testSetMore(true));
		pause(2);
		then("slide", () -> {
			int[] at = BoxPicker.testButton("SPEED");
			mouseTo(at[0] - 70, at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			check("X: left end of the slider is slow (" + ModConfig.INSTANCE.boxPickerSpeed + ")", ModConfig.INSTANCE.boxPickerSpeed <= 100);
			mouseTo(at[0] + 80, at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
			check("X: right end is fast (" + ModConfig.INSTANCE.boxPickerSpeed + ")", ModConfig.INSTANCE.boxPickerSpeed >= 475);
			ModConfig.INSTANCE.boxPickerSpeed = 200;
			BoxPicker.testSetMore(false);
		});
		pause(2);
		hit("INSTANT");
		then("X1", () -> check("X: Instant toggles", ModConfig.INSTANCE.boxPickerInstant));
		hit("INSTANT");
		hit("GUARD");
		then("X2", () -> {
			check("X: Guard toggles", !ModConfig.INSTANCE.boxPickerGuard);
			ModConfig.INSTANCE.boxPickerGuard = true;
		});
		// Y: someone close: held back, and it says why. The same when they walk up after you press the button.
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		then("Y0", () -> {
			BoxPicker.testNearby = 5;
			check("Y: someone 5m away holds it: " + BoxPicker.testProblem().orElse("(none)"), BoxPicker.testProblem().isPresent());
			BoxPicker.testNearby = -1;
			BoxGrab.testGuardTrip = true;
		});
		pause(1);
		pressEnterAndWait();
		then("Y1", () -> {
			BoxGrab.testGuardTrip = false;
			check("Y: walked up: stayed safe: " + BoxGrab.lastMessage, !BoxGrab.lastOk && BoxGrab.lastMessage.contains("came close") && count(Items.TOTEM_OF_UNDYING) == 0);
			check("Y: box back in the chest", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
		});

		// Z: Esc keeps the plan, Cancel forgets it. The Boxes button, and G with the mouse anywhere.
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		keys(GLFW.GLFW_KEY_ESCAPE);
		then("Z0", () -> check("Z: Esc closes the panel and the chest stays open", !BoxPicker.isOpen() && mc.currentScreen instanceof GenericContainerScreen));
		then("Z1", () -> {
			mouseTo(2, 2);
			key(GLFW.GLFW_KEY_G);
			check("Z: G opens it with the mouse anywhere", BoxPicker.isOpen());
		});
		pause(2);
		then("Z2", () -> check("Z: and the plan is still there", BoxPicker.testWant(0) == 1));
		hit("CANCEL");
		then("Z3", () -> {
			var buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(mc.currentScreen);
			check("Z: the Boxes button is showing", buttons.stream().anyMatch(b -> b.visible && b.getMessage().getString().startsWith("Boxes")));
		});
		then("Z4", () -> {
			key(GLFW.GLFW_KEY_G);
			check("Z: Cancel forgot the plan", BoxPicker.isOpen() && BoxPicker.testWant(0) == 0);
		});
		pause(2);
		hit("CANCEL");

		// AA: stop part way: everything goes back, nothing else starts.
		reset();
		boxInChest(5);
		boxOf(6, "{slot:0,item:{id:\"diamond\",count:9}}");
		pick();
		openChest();
		openPickerOn(5);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		hit("TAB2");
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(1);
		then("enter", () -> key(GLFW.GLFW_KEY_ENTER));
		waitFor("box out", 200, () -> BoxGrab.step() == BoxGrab.Step.OPEN || BoxGrab.step() == BoxGrab.Step.TAKE || BoxGrab.step() == BoxGrab.Step.CHECK);
		then("stop", () -> BoxGrab.requestStop());
		waitFor("bot to stop", 400, () -> !BoxGrab.running());
		pause(5);
		then("AA", () -> {
			check("AA: says it stopped: " + BoxGrab.lastMessage, BoxGrab.lastMessage.contains("Stopped"));
			check("AA: the second box was never touched", count(Items.DIAMOND) == 0 && mc.player.currentScreenHandler.slots.get(6).getStack().isOf(Items.BLUE_SHULKER_BOX));
			check("AA: the first is back", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
		});
		checkWorldClean("AA");

		// AB: emergency: looking through a box on the ground that you placed: one press and it's in your inventory.
		reset();
		pick();
		BlockPos[] ground = new BlockPos[1];
		then("place", () -> {
			ground[0] = origin.add(-1, 0, 1);
			cmd("setblock " + ground[0].getX() + " " + ground[0].getY() + " " + ground[0].getZ() + " red_shulker_box{Items:[{Slot:3b,id:\"diamond\",count:12}]}");
			usedSpot = ground[0];
		});
		pause(8);
		then("open", () -> openBlock(ground[0]));
		waitFor("box screen", 100, () -> mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen);
		pause(3);
		then("AB0", () -> {
			var buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(mc.currentScreen);
			check("AB: the Grab box button is showing", buttons.stream().anyMatch(b -> b.visible && b.getMessage().getString().startsWith("Grab")));
		});
		shot("13_grab_button");
		then("press", () -> key(GLFW.GLFW_KEY_G));
		waitFor("bot to finish", 300, () -> !BoxGrab.running());
		pause(5);
		then("AB", () -> {
			check("AB: done: " + BoxGrab.lastMessage, BoxGrab.lastOk && BoxGrab.lastMessage.contains("Got the box"));
			check("AB: it's off the ground", !(mc.world.getBlockState(ground[0]).getBlock() instanceof ShulkerBoxBlock));
			check("AB: it's in your inventory", count(Items.RED_SHULKER_BOX) == 1);
			ItemStack box = ItemStack.EMPTY;
			for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (mc.player.getInventory().getStack(i).isOf(Items.RED_SHULKER_BOX)) box = mc.player.getInventory().getStack(i);
			check("AB: with everything still in it", contentsOf(box).get(3).isOf(Items.DIAMOND) && contentsOf(box).get(3).getCount() == 12);
			check("AB: nothing on the ground", groundItems() == 0);
			check("AB: pickaxe still in hand", mc.player.getInventory().getSelectedSlot() == 0 && mc.player.getInventory().getStack(0).isOf(Items.NETHERITE_PICKAXE));
		});

		// AB2: inventory full: the button says so, and the box is NOT broken: it would be left lying there for anyone.
		reset();
		pick();
		then("place", () -> {
			ground[0] = origin.add(-1, 0, 1);
			usedSpot = ground[0];
			cmd("setblock " + ground[0].getX() + " " + ground[0].getY() + " " + ground[0].getZ() + " red_shulker_box{Items:[{Slot:3b,id:\"diamond\",count:12}]}");
			for (int i = 1; i < 9; i++) cmd("item replace entity @s hotbar." + i + " with stone 64");
			for (int i = 0; i < 27; i++) cmd("item replace entity @s inventory." + i + " with dirt 64");
		});
		pause(12);
		then("open", () -> openBlock(ground[0]));
		waitFor("box screen", 100, () -> mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen);
		pause(3);
		then("AB2 button", () -> {
			var buttons = net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(mc.currentScreen);
			check("AB2: the button says the inventory is full and can't be pressed", buttons.stream().anyMatch(b -> b.visible && !b.active && b.getMessage().getString().contains("full")));
		});
		shot("13b_grab_full");
		then("press", () -> key(GLFW.GLFW_KEY_G));
		pause(10);
		then("AB2", () -> {
			check("AB2: G does nothing to the box", !BoxGrab.running());
			check("AB2: the box still stands", mc.world.getBlockState(ground[0]).getBlock() instanceof ShulkerBoxBlock);
			check("AB2: nothing dropped", groundItems() == 0);
			check("AB2: you're still looking inside", mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen);
		});
		// AB3: room for the box, but what's on your cursor would take that last slot when the screen closes.
		then("one slot", () -> {
			mc.player.closeHandledScreen();
			cmd("item replace entity @s inventory.0 with air");
			cmd("item replace entity @s inventory.1 with air");
		});
		pause(8);
		then("open", () -> openBlock(ground[0]));
		waitFor("box screen", 100, () -> mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen);
		pause(3);
		then("hold", () -> {
			var h = mc.player.currentScreenHandler;
			mc.interactionManager.clickSlot(h.syncId, 3, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
			var again = h.slots.stream().filter(s -> s.inventory == mc.player.getInventory() && s.getIndex() == 9).findFirst().orElseThrow();
			mc.interactionManager.clickSlot(h.syncId, again.id, 1, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
			check("AB3: two slots free and a stack on the cursor: still room", BoxGrab.roomToGrab(mc));
			var second = h.slots.stream().filter(s -> s.inventory == mc.player.getInventory() && s.getIndex() == 10).findFirst().orElseThrow();
			mc.interactionManager.clickSlot(h.syncId, second.id, 1, net.minecraft.screen.slot.SlotActionType.PICKUP, mc.player);
			check("AB3: no empty slot left once the cursor comes home: refused", !BoxGrab.roomToGrab(mc));
		});
		pause(3);
		then("tidy up", () -> mc.player.closeHandledScreen());
		pause(6);

		// AB4: the inventory fills while a slow break is under way: it stops and leaves the box standing.
		reset();
		then("place", () -> {
			ground[0] = origin.add(-1, 0, 1);
			usedSpot = ground[0];
			cmd("setblock " + ground[0].getX() + " " + ground[0].getY() + " " + ground[0].getZ() + " red_shulker_box{Items:[{Slot:3b,id:\"diamond\",count:12}]}");
		});
		pause(8);
		then("open", () -> openBlock(ground[0]));
		waitFor("box screen", 100, () -> mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen);
		pause(3);
		then("press", () -> key(GLFW.GLFW_KEY_G));
		pause(6);
		then("fill", () -> {
			for (int i = 0; i < 9; i++) cmd("item replace entity @s hotbar." + i + " with stone 64");
			for (int i = 0; i < 27; i++) cmd("item replace entity @s inventory." + i + " with dirt 64");
		});
		waitFor("bot to stop", 200, () -> !BoxGrab.running());
		pause(5);
		then("AB4", () -> {
			check("AB4: says why and where: " + BoxGrab.lastMessage, !BoxGrab.lastOk && BoxGrab.lastMessage.contains("wasn't broken") && BoxGrab.lastMessage.contains(ground[0].toShortString()));
			check("AB4: the box still stands", mc.world.getBlockState(ground[0]).getBlock() instanceof ShulkerBoxBlock);
			check("AB4: nothing dropped", groundItems() == 0);
		});

		// AC: the loose items in a container: any amount, nothing placed, nothing at risk. Sort it, boxes stay put.
		reset();
		boxInChest(5);
		then("loose", () -> {
			cmd("item replace block " + chestAt() + " container.0 with iron_ingot 40");
			cmd("item replace block " + chestAt() + " container.20 with iron_ingot 40");
			cmd("item replace block " + chestAt() + " container.9 with coal 30");
			cmd("item replace entity @s inventory.0 with stone 8");
		});
		pause(8);
		openChest();
		openPickerOn(5);
		hit("TAB0");
		then("AC0", () -> check("AC: this tab is the loose items", BoxPicker.testIsLoose()));
		shot("14_loose");
		hoverCell(0);
		keys(GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_0);
		keys(GLFW.GLFW_KEY_ENTER);
		clickInv(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		then("AC1", () -> check("AC: a box in the grid opens its tab rather than being picked", BoxPicker.testWant(5) == 0));
		hit("TIDY");
		pressEnterAndWait();
		then("AC", () -> {
			check("AC: done: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			check("AC: never set anything down", BoxGrab.lastBoxOutTicks == 0);
			check("AC: 50 iron, spread over two stacks", count(Items.IRON_INGOT) == 50);
			var slots = mc.player.currentScreenHandler.slots;
			check("AC: the box didn't move", slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
			int stone = 0;
			for (int i = 0; i < 27; i++) if (slots.get(i).getStack().isOf(Items.STONE)) stone += slots.get(i).getStack().getCount();
			check("AC: the stone went in", stone == 8 && count(Items.STONE) == 0);
			log("AC chest: " + slots.get(0).getStack() + " " + slots.get(1).getStack() + " " + slots.get(2).getStack() + " " + slots.get(3).getStack());
			check("AC: sorted", slots.get(0).getStack().isOf(Items.COAL) && slots.get(1).getStack().isOf(Items.IRON_INGOT));
		});

		// AD: inside bundles: in a box, and loose in the chest. Any amount of any stack; the rest stays in the bundle.
		String bundle = "bundle[bundle_contents=[{id:\"ender_pearl\",count:8},{id:\"golden_carrot\",count:20},{id:\"arrow\",count:16}]]";
		reset();
		boxOf(5, "{slot:2,item:{id:\"" + "bundle\",count:1,components:{\"bundle_contents\":[{id:\"ender_pearl\",count:8},{id:\"golden_carrot\",count:20},{id:\"arrow\",count:16}]}}}");
		then("loose bundle", () -> cmd("item replace block " + chestAt() + " container.0 with " + bundle));
		pause(6);
		pick();
		openChest();
		openPickerOn(5);
		clickCell(2, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0);
		pause(2);
		then("AD0", () -> check("AD: middle-click goes inside a bundle", BoxPicker.testInBundle()));
		shot("15_bundle");
		hoverCell(1);
		keys(GLFW.GLFW_KEY_7);
		keys(GLFW.GLFW_KEY_ENTER);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		then("AD1", () -> check("AD: picks inside (" + BoxPicker.testBundleWant(1) + "," + BoxPicker.testBundleWant(0) + ")", BoxPicker.testBundleWant(1) == 7 && BoxPicker.testBundleWant(0) == 8));
		hit("BACK");
		then("AD2", () -> check("AD: Back keeps them: " + BoxPicker.testGoLabel(), !BoxPicker.testInBundle() && BoxPicker.testGoLabel().contains("2")));
		pressEnterAndWait();
		then("AD", () -> {
			check("AD: from inside a bundle in a box: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			check("AD: exact amounts (" + count(Items.GOLDEN_CARROT) + "," + count(Items.ENDER_PEARL) + ")", count(Items.GOLDEN_CARROT) == 7 && count(Items.ENDER_PEARL) == 8);
			ItemStack box = mc.player.currentScreenHandler.slots.get(5).getStack();
			ItemStack inner = contentsOf(box).get(2);
			var kept = inner.get(DataComponentTypes.BUNDLE_CONTENTS);
			int carrots = 0, pearls = 0, arrows = 0;
			if (kept != null) for (ItemStack s : kept.iterate()) {
				if (s.isOf(Items.GOLDEN_CARROT)) carrots += s.getCount();
				if (s.isOf(Items.ENDER_PEARL)) pearls += s.getCount();
				if (s.isOf(Items.ARROW)) arrows += s.getCount();
			}
			check("AD: the bundle went back in the box with the rest (" + carrots + "," + pearls + "," + arrows + ")", carrots == 13 && pearls == 0 && arrows == 16);
		});
		checkWorldClean("AD");
		// The bundle loose in the chest: no box out at all.
		then("hover", () -> {
			HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
			Slot slot = screen.getScreenHandler().slots.get(5);
			HandledScreenAccessor a = (HandledScreenAccessor) screen;
			mouseTo(a.hoardeye$getX() + slot.x + 8, a.hoardeye$getY() + slot.y + 8);
		});
		pause(3);
		then("open", () -> clickButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0));
		pause(3);
		hit("TAB0");
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0);
		pause(2);
		clickCell(2, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(2);
		pressEnterAndWait();
		then("AD3", () -> {
			check("AD: from a bundle loose in the chest: " + BoxGrab.lastMessage, BoxGrab.lastOk && count(Items.ARROW) == 16 && BoxGrab.lastBoxOutTicks == 0);
			var kept = mc.player.currentScreenHandler.slots.get(0).getStack().get(DataComponentTypes.BUNDLE_CONTENTS);
			check("AD: the rest stayed in it", kept != null && kept.size() == 2);
		});

		// AE: a double chest: pages of loose items, boxes on the second page reached too. A small window: still drawn, nothing thrown.
		reset();
		BlockPos[] doubleChest = new BlockPos[2];
		then("double", () -> {
			doubleChest[0] = chest;
			doubleChest[1] = chest.add(0, 0, 1);
			cmd("setblock " + chest.getX() + " " + chest.getY() + " " + (chest.getZ() + 1) + " air");
			cmd("setblock " + chestAt() + " chest[type=left,facing=east]");
			cmd("setblock " + chest.getX() + " " + chest.getY() + " " + (chest.getZ() + 1) + " chest[type=right,facing=east]");
		});
		pause(8);
		then("fill", () -> {
			cmd("item replace block " + chestAt() + " container.0 with iron_ingot 40");
			cmd("item replace block " + chest.getX() + " " + chest.getY() + " " + (chest.getZ() + 1) + " container.0 with " + KIT);
			cmd("item replace block " + chest.getX() + " " + chest.getY() + " " + (chest.getZ() + 1) + " container.1 with coal 5");
		});
		pause(8);
		openChest();
		then("G", () -> {
			mouseTo(2, 2);
			key(GLFW.GLFW_KEY_G);
		});
		pause(3);
		hit("TAB0");
		then("AE", () -> check("AE: two pages of loose items", BoxPicker.testHasButton("PAGED")));
		hit("PAGED");
		pause(1);
		shot("16_pages");
		then("small", () -> {
			mc.getWindow().setScaleFactor(4);
			mc.currentScreen.resize(mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
		});
		pause(3);
		shot("17_small");
		then("AE small", () -> {
			int[] p = BoxPicker.testPanel();
			check("AE: at a huge GUI scale the whole panel is on screen (" + java.util.Arrays.toString(p) + " in " + mc.getWindow().getScaledWidth() + "x" + mc.getWindow().getScaledHeight() + ")",
					BoxPicker.isOpen() && p[0] >= 0 && p[1] >= 0 && p[2] <= mc.getWindow().getScaledWidth() && p[3] <= mc.getWindow().getScaledHeight());
		});
		hit("PAGEU");
		then("AE small2", () -> check("AE: and its buttons still work there", BoxPicker.testHasButton("PAGED")));
		clickCell(1, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		pause(2);
		then("AE small3", () -> check("AE: so do its cells (" + BoxPicker.testWant(1) + ")", BoxPicker.testWant(1) == 5));
		then("back", () -> {
			mc.onResolutionChanged();
			mc.currentScreen.resize(mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
		});
		pause(3);
		then("AE esc", () -> BoxPicker.close());

		// Every message the picker can show has to fit its panel.
		then("texts", () -> {
			for (String key : List.of("hint", "hint_box", "hint_inv", "hint_typing", "hint_bundle", "hint_bundle_cell", "safe", "no_pick", "no_spot", "no_room", "no_room_box", "cursor", "survival_only", "nobody", "amount_hint", "nothing_to_top_up",
					"hint.tab", "hint.kit", "hint.last", "hint.all", "hint.none", "hint.tidy", "hint.instant", "hint.guard", "hint.topup", "hint.match", "hint.storeall", "hint.save", "hint.speed", "hint.take", "hint.cancel", "hint.undo", "hint.more", "confirm_keys", "confirm_arrange", "holding")) {
				int width = mc.textRenderer.getWidth(net.minecraft.text.Text.translatable("hoardeye.picker." + key));
				int room = 198;
				check("picker text '" + key + "' fits (" + width + " of " + room + ")", width <= room);
			}
		});

		// AH: the ender chest above your head. Clicked on the face you can see, not on a lid out of view.
		reset();
		BlockPos[] high = new BlockPos[1];
		then("AH set", () -> {
			high[0] = origin.add(0, 3, 2);
			cmd("setblock " + high[0].getX() + " " + high[0].getY() + " " + high[0].getZ() + " ender_chest");
			cmd("item replace entity @s enderchest.3 with " + KIT);
		});
		enderRun("AH", high, null);
		then("AH2", () -> {
			Vec3d aim = BoxGrab.lastReopenAim.getPos();
			check("AH: never aims above the lid (" + aim + ")", aim.y <= high[0].getY() + 0.875 + 0.001);
			check("AH: the face clicked is one in view (" + BoxGrab.lastReopenAim.getSide() + ")", BoxGrab.lastReopenAim.getSide() != Direction.UP);
		});

		// AI: at the edge of reach, and the only place for the box is away from the ender chest. It has to walk back.
		reset();
		BlockPos[] far = new BlockPos[1];
		then("AI set", () -> {
			far[0] = origin.add(-4, 0, 0);
			cmd("setblock " + far[0].getX() + " " + far[0].getY() + " " + far[0].getZ() + " ender_chest");
			cmd("item replace entity @s enderchest.3 with " + KIT);
			int x = origin.getX(), y = origin.getY(), z = origin.getZ();
			// Tripwire: nothing to set a box on, in it or on top, and low enough to see the chest over. (Carpet won't do: a box sits on carpet like on a slab.)
			cmd("fill " + (x - 2) + " " + y + " " + (z - 2) + " " + (x + 1) + " " + y + " " + (z + 2) + " tripwire");
			cmd("setblock " + x + " " + y + " " + z + " air");
		});
		enderRun("AI", far, null);
		then("AI2", () -> check("AI: the box had to go away from the chest (" + String.valueOf(BoxGrab.spot()) + ")", BoxGrab.spot() != null && BoxGrab.spot().getX() == origin.getX() + 2));

		// AJ: a server that hides chests: this client sees only air where it's standing.
		reset();
		BlockPos[] hidden = new BlockPos[1];
		then("AJ set", () -> {
			hidden[0] = origin.add(0, 0, 2);
			cmd("setblock " + hidden[0].getX() + " " + hidden[0].getY() + " " + hidden[0].getZ() + " ender_chest");
			cmd("item replace entity @s enderchest.3 with " + KIT);
		});
		enderRun("AJ", hidden, () -> {
			mc.world.setBlockState(hidden[0], net.minecraft.block.Blocks.AIR.getDefaultState(), 0);
			check("AJ: still known as the container", hidden[0].equals(BoxGrab.openContainer(mc)));
		});

		// AK: its own storage: drag, click to carry, right for half, undo. Then confirm, and the bot makes it real, slot for slot.
		reset();
		boxInChest(5);
		then("AK have", () -> cmd("item replace entity @s hotbar.0 with dirt 32"));
		pause(10);
		openChest();
		openPickerOn(5);
		// Dragged out of the box into one particular slot.
		drag(() -> BoxPicker.testCell(9), () -> BoxPicker.testInv(20), GLFW.GLFW_MOUSE_BUTTON_LEFT);
		shot("18_dragging");
		then("AK0", () -> {
			check("AK: it's in your hand while dragging, and gone from its cell", BoxPicker.testCursor().isOf(Items.ENDER_PEARL) && BoxPicker.testBoxSlot(9).isEmpty());
		});
		drop(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		then("AK1", () -> {
			check("AK: dropped, it's in that exact slot (" + BoxPicker.testInvSlot(20) + ")", BoxPicker.testInvSlot(20).isOf(Items.ENDER_PEARL) && BoxPicker.testInvSlot(20).getCount() == 16);
			check("AK: that plans taking it (" + BoxPicker.testWant(9) + ")", BoxPicker.testWant(9) == 16);
			check("AK: nothing has really moved", count(Items.ENDER_PEARL) == 0);
		});
		// Right-click takes half onto the mouse; a click elsewhere puts it down.
		then("AK half", () -> {
			int[] at = BoxPicker.testCell(10);
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);
		});
		pause(2);
		then("AK2", () -> {
			check("AK: right-click holds half (" + BoxPicker.testCursor() + ")", BoxPicker.testCursor().isOf(Items.GOLDEN_APPLE) && BoxPicker.testCursor().getCount() == 32);
			int[] at = BoxPicker.testInv(30);
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		});
		pause(2);
		then("AK3", () -> check("AK: a click puts it down (" + BoxPicker.testInvSlot(30) + "), half stays (" + BoxPicker.testBoxSlot(10) + ")",
				BoxPicker.testInvSlot(30).getCount() == 32 && BoxPicker.testBoxSlot(10).getCount() == 32 && BoxPicker.testWant(10) == 32));
		// Undo steps back one move.
		hit("UNDO");
		then("AK4", () -> check("AK: Undo puts the apples back (" + BoxPicker.testWant(10) + ")", BoxPicker.testWant(10) == 0 && BoxPicker.testInvSlot(30).isEmpty() && BoxPicker.testBoxSlot(10).getCount() == 64));
		// Dropped on nothing: as if it never happened.
		drag(() -> BoxPicker.testCell(26), () -> new int[]{1, 1}, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		drop(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		then("AK5", () -> check("AK: dropped outside, it goes back (" + BoxPicker.testWant(26) + ")", BoxPicker.testWant(26) == 0 && BoxPicker.testBoxSlot(26).isOf(Items.FIREWORK_ROCKET)));
		// Inventory to one exact cell of the box.
		drag(() -> BoxPicker.testInv(0), () -> BoxPicker.testCell(1), GLFW.GLFW_MOUSE_BUTTON_LEFT);
		drop(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		then("AK6", () -> check("AK: dragged into the box, in that cell (" + BoxPicker.testBoxSlot(1) + ")", BoxPicker.testBoxSlot(1).isOf(Items.DIRT) && BoxPicker.testStore(0) == 32));
		// Shuffled inside the box: nothing needs to leave it.
		drag(() -> BoxPicker.testCell(26), () -> BoxPicker.testCell(25), GLFW.GLFW_MOUSE_BUTTON_LEFT);
		drop(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		then("AK7", () -> check("AK: moved within the box isn't a take (" + BoxPicker.testWant(26) + ")", BoxPicker.testWant(26) == 0 && BoxPicker.testBoxSlot(25).isOf(Items.FIREWORK_ROCKET)));
		// Confirm.
		then("AK confirm on", () -> {
			ModConfig.INSTANCE.boxPickerConfirm = true;
			key(GLFW.GLFW_KEY_ENTER);
		});
		pause(3);
		shot("19_confirm");
		then("AK8", () -> {
			check("AK: Enter asks first", BoxPicker.testConfirming() && !BoxGrab.running());
			key(GLFW.GLFW_KEY_ESCAPE);
		});
		pause(2);
		then("AK9", () -> {
			check("AK: Esc goes back, plan intact", BoxPicker.isOpen() && !BoxPicker.testConfirming() && BoxPicker.testWant(9) == 16 && BoxPicker.testStore(0) == 32);
			check("AK: still nothing moved", count(Items.ENDER_PEARL) == 0 && count(Items.DIRT) == 32);
		});
		shot("20_main");
		hit("TAKE");
		pause(3);
		then("AK10", () -> {
			int[] at = BoxPicker.testButton("CONFIRM_GO");
			mouseTo(at[0], at[1]);
		});
		pause(2);
		then("AK click", () -> clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
		waitFor("the bot to start", 40, BoxGrab::running);
		waitFor("the bot to finish", 1600, () -> !BoxGrab.running());
		pause(5);
		then("AK11", () -> {
			check("AK: Confirm runs it: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			check("AK: exactly what was planned happened", count(Items.ENDER_PEARL) == 16 && count(Items.GOLDEN_APPLE) == 0 && count(Items.DIRT) == 0);
			check("AK: the pearls are in the very slot you chose (" + mc.player.getInventory().getStack(20) + ")", mc.player.getInventory().getStack(20).isOf(Items.ENDER_PEARL) && mc.player.getInventory().getStack(20).getCount() == 16);
			ItemStack box = mc.player.currentScreenHandler.slots.get(5).getStack();
			List<ItemStack> in = contentsOf(box);
			check("AK: the dirt is in the very cell you chose (" + in.get(1) + ")", box.isOf(Items.RED_SHULKER_BOX) && in.get(1).isOf(Items.DIRT) && in.get(1).getCount() == 32);
			check("AK: the rockets moved where you put them (" + in.get(25) + ", " + in.get(26) + ")", in.get(25).isOf(Items.FIREWORK_ROCKET) && in.get(26).isEmpty());
			ModConfig.INSTANCE.boxPickerConfirm = false;
		});
		checkWorldClean("AK");

		// AM: rearranging only your inventory is a plan of its own, and swaps work with no free slot needed.
		reset();
		boxInChest(5);
		then("AM have", () -> {
			cmd("item replace entity @s inventory.0 with dirt 5");
			cmd("item replace entity @s inventory.1 with stone 7");
		});
		pause(10);
		openChest();
		openPickerOn(5);
		drag(() -> BoxPicker.testInv(9), () -> BoxPicker.testInv(10), GLFW.GLFW_MOUSE_BUTTON_LEFT);
		drop(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		then("AM1", () -> check("AM: dropped on stone, you hold the stone (" + BoxPicker.testCursor() + ")", BoxPicker.testCursor().isOf(Items.STONE)));
		then("AM2", () -> {
			int[] at = BoxPicker.testInv(9);
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		});
		pause(2);
		pressEnterAndWait2();
		then("AM3", () -> {
			check("AM: swapped for real (" + mc.player.getInventory().getStack(9) + ", " + mc.player.getInventory().getStack(10) + ")",
					mc.player.getInventory().getStack(9).isOf(Items.STONE) && mc.player.getInventory().getStack(10).isOf(Items.DIRT));
			check("AM: no box was touched", mc.player.currentScreenHandler.slots.get(5).getStack().isOf(Items.RED_SHULKER_BOX));
		});

		// AN: from one box's tab to another's: carried across, and it happens in the right order.
		reset();
		boxOf(5, "{slot:0,item:{id:\"diamond\",count:40}}");
		boxOf(6, "{slot:0,item:{id:\"dirt\",count:1}}");
		openChest();
		openPickerOn(5);
		then("AN carry", () -> {
			int[] at = BoxPicker.testCell(0);
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		});
		pause(2);
		then("AN tab", () -> {
			int[] at = BoxPicker.testButton("TAB2");
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		});
		pause(2);
		then("AN place", () -> {
			int[] at = BoxPicker.testCell(5);
			mouseTo(at[0], at[1]);
			clickButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
		});
		pause(2);
		pressEnterAndWait2();
		then("AN2", () -> {
			check("AN: moved between boxes: " + BoxGrab.lastMessage, BoxGrab.lastOk);
			List<ItemStack> first = contentsOf(mc.player.currentScreenHandler.slots.get(5).getStack()), second = contentsOf(mc.player.currentScreenHandler.slots.get(6).getStack());
			check("AN: out of the first", first.stream().noneMatch(x -> x.isOf(Items.DIAMOND)));
			check("AN: into that cell of the second (" + second.get(5) + ")", second.get(5).isOf(Items.DIAMOND) && second.get(5).getCount() == 40);
			check("AN: nothing left on you", count(Items.DIAMOND) == 0);
		});
		checkWorldClean("AN");

		// AL: take one thing, then a few, from the same box in an ender chest. It goes back to its own slot each time.
		reset();
		BlockPos[] again = new BlockPos[1];
		then("AL set", () -> {
			again[0] = origin.add(0, 0, 2);
			cmd("setblock " + again[0].getX() + " " + again[0].getY() + " " + again[0].getZ() + " ender_chest");
			cmd("item replace entity @s enderchest.3 with " + KIT);
			cmd("item replace entity @s enderchest.4 with dirt 3");
		});
		pause(10);
		then("AL open", () -> openBlockInView(again[0]));
		waitFor("AL screen", 100, () -> mc.currentScreen instanceof GenericContainerScreen && mc.player.currentScreenHandler.getRevision() != 0);
		pause(3);
		openPickerOn(3);
		clickCell(9, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT);
		pause(2);
		then("AL1", () -> check("AL: shift-click plans the one stack (" + BoxPicker.testWant(9) + ")", BoxPicker.testWant(9) == 16 && BoxPicker.testWant(10) == 0));
		pressEnterAndWait();
		then("AL2", () -> {
			check("AL: one item out: " + BoxGrab.lastMessage, BoxGrab.lastOk && count(Items.ENDER_PEARL) == 16);
			ItemStack box = mc.player.currentScreenHandler.slots.get(3).getStack();
			check("AL: the box is back in its slot, without it", box.isOf(Items.RED_SHULKER_BOX) && contentsOf(box).stream().noneMatch(s -> s.isOf(Items.ENDER_PEARL)));
			check("AL: and the ender chest screen is still open", mc.currentScreen instanceof GenericContainerScreen);
			check("AL: it still knows the ender chest", again[0].equals(BoxGrab.openContainer(mc)));
		});
		openPickerOn(3);
		clickCell(0, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT);
		clickCell(10, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT);
		clickCell(26, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_MOD_SHIFT);
		pause(2);
		pressEnterAndWait();
		then("AL3", () -> {
			check("AL: then several out: " + BoxGrab.lastMessage, BoxGrab.lastOk && count(Items.GOLDEN_APPLE) == 64 && count(Items.FIREWORK_ROCKET) == 64);
			ItemStack box = mc.player.currentScreenHandler.slots.get(3).getStack();
			check("AL: the box is back again, with the rest", box.isOf(Items.RED_SHULKER_BOX) && contentsOf(box).stream().anyMatch(s -> s.isOf(Items.NETHERITE_SWORD)));
			check("AL: nothing else in the ender chest moved", mc.player.currentScreenHandler.slots.get(4).getStack().isOf(Items.DIRT));
		});
		checkWorldClean("AL");

		// AO: a fussy server, as a real one can be. No opening with a shulker in hand, none unless you're really looking at it,
		// and the first tries refused. It still has to get back in, promptly, by clicking the way a person does.
		if (!remote) {
			then("AO watch", () -> net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
				if (world.isClient() || !strictEnder || !world.getBlockState(hit.getBlockPos()).isOf(net.minecraft.block.Blocks.ENDER_CHEST)) return net.minecraft.util.ActionResult.PASS;
				if (BoxGrab.isBox(player.getMainHandStack())) {
					refusedHolding++;
					return net.minecraft.util.ActionResult.FAIL;
				}
				Vec3d eye = player.getEyePos();
				BlockHitResult ray = world.raycast(new net.minecraft.world.RaycastContext(eye, eye.add(lookOf(player.getYaw(), player.getPitch()).multiply(5)),
						net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, player));
				if (ray.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK || !ray.getBlockPos().equals(hit.getBlockPos()) || ray.getSide() != hit.getSide()) {
					refusedAim++;
					log("AO refused aim: client said " + hit.getBlockPos() + " " + hit.getSide() + ", server sees " + ray.getBlockPos() + " " + ray.getSide() + " " + ray.getType() + ", server angles " + player.getYaw() + " " + player.getPitch() + ", eye " + eye);
					return net.minecraft.util.ActionResult.FAIL;
				}
				if (refuseNext > 0) {
					refuseNext--;
					refusedCount++;
					return net.minecraft.util.ActionResult.FAIL;
				}
				openedStrict++;
				return net.minecraft.util.ActionResult.PASS;
			}));
			reset();
			BlockPos[] fussy = new BlockPos[1];
			then("AO set", () -> {
				fussy[0] = origin.add(0, 0, 3);
				cmd("setblock " + fussy[0].getX() + " " + fussy[0].getY() + " " + fussy[0].getZ() + " ender_chest");
				cmd("item replace entity @s enderchest.3 with " + KIT);
			});
			enderRun("AO", fussy, () -> {
				strictEnder = true;
				refuseNext = 2;
				refusedHolding = refusedAim = refusedCount = openedStrict = 0;
			});
			then("AO2", () -> {
				strictEnder = false;
				check("AO: never clicked holding the shulker (" + refusedHolding + ")", refusedHolding == 0);
				check("AO: always really looking at it (" + refusedAim + ")", refusedAim == 0);
				check("AO: kept going through refusals (" + refusedCount + " refused, " + openedStrict + " opened)", refusedCount == 2 && openedStrict == 1);
				check("AO: by the game's own key press", BoxGrab.lastReopenByKey);
				check("AO: promptly (box back " + BoxGrab.lastBoxOutTicks + " ticks after leaving, " + BoxGrab.lastGroundTicks + " on the ground)", BoxGrab.lastBoxOutTicks - BoxGrab.lastGroundTicks < 120);
			});
		}

		// AP: ender chest at every speed, with a pickaxe as you'd really have it: right, and back in promptly each time.
		for (int speed : new int[]{100, 200, 500}) {
			reset();
			BlockPos[] apChest = new BlockPos[1];
			then("AP set " + speed, () -> {
				apChest[0] = origin.add(0, 0, 2);
				ModConfig.INSTANCE.boxPickerSpeed = speed;
				cmd("setblock " + apChest[0].getX() + " " + apChest[0].getY() + " " + apChest[0].getZ() + " ender_chest");
				cmd("item replace entity @s enderchest.3 with " + KIT);
				cmd("item replace entity @s hotbar.8 with netherite_pickaxe[enchantments={efficiency:5}]");
			});
			enderRun("AP" + speed, apChest, null);
			then("AP done " + speed, () -> {
				int inHand = BoxGrab.lastBoxOutTicks - BoxGrab.lastGroundTicks;
				check("AP" + speed + ": back in the ender chest within 2s of having the box (" + inHand + " ticks)", inHand <= 40);
				ModConfig.INSTANCE.boxPickerSpeed = 200;
			});
		}

		// AQ: it shuts the chest when it's done, and the box really is in there when you open it again.
		for (boolean inEnder : new boolean[]{true, false}) {
			String aq = "AQ " + (inEnder ? "ender chest" : "chest");
			reset();
			BlockPos[] at = new BlockPos[1];
			if (inEnder) {
				then(aq + " set", () -> {
					at[0] = origin.add(0, 0, 2);
					cmd("setblock " + at[0].getX() + " " + at[0].getY() + " " + at[0].getZ() + " ender_chest");
					cmd("item replace entity @s enderchest.3 with " + KIT);
				});
				pause(10);
				then(aq + " open", () -> openBlockInView(at[0]));
				waitFor(aq + " screen", 100, () -> mc.currentScreen instanceof GenericContainerScreen && mc.player.currentScreenHandler.getRevision() != 0);
				pause(3);
			} else {
				then(aq + " set", () -> at[0] = chest);
				boxInChest(3);
				openChest();
			}
			then(aq + " real end", () -> {
				BoxGrab.testKeepOpen = false;
				cmd("item replace entity @s hotbar.8 with netherite_pickaxe[enchantments={efficiency:5}]");
			});
			pause(3);
			openPickerOn(3);
			hit("ALL");
			pressEnterAndWait();
			shot("closed_" + (inEnder ? "ender" : "chest"));
			then(aq + " closed", () -> {
				BoxGrab.testKeepOpen = true;
				check(aq + ": worked: " + BoxGrab.lastMessage, BoxGrab.lastOk);
				check(aq + ": no screen left open (" + mc.currentScreen + ")", mc.currentScreen == null);
				check(aq + ": the client has left it", mc.player.currentScreenHandler == mc.player.playerScreenHandler);
				check(aq + ": the items are on you", count(Items.ENDER_PEARL) == 16);
				if (!remote) {
					var uuid = mc.player.getUuid();
					mc.getServer().execute(() -> {
						var sp = mc.getServer().getPlayerManager().getPlayer(uuid);
						check(aq + ": the server has left it too", sp.currentScreenHandler == sp.playerScreenHandler);
					});
				}
				log(aq + ": took " + BoxGrab.lastTicks + " ticks, box out " + BoxGrab.lastBoxOutTicks + ", on the ground " + BoxGrab.lastGroundTicks);
			});
			pause(5);
			then(aq + " look again", () -> openBlockInView(at[0]));
			waitFor(aq + " screen again", 100, () -> mc.currentScreen instanceof GenericContainerScreen && mc.player.currentScreenHandler.getRevision() != 0);
			then(aq + " box", () -> {
				check(aq + ": the box is in its slot", mc.player.currentScreenHandler.slots.get(3).getStack().isOf(Items.RED_SHULKER_BOX));
				mc.player.closeHandledScreen();
			});
			pause(5);
			then(aq + " tidy", () -> {
				if (inEnder) cmd("setblock " + at[0].getX() + " " + at[0].getY() + " " + at[0].getZ() + " air");
				cmd("item replace entity @s enderchest.3 with air");
			});
			pause(3);
		}

		// AR: when it couldn't do the job it leaves the chest open for you.
		reset();
		boxInChest(3);
		openChest();
		then("AR real end", () -> {
			BoxGrab.testKeepOpen = false;
			BoxGrab.testFailPlaces = 99;
		});
		openPickerOn(3);
		hit("ALL");
		pressEnterAndWait();
		then("AR", () -> {
			BoxGrab.testKeepOpen = true;
			BoxGrab.testFailPlaces = 0;
			check("AR: it says it couldn't: " + BoxGrab.lastMessage, !BoxGrab.lastOk);
			check("AR: the chest stays open (" + mc.currentScreen + ")", mc.currentScreen instanceof GenericContainerScreen);
		});

		// AF: a screen no block opened isn't the chest you used last. The box must not go into that chest afterwards.
		reset();
		openChest();
		then("AF chest", () -> {
			check("AF: a chest is found as the container", chest.equals(BoxGrab.openContainer(mc)));
			mc.player.closeHandledScreen();
		});
		pause(5);
		then("AF cart", () -> cmd("summon chest_minecart " + origin.getX() + " " + origin.getY() + " " + (origin.getZ() - 2)));
		waitFor("the minecart", 100, () -> !mc.world.getEntitiesByClass(net.minecraft.entity.vehicle.ChestMinecartEntity.class, new Box(origin).expand(6), e -> true).isEmpty());
		pause(5);
		then("AF open", () -> mc.interactionManager.interactEntity(mc.player,
				mc.world.getEntitiesByClass(net.minecraft.entity.vehicle.ChestMinecartEntity.class, new Box(origin).expand(6), e -> true).get(0), Hand.MAIN_HAND));
		waitFor("the minecart's screen", 100, () -> mc.currentScreen instanceof GenericContainerScreen);
		then("AF2", () -> {
			check("AF: a minecart's screen has no container block", BoxGrab.openContainer(mc) == null);
			mc.player.closeHandledScreen();
			cmd("kill @e[type=chest_minecart]");
			cmd("kill @e[type=item]");
		});
		pause(5);

		// AG: asking the server for the truth must never throw away what it thinks is on the cursor.
		if (!remote) {
			reset();
			openChest();
			then("AG cursor", () -> {
				var uuid = mc.player.getUuid();
				mc.getServer().execute(() -> mc.getServer().getPlayerManager().getPlayer(uuid).currentScreenHandler.setCursorStack(new ItemStack(Items.DIAMOND, 5)));
			});
			pause(5);
			then("AG ask", () -> BoxGrab.resync(mc.player));
			pause(10);
			then("AG2", () -> {
				check("AG: the server's cursor reaches the client (" + mc.player.currentScreenHandler.getCursorStack() + ")", mc.player.currentScreenHandler.getCursorStack().isOf(Items.DIAMOND));
				check("AG: nothing was dropped", mc.world.getEntitiesByClass(ItemEntity.class, new Box(origin).expand(10), e -> e.getStack().isOf(Items.DIAMOND)).isEmpty());
				mc.player.closeHandledScreen();
			});
			pause(5);
			then("AG3", () -> check("AG: and it's back in the inventory on closing (" + mc.player.getInventory().count(Items.DIAMOND) + ")", mc.player.getInventory().count(Items.DIAMOND) == 5));
		}

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
