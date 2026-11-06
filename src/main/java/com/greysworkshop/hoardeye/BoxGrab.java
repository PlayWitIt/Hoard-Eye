package com.greysworkshop.hoardeye;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bot behind {@link BoxPicker}. A shulker box can only be opened as a placed block, so: move it out of the chest,
 * set it down at your feet, open it, take what was picked, break it, pick it up and put it back in the same slot.
 * It waits on what the server actually did at every step rather than on fixed delays, so it works at any ping, and
 * whatever goes wrong it goes on to get the box back and says plainly where it is.
 *
 * <p>Normally each action waits its turn, at a speed set against the fastest a person could do them, and the box isn't
 * broken until the server has confirmed the items really arrived. That's what makes it dependable on a real server.
 *
 * <p>With Instant switched on it doesn't wait at all up to the break: packets arrive in the order they're sent, so the move, place,
 * open, take, close and break all go in one burst and the server does them in one tick, at any ping. The screen the
 * server opens for the box is known ahead of time (its number just counts up), so the clicks can be sent before the
 * answer comes back; that answer is thrown away when it arrives. After that the box is on the ground only for the
 * server's own pickup delay, during which nobody at all can pick it up. If a burst doesn't work on some server the bot
 * still gets the box back, and from then on uses the step-by-step way.
 */
public final class BoxGrab {
	enum Step { IDLE, SNEAK, PLACE, OPEN, TAKE, CHECK, BREAK, COLLECT, REOPEN, PUT_BACK, SETTLE }

	/** What the picker hands over. {@code want[i]} is how many to take from the box's slot i. */
	record Request(int sourceSlot, boolean sourceIsInventory, int inventoryIndex, ItemStack box, List<ItemStack> contents,
			int[] want, BlockPos container, int containerSlots, boolean fromInventoryScreen) {}

	private static final double REACH = 4.4;
	/** Most clicks sent in one tick, so a big pick can't trip a server's packet limit. */
	private static final int CLICK_BUDGET = 40;
	/** The fastest a very good player does one inventory action or block click: about 150 ms. An estimate, not a measurement. */
	private static final double HUMAN_ACTION_TICKS = 3;
	private static final int MAX_TAKE_TRIES = 4;
	private static final int OPEN_RETRY = 100, OPEN_TIMEOUT = 200, COLLECT_TIMEOUT = 200, REOPEN_TIMEOUT = 200;

	private static Step step = Step.IDLE;
	private static Request request;
	private static int ticks, totalTicks, boxOutTick, hotbar, savedSelected, nextWant, spotIndex;
	private static float savedYaw, savedPitch;
	private static BlockPos spot;
	private static List<BlockPos> triedSpots = new ArrayList<>();
	private static Item boxItem;
	private static Text boxName;
	/** Inventory slots that already held this kind of box, so the one coming back can be told from them. */
	private static Set<Integer> otherBoxes = new HashSet<>();
	/** Moves to undo at the end: inventory slot, swapped with the hotbar slot. Last in, first undone. */
	private static final List<int[]> swaps = new ArrayList<>();
	private static Map<Item, Integer> before = new LinkedHashMap<>();
	private static final List<String> notes = new ArrayList<>();
	private static boolean breaking, attacked, walking, opened, claimedBack, hadBox, clicked;
	private static int settleTicks, boxBackTick, putBackTries;
	/** This run went in one burst; the answer to its open is still on its way; it asked the server for the truth. */
	private static boolean burstRun, swallowOpen, resynced;
	/** Off once a burst has failed on this server. */
	private static boolean burstWorks = true;
	private static int predictedSync, lastSync, boxInHandTick;
	/** When the next action may go, in ticks of this run; how long each action takes at the chosen speed. */
	private static double nextAction, pace;
	/** How many are still to come out of each slot of the box. */
	private static int[] left;
	private static boolean closedChest;
	private static int takeTries, inventorySyncs, syncAsked;
	/** Self-test: pretend the server lost the clicks for this many passes over the box. */
	static int testLoseClicks;
	static int lastTakeTries;
	/** The last run's result, for the self-test. */
	static boolean lastOk;
	static String lastMessage = "";
	static int lastTicks, lastBoxOutTicks, lastGroundTicks;
	static boolean lastBurst;
	/** Self-test: pretend this many placements were refused. */
	static int testFailPlaces;
	/** The block last right-clicked, so the container can be found again. */
	private static BlockPos lastUsed;

	private BoxGrab() {}

	public static boolean running() {
		return step != Step.IDLE;
	}

	static Step step() {
		return step;
	}

	static BlockPos spot() {
		return spot;
	}

	/** While set, the game's own "attack isn't held" check mustn't cancel the break. */
	public static boolean holdingBreak() {
		return breaking;
	}

	/**
	 * Every screen the server opens. True to throw it away: the answer to a burst's open, by which time the box has
	 * been closed on the server and broken.
	 */
	public static boolean onOpenScreen(int syncId, ScreenHandlerType<?> type) {
		lastSync = syncId;
		if (!swallowOpen || type != ScreenHandlerType.SHULKER_BOX) return false;
		swallowOpen = false;
		// Some server numbers its screens differently, so the clicks went nowhere.
		if (syncId != predictedSync) burstWorks = false;
		return true;
	}

	/** The server sent the whole contents of a screen, which is the answer {@link #resync} asks for. */
	public static void onInventory(int syncId) {
		inventorySyncs++;
	}

	private static boolean ready() {
		return totalTicks >= nextAction;
	}

	private static void acted() {
		nextAction = Math.max(nextAction, totalTicks) + pace;
	}

	static void testAllowBurst() {
		burstWorks = true;
	}

	static boolean testBurstWorks() {
		return burstWorks;
	}

	public static void noteUse(BlockPos pos) {
		if (!running()) lastUsed = pos;
	}

	/** The container that's open, if it's a block we could reopen; null for an entity or a server's menu. */
	static BlockPos openContainer(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (lastUsed == null || p == null || client.world == null || p.currentScreenHandler == p.playerScreenHandler) return null;
		BlockState state = client.world.getBlockState(lastUsed);
		boolean container = state.isOf(Blocks.ENDER_CHEST) || state.createScreenHandlerFactory(client.world, lastUsed) != null;
		return container && !(state.getBlock() instanceof ShulkerBoxBlock) ? lastUsed : null;
	}

	public static boolean isBox(ItemStack stack) {
		return !stack.isEmpty() && Block.getBlockFromItem(stack.getItem()) instanceof ShulkerBoxBlock;
	}

	// ---- Where the box goes ----

	/** Spots to set the box down, best first. Close to your feet so it comes straight back when it breaks. */
	static List<BlockPos> spots(ClientPlayerEntity p, ClientWorld world) {
		record Scored(BlockPos pos, double score) {}
		List<Scored> found = new ArrayList<>();
		BlockPos feet = p.getBlockPos();
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos cell = feet.add(dx, dy, dz);
					if (!canHoldBox(p, world, cell)) continue;
					double flat = Math.hypot(cell.getX() + 0.5 - p.getX(), cell.getZ() + 0.5 - p.getZ());
					found.add(new Scored(cell, flat + Math.abs(dy) * 0.75));
				}
			}
		}
		found.sort(Comparator.comparingDouble(Scored::score));
		List<BlockPos> list = new ArrayList<>();
		for (Scored s : found) list.add(s.pos());
		return list;
	}

	private static boolean canHoldBox(ClientPlayerEntity p, ClientWorld world, BlockPos cell) {
		BlockState here = world.getBlockState(cell), above = world.getBlockState(cell.up());
		// It opens upward, so the cell above must be clear too.
		if (!here.isAir() || !above.getCollisionShape(world, cell.up()).isEmpty() || !above.getFluidState().isEmpty()) return false;
		if (!world.getWorldBorder().contains(cell)) return false;
		BlockPos below = cell.down();
		BlockState floor = world.getBlockState(below);
		if (!floor.isSideSolidFullSquare(world, below, Direction.UP)) return false;
		// Clicking these would use them instead of placing against them; on a chest it'd also block the lid.
		if (floor.hasBlockEntity() || floor.createScreenHandlerFactory(world, below) != null || floor.isIn(BlockTags.TRAPDOORS)
				|| floor.isIn(BlockTags.DOORS) || floor.isIn(BlockTags.BUTTONS) || floor.isOf(Blocks.NOTE_BLOCK) || floor.isOf(Blocks.TNT)) return false;
		if (!world.doesNotIntersectEntities(null, VoxelShapes.fullCube().offset(cell.getX(), cell.getY(), cell.getZ()))) return false;
		// The drop must not land where it would burn or wash away.
		for (Direction d : Direction.Type.HORIZONTAL) {
			BlockState side = world.getBlockState(cell.offset(d));
			FluidState fluid = side.getFluidState();
			if (fluid.isIn(FluidTags.LAVA) || !fluid.isEmpty() || side.isIn(BlockTags.FIRE) || side.isOf(Blocks.CACTUS)) return false;
		}
		Vec3d hit = Vec3d.ofCenter(below).add(0, 0.5, 0), eye = p.getEyePos();
		if (eye.distanceTo(hit) > REACH) return false;
		BlockHitResult ray = world.raycast(new RaycastContext(eye, hit.add(0, -0.01, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
		return ray.getType() == HitResult.Type.MISS || ray.getBlockPos().equals(below);
	}

	/** Whether the picked items and the box itself all fit in the inventory. */
	static boolean fits(PlayerInventory inv, List<ItemStack> contents, int[] want, int boxInventoryIndex) {
		ItemStack[] slots = new ItemStack[PlayerInventory.MAIN_SIZE];
		for (int i = 0; i < slots.length; i++) slots[i] = i == boxInventoryIndex ? ItemStack.EMPTY : inv.getStack(i).copy();
		for (int i = 0; i < want.length; i++) {
			int left = want[i];
			ItemStack item = contents.get(i);
			if (left <= 0) continue;
			int max = item.getMaxCount();
			for (ItemStack slot : slots) {
				if (left <= 0) break;
				if (slot.isEmpty() || !ItemStack.areItemsAndComponentsEqual(slot, item) || slot.getCount() >= max) continue;
				int moved = Math.min(left, max - slot.getCount());
				slot.increment(moved);
				left -= moved;
			}
			for (int s = 0; s < slots.length && left > 0; s++) {
				if (!slots[s].isEmpty()) continue;
				int moved = Math.min(left, max);
				slots[s] = item.copyWithCount(moved);
				left -= moved;
			}
			if (left > 0) return false;
		}
		// Room left for the box to come back into.
		for (ItemStack slot : slots) if (slot.isEmpty()) return true;
		return false;
	}

	/** How long breaking the box will take with the best tool carried, in ticks. */
	static int breakTicks(PlayerInventory inv) {
		float speed = breakSpeed(bestTool(inv) < 0 ? ItemStack.EMPTY : inv.getStack(bestTool(inv)));
		// Hardness 2, and no tool is required for the drop: the game's formula is hardness * 30 / speed ticks.
		return MathHelper.ceil(2f * 30 / speed);
	}

	private static int bestTool(PlayerInventory inv) {
		int best = -1;
		float top = 1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty() || (s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 2)) continue;
			float speed = breakSpeed(s);
			if (speed > top) {
				top = speed;
				best = i;
			}
		}
		return best;
	}

	private static float breakSpeed(ItemStack s) {
		BlockState box = Blocks.SHULKER_BOX.getDefaultState();
		float speed = s.getMiningSpeedMultiplier(box);
		if (speed > 1) {
			for (RegistryEntry<Enchantment> e : s.getEnchantments().getEnchantments()) {
				if (e.matchesKey(Enchantments.EFFICIENCY)) {
					int level = s.getEnchantments().getLevel(e);
					speed += level * level + 1;
				}
			}
		}
		return speed;
	}

	// ---- Running ----

	static void start(MinecraftClient client, Request r) {
		ClientPlayerEntity p = client.player;
		request = r;
		PlayerInventory inv = p.getInventory();
		ScreenHandler h = p.currentScreenHandler;
		savedYaw = p.getYaw();
		savedPitch = p.getPitch();
		savedSelected = inv.getSelectedSlot();
		boxItem = r.box().getItem();
		boxName = r.box().get(DataComponentTypes.CUSTOM_NAME);
		swaps.clear();
		notes.clear();
		triedSpots = new ArrayList<>();
		before = inventoryCounts(inv);
		nextWant = 0;
		spotIndex = 0;
		totalTicks = 0;
		boxOutTick = -1;
		attacked = breaking = walking = opened = burstRun = swallowOpen = resynced = false;
		boxInHandTick = -1;
		pace = HUMAN_ACTION_TICKS * 100.0 / Math.max(10, ModConfig.INSTANCE.boxPickerSpeed);
		nextAction = 0;
		left = r.want().clone();
		closedChest = false;
		takeTries = 0;
		putBackTries = 0;
		spot = null;
		KeyBinding.unpressAll();

		if (r.sourceIsInventory() && r.inventoryIndex() < 9) {
			hotbar = r.inventoryIndex();
		} else {
			hotbar = chooseHotbar(inv);
			if (!r.sourceIsInventory() && !inv.getStack(hotbar).isEmpty()) {
				// A full hotbar: park that item in an empty slot for now, and it goes back at the end.
				int free = firstEmptyMain(inv);
				if (free < 0) {
					report(client, false, Text.translatable("hoardeye.grab.no_room").getString());
					return;
				}
				click(client, h, slotOf(h, inv, free), hotbar, SlotActionType.SWAP);
				swaps.add(new int[]{free, hotbar});
			} else if (r.sourceIsInventory() && !inv.getStack(hotbar).isEmpty()) {
				swaps.add(new int[]{r.inventoryIndex(), hotbar});
			}
			click(client, h, r.sourceSlot(), hotbar, SlotActionType.SWAP);
		}
		otherBoxes = new HashSet<>();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			if (i != hotbar && inv.getStack(i).isOf(boxItem)) otherBoxes.add(i);
		}
		boolean canBurst = ModConfig.INSTANCE.boxPickerInstant && burstWorks && testFailPlaces == 0
				&& !p.isSneaking() && !client.options.sneakKey.isPressed();
		int previousSync = h.syncId != 0 ? h.syncId : lastSync;
		if (canBurst) {
			pace = 0;
			closedChest = true;
			p.closeHandledScreen();
			inv.setSelectedSlot(hotbar);
			if (burst(client, p, previousSync)) return;
		}
		acted();
		go(Step.SNEAK);
	}

	/** Everything up to the break, right now. False if there's nowhere to put the box, which the normal way reports. */
	private static boolean burst(MinecraftClient client, ClientPlayerEntity p, int previousSync) {
		PlayerInventory inv = p.getInventory();
		List<BlockPos> options = spots(p, client.world);
		if (options.isEmpty() || !inv.getStack(hotbar).isOf(boxItem)) return false;
		burstRun = true;
		// Running from here on, so these clicks aren't mistaken for you opening a container.
		go(Step.PLACE);
		spot = options.get(0);
		triedSpots.add(spot);
		BlockPos below = spot.down();
		Vec3d floor = Vec3d.ofCenter(below).add(0, 0.5, 0);
		look(p, floor);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(floor, Direction.UP, below, false));
		p.swingHand(Hand.MAIN_HAND);
		boxOutTick = totalTicks;
		Vec3d top = Vec3d.ofCenter(spot).add(0, 0.5, 0);
		look(p, top);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(top, Direction.UP, spot, false));
		p.swingHand(Hand.MAIN_HAND);
		// The server is about to open this; act as if it already has. The same click code then works unchanged.
		predictedSync = previousSync % 100 + 1;
		swallowOpen = true;
		SimpleInventory contents = new SimpleInventory(27);
		for (int i = 0; i < Math.min(contents.size(), request.contents().size()); i++) contents.setStack(i, request.contents().get(i).copy());
		p.currentScreenHandler = new ShulkerBoxScreenHandler(predictedSync, inv, contents);
		go(Step.TAKE);
		take(client, p);
		return true;
	}

	/** A click on nothing with an out-of-date number makes the server send the whole inventory, correcting any guess. */
	private static void resync(ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		p.networkHandler.sendPacket(new ClickSlotC2SPacket(h.syncId, -1, (short) -999, (byte) 0, SlotActionType.PICKUP,
				new Int2ObjectOpenHashMap<>(), ItemStackHash.EMPTY));
	}

	private static int chooseHotbar(PlayerInventory inv) {
		int selected = inv.getSelectedSlot();
		if (inv.getStack(selected).isEmpty()) return selected;
		for (int i = 0; i < 9; i++) if (inv.getStack(i).isEmpty()) return i;
		// Full: any slot will do, since it's put back. Not the one with a weapon or tool if that can be helped.
		for (int i = 0; i < 9; i++) if (!inv.getStack(i).isDamageable()) return i;
		return selected;
	}

	private static int firstEmptyMain(PlayerInventory inv) {
		for (int i = 9; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) return i;
		return -1;
	}

	private static void go(Step next) {
		step = next;
		ticks = 0;
		clicked = false;
	}

	public static void onDisconnect() {
		breaking = false;
		swallowOpen = false;
		burstWorks = true;
		lastSync = 0;
		step = Step.IDLE;
	}

	public static void tick(MinecraftClient client) {
		if (step == Step.IDLE) return;
		ClientPlayerEntity p = client.player;
		ClientWorld world = client.world;
		if (p == null || world == null || client.interactionManager == null) {
			onDisconnect();
			return;
		}
		if (p.isDead()) {
			stop(client);
			report(client, false, Text.translatable("hoardeye.grab.died").getString());
			return;
		}
		ticks++;
		totalTicks++;
		switch (step) {
			case SNEAK -> sneak(client, p);
			case PLACE -> place(client, p, world);
			case OPEN -> open(client, p, world);
			case TAKE -> take(client, p);
			case CHECK -> check(client, p);
			case BREAK -> breakBox(client, p, world);
			case COLLECT -> collect(client, p, world);
			case REOPEN -> reopen(client, p, world);
			case PUT_BACK -> putBack(client, p);
			case SETTLE -> settle(client, p);
			default -> {}
		}
	}

	private static void sneak(MinecraftClient client, ClientPlayerEntity p) {
		if (!closedChest) {
			if (!ready()) return;
			closedChest = true;
			p.closeHandledScreen();
			p.getInventory().setSelectedSlot(hotbar);
			acted();
		}
		// Crouching would stop the box opening.
		KeyBinding key = client.options.sneakKey;
		if (key.isPressed()) {
			key.setPressed(false);
			// A toggle key flips on "pressed".
			if (key.isPressed()) key.setPressed(true);
		}
		if (!p.isSneaking() || ticks > 10) go(Step.PLACE);
	}

	private static void place(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		if (!ready()) return;
		PlayerInventory inv = p.getInventory();
		if (!inv.getStack(hotbar).isOf(boxItem)) {
			// The server said no to moving it (or it was already in the world and came back).
			if (ticks > 40) {
				notes.add(Text.translatable("hoardeye.grab.not_taken_out").getString());
				go(Step.REOPEN);
			}
			return;
		}
		List<BlockPos> options = spots(p, world);
		options.removeAll(triedSpots);
		if (options.isEmpty() || triedSpots.size() >= 4) {
			notes.add(Text.translatable("hoardeye.grab.no_spot").getString());
			go(Step.REOPEN);
			return;
		}
		spot = options.get(0);
		triedSpots.add(spot);
		if (testFailPlaces > 0) {
			testFailPlaces--;
			return;
		}
		inv.setSelectedSlot(hotbar);
		BlockPos below = spot.down();
		Vec3d hit = Vec3d.ofCenter(below).add(0, 0.5, 0);
		look(p, hit);
		client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
		p.swingHand(Hand.MAIN_HAND);
		if (boxOutTick < 0) boxOutTick = totalTicks;
		acted();
		go(Step.OPEN);
	}

	private static void open(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		ScreenHandler h = p.currentScreenHandler;
		if (h instanceof ShulkerBoxScreenHandler) {
			// Slots fill in just after the screen opens.
			if (h.getRevision() != 0) {
				acted();
				go(Step.TAKE);
			}
			return;
		}
		boolean there = world.getBlockState(spot).getBlock() instanceof ShulkerBoxBlock;
		if (!there) {
			// The server took the placement back: it's returned to the hand, so try the next spot.
			if (p.getInventory().getStack(hotbar).isOf(boxItem)) go(Step.PLACE);
			else if (ticks > OPEN_TIMEOUT) go(Step.COLLECT);
			return;
		}
		if (ticks > OPEN_TIMEOUT) {
			notes.add(Text.translatable("hoardeye.grab.not_opened").getString());
			go(Step.BREAK);
			return;
		}
		if ((!clicked && ready()) || (clicked && ticks % OPEN_RETRY == 0)) {
			clicked = true;
			Vec3d hit = Vec3d.ofCenter(spot).add(0, 0.5, 0);
			if (p.getEyePos().distanceTo(hit) > REACH + 1) {
				stop(client);
				report(client, false, Text.translatable("hoardeye.grab.left_box", spot.toShortString()).getString());
				return;
			}
			acted();
			look(p, hit);
			client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, spot, false));
			p.swingHand(Hand.MAIN_HAND);
		}
	}

	private static void take(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		if (!(h instanceof ShulkerBoxScreenHandler)) {
			// Closed under us. Whatever came out came out; get the box back.
			notes.add(Text.translatable("hoardeye.grab.closed_early").getString());
			go(Step.BREAK);
			return;
		}
		if (!ready()) return;
		int budget = CLICK_BUDGET;
		boolean lose = testLoseClicks > 0;
		while (nextWant < left.length && budget > 0) {
			int i = nextWant++;
			if (left[i] <= 0) continue;
			Slot slot = h.slots.get(i);
			ItemStack here = slot.getStack();
			ItemStack expected = request.contents().get(i);
			if (here.isEmpty() || !ItemStack.areItemsAndComponentsEqual(here, expected)) continue;
			int amount = Math.min(left[i], here.getCount());
			if (lose) break;
			budget -= amount == here.getCount() ? quickMove(client, h, slot) : takePart(client, p, h, slot, amount);
			// One stack per action, unless everything is meant to go at once.
			if (!burstRun) {
				acted();
				return;
			}
		}
		if (lose) {
			testLoseClicks--;
			nextWant = left.length;
		}
		if (nextWant < left.length) return;
		if (burstRun) {
			closeBox(client, p);
			breakBox(client, p, client.world);
			return;
		}
		// Don't break it yet: first see what the server says is really in there.
		syncAsked = inventorySyncs;
		resync(p);
		go(Step.CHECK);
	}

	/** Only when the server has confirmed the items came out does the box get closed and broken. */
	private static void check(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		if (!(h instanceof ShulkerBoxScreenHandler)) {
			notes.add(Text.translatable("hoardeye.grab.closed_early").getString());
			go(Step.BREAK);
			return;
		}
		boolean answered = inventorySyncs != syncAsked;
		if (!answered && ticks <= 20 + 2 * latencyTicks(client, p)) return;
		boolean more = false;
		for (int i = 0; i < left.length; i++) {
			int want = request.want()[i];
			if (want <= 0) {
				left[i] = 0;
				continue;
			}
			ItemStack here = h.slots.get(i).getStack(), was = request.contents().get(i);
			int shouldRemain = was.getCount() - want;
			left[i] = ItemStack.areItemsAndComponentsEqual(here, was) ? Math.max(0, here.getCount() - shouldRemain) : 0;
			more |= left[i] > 0;
		}
		if (more && answered && ++takeTries < MAX_TAKE_TRIES) {
			nextWant = 0;
			acted();
			go(Step.TAKE);
			return;
		}
		if (!ready()) return;
		closeBox(client, p);
	}

	private static void closeBox(MinecraftClient client, ClientPlayerEntity p) {
		lastTakeTries = takeTries + 1;
		p.closeHandledScreen();
		acted();
		go(Step.BREAK);
	}

	private static int quickMove(MinecraftClient client, ScreenHandler h, Slot slot) {
		client.interactionManager.clickSlot(h.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, client.player);
		return 1;
	}

	/** Takes part of a stack, in whichever way needs fewer clicks. Returns the clicks used. */
	private static int takePart(MinecraftClient client, ClientPlayerEntity p, ScreenHandler h, Slot source, int amount) {
		PlayerInventory inv = p.getInventory();
		ItemStack item = source.getStack().copy();
		int count = item.getCount(), used = 1;
		click(client, h, source.id, 0, SlotActionType.PICKUP);
		Slot single = roomFor(h, inv, item, amount);
		if (amount <= count - amount && single != null) {
			for (int n = 0; n < amount; n++, used++) click(client, h, single.id, 1, SlotActionType.PICKUP);
		} else {
			for (int n = 0; n < count - amount; n++, used++) click(client, h, source.id, 1, SlotActionType.PICKUP);
			for (int guard = 0; !h.getCursorStack().isEmpty() && guard < 36; guard++) {
				Slot next = roomFor(h, inv, item, 1);
				if (next == null) break;
				click(client, h, next.id, 0, SlotActionType.PICKUP);
				used++;
			}
		}
		if (!h.getCursorStack().isEmpty()) {
			click(client, h, source.id, 0, SlotActionType.PICKUP);
			used++;
		}
		return used;
	}

	private static Slot roomFor(ScreenHandler h, PlayerInventory inv, ItemStack item, int amount) {
		Slot empty = null;
		for (Slot s : h.slots) {
			if (s.inventory != inv || s.getIndex() >= PlayerInventory.MAIN_SIZE) continue;
			ItemStack here = s.getStack();
			if (here.isEmpty()) {
				if (empty == null || s.getIndex() >= 9 && empty.getIndex() < 9) empty = s;
			} else if (ItemStack.areItemsAndComponentsEqual(here, item) && here.getCount() + amount <= here.getMaxCount()) {
				return s;
			}
		}
		return empty;
	}

	private static void breakBox(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		BlockState state = world.getBlockState(spot);
		if (!(state.getBlock() instanceof ShulkerBoxBlock)) {
			client.interactionManager.cancelBlockBreaking();
			breaking = false;
			attacked = false;
			go(Step.COLLECT);
			return;
		}
		Vec3d center = Vec3d.ofCenter(spot);
		if (p.getEyePos().distanceTo(center) > REACH + 1) {
			stop(client);
			report(client, false, Text.translatable("hoardeye.grab.left_box", spot.toShortString()).getString());
			return;
		}
		if (ticks > 400) {
			stop(client);
			report(client, false, Text.translatable("hoardeye.grab.not_broken", spot.toShortString()).getString());
			return;
		}
		Direction side = Direction.getFacing(p.getEyePos().subtract(center));
		if (!burstRun || !attacked) look(p, center.add(Vec3d.of(side.getVector()).multiply(0.5)));
		if (!attacked) {
			if (!ready()) return;
			holdBestTool(client, p);
			breaking = true;
			attacked = true;
			// Left over from the last box's break, and it would only run down while this one sat waiting.
			((com.greysworkshop.hoardeye.mixin.InteractionManagerAccessor) client.interactionManager).hoardeye$setBreakCooldown(0);
			client.interactionManager.attackBlock(spot, side);
			if (burstRun) {
				// A stop sent early isn't refused: the server finishes the break itself as soon as the tool could have.
				// Only for a quick break, so a slow one can't finish after you've been pulled away from it.
				// Worked out from the tool itself: efficiency only reaches the player's stats a tick after it's in hand.
				float delta = Math.max(state.calcBlockBreakingDelta(p, world, spot), breakSpeed(p.getMainHandStack()) / 60f);
				if (delta < 1 && delta >= 0.3f) {
					p.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, spot, side));
				}
				// The burst is over: your view never moved, and the server sees it go straight back.
				p.setYaw(savedYaw);
				p.setPitch(savedPitch);
			}
		} else {
			client.interactionManager.updateBlockBreakingProgress(spot, side);
		}
		p.swingHand(Hand.MAIN_HAND);
	}

	private static void holdBestTool(MinecraftClient client, ClientPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		int tool = bestTool(inv);
		if (tool < 0) return;
		if (tool < 9) {
			inv.setSelectedSlot(tool);
			return;
		}
		int hand = inv.getSelectedSlot();
		ScreenHandler h = p.playerScreenHandler;
		click(client, h, slotOf(h, inv, tool), hand, SlotActionType.SWAP);
		swaps.add(new int[]{tool, hand});
	}

	private static void collect(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		if (findBox(p.getInventory()) >= 0) {
			boxInHandTick = totalTicks;
			acted();
			// It never left the hand: the server refused the burst.
			if (resynced) {
				burstWorks = false;
				notes.add(Text.translatable("hoardeye.grab.burst_failed").getString());
			}
			walk(client, false);
			go(Step.REOPEN);
			return;
		}
		// The server put the block back: it didn't really break.
		if (world.getBlockState(spot).getBlock() instanceof ShulkerBoxBlock && ticks > 2) {
			walk(client, false);
			go(Step.BREAK);
			return;
		}
		ItemEntity drop = world.getEntitiesByClass(ItemEntity.class, new Box(spot).expand(8), e -> e.getStack().isOf(boxItem)).stream()
				.min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).orElse(null);
		if (ticks > COLLECT_TIMEOUT || (drop == null && ticks > 100)) {
			stop(client);
			report(client, false, Text.translatable(drop == null ? "hoardeye.grab.box_gone" : "hoardeye.grab.left_drop",
					spot.toShortString()).getString());
			return;
		}
		if (drop == null) {
			// By now the drop should be in sight. If not, what we guessed may be wrong, so ask.
			if (burstRun && !resynced && ticks > 6 + 2 * latencyTicks(client, p)) {
				resynced = true;
				resync(p);
			}
			return;
		}
		if (p.getBoundingBox().expand(1, 0.5, 1).intersects(drop.getBoundingBox())) {
			walk(client, false);
			return;
		}
		Vec3d d = drop.getEntityPos().subtract(p.getEntityPos());
		p.setYaw((float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90);
		walk(client, true);
		client.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());
	}

	private static void walk(MinecraftClient client, boolean on) {
		if (!on && !walking) return;
		walking = on;
		client.options.forwardKey.setPressed(on);
		if (!on) client.options.jumpKey.setPressed(false);
	}

	/** The inventory slot the box came back to, or -1. */
	private static int findBox(PlayerInventory inv) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isOf(boxItem) || otherBoxes.contains(i)) continue;
			if (java.util.Objects.equals(s.get(DataComponentTypes.CUSTOM_NAME), boxName)) return i;
		}
		return -1;
	}

	private static void reopen(MinecraftClient client, ClientPlayerEntity p, ClientWorld world) {
		BlockPos container = request.container();
		if (container == null) {
			go(Step.PUT_BACK);
			putBack(client, p);
			return;
		}
		ScreenHandler h = p.currentScreenHandler;
		if (h != p.playerScreenHandler && !(h instanceof ShulkerBoxScreenHandler)) {
			if (h.getRevision() != 0 && ready()) {
				go(Step.PUT_BACK);
				putBack(client, p);
			}
			return;
		}
		Vec3d hit = Vec3d.ofCenter(container).add(0, 0.45, 0);
		if (p.getEyePos().distanceTo(hit) > REACH + 1 || ticks > REOPEN_TIMEOUT) {
			notes.add(Text.translatable("hoardeye.grab.not_reopened").getString());
			request = new Request(request.sourceSlot(), request.sourceIsInventory(), request.inventoryIndex(), request.box(), request.contents(),
					request.want(), null, request.containerSlots(), false);
			go(Step.PUT_BACK);
			putBack(client, p);
			return;
		}
		if (p.isSneaking()) return;
		// Exactly once, then only a slow retry: a second click while the first is on its way opens the chest again,
		// and the server would throw away everything sent to the first one.
		if ((!clicked && ready()) || (clicked && ticks % OPEN_RETRY == 0)) {
			clicked = true;
			acted();
			look(p, hit);
			client.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, container, false));
			p.swingHand(Hand.MAIN_HAND);
			if (burstRun && !walking) {
				p.setYaw(savedYaw);
				p.setPitch(savedPitch);
			}
		}
	}

	private static void putBack(MinecraftClient client, ClientPlayerEntity p) {
		PlayerInventory inv = p.getInventory();
		ScreenHandler h = p.currentScreenHandler;
		// Undo the shuffling first, newest first, so the hotbar is as it was.
		for (int i = swaps.size() - 1; i >= 0; i--) {
			int[] swap = swaps.get(i);
			click(client, h, slotOf(h, inv, swap[0]), swap[1], SlotActionType.SWAP);
			if (otherBoxes.remove(swap[0])) otherBoxes.add(swap[1]);
		}
		swaps.clear();

		int box = findBox(inv);
		if (box < 0) box = inv.getStack(hotbar).isOf(boxItem) ? hotbar : -1;
		boolean returned = false, tookItOut = request.sourceIsInventory() || boxOutTick >= 0;
		boolean sameMenu = request.containerSlots() < 0 || containerSlotCount(h, inv) == request.containerSlots();
		if (box >= 0) {
			if (request.sourceIsInventory()) {
				move(client, h, slotOf(h, inv, box), slotOf(h, inv, request.inventoryIndex()));
				returned = true;
			} else if (request.container() != null && h != p.playerScreenHandler && sameMenu) {
				Slot target = h.slots.get(request.sourceSlot());
				if (target.getStack().isEmpty()) {
					move(client, h, slotOf(h, inv, box), target.id);
				} else {
					click(client, h, slotOf(h, inv, box), 0, SlotActionType.QUICK_MOVE);
				}
				returned = findBox(inv) < 0;
				if (!returned) notes.add(Text.translatable("hoardeye.grab.chest_full").getString());
			} else {
				notes.add(Text.translatable("hoardeye.grab.box_kept").getString());
			}
		}
		// No chest to reopen, which would have corrected everything guessed in a burst.
		if (burstRun && request.container() == null) resync(p);
		inv.setSelectedSlot(savedSelected);
		// The clicks above are only the client's guess until the server answers, so nothing is claimed yet.
		claimedBack = returned;
		hadBox = box >= 0;
		settleTicks = 8 + 2 * latencyTicks(client, p);
		boxBackTick = totalTicks;
				breaking = false;
		walk(client, false);
		p.setYaw(savedYaw);
		p.setPitch(savedPitch);
		if (request.fromInventoryScreen() && client.currentScreen == null) client.setScreen(new InventoryScreen(p));
		go(Step.SETTLE);
	}

	private static int latencyTicks(MinecraftClient client, ClientPlayerEntity p) {
		var entry = client.getNetworkHandler() == null ? null : client.getNetworkHandler().getPlayerListEntry(p.getUuid());
		return entry == null ? 0 : Math.min(40, entry.getLatency() / 50 + 1);
	}

	/** The server has had time to answer: now say what really happened. */
	private static void settle(MinecraftClient client, ClientPlayerEntity p) {
		if (ticks < settleTicks) return;
		PlayerInventory inv = p.getInventory();
		boolean returned;
		if (request.sourceIsInventory()) {
			returned = inv.getStack(request.inventoryIndex()).isOf(boxItem);
		} else {
			boolean stillOnMe = findBox(inv) >= 0;
			// The server didn't take it (the chest closed, or it opened again). One more go before giving up.
			if (claimedBack && stillOnMe && putBackTries++ < 2) {
				go(Step.REOPEN);
				return;
			}
			returned = claimedBack && !stillOnMe;
			if (claimedBack && stillOnMe) notes.add(Text.translatable("hoardeye.grab.not_confirmed").getString());
		}
		String result = summary(inv);
		// Something fell short, or the screen numbers were off: next time go step by step.
		if (burstRun && burstWorks && !notes.isEmpty()) burstWorks = false;
		if (burstRun && !burstWorks && !notes.contains(Text.translatable("hoardeye.grab.burst_failed").getString())) {
			notes.add(Text.translatable("hoardeye.grab.burst_failed").getString());
		}
		if (returned) result += " " + Text.translatable("hoardeye.grab.back").getString();
		int outFor = boxOutTick < 0 ? 0 : boxBackTick - boxOutTick;
		stop(client);
		lastBoxOutTicks = outFor;
		lastTicks = boxBackTick;
		lastGroundTicks = boxInHandTick < 0 || boxOutTick < 0 ? 0 : boxInHandTick - boxOutTick;
		lastBurst = burstRun;
		if (burstRun) lastTakeTries = 1;
		// Only "not ok" if the box itself can't be accounted for; a note says what else happened.
		report(client, hadBox || !(request.sourceIsInventory() || boxOutTick >= 0), result);
	}

	private static int containerSlotCount(ScreenHandler h, PlayerInventory inv) {
		int count = 0;
		for (Slot s : h.slots) if (s.inventory != inv) count++;
		return count;
	}

	/** Moves one stack between two slots with the cursor, swapping anything in the way into the first slot. */
	private static void move(MinecraftClient client, ScreenHandler h, int from, int to) {
		if (from == to) return;
		click(client, h, from, 0, SlotActionType.PICKUP);
		click(client, h, to, 0, SlotActionType.PICKUP);
		if (!h.getCursorStack().isEmpty()) click(client, h, from, 0, SlotActionType.PICKUP);
	}

	private static void click(MinecraftClient client, ScreenHandler h, int slot, int button, SlotActionType type) {
		client.interactionManager.clickSlot(h.syncId, slot, button, type, client.player);
	}

	private static int slotOf(ScreenHandler h, PlayerInventory inv, int index) {
		for (Slot s : h.slots) if (s.inventory == inv && s.getIndex() == index) return s.id;
		return -1;
	}

	private static void look(ClientPlayerEntity p, Vec3d at) {
		Vec3d d = at.subtract(p.getEyePos());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90;
		float pitch = (float) -(MathHelper.atan2(d.y, flat) * 180 / Math.PI);
		p.setYaw(yaw);
		p.setPitch(pitch);
		// Now, not at the end of the tick: the click that follows has to come from this direction.
		p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, p.isOnGround(), p.horizontalCollision));
	}

	/** Stops the run and hands the player back as they were. */
	private static void stop(MinecraftClient client) {
		if (breaking && client.interactionManager != null) {
			breaking = false;
			client.interactionManager.cancelBlockBreaking();
		}
		breaking = false;
		walk(client, false);
		ClientPlayerEntity p = client.player;
		// By SETTLE the player has been handed back already and may have moved on.
		if (p != null && step != Step.SETTLE) {
			p.setYaw(savedYaw);
			p.setPitch(savedPitch);
			p.getInventory().setSelectedSlot(savedSelected);
		}
		lastTicks = totalTicks;
		lastBoxOutTicks = boxOutTick < 0 ? 0 : totalTicks - boxOutTick;
		swallowOpen = false;
		step = Step.IDLE;
	}

	private static Map<Item, Integer> inventoryCounts(PlayerInventory inv) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack s = inv.getStack(i);
			if (!s.isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return counts;
	}

	/** What really arrived, by counting, and anything that fell short of the pick. */
	private static String summary(PlayerInventory inv) {
		Map<Item, Integer> wanted = new LinkedHashMap<>();
		int[] want = request.want();
		for (int i = 0; i < want.length; i++) {
			if (want[i] > 0) wanted.merge(request.contents().get(i).getItem(), want[i], Integer::sum);
		}
		Map<Item, Integer> now = inventoryCounts(inv);
		List<String> got = new ArrayList<>();
		List<String> shortOf = new ArrayList<>();
		for (Map.Entry<Item, Integer> e : wanted.entrySet()) {
			int arrived = now.getOrDefault(e.getKey(), 0) - before.getOrDefault(e.getKey(), 0);
			String name = new ItemStack(e.getKey()).getName().getString();
			if (arrived > 0) got.add(arrived + " " + name);
			if (arrived < e.getValue()) shortOf.add((e.getValue() - Math.max(arrived, 0)) + " " + name);
		}
		// When nothing came out at all a note already says why.
		if (!shortOf.isEmpty() && !got.isEmpty()) notes.add(Text.translatable("hoardeye.grab.short", String.join(", ", shortOf)).getString());
		return got.isEmpty() ? Text.translatable("hoardeye.grab.got_nothing").getString()
				: Text.translatable("hoardeye.grab.got", String.join(", ", got)).getString();
	}

	private static void report(MinecraftClient client, boolean ok, String message) {
		boolean clean = ok && notes.isEmpty();
		lastOk = clean;
		lastMessage = notes.isEmpty() ? message : message + " " + String.join(" ", notes);
		HoardEyeClient.LOGGER.info("Box picker: {} ({} ticks, box out {} ticks, on the ground {} ticks, {})", lastMessage, lastTicks, lastBoxOutTicks, lastGroundTicks, burstRun ? "burst" : "step by step");
		if (client.player == null) return;
		if (clean) client.player.sendMessage(Text.literal(lastMessage).formatted(Formatting.GREEN), true);
		else client.player.sendMessage(Text.empty().append(Text.literal("[Hoard Eye] ").formatted(Formatting.GOLD))
				.append(Text.literal(lastMessage).formatted(ok ? Formatting.YELLOW : Formatting.RED)), false);
	}

	// ---- On screen while it runs ----

	public static void renderHud(DrawContext context, RenderTickCounter counter) {
		if (step == Step.IDLE) return;
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer font = client.textRenderer;
		String text = Text.translatable("hoardeye.grab.step." + step.name().toLowerCase(java.util.Locale.ROOT)).getString();
		int width = font.getWidth(text) + 12, x = (context.getScaledWindowWidth() - width) / 2, y = 8;
		context.fill(x, y, x + width, y + 16, 0xE0101018);
		context.drawStrokedRectangle(x, y, width, 16, 0xFF40E0FF);
		context.drawTextWithShadow(font, text, x + 6, y + 4, 0xFFF0F0F0);
	}
}
