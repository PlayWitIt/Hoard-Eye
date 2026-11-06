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
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BundleItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket;
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
	enum Step { IDLE, SNEAK, PLACE, OPEN, TAKE, BUNDLES, STORE, TIDY, ARRANGE, CHECK, BREAK, COLLECT, REOPEN, PUT_BACK, SETTLE, ARRANGE_INV }

	private enum Phase { TAKE, BUNDLES, STORE, TIDY, ARRANGE }

	/** Part of what's inside a bundle sitting in slot {@code slot}: this many of this item. */
	record BundlePick(int slot, ItemStack item, int amount) {}

	/**
	 * One box's part of the plan. {@code want[i]} is how many to take from slot i, {@code deposits} what to put in, and
	 * {@code tidy} whether to sort it afterwards. With {@code loose}, it's the open container itself: nothing goes anywhere.
	 */
	record Request(int sourceSlot, boolean sourceIsInventory, int inventoryIndex, ItemStack box, List<ItemStack> contents,
			int[] want, BlockPos container, int containerSlots, boolean fromInventoryScreen, List<ItemStack> deposits, boolean tidy, boolean loose,
			List<BundlePick> bundlePicks, Map<Integer, ItemStack> pins) {}

	private static final double REACH = 4.4;
	/** Most clicks sent in one tick, so a big pick can't trip a server's packet limit. */
	private static final int CLICK_BUDGET = 40;
	/** The fastest a very good player does one inventory action or block click: about 150 ms. An estimate, not a measurement. */
	private static final double HUMAN_ACTION_TICKS = 3;
	private static final int MAX_TAKE_TRIES = 4;
	private static final int OPEN_RETRY = 100, OPEN_TIMEOUT = 200, COLLECT_TIMEOUT = 200, REOPEN_TIMEOUT = 400;

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
	/** Self-test: pretend somebody just walked up. */
	static boolean testGuardTrip;

	/** The rest of the plan, and what has really been done so far. */
	private static final java.util.ArrayDeque<Request> queue = new java.util.ArrayDeque<>();
	private static final java.util.ArrayDeque<Phase> phases = new java.util.ArrayDeque<>();
	/** Inventory slots to get right at the very end. */
	private static Map<Integer, ItemStack> finalPins = new LinkedHashMap<>();
	private static final Set<Integer> arrangeSkip = new HashSet<>();
	private static int arrangePasses;
	private static final Map<String, Integer> gotTotals = new LinkedHashMap<>(), storedTotals = new LinkedHashMap<>();
	private static int boxesBack, runNumber, runTotal;
	private static boolean stopRequested, storeFirst, looseRun, queueFailed;
	private static ScreenHandler looseHandler;
	private static Phase checking;
	private static int[] shouldRemain, storeBase, storeLeft;
	private static int nextStore, nextBundle;
	/** Just getting a box you've got open off the ground and into your inventory. */
	private static boolean grabbing;
	private static int[] bundleLeft, bundleBase;
	/** The last run's result, for the self-test. */
	static boolean lastOk;
	static String lastMessage = "";
	static int lastTicks, lastBoxOutTicks, lastGroundTicks;
	static boolean lastBurst;
	/** Self-test: pretend this many placements were refused. */
	static int testFailPlaces;
	/** The block last right-clicked, until a screen opens or it's too long ago for one to be its answer. */
	private static BlockHitResult lastUsed;
	private static long lastUsedAt;
	/** The block the screen that's open came from. Null for a screen no block opened: an entity's, or a server's menu. */
	private static BlockHitResult openedFrom;
	/** Where you stood when this began, within reach of the container: where to go back to. */
	private static Vec3d standAt;
	private static int reopenTries, reopenClickTick;
	/** How the container was last clicked to reopen it, for the self-test. */
	static double lastReopenDistance;
	static BlockHitResult lastReopenAim;
	private static final long USE_ANSWER_MS = 10_000;

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
		// The bot's own screens don't count: it's reopening the container this all began from.
		if (!running()) {
			openedFrom = lastUsed != null && System.currentTimeMillis() - lastUsedAt <= USE_ANSWER_MS ? lastUsed : null;
			lastUsed = null;
		}
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

	public static void noteUse(BlockHitResult hit) {
		if (running()) return;
		lastUsed = hit;
		lastUsedAt = System.currentTimeMillis();
	}

	/** The container that's open, if it's a block we could reopen; null for an entity or a server's menu. */
	static BlockPos openContainer(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (openedFrom == null || p == null || client.world == null || p.currentScreenHandler == p.playerScreenHandler) return null;
		// That it answered with this screen is the proof. What the block looks like here isn't: a server may hide chests.
		BlockPos pos = openedFrom.getBlockPos();
		return client.world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock ? null : pos;
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

	// ---- Emergency: get this box off the ground ----

	/** The placed box whose screen is open, if you opened it yourself. */
	static BlockPos placedBoxOpen(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (running() || p == null || client.world == null || openedFrom == null || !(p.currentScreenHandler instanceof ShulkerBoxScreenHandler)) return null;
		BlockPos pos = openedFrom.getBlockPos();
		return client.world.getBlockState(pos).getBlock() instanceof ShulkerBoxBlock ? pos : null;
	}

	/**
	 * Whether a box could come back to you if it were broken now: it needs a slot of its own, and anything on the
	 * cursor comes back to your inventory first when the screen closes.
	 */
	static boolean roomToGrab(MinecraftClient client) {
		ClientPlayerEntity p = client.player;
		if (p == null) return false;
		ItemStack[] slots = copyOf(p.getInventory());
		ItemStack cursor = p.currentScreenHandler.getCursorStack();
		if (!cursor.isEmpty()) insert(slots, cursor.copy());
		for (ItemStack s : slots) if (s.isEmpty()) return true;
		return false;
	}

	/** Shuts the box you're looking through, breaks it and picks it up, the quickest way there is. */
	static void grabOpenBox(MinecraftClient client) {
		BlockPos at = placedBoxOpen(client);
		if (at == null) return;
		ClientPlayerEntity p = client.player;
		if (!roomToGrab(client)) {
			p.sendMessage(Text.translatable("hoardeye.grab.grab_full_message").formatted(Formatting.RED), true);
			return;
		}
		PlayerInventory inv = p.getInventory();
		Item item = client.world.getBlockState(at).getBlock().asItem();
		queue.clear();
		gotTotals.clear();
		storedTotals.clear();
		boxesBack = 0;
		runNumber = 0;
		runTotal = 0;
		stopRequested = false;
		queueFailed = false;
		anyTidy = false;
		notes.clear();
		request = new Request(-1, false, -1, new ItemStack(item), List.of(), new int[0], null, -1, false, List.of(), false, false, List.of(), Map.of());
		savedYaw = p.getYaw();
		savedPitch = p.getPitch();
		savedSelected = inv.getSelectedSlot();
		hotbar = savedSelected;
		boxItem = item;
		boxName = null;
		grabbing = true;
		burstRun = looseRun = false;
		swaps.clear();
		triedSpots = new ArrayList<>();
		before = inventoryCounts(inv);
		left = new int[0];
		totalTicks = 0;
		boxOutTick = -1;
		boxInHandTick = -1;
		attacked = breaking = walking = false;
		// As fast as it goes, whatever the speed is set to: that's what this button is for.
		pace = 0;
		nextAction = 0;
		spot = at;
		otherBoxes = new HashSet<>();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isOf(item)) otherBoxes.add(i);
		KeyBinding.unpressAll();
		p.closeHandledScreen();
		go(Step.BREAK);
		breakBox(client, p, client.world);
	}

	// ---- The whole plan ----

	/** Starts a plan of one or more parts. They run one after another; anything that goes wrong stops what's left. */
	static void startQueue(MinecraftClient client, List<Request> plan) {
		startQueue(client, plan, Map.of());
	}

	/** And, once the rest is done, these inventory slots end up holding exactly these. */
	static void startQueue(MinecraftClient client, List<Request> plan, Map<Integer, ItemStack> inventoryPins) {
		finalPins = new LinkedHashMap<>(inventoryPins);
		pace = HUMAN_ACTION_TICKS * 100.0 / Math.max(10, ModConfig.INSTANCE.boxPickerSpeed);
		queue.clear();
		queue.addAll(plan);
		gotTotals.clear();
		storedTotals.clear();
		boxesBack = 0;
		runNumber = 0;
		runTotal = plan.size();
		stopRequested = false;
		queueFailed = false;
		anyTidy = false;
		grabbing = false;
		notes.clear();
		startNext(client);
	}

	private static void startNext(MinecraftClient client) {
		Request next = queue.poll();
		if (next == null) {
			if (!finalPins.isEmpty() && !queueFailed && !stopRequested) {
				arrangeSkip.clear();
				arrangePasses = 0;
				nextAction = totalTicks;
				if (request == null) request = new Request(-1, false, -1, ItemStack.EMPTY, List.of(), new int[0], null, -1, false, List.of(), false, true, List.of(), Map.of());
				go(Step.ARRANGE_INV);
				return;
			}
			finish(client);
			return;
		}
		runNumber++;
		ClientPlayerEntity p = client.player;
		ScreenHandler h = p.currentScreenHandler;
		if (!next.loose() && (next.sourceSlot() >= h.slots.size() || !ItemStack.areEqual(h.slots.get(next.sourceSlot()).getStack(), next.box()))) {
			notes.add(Text.translatable("hoardeye.grab.skipped", next.box().getName()).getString());
			startNext(client);
			return;
		}
		start(client, next);
	}

	/** The G key while it runs. Whatever's out comes back; nothing new starts. */
	public static void requestStop() {
		if (!running() || stopRequested) return;
		stopRequested = true;
		queue.clear();
		notes.add(Text.translatable("hoardeye.grab.stopped").getString());
	}

	private static boolean guardTripped(ClientPlayerEntity p, ClientWorld world) {
		if (testGuardTrip) return true;
		if (!ModConfig.INSTANCE.boxPickerGuard) return false;
		if (p.hurtTime > 0) return true;
		for (var other : world.getPlayers()) if (other != p && other.squaredDistanceTo(p) <= 64) return true;
		return false;
	}

	// ---- Will it all fit? ----

	/** Why the plan can't work out, as a translation key, or null. Runs it on a copy of the inventory. */
	static String simulate(PlayerInventory inv, List<Request> plan) {
		ItemStack[] slots = copyOf(inv);
		for (Request r : plan) {
			String why = simulateOne(slots, r);
			if (why != null) return why;
		}
		return null;
	}

	private static ItemStack[] copyOf(PlayerInventory inv) {
		ItemStack[] slots = new ItemStack[PlayerInventory.MAIN_SIZE];
		for (int i = 0; i < slots.length; i++) slots[i] = inv.getStack(i).copy();
		return slots;
	}

	/** Takes first if that works, otherwise puts things in first, and leaves the result in {@code slots}. */
	private static String simulateOne(ItemStack[] slots, Request r) {
		ItemStack[] trial = copyAll(slots);
		if (runOrder(trial, r, true)) {
			System.arraycopy(trial, 0, slots, 0, slots.length);
			return null;
		}
		trial = copyAll(slots);
		if (runOrder(trial, r, false)) {
			System.arraycopy(trial, 0, slots, 0, slots.length);
			return null;
		}
		return boxOverflows(r) ? "hoardeye.picker.no_room_box" : "hoardeye.picker.no_room";
	}

	private static boolean boxOverflows(Request r) {
		ItemStack[] box = r.contents().stream().map(ItemStack::copy).toArray(ItemStack[]::new);
		for (int i = 0; i < box.length; i++) box[i].decrement(r.want()[i]);
		for (ItemStack d : r.deposits()) if (insert(box, d.copy()) > 0) return true;
		return false;
	}

	private static ItemStack[] copyAll(ItemStack[] from) {
		ItemStack[] to = new ItemStack[from.length];
		for (int i = 0; i < to.length; i++) to[i] = from[i].copy();
		return to;
	}

	private static boolean runOrder(ItemStack[] slots, Request r, boolean takeFirst) {
		ItemStack[] box = r.contents().stream().map(ItemStack::copy).toArray(ItemStack[]::new);
		int held = -1;
		if (!r.loose()) {
			if (r.sourceIsInventory()) {
				held = r.inventoryIndex();
				slots[held] = ItemStack.EMPTY;
			} else {
				// Coming out of the chest, it needs a place in your inventory before anything else can happen.
				boolean room = false;
				for (ItemStack s : slots) room |= s.isEmpty();
				if (!room) return false;
			}
		}
		if (takeFirst) {
			if (!simTake(slots, box, r) || !simStore(slots, box, r)) return false;
		} else if (!simStore(slots, box, r) || !simTake(slots, box, r)) {
			return false;
		}
		if (r.loose()) return true;
		// And when it's picked up again.
		if (held >= 0) {
			if (!slots[held].isEmpty()) {
				boolean room = false;
				for (ItemStack s : slots) room |= s.isEmpty();
				if (!room) return false;
			}
			if (slots[held].isEmpty()) slots[held] = r.box().copy();
			return true;
		}
		for (ItemStack s : slots) if (s.isEmpty()) return true;
		return false;
	}

	private static boolean simTake(ItemStack[] slots, ItemStack[] box, Request r) {
		for (int i = 0; i < r.want().length; i++) {
			if (r.want()[i] <= 0) continue;
			if (insert(slots, box[i].copyWithCount(r.want()[i])) > 0) return false;
			box[i].decrement(r.want()[i]);
		}
		for (BundlePick pick : r.bundlePicks()) if (insert(slots, pick.item().copyWithCount(pick.amount())) > 0) return false;
		return true;
	}

	private static boolean simStore(ItemStack[] slots, ItemStack[] box, Request r) {
		for (ItemStack d : r.deposits()) {
			if (insert(box, d.copy()) > 0) return false;
			int left = d.getCount();
			for (ItemStack s : slots) {
				if (left <= 0) break;
				if (s.isEmpty() || !ItemStack.areItemsAndComponentsEqual(s, d)) continue;
				int moved = Math.min(left, s.getCount());
				s.decrement(moved);
				left -= moved;
			}
		}
		return true;
	}

	/** Puts a stack into slots as the game would. Returns how many didn't fit. */
	private static int insert(ItemStack[] slots, ItemStack item) {
		int left = item.getCount(), max = item.getMaxCount();
		for (ItemStack s : slots) {
			if (left <= 0) return 0;
			if (s.isEmpty() || !ItemStack.areItemsAndComponentsEqual(s, item) || s.getCount() >= max) continue;
			int moved = Math.min(left, max - s.getCount());
			s.increment(moved);
			left -= moved;
		}
		for (int i = 0; i < slots.length && left > 0; i++) {
			if (!slots[i].isEmpty()) continue;
			int moved = Math.min(left, max);
			slots[i] = item.copyWithCount(moved);
			left -= moved;
		}
		return left;
	}

	/** Your inventory as it would be with the plan done: for showing it before anything happens. */
	static ItemStack[] inventoryAfter(PlayerInventory inv, List<Request> plan) {
		ItemStack[] slots = copyOf(inv);
		for (Request r : plan) simulateOne(slots, r);
		return slots;
	}

	/** A box's slots with these taken out and those put in. */
	static ItemStack[] boxAfter(List<ItemStack> contents, int[] want, List<ItemStack> deposits) {
		ItemStack[] box = contents.stream().map(ItemStack::copy).toArray(ItemStack[]::new);
		for (int i = 0; i < box.length; i++) box[i].decrement(want[i]);
		for (ItemStack d : deposits) insert(box, d.copy());
		return box;
	}

	/** How many empty slots you'd have with the plan done. */
	static int freeAfter(PlayerInventory inv, List<Request> plan) {
		ItemStack[] slots = copyOf(inv);
		for (Request r : plan) simulateOne(slots, r);
		int n = 0;
		for (ItemStack s : slots) if (s.isEmpty()) n++;
		return n;
	}

	/** Roughly how long a plan takes, in ticks: measured against real runs, at no lag and at 250ms. */
	static double estimateTicks(ClientPlayerEntity p, List<Request> plan) {
		double pace = HUMAN_ACTION_TICKS * 100.0 / Math.max(10, ModConfig.INSTANCE.boxPickerSpeed);
		int ping = latencyTicks(MinecraftClient.getInstance(), p);
		double total = 0;
		for (Request r : plan) {
			int actions = 0;
			for (int w : r.want()) if (w > 0) actions++;
			actions += r.deposits().size() + r.bundlePicks().size() + (r.tidy() ? 6 : 0);
			if (r.loose()) {
				total += pace * actions + ping * 2 + 2;
				continue;
			}
			total += pace * (actions + 7) + breakTicks(p.getInventory()) + 16 + ping * 5;
		}
		return total;
	}

	static void start(MinecraftClient client, Request r) {
		ClientPlayerEntity p = client.player;
		request = r;
		PlayerInventory inv = p.getInventory();
		ScreenHandler h = p.currentScreenHandler;
		savedYaw = p.getYaw();
		savedPitch = p.getPitch();
		savedSelected = inv.getSelectedSlot();
		standAt = p.getEntityPos();
		reopenTries = 0;
		boxItem = r.box().getItem();
		boxName = r.box().get(DataComponentTypes.CUSTOM_NAME);
		swaps.clear();
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
		looseRun = r.loose();
		ItemStack[] order = copyOf(inv);
		storeFirst = !runOrder(order, r, true);
		if (looseRun) {
			looseHandler = h;
			hotbar = savedSelected;
			otherBoxes = new HashSet<>();
			closedChest = true;
			boxBackTick = 0;
			go(Step.TAKE);
			beginPhases(client, p);
			return;
		}
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
		boolean canBurst = ModConfig.INSTANCE.boxPickerInstant && burstWorks && testFailPlaces == 0 && !stopRequested
				&& r.deposits().isEmpty() && !r.tidy() && r.bundlePicks().isEmpty() && r.pins().isEmpty() && !guardTripped(p, client.world)
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

	/**
	 * A click on nothing with an out-of-date number makes the server send the whole inventory, correcting any guess.
	 * Slot -1, the gap between slots: -999 is outside the window, which would throw whatever the server thinks is on the cursor.
	 */
	static void resync(ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		p.networkHandler.sendPacket(new ClickSlotC2SPacket(h.syncId, -1, (short) -1, (byte) 0, SlotActionType.PICKUP,
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
		queue.clear();
		finalPins = new LinkedHashMap<>();
		stopRequested = false;
		swallowOpen = false;
		burstWorks = true;
		lastSync = 0;
		lastUsed = openedFrom = null;
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
			case STORE -> store(client, p);
			case TIDY -> tidy(client, p);
			case ARRANGE -> arrangeBox(client, p);
			case ARRANGE_INV -> arrangeInventory(client, p);
			case BUNDLES -> bundles(client, p);
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
		// The last moment to change your mind: nothing has left your hands yet.
		if (stopRequested || (boxOutTick < 0 && guardTripped(p, world))) {
			if (!stopRequested) {
				stopRequested = true;
				queue.clear();
				notes.add(Text.translatable("hoardeye.grab.guard_stopped").getString());
			}
			go(Step.REOPEN);
			return;
		}
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
				beginPhases(client, p);
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
		if (!workingOn(h)) {
			// Closed under us. Whatever came out came out; get the box back.
			notes.add(Text.translatable("hoardeye.grab.closed_early").getString());
			if (looseRun) endRun(client, p, true);
			else go(Step.BREAK);
			return;
		}
		if (!ready()) return;
		if (stopRequested && !burstRun) {
			nextPhase(client, p);
			return;
		}
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
			budget -= amount == here.getCount() ? quickMove(client, h, slot) : movePart(client, p, h, slot, amount, false);
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
		askServer(p, Phase.TAKE);
	}

	/** Don't go on yet: first see what the server says is really in there. */
	private static void askServer(ClientPlayerEntity p, Phase phase) {
		checking = phase;
		syncAsked = inventorySyncs;
		resync(p);
		go(Step.CHECK);
	}

	private static boolean workingOn(ScreenHandler h) {
		return looseRun ? h == looseHandler : h instanceof ShulkerBoxScreenHandler;
	}

	/** Lines up what this part of the plan does while the box is open, in the order that makes it all fit. */
	private static void beginPhases(MinecraftClient client, ClientPlayerEntity p) {
		phases.clear();
		boolean takes = false;
		for (int w : request.want()) takes |= w > 0;
		boolean stores = !request.deposits().isEmpty(), bundles = !request.bundlePicks().isEmpty();
		if (storeFirst) {
			if (stores) phases.add(Phase.STORE);
			if (takes) phases.add(Phase.TAKE);
			if (bundles) phases.add(Phase.BUNDLES);
		} else {
			if (takes) phases.add(Phase.TAKE);
			if (bundles) phases.add(Phase.BUNDLES);
			if (stores) phases.add(Phase.STORE);
		}
		if (request.tidy()) phases.add(Phase.TIDY);
		if (!request.pins().isEmpty()) phases.add(Phase.ARRANGE);
		nextPhase(client, p);
	}

	private static void nextPhase(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		Phase phase = stopRequested ? null : phases.poll();
		if (phase == null || !workingOn(h)) {
			if (looseRun) endRun(client, p, true);
			else closeBox(client, p);
			return;
		}
		int size = request.contents().size();
		switch (phase) {
			case TAKE -> {
				// From what's really there now, which may have grown if things went in first.
				shouldRemain = new int[size];
				for (int i = 0; i < size; i++) shouldRemain[i] = h.slots.get(i).getStack().getCount() - request.want()[i];
				left = request.want().clone();
				nextWant = 0;
				takeTries = 0;
				go(Step.TAKE);
			}
			case STORE -> {
				storeBase = new int[request.deposits().size()];
				storeLeft = new int[storeBase.length];
				for (int d = 0; d < storeBase.length; d++) {
					storeBase[d] = countIn(h, request.deposits().get(d));
					storeLeft[d] = request.deposits().get(d).getCount();
				}
				nextStore = 0;
				takeTries = 0;
				go(Step.STORE);
			}
			case TIDY -> go(Step.TIDY);
			case ARRANGE -> {
				arrangeSkip.clear();
				arrangePasses = 0;
				go(Step.ARRANGE);
			}
			case BUNDLES -> {
				List<BundlePick> picks = request.bundlePicks();
				bundleLeft = new int[picks.size()];
				bundleBase = new int[picks.size()];
				for (int n = 0; n < picks.size(); n++) {
					bundleLeft[n] = picks.get(n).amount();
					bundleBase[n] = countInBundle(h.slots.get(picks.get(n).slot()).getStack(), picks.get(n).item());
				}
				nextBundle = 0;
				takeTries = 0;
				go(Step.BUNDLES);
			}
		}
	}

	private static int countInBundle(ItemStack bundle, ItemStack kind) {
		BundleContentsComponent contents = bundle.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (contents == null) return 0;
		int n = 0;
		for (ItemStack s : contents.iterate()) if (ItemStack.areItemsAndComponentsEqual(s, kind)) n += s.getCount();
		return n;
	}

	/**
	 * One stack out of a bundle, where it sits, as the game lets you by hand: choose it, right-click it onto the cursor,
	 * drop what you wanted and put the rest back. Stacks in a bundle shift about, so it's looked for afresh every time.
	 */
	private static void bundles(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		if (!workingOn(h) || stopRequested) {
			nextPhase(client, p);
			return;
		}
		if (!ready()) return;
		List<BundlePick> picks = request.bundlePicks();
		while (nextBundle < picks.size() && bundleLeft[nextBundle] <= 0) nextBundle++;
		if (nextBundle >= picks.size()) {
			askServer(p, Phase.BUNDLES);
			return;
		}
		BundlePick pick = picks.get(nextBundle);
		Slot slot = h.slots.get(pick.slot());
		BundleContentsComponent contents = slot.getStack().get(DataComponentTypes.BUNDLE_CONTENTS);
		int index = -1;
		if (contents != null) {
			for (int i = 0; i < contents.size(); i++) {
				if (ItemStack.areItemsAndComponentsEqual(contents.get(i), pick.item())) {
					index = i;
					break;
				}
			}
		}
		if (index < 0 || !h.getCursorStack().isEmpty()) {
			bundleLeft[nextBundle] = 0;
			return;
		}
		p.networkHandler.sendPacket(new BundleItemSelectedC2SPacket(slot.id, index));
		BundleItem.setSelectedStackIndex(slot.getStack(), index);
		click(client, h, slot.id, 1, SlotActionType.PICKUP);
		ItemStack out = h.getCursorStack();
		if (out.isEmpty()) {
			bundleLeft[nextBundle] = 0;
			return;
		}
		int amount = Math.min(bundleLeft[nextBundle], out.getCount());
		ItemStack kind = out.copy();
		PlayerInventory inv = p.getInventory();
		if (amount == out.getCount()) {
			for (int guard = 0; !h.getCursorStack().isEmpty() && guard < 36; guard++) {
				Slot room = roomFor(h, inv, kind, 1, false);
				if (room == null) break;
				click(client, h, room.id, 0, SlotActionType.PICKUP);
			}
		} else {
			for (int n = 0; n < amount; n++) {
				Slot room = roomFor(h, inv, kind, 1, false);
				if (room == null) break;
				click(client, h, room.id, 1, SlotActionType.PICKUP);
			}
		}
		// Whatever wasn't wanted goes straight back in the bundle.
		if (!h.getCursorStack().isEmpty()) click(client, h, slot.id, 0, SlotActionType.PICKUP);
		bundleLeft[nextBundle] -= amount;
		acted();
	}

	/** How many of an item the box or container holds. */
	private static int countIn(ScreenHandler h, ItemStack kind) {
		int n = 0;
		for (int i = 0; i < request.contents().size(); i++) {
			ItemStack s = h.slots.get(i).getStack();
			if (ItemStack.areItemsAndComponentsEqual(s, kind)) n += s.getCount();
		}
		return n;
	}

	/** Puts things in, one stack per action. Nothing else in your inventory is touched. */
	private static void store(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		if (!workingOn(h)) {
			nextPhase(client, p);
			return;
		}
		if (!ready()) return;
		if (stopRequested) {
			nextPhase(client, p);
			return;
		}
		while (nextStore < storeLeft.length && storeLeft[nextStore] <= 0) nextStore++;
		if (nextStore >= storeLeft.length) {
			askServer(p, Phase.STORE);
			return;
		}
		ItemStack kind = request.deposits().get(nextStore);
		PlayerInventory inv = p.getInventory();
		Slot source = null;
		for (Slot s : h.slots) {
			if (s.inventory == inv && s.getIndex() < PlayerInventory.MAIN_SIZE && ItemStack.areItemsAndComponentsEqual(s.getStack(), kind)) {
				source = s;
				break;
			}
		}
		if (source == null) {
			storeLeft[nextStore] = 0;
			return;
		}
		int amount = Math.min(storeLeft[nextStore], source.getStack().getCount());
		int before = source.getStack().getCount();
		if (amount == before) quickMove(client, h, source);
		else movePart(client, p, h, source, amount, true);
		storeLeft[nextStore] -= before - source.getStack().getCount();
		// The box is full: no use trying this one any more.
		if (source.getStack().getCount() == before) storeLeft[nextStore] = 0;
		acted();
	}

	/** Merges stacks, then puts everything in order, a couple of moves per action. */
	private static void tidy(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		if (!workingOn(h) || stopRequested) {
			nextPhase(client, p);
			return;
		}
		if (!ready()) return;
		int moves = burstRun ? 27 : 2;
		while (moves-- > 0) {
			if (!tidyOneMove(client, h)) {
				askServer(p, Phase.TIDY);
				return;
			}
		}
		acted();
	}

	private static boolean tidyOneMove(MinecraftClient client, ScreenHandler h) {
		int size = request.contents().size();
		// Boxes in a container stay out of it: that's where you put them.
		for (int i = 0; i < size; i++) {
			ItemStack a = h.slots.get(i).getStack();
			if (a.isEmpty() || isBox(a) || a.getCount() >= a.getMaxCount()) continue;
			for (int j = i + 1; j < size; j++) {
				ItemStack b = h.slots.get(j).getStack();
				if (b.isEmpty() || !ItemStack.areItemsAndComponentsEqual(a, b)) continue;
				move(client, h, j, i);
				return true;
			}
		}
		List<Integer> places = new ArrayList<>();
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			ItemStack s = h.slots.get(i).getStack();
			if (isBox(s)) continue;
			places.add(i);
			if (!s.isEmpty()) items.add(s);
		}
		items.sort(Comparator.<ItemStack, String>comparing(s -> net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString())
				.thenComparing(s -> -s.getCount()).thenComparing(s -> String.valueOf(s.getComponentChanges().hashCode())));
		for (int n = 0; n < places.size(); n++) {
			int place = places.get(n);
			ItemStack here = h.slots.get(place).getStack();
			if (n >= items.size()) break;
			ItemStack wanted = items.get(n);
			if (ItemStack.areEqual(here, wanted)) continue;
			for (int m = n + 1; m < places.size(); m++) {
				int from = places.get(m);
				if (ItemStack.areEqual(h.slots.get(from).getStack(), wanted)) {
					move(client, h, from, place);
					return true;
				}
			}
		}
		return false;
	}

	/** Puts things in the slots you put them in, one move per action. */
	private static void arrangeBox(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		if (!workingOn(h) || stopRequested) {
			nextPhase(client, p);
			return;
		}
		if (!ready()) return;
		List<Integer> scope = new ArrayList<>();
		for (int i = 0; i < request.contents().size(); i++) scope.add(i);
		if (arrangeOne(client, h, scope, request.pins())) {
			acted();
			return;
		}
		if (!arrangeSkip.isEmpty()) notes.add(Text.translatable("hoardeye.grab.not_arranged").getString());
		askServer(p, Phase.ARRANGE);
	}

	/** The same for your inventory, last of all, wherever you've ended up. */
	private static void arrangeInventory(MinecraftClient client, ClientPlayerEntity p) {
		if (!ready()) return;
		ScreenHandler h = p.currentScreenHandler;
		PlayerInventory inv = p.getInventory();
		List<Integer> scope = new ArrayList<>();
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) scope.add(slotOf(h, inv, i));
		scope.removeIf(id -> id < 0);
		Map<Integer, ItemStack> pins = new LinkedHashMap<>();
		finalPins.forEach((index, stack) -> {
			int id = slotOf(h, inv, index);
			if (id >= 0) pins.put(id, stack);
		});
		if (arrangeOne(client, h, scope, pins)) {
			acted();
			return;
		}
		if (!arrangeSkip.isEmpty()) notes.add(Text.translatable("hoardeye.grab.not_arranged").getString());
		finalPins = new LinkedHashMap<>();
		stop(client);
		finish(client);
	}

	/**
	 * One move towards every pinned slot holding exactly what it should, using only the slots in scope. False when
	 * there's nothing left that can be done. Whatever is in the way goes to where the wanted thing came from, so it
	 * never needs a free slot, and an item that isn't there just gets left out.
	 */
	private static boolean arrangeOne(MinecraftClient client, ScreenHandler h, List<Integer> scope, Map<Integer, ItemStack> pins) {
		if (++arrangePasses > 400) return false;
		for (Map.Entry<Integer, ItemStack> pin : pins.entrySet()) {
			int id = pin.getKey();
			ItemStack want = pin.getValue(), here = h.getSlot(id).getStack();
			if (arrangeSkip.contains(id) || ItemStack.areEqual(here, want)) continue;
			if (want.isEmpty()) {
				int to = arrangeRoom(h, scope, pins, here, id);
				if (to < 0) {
					arrangeSkip.add(id);
					continue;
				}
				move(client, h, id, to);
				return true;
			}
			boolean sameKind = ItemStack.areItemsAndComponentsEqual(here, want);
			if (sameKind && here.getCount() > want.getCount()) {
				int to = arrangeRoom(h, scope, pins, here, id);
				if (to < 0) {
					arrangeSkip.add(id);
					continue;
				}
				click(client, h, id, 0, SlotActionType.PICKUP);
				for (int n = 0; n < want.getCount(); n++) click(client, h, id, 1, SlotActionType.PICKUP);
				click(client, h, to, 0, SlotActionType.PICKUP);
				if (!h.getCursorStack().isEmpty()) click(client, h, id, 0, SlotActionType.PICKUP);
				return true;
			}
			int from = -1;
			for (int s : scope) {
				if (s == id) continue;
				ItemStack there = h.getSlot(s).getStack();
				if (!ItemStack.areItemsAndComponentsEqual(there, want)) continue;
				ItemStack itsPin = pins.get(s);
				if (itsPin != null && ItemStack.areItemsAndComponentsEqual(itsPin, there) && there.getCount() <= itsPin.getCount()) continue;
				from = s;
				break;
			}
			if (from < 0) {
				arrangeSkip.add(id);
				continue;
			}
			if (!here.isEmpty() && !sameKind) {
				click(client, h, from, 0, SlotActionType.PICKUP);
				click(client, h, id, 0, SlotActionType.PICKUP);
				click(client, h, from, 0, SlotActionType.PICKUP);
				return true;
			}
			int need = want.getCount() - (sameKind ? here.getCount() : 0);
			click(client, h, from, 0, SlotActionType.PICKUP);
			for (int n = 0; n < need && !h.getCursorStack().isEmpty(); n++) click(client, h, id, 1, SlotActionType.PICKUP);
			if (!h.getCursorStack().isEmpty()) click(client, h, from, 0, SlotActionType.PICKUP);
			return true;
		}
		return false;
	}

	/** Somewhere for what's left over: an empty slot that isn't meant to hold something else. */
	private static int arrangeRoom(ScreenHandler h, List<Integer> scope, Map<Integer, ItemStack> pins, ItemStack item, int not) {
		for (int s : scope) {
			if (s == not || !h.getSlot(s).getStack().isEmpty()) continue;
			ItemStack itsPin = pins.get(s);
			if (itsPin == null || itsPin.isEmpty() || ItemStack.areItemsAndComponentsEqual(itsPin, item)) return s;
		}
		return -1;
	}

	/** Only when the server has confirmed the work is done does it go on, and only then does the box get broken. */
	private static void check(MinecraftClient client, ClientPlayerEntity p) {
		ScreenHandler h = p.currentScreenHandler;
		if (!workingOn(h)) {
			notes.add(Text.translatable("hoardeye.grab.closed_early").getString());
			if (looseRun) endRun(client, p, true);
			else go(Step.BREAK);
			return;
		}
		boolean answered = inventorySyncs != syncAsked;
		if (!answered && ticks <= 20 + 2 * latencyTicks(client, p)) return;
		if (!ready()) return;
		boolean more = false;
		if (checking == Phase.BUNDLES) {
			List<BundlePick> picks = request.bundlePicks();
			for (int n = 0; n < picks.size(); n++) {
				int stillIn = countInBundle(h.slots.get(picks.get(n).slot()).getStack(), picks.get(n).item());
				bundleLeft[n] = Math.max(0, picks.get(n).amount() - (bundleBase[n] - stillIn));
				more |= bundleLeft[n] > 0 && stillIn > 0;
			}
			if (more && answered && ++takeTries < MAX_TAKE_TRIES) {
				nextBundle = 0;
				acted();
				go(Step.BUNDLES);
				return;
			}
		} else if (checking == Phase.TAKE) {
			for (int i = 0; i < left.length; i++) {
				int want = request.want()[i];
				if (want <= 0) {
					left[i] = 0;
					continue;
				}
				ItemStack here = h.slots.get(i).getStack(), was = request.contents().get(i);
				left[i] = ItemStack.areItemsAndComponentsEqual(here, was) ? Math.max(0, here.getCount() - Math.max(0, shouldRemain[i])) : 0;
				more |= left[i] > 0;
			}
			if (more && answered && ++takeTries < MAX_TAKE_TRIES) {
				nextWant = 0;
				acted();
				go(Step.TAKE);
				return;
			}
		} else if (checking == Phase.STORE) {
			boolean inventoryHasMore = false;
			for (int d = 0; d < storeLeft.length; d++) {
				ItemStack kind = request.deposits().get(d);
				int in = countIn(h, kind) - storeBase[d];
				storeLeft[d] = Math.max(0, kind.getCount() - in);
				inventoryHasMore |= storeLeft[d] > 0;
			}
			if (inventoryHasMore && answered && ++takeTries < MAX_TAKE_TRIES) {
				nextStore = 0;
				acted();
				go(Step.STORE);
				return;
			}
			for (int d = 0; d < storeLeft.length; d++) {
				ItemStack kind = request.deposits().get(d);
				int in = kind.getCount() - storeLeft[d];
				if (in > 0) storedTotals.merge(kind.getName().getString(), in, Integer::sum);
				if (storeLeft[d] > 0) notes.add(Text.translatable("hoardeye.grab.not_stored", storeLeft[d], kind.getName()).getString());
			}
		}
		nextPhase(client, p);
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

	/** Moves part of a stack, in whichever way needs fewer clicks. Returns the clicks used. */
	private static int movePart(MinecraftClient client, ClientPlayerEntity p, ScreenHandler h, Slot source, int amount, boolean intoBox) {
		PlayerInventory inv = p.getInventory();
		ItemStack item = source.getStack().copy();
		int count = item.getCount(), used = 1;
		click(client, h, source.id, 0, SlotActionType.PICKUP);
		Slot single = roomFor(h, inv, item, amount, intoBox);
		if (amount <= count - amount && single != null) {
			for (int n = 0; n < amount; n++, used++) click(client, h, single.id, 1, SlotActionType.PICKUP);
		} else {
			for (int n = 0; n < count - amount; n++, used++) click(client, h, source.id, 1, SlotActionType.PICKUP);
			for (int guard = 0; !h.getCursorStack().isEmpty() && guard < 36; guard++) {
				Slot next = roomFor(h, inv, item, 1, intoBox);
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

	private static Slot roomFor(ScreenHandler h, PlayerInventory inv, ItemStack item, int amount, boolean intoBox) {
		Slot empty = null;
		for (Slot s : h.slots) {
			if (intoBox ? s.inventory == inv : (s.inventory != inv || s.getIndex() >= PlayerInventory.MAIN_SIZE)) continue;
			ItemStack here = s.getStack();
			if (here.isEmpty()) {
				if (empty == null || !intoBox && s.getIndex() >= 9 && empty.getIndex() < 9) empty = s;
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
		// Your inventory filled up (picked something up, say): a broken box would have nowhere to go. Leave it standing.
		if (!hasEmptySlot(p.getInventory())) {
			stop(client);
			report(client, false, Text.translatable("hoardeye.grab.full_no_break", spot.toShortString()).getString());
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

	private static boolean hasEmptySlot(PlayerInventory inv) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) return true;
		return false;
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
			if (grabbing) return i;
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
			walk(client, false);
			if (h.getRevision() != 0 && ready()) {
				go(Step.PUT_BACK);
				putBack(client, p);
			}
			return;
		}
		double distance = p.getEyePos().distanceTo(Vec3d.ofCenter(container));
		Vec3d back = standAt.subtract(p.getEntityPos());
		boolean canStepBack = Math.hypot(back.x, back.z) > 0.4;
		if (ticks > REOPEN_TIMEOUT || (distance > REACH + 1 && !canStepBack)) {
			walk(client, false);
			notes.add(Text.translatable("hoardeye.grab.not_reopened").getString());
			request = new Request(request.sourceSlot(), request.sourceIsInventory(), request.inventoryIndex(), request.box(), request.contents(),
					request.want(), null, request.containerSlots(), false, request.deposits(), request.tidy(), request.loose(), request.bundlePicks(), request.pins());
			go(Step.PUT_BACK);
			putBack(client, p);
			return;
		}
		// Getting the box may have taken you out of reach of a chest you were already at the edge of.
		if (distance > REACH && canStepBack) {
			p.setYaw((float) (MathHelper.atan2(back.z, back.x) * 180 / Math.PI) - 90);
			walk(client, true);
			client.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());
			return;
		}
		walk(client, false);
		if (p.isSneaking()) return;
		// Once, then a retry when an answer is overdue: a second click while the first is on its way opens the chest again,
		// and the server would throw away everything sent to the first one. Each retry clicks somewhere else on it.
		if ((!clicked && ready()) || (clicked && ticks - reopenClickTick >= 30 + 2 * latencyTicks(client, p))) {
			clicked = true;
			reopenClickTick = ticks;
			acted();
			BlockHitResult aim = aimAt(p, world, container, reopenTries++);
			lastReopenAim = aim;
			lastReopenDistance = p.getEyePos().distanceTo(aim.getPos());
			look(p, aim.getPos());
			client.interactionManager.interactBlock(p, Hand.MAIN_HAND, aim);
			p.swingHand(Hand.MAIN_HAND);
			if (burstRun && !walking) {
				p.setYaw(savedYaw);
				p.setPitch(savedPitch);
			}
		}
	}

	/**
	 * Somewhere on the block a person could really click from here: first where you clicked it yourself, then whichever
	 * parts of it are in view. An ender chest is lower and narrower than a full block, and might be above your head.
	 */
	private static BlockHitResult aimAt(ClientPlayerEntity p, ClientWorld world, BlockPos pos, int attempt) {
		Vec3d eye = p.getEyePos();
		BlockHitResult own = openedFrom != null && openedFrom.getBlockPos().equals(pos) ? openedFrom : null;
		List<BlockHitResult> found = new ArrayList<>();
		if (own != null && eye.distanceTo(own.getPos()) <= REACH) found.add(own);
		var shape = world.getBlockState(pos).getOutlineShape(world, pos);
		if (!shape.isEmpty()) {
			Box box = shape.getBoundingBox().offset(pos);
			Vec3d c = box.getCenter();
			double dx = box.getLengthX() * 0.4, dy = box.getLengthY() * 0.4, dz = box.getLengthZ() * 0.4;
			Vec3d[] points = {c, c.add(0, dy, 0), c.add(0, -dy, 0), c.add(dx, 0, 0), c.add(-dx, 0, 0), c.add(0, 0, dz), c.add(0, 0, -dz)};
			for (Vec3d point : points) {
				// A little past it, so the ray reaches the block's surface.
				Vec3d end = eye.add(point.subtract(eye).multiply(1.2));
				BlockHitResult ray = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
				if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(pos) && eye.distanceTo(ray.getPos()) <= REACH) found.add(ray);
			}
		}
		if (!found.isEmpty()) return found.get(attempt % found.size());
		// Nothing in view that we know of. It did open for you, so try anyway.
		if (own != null) return own;
		double top = shape.isEmpty() ? 0.95 : shape.getMax(Direction.Axis.Y);
		return new BlockHitResult(new Vec3d(pos.getX() + 0.5, pos.getY() + top, pos.getZ() + 0.5), Direction.UP, pos, false);
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
			} else if (!grabbing) {
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
		tally(inv);
		// Something fell short, or the screen numbers were off: next time go step by step.
		if (burstRun && burstWorks && !notes.isEmpty()) burstWorks = false;
		if (burstRun && !burstWorks && !notes.contains(Text.translatable("hoardeye.grab.burst_failed").getString())) {
			notes.add(Text.translatable("hoardeye.grab.burst_failed").getString());
		}
		if (returned) boxesBack++;
		int outFor = boxOutTick < 0 ? 0 : boxBackTick - boxOutTick;
		stop(client);
		lastBoxOutTicks = outFor;
		lastTicks = boxBackTick;
		lastGroundTicks = boxInHandTick < 0 || boxOutTick < 0 ? 0 : boxInHandTick - boxOutTick;
		lastBurst = burstRun;
		anyTidy |= request.tidy();
		if (burstRun) lastTakeTries = 1;
		// Only "not ok" if the box itself can't be accounted for; a note says what else happened.
		boolean ok = hadBox || !(request.sourceIsInventory() || boxOutTick >= 0);
		queueFailed |= !ok || (!returned && boxOutTick >= 0);
		if (queueFailed) queue.clear();
		startNext(client);
	}

	/** The container itself is done: nothing left the chest, so nothing needs putting back. */
	private static void endRun(MinecraftClient client, ClientPlayerEntity p, boolean ok) {
		tally(p.getInventory());
		stop(client);
		lastGroundTicks = 0;
		queueFailed |= !ok;
		if (queueFailed) queue.clear();
		startNext(client);
	}

	/** Everything's finished: one message for all of it. */
	private static void finish(MinecraftClient client) {
		List<String> parts = new ArrayList<>();
		parts.add(gotTotals.isEmpty() ? (storedTotals.isEmpty() && !anyTidy ? Text.translatable("hoardeye.grab.got_nothing").getString() : "")
				: Text.translatable("hoardeye.grab.got", join(gotTotals)).getString());
		if (!storedTotals.isEmpty()) parts.add(Text.translatable("hoardeye.grab.stored", join(storedTotals)).getString());
		if (boxesBack == 1) parts.add(Text.translatable("hoardeye.grab.back").getString());
		else if (boxesBack > 1) parts.add(Text.translatable("hoardeye.grab.back_many", boxesBack).getString());
		if (grabbing) {
			parts.clear();
			parts.add(Text.translatable("hoardeye.grab.grabbed").getString());
			grabbing = false;
		}
		parts.removeIf(String::isEmpty);
		report(client, !queueFailed, String.join(" ", parts));
	}

	private static String join(Map<String, Integer> totals) {
		List<String> list = new ArrayList<>();
		totals.forEach((name, n) -> list.add(n + " " + name));
		return String.join(", ", list);
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

	private static boolean anyTidy;

	/** What really arrived, by counting, and anything that fell short of the pick. */
	private static void tally(PlayerInventory inv) {
		Map<Item, Integer> wanted = new LinkedHashMap<>();
		int[] want = request.want();
		for (int i = 0; i < want.length; i++) {
			if (want[i] > 0) wanted.merge(request.contents().get(i).getItem(), want[i], Integer::sum);
		}
		for (BundlePick pick : request.bundlePicks()) wanted.merge(pick.item().getItem(), pick.amount(), Integer::sum);
		Map<Item, Integer> now = inventoryCounts(inv);
		List<String> got = new ArrayList<>();
		List<String> shortOf = new ArrayList<>();
		for (Map.Entry<Item, Integer> e : wanted.entrySet()) {
			int arrived = now.getOrDefault(e.getKey(), 0) - before.getOrDefault(e.getKey(), 0);
			// Some of the same item may have gone the other way, into the box.
			for (ItemStack d : request.deposits()) if (d.isOf(e.getKey())) arrived += d.getCount();
			String name = new ItemStack(e.getKey()).getName().getString();
			if (arrived > 0) {
				got.add(arrived + " " + name);
				gotTotals.merge(name, Math.min(arrived, e.getValue()), Integer::sum);
			}
			if (arrived < e.getValue()) shortOf.add((e.getValue() - Math.max(arrived, 0)) + " " + name);
		}
		// When nothing came out at all a note already says why.
		if (!shortOf.isEmpty() && !got.isEmpty()) notes.add(Text.translatable("hoardeye.grab.short", String.join(", ", shortOf)).getString());
	}

	private static void report(MinecraftClient client, boolean ok, String message) {
		boolean clean = ok && notes.isEmpty();
		lastOk = clean;
		queue.clear();
		runTotal = 0;
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
		if (runTotal > 1) text = runNumber + "/" + runTotal + "  " + text;
		String stop = stopRequested ? Text.translatable("hoardeye.grab.stopping").getString() : Text.translatable("hoardeye.grab.stop_hint").getString();
		int width = Math.max(font.getWidth(text), font.getWidth(stop)) + 12, x = (context.getScaledWindowWidth() - width) / 2, y = 8;
		context.fill(x, y, x + width, y + 27, 0xE0101018);
		context.drawStrokedRectangle(x, y, width, 27, 0xFF40E0FF);
		context.drawTextWithShadow(font, text, x + 6, y + 4, 0xFFF0F0F0);
		context.drawTextWithShadow(font, stop, x + 6, y + 15, 0xFF9AA0AC);
	}
}
