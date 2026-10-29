package com.greysworkshop.hoardeye;

import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Storage search: every item in every container you've opened, searchable and sortable, with a locator to
 * walk you to it.
 */
public class SearchScreen extends Screen {
	private enum View { ITEMS, CONTAINERS, FETCH }

	private enum ItemSort { COUNT, NAME, NEAREST, SPREAD }

	private enum BoxSort { NEAREST, NAME, FULLEST, RECENT }

	private enum Scope { ALL, HERE }

	private enum TypeFilter { ALL, SHULKERS, CHESTS, BARRELS, OTHER }

	private static final String ENDER = "#ender";
	private static final int PAD = 8;
	private static final int ROW = 20;
	private static final int LOC_ROW = 22;
	private static final int CELL = 18;

	private static final int PANEL = 0xD0101018;
	private static final int PANEL_HEADER = 0xE0181826;
	private static final int BORDER = 0xFF33334D;
	private static final int ACCENT = 0xFF40E0FF;
	private static final int GOLD = 0xFFFFC040;
	private static final int WHITE = 0xFFF0F0F0;
	private static final int GRAY = 0xFF9090A8;
	private static final int DIM = 0xFF606078;
	private static final int HOVER = 0x28FFFFFF;
	private static final int SELECTED = 0x5030A0FF;

	// Remembered between openings.
	private static String lastQuery = "";
	private static View view = View.ITEMS;
	private static ItemSort itemSort = ItemSort.COUNT;
	private static BoxSort boxSort = BoxSort.NEAREST;
	private static Scope scope = Scope.ALL;
	private static TypeFilter typeFilter = TypeFilter.ALL;
	private static boolean nested = true;

	/** One known container. */
	private record Box(String dimension, BlockPos pos, String label, ItemStack icon, TypeFilter type, long updated,
			List<ItemStack> stacks, int totalItems, String labelText, String itemText, double distance) {
		boolean findable() {
			return !dimension.equals(ENDER);
		}
	}

	/** Some amount of an item in one container (possibly inside a shulker box in it). */
	private record Loc(Box box, int count, String inside) {}

	/** One kind of item across all containers. */
	private static final class Group {
		final String name;
		final ItemStack icon;
		final String text;
		int total;
		final Map<String, Loc> locs = new LinkedHashMap<>();
		List<Loc> sorted;
		double nearest = Double.MAX_VALUE;

		Group(String name, ItemStack icon, String text) {
			this.name = name;
			this.icon = icon;
			this.text = text;
		}

		int spread() {
			Set<String> boxes = new HashSet<>();
			for (Loc loc : locs.values()) boxes.add(loc.box.dimension + loc.box.pos.asLong());
			return boxes.size();
		}
	}

	private final List<Box> allBoxes = new ArrayList<>();
	private List<Group> groups = new ArrayList<>();
	private List<Box> boxes = new ArrayList<>();
	private Group selectedGroup;
	private Box selectedBox;
	private double leftScroll;
	private double rightScroll;
	private int totalItems;
	private int totalKinds;

	private EditBox search;
	private Button findButton;
	private Button allButton;
	private Button stopButton;
	private Button fetchButton;
	private ItemStack hoveredStack = ItemStack.EMPTY;
	private int hoverX;
	private int hoverY;

	// Layout.
	private int bodyTop;
	private int bodyBottom;
	private int leftX0, leftX1, rightX0, rightX1;

	public SearchScreen() {
		super(Component.translatable("hoardeye.search.title"));
	}

	// ---- Data ----

	private void loadBoxes() {
		allBoxes.clear();
		Minecraft mc = Minecraft.getInstance();
		String worldKey = HoardEyeClient.worldKey(mc);
		if (worldKey == null || mc.level == null) return;
		ClientLevel world = mc.level;
		String here = HoardEyeClient.dimensionKey(world);
		Set<String> kinds = new HashSet<>();
		totalItems = 0;

		for (var dim : HoardEyeClient.INSTANCE.store().world(worldKey).entrySet()) {
			for (var e : dim.getValue().entrySet()) {
				NameStore.Entry entry = e.getValue();
				List<ItemStack> stacks = entry.stacks(world.registryAccess());
				if (stacks == null) continue;
				BlockPos pos = BlockPos.of(e.getKey());
				String dimension = dim.getKey();

				String blockId = entry.block;
				ContainerKind kind = entry.kind != null ? parseKind(entry.kind) : null;
				if (dimension.equals(ENDER)) {
					blockId = "minecraft:ender_chest";
					kind = ContainerKind.ENDER_CHEST;
				} else if (blockId == null && dimension.equals(here) && world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
					// Older entries didn't record their block; look it up if we can.
					blockId = BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString();
					kind = ContainerKind.of(world.getBlockEntity(pos));
				}
				Block block = blockId != null ? BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(blockId)) : null;
				ItemStack icon = block != null && block.asItem() != Items.AIR ? new ItemStack(block.asItem()) : new ItemStack(Items.CHEST);
				String label = !entry.name.isEmpty() ? entry.name
						: block != null && block.asItem() != Items.AIR ? block.getName().getString()
						: Component.translatable("hoardeye.search.container").getString();

				int items = 0;
				StringBuilder itemText = new StringBuilder();
				for (ItemStack stack : stacks) {
					items += stack.getCount();
					appendText(itemText, stack);
					kinds.add(key(stack));
					ItemContainerContents inner = stack.get(DataComponents.CONTAINER);
					if (inner != null) {
						inner.nonEmptyItemCopyStream().forEach(s -> {
							appendText(itemText, s);
							kinds.add(key(s));
						});
					}
				}
				totalItems += items;
				double distance = dimension.equals(here) && mc.player != null
						? Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(pos))) : Double.MAX_VALUE;
				allBoxes.add(new Box(dimension, pos, label, icon, typeOf(kind, blockId), entry.updated, stacks, items,
						label.toLowerCase(Locale.ROOT), itemText.toString(), distance));
			}
		}
		totalKinds = kinds.size();
	}

	private static ContainerKind parseKind(String name) {
		try {
			return ContainerKind.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static TypeFilter typeOf(ContainerKind kind, String blockId) {
		if (kind != null) {
			return switch (kind) {
				case SHULKER_BOX -> TypeFilter.SHULKERS;
				case CHEST, TRAPPED_CHEST, COPPER_CHEST, ENDER_CHEST -> TypeFilter.CHESTS;
				case BARREL -> TypeFilter.BARRELS;
				default -> TypeFilter.OTHER;
			};
		}
		if (blockId == null) return TypeFilter.OTHER;
		if (blockId.contains("shulker_box")) return TypeFilter.SHULKERS;
		if (blockId.contains("chest")) return TypeFilter.CHESTS;
		if (blockId.contains("barrel")) return TypeFilter.BARRELS;
		return TypeFilter.OTHER;
	}

	private static String key(ItemStack stack) {
		return stack.getHoverName().getString() + "|" + BuiltInRegistries.ITEM.getKey(stack.getItem());
	}

	private static String itemText(ItemStack stack) {
		return (stack.getHoverName().getString() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().replace('_', ' '))
				.toLowerCase(Locale.ROOT);
	}

	private static void appendText(StringBuilder sb, ItemStack stack) {
		sb.append(itemText(stack)).append('\n');
	}

	private static boolean matches(String text, String[] tokens) {
		for (String token : tokens) {
			if (!text.contains(token)) return false;
		}
		return true;
	}

	private boolean passesFilters(Box box) {
		if (scope == Scope.HERE && box.distance == Double.MAX_VALUE && !box.dimension.equals(ENDER)) return false;
		return typeFilter == TypeFilter.ALL || box.type == typeFilter;
	}

	/** Re-applies the search text, filters and sort. */
	private void refresh() {
		String query = search == null ? lastQuery : search.getValue();
		lastQuery = query;
		String[] tokens = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		if (tokens.length == 1 && tokens[0].isEmpty()) tokens = new String[0];

		Map<String, Group> byKey = new LinkedHashMap<>();
		List<Box> matchingBoxes = new ArrayList<>();
		for (Box box : allBoxes) {
			if (!passesFilters(box)) continue;
			boolean boxMatches = matches(box.labelText, tokens);
			boolean anyItem = false;
			for (ItemStack stack : box.stacks) {
				anyItem |= addLoc(byKey, box, stack, null, tokens, boxMatches);
				ItemContainerContents inner = stack.get(DataComponents.CONTAINER);
				if (nested && inner != null) {
					String inside = stack.getHoverName().getString();
					for (ItemStack s : inner.nonEmptyItemCopyStream().toList()) anyItem |= addLoc(byKey, box, s, inside, tokens, boxMatches);
				}
			}
			if (tokens.length == 0 || boxMatches || anyItem) matchingBoxes.add(box);
		}

		groups = new ArrayList<>(byKey.values());
		for (Group group : groups) {
			group.sorted = new ArrayList<>(group.locs.values());
			group.sorted.sort(Comparator.comparingDouble((Loc l) -> l.box.distance).thenComparing(l -> -l.count));
			for (Loc loc : group.sorted) group.nearest = Math.min(group.nearest, loc.box.distance);
		}
		Comparator<Group> groupOrder = switch (itemSort) {
			case COUNT -> Comparator.comparingInt((Group g) -> -g.total);
			case NAME -> Comparator.comparing((Group g) -> g.name.toLowerCase(Locale.ROOT));
			case NEAREST -> Comparator.comparingDouble((Group g) -> g.nearest);
			case SPREAD -> Comparator.comparingInt((Group g) -> -g.spread());
		};
		groups.sort(groupOrder.thenComparing(g -> g.name));

		Comparator<Box> boxOrder = switch (boxSort) {
			case NEAREST -> Comparator.comparingDouble(Box::distance);
			case NAME -> Comparator.comparing(Box::labelText);
			case FULLEST -> Comparator.comparingInt((Box b) -> -b.totalItems);
			case RECENT -> Comparator.comparingLong((Box b) -> -b.updated);
		};
		matchingBoxes.sort(boxOrder);
		boxes = matchingBoxes;

		// Keep the selection if it's still there, otherwise pick the first result.
		if (selectedGroup != null) {
			String name = selectedGroup.name;
			selectedGroup = groups.stream().filter(g -> g.name.equals(name)).findFirst().orElse(null);
		}
		if (selectedGroup == null && !groups.isEmpty()) selectedGroup = groups.get(0);
		if (selectedBox != null && !boxes.contains(selectedBox)) selectedBox = null;
		if (selectedBox == null && !boxes.isEmpty()) selectedBox = boxes.get(0);
		if (view == View.FETCH) planFetch();
		leftScroll = Math.min(leftScroll, maxLeftScroll());
		rightScroll = 0;
		updateButtons();
	}

	private boolean addLoc(Map<String, Group> byKey, Box box, ItemStack stack, String inside, String[] tokens, boolean boxMatches) {
		String text = itemText(stack);
		if (tokens.length > 0 && !boxMatches && !matches(text, tokens)) return false;
		String key = key(stack);
		Group group = byKey.computeIfAbsent(key, k -> new Group(stack.getHoverName().getString(), stack.copyWithCount(1), text));
		group.total += stack.getCount();
		String locKey = box.dimension + "|" + box.pos.asLong() + "|" + inside;
		Loc old = group.locs.get(locKey);
		group.locs.put(locKey, new Loc(box, (old == null ? 0 : old.count) + stack.getCount(), inside));
		return true;
	}

	// ---- Layout and widgets ----

	@Override
	protected void init() {
		String handedOver = Fetch.importHandOff();
		if (handedOver != null) {
			// Another mod sent a list: show it straight away.
			view = View.FETCH;
			handOffNote = Component.translatable("hoardeye.fetch.imported", handedOver).getString();
		}
		loadBoxes();

		int contentWidth = width - 2 * PAD;
		search = new EditBox(font, PAD + 1, 25, contentWidth - 2, 16, Component.translatable("hoardeye.search.title"));
		search.setMaxLength(64);
		search.setHint(Component.translatable("hoardeye.search.placeholder").withStyle(s -> s.withColor(DIM)));
		search.setValue(lastQuery);
		search.setResponder(text -> {
			leftScroll = 0;
			refresh();
		});
		addRenderableWidget(search);
		setInitialFocus(search);

		int buttonY = 46;
		int buttonWidth = Math.min(120, (contentWidth - 4 * 4) / 5);
		int x = PAD;
		addRenderableWidget(cycle(x, buttonY, buttonWidth, this::viewLabel, () -> {
			view = View.values()[(view.ordinal() + 1) % View.values().length];
			leftScroll = 0;
		}));
		x += buttonWidth + 4;
		Button sortButton = addRenderableWidget(cycle(x, buttonY, buttonWidth, this::sortLabel, () -> {
			if (view == View.ITEMS) itemSort = ItemSort.values()[(itemSort.ordinal() + 1) % ItemSort.values().length];
			else if (view == View.CONTAINERS) boxSort = BoxSort.values()[(boxSort.ordinal() + 1) % BoxSort.values().length];
		}));
		sortButton.active = view != View.FETCH;
		x += buttonWidth + 4;
		addRenderableWidget(cycle(x, buttonY, buttonWidth, () -> label("scope", scope.name()), () ->
				scope = scope == Scope.ALL ? Scope.HERE : Scope.ALL));
		x += buttonWidth + 4;
		addRenderableWidget(cycle(x, buttonY, buttonWidth, () -> label("type", typeFilter.name()), () ->
				typeFilter = TypeFilter.values()[(typeFilter.ordinal() + 1) % TypeFilter.values().length]));
		x += buttonWidth + 4;
		addRenderableWidget(cycle(x, buttonY, buttonWidth, () -> label("nested", nested ? "ON" : "OFF"), () -> nested = !nested));

		bodyTop = 72;
		bodyBottom = height - 28;
		leftX0 = PAD;
		leftX1 = PAD + (int) (contentWidth * 0.55);
		rightX0 = leftX1 + 6;
		rightX1 = width - PAD;

		int half = (rightX1 - rightX0 - 12) / 2;
		findButton = addRenderableWidget(Button.builder(Component.translatable("hoardeye.search.find"), b -> {
			if (view == View.FETCH) startRoute();
			else findNearest();
		}).bounds(rightX0 + 4, bodyBottom - 24, half, 20).build());
		allButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			if (view == View.FETCH) {
				Fetch.clear();
				refresh();
			} else {
				highlightAll();
			}
		}).bounds(rightX0 + 8 + half, bodyBottom - 24, half, 20).build());
		fetchButton = addRenderableWidget(Button.builder(Component.translatable("hoardeye.fetch.add"), b -> {
			if (selectedGroup != null) {
				Fetch.addStack(selectedGroup.icon);
				updateButtons();
			}
		}).bounds(rightX1 - 64, bodyTop + 18, 58, 20).build());
		stopButton = addRenderableWidget(Button.builder(Component.translatable("hoardeye.search.stop"), b -> {
			Locator.stop();
			updateButtons();
		}).bounds(width - PAD - 100, height - 24, 100, 20).build());

		refresh();
	}

	private interface LabelSupplier {
		Component get();
	}

	private Button cycle(int x, int y, int w, LabelSupplier label, Runnable action) {
		return Button.builder(label.get(), b -> {
			action.run();
			refresh();
			b.setMessage(label.get());
			rebuildWidgets();
		}).bounds(x, y, w, 20).build();
	}

	private Component viewLabel() {
		return label("view", view.name());
	}

	private Component sortLabel() {
		if (view == View.FETCH) return Component.translatable("hoardeye.search.sort", "—");
		return label("sort", view == View.ITEMS ? itemSort.name() : boxSort.name());
	}

	private static Component label(String key, String value) {
		return Component.translatable("hoardeye.search." + key,
				Component.translatable("hoardeye.search." + key + "." + value.toLowerCase(Locale.ROOT)));
	}

	private void updateButtons() {
		if (findButton == null) return;
		stopButton.visible = Locator.active();
		fetchButton.visible = view == View.ITEMS && selectedGroup != null;
		if (fetchButton.visible) {
			fetchButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("hoardeye.fetch.add.tooltip", Fetch.size())));
		}
		if (view == View.FETCH) {
			findButton.setMessage(Component.translatable("hoardeye.fetch.start"));
			findButton.active = !fetchRoute.isEmpty();
			allButton.setMessage(Component.translatable("hoardeye.fetch.clear"));
			allButton.active = Fetch.size() > 0;
			return;
		}
		List<Locator.Target> targets = currentTargets();
		findButton.setMessage(Component.translatable("hoardeye.search.find"));
		findButton.active = !targets.isEmpty();
		allButton.active = targets.size() > 1;
		allButton.setMessage(Component.translatable("hoardeye.search.highlight_all", targets.size()));
	}

	// ---- Fetch list ----

	private List<Fetch.Stop> fetchRoute = new ArrayList<>();
	private final List<String> fetchShortages = new ArrayList<>();
	private final Map<String, Integer> fetchAvailable = new LinkedHashMap<>();
	private record Hit(int x0, int y0, int x1, int y1, Runnable run) {}
	private final List<Hit> fetchHits = new ArrayList<>();

	/** Containers in this dimension you can walk to, with how many of each item they hold. */
	private List<Fetch.Candidate> candidates() {
		Minecraft mc = Minecraft.getInstance();
		String here = HoardEyeClient.dimensionKey(mc.level);
		List<Fetch.Candidate> result = new ArrayList<>();
		for (Box box : allBoxes) {
			if (!box.findable() || !box.dimension.equals(here)) continue;
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (ItemStack stack : box.stacks) counts.merge(Fetch.key(stack), stack.getCount(), Integer::sum);
			result.add(new Fetch.Candidate(box.dimension, box.pos, box.label, box.icon, counts, box.distance));
		}
		return result;
	}

	private void planFetch() {
		fetchShortages.clear();
		fetchAvailable.clear();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		List<Fetch.Candidate> candidates = candidates();
		for (Fetch.Candidate candidate : candidates) {
			candidate.counts().forEach((key, count) -> fetchAvailable.merge(key, count, Integer::sum));
		}
		fetchRoute = Fetch.plan(candidates, mc.player.getEyePosition(), fetchShortages);
	}

	private void startRoute() {
		if (fetchRoute.isEmpty()) return;
		Locator.guideRoute(fetchRoute);
		onClose();
	}

	// ---- Actions ----

	/** Findable containers for the current selection, nearest first. */
	private List<Locator.Target> currentTargets() {
		List<Locator.Target> targets = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		if (view == View.ITEMS && selectedGroup != null) {
			for (Loc loc : selectedGroup.sorted) {
				if (loc.box.findable() && seen.add(loc.box.dimension + loc.box.pos.asLong())) targets.add(target(loc.box));
			}
		} else if (view == View.CONTAINERS && selectedBox != null && selectedBox.findable()) {
			targets.add(target(selectedBox));
		}
		return targets;
	}

	private static Locator.Target target(Box box) {
		return new Locator.Target(box.dimension, box.pos, box.label, box.icon);
	}

	private String what() {
		return view == View.ITEMS && selectedGroup != null ? selectedGroup.name : selectedBox != null ? selectedBox.label : "";
	}

	private ItemStack whatIcon() {
		return view == View.ITEMS && selectedGroup != null ? selectedGroup.icon : selectedBox != null ? selectedBox.icon : ItemStack.EMPTY;
	}

	private void findNearest() {
		List<Locator.Target> targets = currentTargets();
		if (targets.isEmpty()) return;
		guide(List.of(withIcon(targets.get(0))));
	}

	private void highlightAll() {
		List<Locator.Target> targets = new ArrayList<>();
		for (Locator.Target t : currentTargets()) targets.add(withIcon(t));
		if (!targets.isEmpty()) guide(targets);
	}

	private Locator.Target withIcon(Locator.Target t) {
		ItemStack icon = whatIcon();
		return new Locator.Target(t.dimension(), t.pos(), t.label(), icon.isEmpty() ? t.icon() : icon);
	}

	private void guide(List<Locator.Target> targets) {
		Locator.guide(targets, what());
		onClose();
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		if (view == View.FETCH) {
			for (Hit hit : List.copyOf(fetchHits)) {
				if (click.x() >= hit.x0 && click.x() < hit.x1 && click.y() >= hit.y0 && click.y() < hit.y1) {
					hit.run.run();
					refresh();
					return true;
				}
			}
			return false;
		}
		double mx = click.x(), my = click.y();
		boolean shift = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
		int listTop = bodyTop + 16;

		if (mx >= leftX0 && mx < leftX1 && my >= listTop && my < bodyBottom) {
			int index = (int) ((my - listTop + leftScroll) / ROW);
			if (view == View.ITEMS && index >= 0 && index < groups.size()) {
				selectedGroup = groups.get(index);
				rightScroll = 0;
				updateButtons();
				if (doubled) highlightAll();
				return true;
			}
			if (view == View.CONTAINERS && index >= 0 && index < boxes.size()) {
				selectedBox = boxes.get(index);
				rightScroll = 0;
				updateButtons();
				if (doubled) findNearest();
				return true;
			}
		}

		if (view == View.ITEMS && selectedGroup != null && mx >= rightX0 && mx < rightX1) {
			int locTop = bodyTop + 50;
			if (my >= locTop && my < bodyBottom - 28) {
				int index = (int) ((my - locTop + rightScroll) / LOC_ROW);
				if (index >= 0 && index < selectedGroup.sorted.size()) {
					Loc loc = selectedGroup.sorted.get(index);
					if (shift) highlightAll();
					else if (loc.box.findable()) guide(List.of(withIcon(target(loc.box))));
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX < leftX1) {
			leftScroll = clamp(leftScroll - verticalAmount * ROW * 2, maxLeftScroll());
		} else {
			rightScroll = clamp(rightScroll - verticalAmount * LOC_ROW * 2, maxRightScroll());
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		int key = input.key();
		if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
			moveSelection(key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			findNearest();
			return true;
		}
		return super.keyPressed(input);
	}

	private void moveSelection(int delta) {
		int listHeight = bodyBottom - bodyTop - 16;
		int index;
		if (view == View.ITEMS) {
			if (groups.isEmpty()) return;
			index = Math.max(0, Math.min(groups.size() - 1, groups.indexOf(selectedGroup) + delta));
			selectedGroup = groups.get(index);
		} else {
			if (boxes.isEmpty()) return;
			index = Math.max(0, Math.min(boxes.size() - 1, boxes.indexOf(selectedBox) + delta));
			selectedBox = boxes.get(index);
		}
		// Keep the selection in view.
		if (index * ROW < leftScroll) leftScroll = index * ROW;
		if ((index + 1) * ROW > leftScroll + listHeight) leftScroll = (index + 1) * ROW - listHeight;
		rightScroll = 0;
		updateButtons();
	}

	private static double clamp(double value, double max) {
		return Math.max(0, Math.min(max, value));
	}

	private double maxLeftScroll() {
		int count = view == View.ITEMS ? groups.size() : view == View.FETCH ? Fetch.size() : boxes.size();
		return Math.max(0, count * ROW - (bodyBottom - bodyTop - 16));
	}

	private double maxRightScroll() {
		if (view == View.FETCH) return Math.max(0, routeHeight - (bodyBottom - 28 - (bodyTop + 20)));
		if (view != View.ITEMS || selectedGroup == null) return 0;
		return Math.max(0, selectedGroup.sorted.size() * LOC_ROW - (bodyBottom - 28 - (bodyTop + 50)));
	}

	// ---- Drawing ----

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractBackground(context, mouseX, mouseY, delta);
		hoveredStack = ItemStack.EMPTY;
		Font font = this.font;

		// Header.
		context.text(font, Component.literal("▣ ").withStyle(s -> s.withColor(ACCENT)).append(title), PAD, 9, GOLD);
		String stats = Component.translatable("hoardeye.search.stats", allBoxes.size(), fmt(totalItems), totalKinds).getString();
		context.text(font, stats, width - PAD - font.width(stats), 9, GRAY);
		panel(context, PAD, 23, width - PAD, 43);

		// Left list.
		String leftTitle = switch (view) {
			case ITEMS -> Component.translatable("hoardeye.search.items_header", groups.size()).getString();
			case CONTAINERS -> Component.translatable("hoardeye.search.containers_header", boxes.size()).getString();
			case FETCH -> Component.translatable("hoardeye.fetch.header", Fetch.size()).getString();
		};
		panelWithHeader(context, leftX0, bodyTop, leftX1, bodyBottom, leftTitle);
		fetchHits.clear();
		if (view == View.FETCH) {
			drawFetchList(context, mouseX, mouseY);
		} else if (allBoxes.isEmpty()) {
			drawWrapped(context, Component.translatable("hoardeye.search.empty_world").getString(), leftX0 + 6, bodyTop + 22, leftX1 - leftX0 - 12, GRAY);
		} else if (view == View.ITEMS) {
			drawGroupList(context, mouseX, mouseY);
		} else {
			drawBoxList(context, mouseX, mouseY);
		}

		// Right details.
		panelWithHeader(context, rightX0, bodyTop, rightX1, bodyBottom, view == View.FETCH
				? Component.translatable("hoardeye.fetch.route_header", fetchRoute.size()).getString()
				: Component.translatable("hoardeye.search.details").getString());
		if (view == View.ITEMS) drawGroupDetails(context, mouseX, mouseY);
		else if (view == View.CONTAINERS) drawBoxDetails(context, mouseX, mouseY);
		else drawRoute(context);

		// Footer hint.
		String hint = Component.translatable(switch (view) {
			case ITEMS -> "hoardeye.search.hint_items";
			case CONTAINERS -> "hoardeye.search.hint_containers";
			case FETCH -> "hoardeye.fetch.hint";
		}).getString();
		context.text(font, font.plainSubstrByWidth(hint, width - 2 * PAD - (Locator.active() ? 108 : 0)), PAD, height - 18, DIM);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractRenderState(context, mouseX, mouseY, delta);
		if (!hoveredStack.isEmpty()) context.setTooltipForNextFrame(font, hoveredStack, hoverX, hoverY);
	}

	private void drawGroupList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int top = bodyTop + 16;
		context.enableScissor(leftX0 + 1, top, leftX1 - 1, bodyBottom - 1);
		int y = top - (int) leftScroll;
		for (Group group : groups) {
			if (y + ROW >= top && y <= bodyBottom) {
				boolean hovered = mouseX >= leftX0 && mouseX < leftX1 && mouseY >= y && mouseY < y + ROW && mouseY >= top && mouseY < bodyBottom;
				rowBackground(context, leftX0 + 1, y, leftX1 - 1, group == selectedGroup, hovered);
				context.item(group.icon, leftX0 + 5, y + 2);
				String count = "×" + fmt(group.total);
				String spread = Component.translatable("hoardeye.search.in_containers", group.spread()).getString();
				int rightWidth = font.width(count) + font.width(spread) + 10;
				int nameWidth = leftX1 - leftX0 - 30 - rightWidth;
				context.text(font, font.plainSubstrByWidth(group.name, nameWidth), leftX0 + 25, y + 6, WHITE);
				context.text(font, spread, leftX1 - 6 - font.width(spread), y + 6, DIM);
				context.text(font, count, leftX1 - 10 - font.width(spread) - font.width(count), y + 6, GOLD);
				if (hovered && mouseX < leftX0 + 23) setHovered(group.icon, mouseX, mouseY);
			}
			y += ROW;
		}
		context.disableScissor();
		if (groups.isEmpty()) {
			context.text(font, Component.translatable("hoardeye.search.no_results"), leftX0 + 6, top + 6, GRAY);
		}
		scrollbar(context, leftX1 - 4, top, bodyBottom - 1, leftScroll, maxLeftScroll());
	}

	private void drawBoxList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int top = bodyTop + 16;
		context.enableScissor(leftX0 + 1, top, leftX1 - 1, bodyBottom - 1);
		int y = top - (int) leftScroll;
		for (Box box : boxes) {
			if (y + ROW >= top && y <= bodyBottom) {
				boolean hovered = mouseX >= leftX0 && mouseX < leftX1 && mouseY >= y && mouseY < y + ROW && mouseY >= top && mouseY < bodyBottom;
				rowBackground(context, leftX0 + 1, y, leftX1 - 1, box == selectedBox, hovered);
				context.item(box.icon, leftX0 + 5, y + 2);
				String where = whereText(box);
				int whereWidth = font.width(where);
				String count = fmt(box.totalItems);
				int countWidth = font.width(count);
				context.text(font, font.plainSubstrByWidth(box.label, leftX1 - leftX0 - 40 - whereWidth - countWidth), leftX0 + 25, y + 6, WHITE);
				context.text(font, count, leftX1 - 12 - whereWidth - countWidth, y + 6, GOLD);
				context.text(font, where, leftX1 - 6 - whereWidth, y + 6, DIM);
			}
			y += ROW;
		}
		context.disableScissor();
		if (boxes.isEmpty()) {
			context.text(font, Component.translatable("hoardeye.search.no_results"), leftX0 + 6, top + 6, GRAY);
		}
		scrollbar(context, leftX1 - 4, top, bodyBottom - 1, leftScroll, maxLeftScroll());
	}

	private void drawGroupDetails(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int x = rightX0 + 6;
		int y = bodyTop + 18;
		if (selectedGroup == null) {
			drawWrapped(context, Component.translatable("hoardeye.search.select_item").getString(), x, y, rightX1 - rightX0 - 12, GRAY);
			return;
		}
		Group group = selectedGroup;
		bigIcon(context, group.icon, x, y);
		if (mouseX >= x && mouseX < x + 32 && mouseY >= y && mouseY < y + 32) setHovered(group.icon, mouseX, mouseY);
		int textX = x + 38;
		context.text(font, font.plainSubstrByWidth(group.name, rightX1 - textX - 70), textX, y + 4, WHITE);
		String summary = Component.translatable("hoardeye.search.group_summary", fmt(group.total), group.spread()).getString();
		// Never cut off mid-word: a shorter way of saying it when there's no room.
		if (font.width(summary) > rightX1 - textX - 70) summary = Component.translatable("hoardeye.search.group_summary_short", fmt(group.total), group.spread()).getString();
		context.text(font, font.plainSubstrByWidth(summary, rightX1 - textX - 70), textX, y + 17, GOLD);

		int top = bodyTop + 50;
		int bottom = bodyBottom - 28;
		context.horizontalLine(rightX0 + 4, rightX1 - 5, top - 3, BORDER);
		context.enableScissor(rightX0 + 1, top, rightX1 - 1, bottom);
		int rowY = top - (int) rightScroll;
		for (Loc loc : group.sorted) {
			if (rowY + LOC_ROW >= top && rowY <= bottom) {
				boolean hovered = mouseX >= rightX0 && mouseX < rightX1 && mouseY >= rowY && mouseY < rowY + LOC_ROW && mouseY >= top && mouseY < bottom;
				rowBackground(context, rightX0 + 1, rowY, rightX1 - 1, false, hovered && loc.box.findable());
				context.item(loc.box.icon, rightX0 + 5, rowY + 3);
				String count = "×" + fmt(loc.count);
				int countWidth = font.width(count);
				context.text(font, font.plainSubstrByWidth(loc.box.label, rightX1 - rightX0 - 36 - countWidth), rightX0 + 25, rowY + 2, WHITE);
				context.text(font, count, rightX1 - 8 - countWidth, rowY + 2, GOLD);
				String detail = locationText(loc.box) + (loc.inside != null ? "  ▸ " + loc.inside : "");
				context.text(font, font.plainSubstrByWidth(detail, rightX1 - rightX0 - 32), rightX0 + 25, rowY + 12, loc.inside != null ? 0xFFB088FF : GRAY);
			}
			rowY += LOC_ROW;
		}
		context.disableScissor();
		scrollbar(context, rightX1 - 4, top, bottom, rightScroll, maxRightScroll());
	}

	private void drawBoxDetails(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int x = rightX0 + 6;
		int y = bodyTop + 18;
		if (selectedBox == null) {
			drawWrapped(context, Component.translatable("hoardeye.search.select_container").getString(), x, y, rightX1 - rightX0 - 12, GRAY);
			return;
		}
		Box box = selectedBox;
		bigIcon(context, box.icon, x, y);
		int textX = x + 38;
		context.text(font, font.plainSubstrByWidth(box.label, rightX1 - textX - 6), textX, y + 2, WHITE);
		context.text(font, font.plainSubstrByWidth(locationText(box), rightX1 - textX - 6), textX, y + 13, GRAY);
		String checked = box.updated > 0 ? Component.translatable("hoardeye.search.checked", ago(box.updated)).getString() : "";
		context.text(font, checked, textX, y + 24, DIM);

		// The contents as a grid.
		int columns = Math.max(1, (rightX1 - rightX0 - 12) / CELL);
		int gridX = rightX0 + 6;
		int gridY = bodyTop + 54;
		List<ItemStack> stacks = box.stacks;
		for (int i = 0; i < stacks.size(); i++) {
			int cx = gridX + (i % columns) * CELL;
			int cy = gridY + (i / columns) * CELL;
			if (cy + CELL > bodyBottom - 28) break;
			context.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, 0x40000000);
			ItemStack stack = stacks.get(i);
			context.item(stack, cx, cy);
			context.itemDecorations(font, stack, cx, cy);
			if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
				context.fill(cx, cy, cx + 16, cy + 16, 0x50FFFFFF);
				setHovered(stack, mouseX, mouseY);
			}
		}
		if (stacks.isEmpty()) context.text(font, Component.translatable("hoardeye.empty"), gridX, gridY, GRAY);
	}

	/** The fetch list: each item with how many you want and how many are stored, plus − / + / ✖ chips. */
	private void drawFetchList(GuiGraphicsExtractor context, int mouseX, int mouseY) {
		int top = bodyTop + 16;
		List<Fetch.Want> wants = Fetch.wants();
		if (wants.isEmpty()) {
			drawWrapped(context, Component.translatable("hoardeye.fetch.empty").getString(), leftX0 + 6, top + 6, leftX1 - leftX0 - 12, GRAY);
			return;
		}
		context.enableScissor(leftX0 + 1, top, leftX1 - 1, bodyBottom - 1);
		int y = top - (int) leftScroll;
		for (Fetch.Want want : wants) {
			if (y + ROW >= top && y <= bodyBottom) {
				boolean hovered = mouseX >= leftX0 && mouseX < leftX1 && mouseY >= y && mouseY < y + ROW;
				if (hovered) context.fill(leftX0 + 1, y, leftX1 - 1, y + ROW, HOVER);
				context.item(want.icon, leftX0 + 5, y + 2);
				int have = fetchAvailable.getOrDefault(want.key, 0);
				String count = "×" + fmt(want.count);
				String stock = Component.translatable("hoardeye.fetch.stored", fmt(have)).getString();
				int chipsWidth = 3 * 16;
				int x = leftX1 - 6 - chipsWidth;
				// Right to left: chips, stored, wanted; the name gets whatever room is left.
				int stockX = x - 6 - font.width(stock);
				int countX = stockX - 6 - font.width(count);
				context.text(font, font.plainSubstrByWidth(want.name, Math.max(0, countX - leftX0 - 30)), leftX0 + 25, y + 6, WHITE);
				context.text(font, count, countX, y + 6, GOLD);
				context.text(font, stock, stockX, y + 6, have >= want.count ? 0xFF55FF55 : 0xFFFF7070);
				x = chip(context, x, y + 3, "−", 0xFFFFAA40, mouseX, mouseY, () -> Fetch.adjust(want.key, -1));
				x = chip(context, x, y + 3, "+", 0xFF55CC55, mouseX, mouseY, () -> Fetch.adjust(want.key, 1));
				chip(context, x, y + 3, "✖", 0xFFFF5555, mouseX, mouseY, () -> Fetch.remove(want.key));
			}
			y += ROW;
		}
		context.disableScissor();
	}

	private int chip(GuiGraphicsExtractor context, int x, int y, String label, int color, int mouseX, int mouseY, Runnable action) {
		boolean over = mouseX >= x && mouseX < x + 14 && mouseY >= y && mouseY < y + 14;
		context.fill(x, y, x + 14, y + 14, (color & 0xFFFFFF) | (over ? 0x70000000 : 0x30000000));
		context.outline(x, y, 14, 14, color);
		context.text(font, label, x + 7 - font.width(label) / 2, y + 3, over ? 0xFFFFFFFF : color);
		if (y >= bodyTop + 16 && y + 14 <= bodyBottom) fetchHits.add(new Hit(x, y, x + 14, y + 14, action));
		return x + 16;
	}

	/** The planned route: each stop and what to take there, plus anything that can't be found in full. */
	private void drawRoute(GuiGraphicsExtractor context) {
		int x = rightX0 + 6;
		int y = bodyTop + 20;
		int width = rightX1 - rightX0 - 12;
		if (Fetch.size() == 0) {
			drawWrapped(context, Component.translatable("hoardeye.fetch.route_empty").getString(), x, y, width, GRAY);
			return;
		}
		int bottom = bodyBottom - 28;
		int start = y;
		context.enableScissor(rightX0 + 1, y - 2, rightX1 - 1, bottom);
		y -= (int) rightScroll;
		if (handOffNote != null) {
			for (var line : font.split(Component.literal(handOffNote), width)) {
				context.text(font, line, x, y, ACCENT);
				y += 10;
			}
			y += 4;
		}
		// Shortages first: they're what you need to know before setting off.
		for (String shortage : fetchShortages) {
			context.text(font, font.plainSubstrByWidth("⚠ " + shortage, width), x, y, 0xFFFF7070);
			y += 11;
		}
		if (!fetchShortages.isEmpty()) y += 4;
		for (int i = 0; i < fetchRoute.size(); i++) {
			Fetch.Stop stop = fetchRoute.get(i);
			context.item(stop.icon(), x, y);
			double distance = Minecraft.getInstance().player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(stop.pos()));
			context.text(font, font.plainSubstrByWidth((i + 1) + ". " + stop.label() + "  (" + (int) distance + "m)", width - 20), x + 20, y + 4, WHITE);
			y += 18;
			for (Fetch.Take take : stop.takes()) {
				context.text(font, font.plainSubstrByWidth("   " + take.count() + " × " + take.name(), width - 20), x + 20, y, GRAY);
				y += 10;
			}
			y += 4;
		}
		context.disableScissor();
		routeHeight = y + (int) rightScroll - start;
		scrollbar(context, rightX1 - 4, start - 2, bottom, rightScroll, maxRightScroll());
	}

	private int routeHeight;
	private String handOffNote;

	private void setHovered(ItemStack stack, int x, int y) {
		hoveredStack = stack;
		hoverX = x;
		hoverY = y;
	}

	private void panel(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1) {
		context.fill(x0, y0, x1, y1, PANEL);
		context.outline(x0, y0, x1 - x0, y1 - y0, BORDER);
	}

	private void panelWithHeader(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1, String title) {
		panel(context, x0, y0, x1, y1);
		context.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 14, PANEL_HEADER);
		context.horizontalLine(x0 + 1, x1 - 2, y0 + 14, BORDER);
		context.fill(x0 + 1, y0 + 1, x0 + 3, y0 + 14, ACCENT);
		context.text(font, title, x0 + 7, y0 + 4, GOLD);
	}

	private static void rowBackground(GuiGraphicsExtractor context, int x0, int y, int x1, boolean selected, boolean hovered) {
		if (selected) {
			context.fill(x0, y, x1, y + ROW, SELECTED);
			context.fill(x0, y, x0 + 2, y + ROW, ACCENT);
		} else if (hovered) {
			context.fill(x0, y, x1, y + ROW, HOVER);
		}
	}

	private static void scrollbar(GuiGraphicsExtractor context, int x, int top, int bottom, double scroll, double max) {
		if (max <= 0) return;
		int view = bottom - top;
		int bar = Math.max(16, (int) (view * view / (view + max)));
		int barY = top + (int) ((view - bar) * scroll / max);
		context.fill(x, top, x + 2, bottom, 0x30FFFFFF);
		context.fill(x, barY, x + 2, barY + bar, ACCENT);
	}

	private void bigIcon(GuiGraphicsExtractor context, ItemStack stack, int x, int y) {
		context.fill(x - 1, y - 1, x + 33, y + 33, 0x60000000);
		context.outline(x - 1, y - 1, 34, 34, BORDER);
		Matrix3x2fStack matrices = context.pose();
		matrices.pushMatrix();
		matrices.translate(x, y);
		matrices.scale(2, 2);
		context.item(stack, 0, 0);
		matrices.popMatrix();
	}

	private void drawWrapped(GuiGraphicsExtractor context, String text, int x, int y, int width, int color) {
		for (var line : font.split(Component.literal(text), width)) {
			context.text(font, line, x, y, color);
			y += 11;
		}
	}

	private static String whereText(Box box) {
		if (box.dimension.equals(ENDER)) return Component.translatable("hoardeye.search.ender").getString();
		if (box.distance != Double.MAX_VALUE) return (int) box.distance + "m";
		return dimensionName(box.dimension);
	}

	private static String locationText(Box box) {
		if (box.dimension.equals(ENDER)) return Component.translatable("hoardeye.search.ender_long").getString();
		String coords = box.pos.getX() + " " + box.pos.getY() + " " + box.pos.getZ();
		return box.distance != Double.MAX_VALUE ? coords + "  ·  " + (int) box.distance + "m" : coords + "  ·  " + dimensionName(box.dimension);
	}

	private static String dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> dimension.replace("minecraft:", "");
		};
	}

	private static String fmt(int n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	private static String ago(long millis) {
		long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
		if (seconds < 60) return seconds + "s";
		if (seconds < 3600) return seconds / 60 + "m";
		if (seconds < 86400) return seconds / 3600 + "h";
		return seconds / 86400 + "d";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---- Hooks for the development self-test ----

	public void testQuery(String query) {
		search.setValue(query);
	}

	public void testView(boolean containers) {
		view = containers ? View.CONTAINERS : View.ITEMS;
		rebuildWidgets();
	}

	/** Total count of the item with this display name in the current results, or -1 if absent. */
	public int testTotal(String name) {
		return groups.stream().filter(g -> g.name.equals(name)).mapToInt(g -> g.total).findFirst().orElse(-1);
	}

	/** Adds the selected item to the fetch list, switches to the fetch view and starts the route; for the self-test. */
	public void testFetch(String itemName, int stacks) {
		for (Group group : groups) {
			if (group.name.equals(itemName)) for (int i = 0; i < stacks; i++) Fetch.addStack(group.icon);
		}
	}

	public void testShowFetch() {
		view = View.FETCH;
		rebuildWidgets();
	}

	public int testRouteStops() {
		return fetchRoute.size();
	}

	public void testStartRoute() {
		startRoute();
	}

	public void testHighlightAll() {
		highlightAll();
	}
}
