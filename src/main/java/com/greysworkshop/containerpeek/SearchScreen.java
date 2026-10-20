package com.greysworkshop.containerpeek;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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

/**
 * Storage search: every item in every container you've opened, searchable and sortable, with a locator to
 * walk you to it.
 */
public class SearchScreen extends Screen {
	private enum View { ITEMS, CONTAINERS }

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

	private TextFieldWidget search;
	private ButtonWidget findButton;
	private ButtonWidget allButton;
	private ButtonWidget stopButton;
	private ItemStack hoveredStack = ItemStack.EMPTY;
	private int hoverX;
	private int hoverY;

	// Layout.
	private int bodyTop;
	private int bodyBottom;
	private int leftX0, leftX1, rightX0, rightX1;

	public SearchScreen() {
		super(Text.translatable("containerpeek.search.title"));
	}

	// ---- Data ----

	private void loadBoxes() {
		allBoxes.clear();
		MinecraftClient mc = MinecraftClient.getInstance();
		String worldKey = ContainerPeekClient.worldKey(mc);
		if (worldKey == null || mc.world == null) return;
		ClientWorld world = mc.world;
		String here = ContainerPeekClient.dimensionKey(world);
		Set<String> kinds = new HashSet<>();
		totalItems = 0;

		for (var dim : ContainerPeekClient.INSTANCE.store().world(worldKey).entrySet()) {
			for (var e : dim.getValue().entrySet()) {
				NameStore.Entry entry = e.getValue();
				List<ItemStack> stacks = entry.stacks(world.getRegistryManager());
				if (stacks == null) continue;
				BlockPos pos = BlockPos.fromLong(e.getKey());
				String dimension = dim.getKey();

				String blockId = entry.block;
				ContainerKind kind = entry.kind != null ? parseKind(entry.kind) : null;
				if (dimension.equals(ENDER)) {
					blockId = "minecraft:ender_chest";
					kind = ContainerKind.ENDER_CHEST;
				} else if (blockId == null && dimension.equals(here) && world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
					// Older entries didn't record their block; look it up if we can.
					blockId = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).toString();
					kind = ContainerKind.of(world.getBlockEntity(pos));
				}
				Block block = blockId != null ? Registries.BLOCK.get(Identifier.tryParse(blockId)) : null;
				ItemStack icon = block != null && block.asItem() != Items.AIR ? new ItemStack(block.asItem()) : new ItemStack(Items.CHEST);
				String label = !entry.name.isEmpty() ? entry.name
						: block != null && block.asItem() != Items.AIR ? block.getName().getString()
						: Text.translatable("containerpeek.search.container").getString();

				int items = 0;
				StringBuilder itemText = new StringBuilder();
				for (ItemStack stack : stacks) {
					items += stack.getCount();
					appendText(itemText, stack);
					kinds.add(key(stack));
					ContainerComponent inner = stack.get(DataComponentTypes.CONTAINER);
					if (inner != null) {
						inner.streamNonEmpty().forEach(s -> {
							appendText(itemText, s);
							kinds.add(key(s));
						});
					}
				}
				totalItems += items;
				double distance = dimension.equals(here) && mc.player != null
						? Math.sqrt(mc.player.squaredDistanceTo(Vec3d.ofCenter(pos))) : Double.MAX_VALUE;
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
		return stack.getName().getString() + "|" + Registries.ITEM.getId(stack.getItem());
	}

	private static String itemText(ItemStack stack) {
		return (stack.getName().getString() + " " + Registries.ITEM.getId(stack.getItem()).getPath().replace('_', ' '))
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
		String query = search == null ? lastQuery : search.getText();
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
				ContainerComponent inner = stack.get(DataComponentTypes.CONTAINER);
				if (nested && inner != null) {
					String inside = stack.getName().getString();
					for (ItemStack s : inner.iterateNonEmpty()) anyItem |= addLoc(byKey, box, s, inside, tokens, boxMatches);
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
		leftScroll = Math.min(leftScroll, maxLeftScroll());
		rightScroll = 0;
		updateButtons();
	}

	private boolean addLoc(Map<String, Group> byKey, Box box, ItemStack stack, String inside, String[] tokens, boolean boxMatches) {
		String text = itemText(stack);
		if (tokens.length > 0 && !boxMatches && !matches(text, tokens)) return false;
		String key = key(stack);
		Group group = byKey.computeIfAbsent(key, k -> new Group(stack.getName().getString(), stack.copyWithCount(1), text));
		group.total += stack.getCount();
		String locKey = box.dimension + "|" + box.pos.asLong() + "|" + inside;
		Loc old = group.locs.get(locKey);
		group.locs.put(locKey, new Loc(box, (old == null ? 0 : old.count) + stack.getCount(), inside));
		return true;
	}

	// ---- Layout and widgets ----

	@Override
	protected void init() {
		loadBoxes();

		int contentWidth = width - 2 * PAD;
		search = new TextFieldWidget(textRenderer, PAD + 1, 25, contentWidth - 2, 16, Text.translatable("containerpeek.search.title"));
		search.setMaxLength(64);
		search.setPlaceholder(Text.translatable("containerpeek.search.placeholder").styled(s -> s.withColor(DIM)));
		search.setText(lastQuery);
		search.setChangedListener(text -> {
			leftScroll = 0;
			refresh();
		});
		addDrawableChild(search);
		setInitialFocus(search);

		int buttonY = 46;
		int buttonWidth = Math.min(120, (contentWidth - 4 * 4) / 5);
		int x = PAD;
		addDrawableChild(cycle(x, buttonY, buttonWidth, this::viewLabel, () -> {
			view = view == View.ITEMS ? View.CONTAINERS : View.ITEMS;
			leftScroll = 0;
		}));
		x += buttonWidth + 4;
		addDrawableChild(cycle(x, buttonY, buttonWidth, this::sortLabel, () -> {
			if (view == View.ITEMS) itemSort = ItemSort.values()[(itemSort.ordinal() + 1) % ItemSort.values().length];
			else boxSort = BoxSort.values()[(boxSort.ordinal() + 1) % BoxSort.values().length];
		}));
		x += buttonWidth + 4;
		addDrawableChild(cycle(x, buttonY, buttonWidth, () -> label("scope", scope.name()), () ->
				scope = scope == Scope.ALL ? Scope.HERE : Scope.ALL));
		x += buttonWidth + 4;
		addDrawableChild(cycle(x, buttonY, buttonWidth, () -> label("type", typeFilter.name()), () ->
				typeFilter = TypeFilter.values()[(typeFilter.ordinal() + 1) % TypeFilter.values().length]));
		x += buttonWidth + 4;
		addDrawableChild(cycle(x, buttonY, buttonWidth, () -> label("nested", nested ? "ON" : "OFF"), () -> nested = !nested));

		bodyTop = 72;
		bodyBottom = height - 28;
		leftX0 = PAD;
		leftX1 = PAD + (int) (contentWidth * 0.55);
		rightX0 = leftX1 + 6;
		rightX1 = width - PAD;

		int half = (rightX1 - rightX0 - 12) / 2;
		findButton = addDrawableChild(ButtonWidget.builder(Text.translatable("containerpeek.search.find"), b -> findNearest())
				.dimensions(rightX0 + 4, bodyBottom - 24, half, 20).build());
		allButton = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> highlightAll())
				.dimensions(rightX0 + 8 + half, bodyBottom - 24, half, 20).build());
		stopButton = addDrawableChild(ButtonWidget.builder(Text.translatable("containerpeek.search.stop"), b -> {
			Locator.stop();
			updateButtons();
		}).dimensions(width - PAD - 100, height - 24, 100, 20).build());

		refresh();
	}

	private interface LabelSupplier {
		Text get();
	}

	private ButtonWidget cycle(int x, int y, int w, LabelSupplier label, Runnable action) {
		return ButtonWidget.builder(label.get(), b -> {
			action.run();
			refresh();
			b.setMessage(label.get());
			clearAndInit();
		}).dimensions(x, y, w, 20).build();
	}

	private Text viewLabel() {
		return label("view", view.name());
	}

	private Text sortLabel() {
		return label("sort", view == View.ITEMS ? itemSort.name() : boxSort.name());
	}

	private static Text label(String key, String value) {
		return Text.translatable("containerpeek.search." + key,
				Text.translatable("containerpeek.search." + key + "." + value.toLowerCase(Locale.ROOT)));
	}

	private void updateButtons() {
		if (findButton == null) return;
		List<Locator.Target> targets = currentTargets();
		findButton.active = !targets.isEmpty();
		allButton.active = targets.size() > 1;
		allButton.setMessage(Text.translatable("containerpeek.search.highlight_all", targets.size()));
		stopButton.visible = Locator.active();
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
		close();
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
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
	public boolean keyPressed(KeyInput input) {
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
		int count = view == View.ITEMS ? groups.size() : boxes.size();
		return Math.max(0, count * ROW - (bodyBottom - bodyTop - 16));
	}

	private double maxRightScroll() {
		if (view != View.ITEMS || selectedGroup == null) return 0;
		return Math.max(0, selectedGroup.sorted.size() * LOC_ROW - (bodyBottom - 28 - (bodyTop + 50)));
	}

	// ---- Drawing ----

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		super.renderBackground(context, mouseX, mouseY, delta);
		hoveredStack = ItemStack.EMPTY;
		TextRenderer font = textRenderer;

		// Header.
		context.drawTextWithShadow(font, Text.literal("▣ ").styled(s -> s.withColor(ACCENT)).append(title), PAD, 9, GOLD);
		String stats = Text.translatable("containerpeek.search.stats", allBoxes.size(), fmt(totalItems), totalKinds).getString();
		context.drawTextWithShadow(font, stats, width - PAD - font.getWidth(stats), 9, GRAY);
		panel(context, PAD, 23, width - PAD, 43);

		// Left list.
		String leftTitle = view == View.ITEMS
				? Text.translatable("containerpeek.search.items_header", groups.size()).getString()
				: Text.translatable("containerpeek.search.containers_header", boxes.size()).getString();
		panelWithHeader(context, leftX0, bodyTop, leftX1, bodyBottom, leftTitle);
		if (allBoxes.isEmpty()) {
			drawWrapped(context, Text.translatable("containerpeek.search.empty_world").getString(), leftX0 + 6, bodyTop + 22, leftX1 - leftX0 - 12, GRAY);
		} else if (view == View.ITEMS) {
			drawGroupList(context, mouseX, mouseY);
		} else {
			drawBoxList(context, mouseX, mouseY);
		}

		// Right details.
		panelWithHeader(context, rightX0, bodyTop, rightX1, bodyBottom, Text.translatable("containerpeek.search.details").getString());
		if (view == View.ITEMS) drawGroupDetails(context, mouseX, mouseY);
		else drawBoxDetails(context, mouseX, mouseY);

		// Footer hint.
		String hint = Text.translatable(view == View.ITEMS ? "containerpeek.search.hint_items" : "containerpeek.search.hint_containers").getString();
		context.drawTextWithShadow(font, font.trimToWidth(hint, width - 2 * PAD - (Locator.active() ? 108 : 0)), PAD, height - 18, DIM);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (!hoveredStack.isEmpty()) context.drawItemTooltip(textRenderer, hoveredStack, hoverX, hoverY);
	}

	private void drawGroupList(DrawContext context, int mouseX, int mouseY) {
		int top = bodyTop + 16;
		context.enableScissor(leftX0 + 1, top, leftX1 - 1, bodyBottom - 1);
		int y = top - (int) leftScroll;
		for (Group group : groups) {
			if (y + ROW >= top && y <= bodyBottom) {
				boolean hovered = mouseX >= leftX0 && mouseX < leftX1 && mouseY >= y && mouseY < y + ROW && mouseY >= top && mouseY < bodyBottom;
				rowBackground(context, leftX0 + 1, y, leftX1 - 1, group == selectedGroup, hovered);
				context.drawItem(group.icon, leftX0 + 5, y + 2);
				String count = "×" + fmt(group.total);
				String spread = Text.translatable("containerpeek.search.in_containers", group.spread()).getString();
				int rightWidth = textRenderer.getWidth(count) + textRenderer.getWidth(spread) + 10;
				int nameWidth = leftX1 - leftX0 - 30 - rightWidth;
				context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(group.name, nameWidth), leftX0 + 25, y + 6, WHITE);
				context.drawTextWithShadow(textRenderer, spread, leftX1 - 6 - textRenderer.getWidth(spread), y + 6, DIM);
				context.drawTextWithShadow(textRenderer, count, leftX1 - 10 - textRenderer.getWidth(spread) - textRenderer.getWidth(count), y + 6, GOLD);
				if (hovered && mouseX < leftX0 + 23) setHovered(group.icon, mouseX, mouseY);
			}
			y += ROW;
		}
		context.disableScissor();
		if (groups.isEmpty()) {
			context.drawTextWithShadow(textRenderer, Text.translatable("containerpeek.search.no_results"), leftX0 + 6, top + 6, GRAY);
		}
		scrollbar(context, leftX1 - 4, top, bodyBottom - 1, leftScroll, maxLeftScroll());
	}

	private void drawBoxList(DrawContext context, int mouseX, int mouseY) {
		int top = bodyTop + 16;
		context.enableScissor(leftX0 + 1, top, leftX1 - 1, bodyBottom - 1);
		int y = top - (int) leftScroll;
		for (Box box : boxes) {
			if (y + ROW >= top && y <= bodyBottom) {
				boolean hovered = mouseX >= leftX0 && mouseX < leftX1 && mouseY >= y && mouseY < y + ROW && mouseY >= top && mouseY < bodyBottom;
				rowBackground(context, leftX0 + 1, y, leftX1 - 1, box == selectedBox, hovered);
				context.drawItem(box.icon, leftX0 + 5, y + 2);
				String where = whereText(box);
				int whereWidth = textRenderer.getWidth(where);
				String count = fmt(box.totalItems);
				int countWidth = textRenderer.getWidth(count);
				context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(box.label, leftX1 - leftX0 - 40 - whereWidth - countWidth), leftX0 + 25, y + 6, WHITE);
				context.drawTextWithShadow(textRenderer, count, leftX1 - 12 - whereWidth - countWidth, y + 6, GOLD);
				context.drawTextWithShadow(textRenderer, where, leftX1 - 6 - whereWidth, y + 6, DIM);
			}
			y += ROW;
		}
		context.disableScissor();
		if (boxes.isEmpty()) {
			context.drawTextWithShadow(textRenderer, Text.translatable("containerpeek.search.no_results"), leftX0 + 6, top + 6, GRAY);
		}
		scrollbar(context, leftX1 - 4, top, bodyBottom - 1, leftScroll, maxLeftScroll());
	}

	private void drawGroupDetails(DrawContext context, int mouseX, int mouseY) {
		int x = rightX0 + 6;
		int y = bodyTop + 18;
		if (selectedGroup == null) {
			drawWrapped(context, Text.translatable("containerpeek.search.select_item").getString(), x, y, rightX1 - rightX0 - 12, GRAY);
			return;
		}
		Group group = selectedGroup;
		bigIcon(context, group.icon, x, y);
		if (mouseX >= x && mouseX < x + 32 && mouseY >= y && mouseY < y + 32) setHovered(group.icon, mouseX, mouseY);
		int textX = x + 38;
		context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(group.name, rightX1 - textX - 6), textX, y + 4, WHITE);
		String summary = Text.translatable("containerpeek.search.group_summary", fmt(group.total), group.spread()).getString();
		context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(summary, rightX1 - textX - 6), textX, y + 17, GOLD);

		int top = bodyTop + 50;
		int bottom = bodyBottom - 28;
		context.drawHorizontalLine(rightX0 + 4, rightX1 - 5, top - 3, BORDER);
		context.enableScissor(rightX0 + 1, top, rightX1 - 1, bottom);
		int rowY = top - (int) rightScroll;
		for (Loc loc : group.sorted) {
			if (rowY + LOC_ROW >= top && rowY <= bottom) {
				boolean hovered = mouseX >= rightX0 && mouseX < rightX1 && mouseY >= rowY && mouseY < rowY + LOC_ROW && mouseY >= top && mouseY < bottom;
				rowBackground(context, rightX0 + 1, rowY, rightX1 - 1, false, hovered && loc.box.findable());
				context.drawItem(loc.box.icon, rightX0 + 5, rowY + 3);
				String count = "×" + fmt(loc.count);
				int countWidth = textRenderer.getWidth(count);
				context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(loc.box.label, rightX1 - rightX0 - 36 - countWidth), rightX0 + 25, rowY + 2, WHITE);
				context.drawTextWithShadow(textRenderer, count, rightX1 - 8 - countWidth, rowY + 2, GOLD);
				String detail = locationText(loc.box) + (loc.inside != null ? "  ▸ " + loc.inside : "");
				context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(detail, rightX1 - rightX0 - 32), rightX0 + 25, rowY + 12, loc.inside != null ? 0xFFB088FF : GRAY);
			}
			rowY += LOC_ROW;
		}
		context.disableScissor();
		scrollbar(context, rightX1 - 4, top, bottom, rightScroll, maxRightScroll());
	}

	private void drawBoxDetails(DrawContext context, int mouseX, int mouseY) {
		int x = rightX0 + 6;
		int y = bodyTop + 18;
		if (selectedBox == null) {
			drawWrapped(context, Text.translatable("containerpeek.search.select_container").getString(), x, y, rightX1 - rightX0 - 12, GRAY);
			return;
		}
		Box box = selectedBox;
		bigIcon(context, box.icon, x, y);
		int textX = x + 38;
		context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(box.label, rightX1 - textX - 6), textX, y + 2, WHITE);
		context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(locationText(box), rightX1 - textX - 6), textX, y + 13, GRAY);
		String checked = box.updated > 0 ? Text.translatable("containerpeek.search.checked", ago(box.updated)).getString() : "";
		context.drawTextWithShadow(textRenderer, checked, textX, y + 24, DIM);

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
			context.drawItem(stack, cx, cy);
			context.drawStackOverlay(textRenderer, stack, cx, cy);
			if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
				context.fill(cx, cy, cx + 16, cy + 16, 0x50FFFFFF);
				setHovered(stack, mouseX, mouseY);
			}
		}
		if (stacks.isEmpty()) context.drawTextWithShadow(textRenderer, Text.translatable("containerpeek.empty"), gridX, gridY, GRAY);
	}

	private void setHovered(ItemStack stack, int x, int y) {
		hoveredStack = stack;
		hoverX = x;
		hoverY = y;
	}

	private void panel(DrawContext context, int x0, int y0, int x1, int y1) {
		context.fill(x0, y0, x1, y1, PANEL);
		context.drawStrokedRectangle(x0, y0, x1 - x0, y1 - y0, BORDER);
	}

	private void panelWithHeader(DrawContext context, int x0, int y0, int x1, int y1, String title) {
		panel(context, x0, y0, x1, y1);
		context.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 14, PANEL_HEADER);
		context.drawHorizontalLine(x0 + 1, x1 - 2, y0 + 14, BORDER);
		context.fill(x0 + 1, y0 + 1, x0 + 3, y0 + 14, ACCENT);
		context.drawTextWithShadow(textRenderer, title, x0 + 7, y0 + 4, GOLD);
	}

	private static void rowBackground(DrawContext context, int x0, int y, int x1, boolean selected, boolean hovered) {
		if (selected) {
			context.fill(x0, y, x1, y + ROW, SELECTED);
			context.fill(x0, y, x0 + 2, y + ROW, ACCENT);
		} else if (hovered) {
			context.fill(x0, y, x1, y + ROW, HOVER);
		}
	}

	private static void scrollbar(DrawContext context, int x, int top, int bottom, double scroll, double max) {
		if (max <= 0) return;
		int view = bottom - top;
		int bar = Math.max(16, (int) (view * view / (view + max)));
		int barY = top + (int) ((view - bar) * scroll / max);
		context.fill(x, top, x + 2, bottom, 0x30FFFFFF);
		context.fill(x, barY, x + 2, barY + bar, ACCENT);
	}

	private void bigIcon(DrawContext context, ItemStack stack, int x, int y) {
		context.fill(x - 1, y - 1, x + 33, y + 33, 0x60000000);
		context.drawStrokedRectangle(x - 1, y - 1, 34, 34, BORDER);
		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(x, y);
		matrices.scale(2, 2);
		context.drawItem(stack, 0, 0);
		matrices.popMatrix();
	}

	private void drawWrapped(DrawContext context, String text, int x, int y, int width, int color) {
		for (var line : textRenderer.wrapLines(Text.literal(text), width)) {
			context.drawTextWithShadow(textRenderer, line, x, y, color);
			y += 11;
		}
	}

	private static String whereText(Box box) {
		if (box.dimension.equals(ENDER)) return Text.translatable("containerpeek.search.ender").getString();
		if (box.distance != Double.MAX_VALUE) return (int) box.distance + "m";
		return dimensionName(box.dimension);
	}

	private static String locationText(Box box) {
		if (box.dimension.equals(ENDER)) return Text.translatable("containerpeek.search.ender_long").getString();
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
	public boolean shouldPause() {
		return false;
	}

	// ---- Hooks for the development self-test ----

	public void testQuery(String query) {
		search.setText(query);
	}

	public void testView(boolean containers) {
		view = containers ? View.CONTAINERS : View.ITEMS;
		clearAndInit();
	}

	/** Total count of the item with this display name in the current results, or -1 if absent. */
	public int testTotal(String name) {
		return groups.stream().filter(g -> g.name.equals(name)).mapToInt(g -> g.total).findFirst().orElse(-1);
	}

	public void testHighlightAll() {
		highlightAll();
	}
}
