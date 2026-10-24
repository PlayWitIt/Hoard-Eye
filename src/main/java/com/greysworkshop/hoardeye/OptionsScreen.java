package com.greysworkshop.hoardeye;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * A tabbed, scrollable settings screen. Each row is a label on the left and a control on the right; hovering
 * either shows the option's description. Subclasses list their tabs and rows with {@link Rows}.
 * Labels come from {@code <prefix>.<key>} and descriptions from {@code <prefix>.<key>.desc}.
 */
public abstract class OptionsScreen extends Screen {
	private static final int ROW = 24;
	private static final int TOP = 56;
	private static final int CONTROL_WIDTH = 150;
	private static final int GOLD = 0xFFFFC040;
	private static final int WHITE = 0xFFF0F0F0;
	private static final int GRAY = 0xFF9090A8;

	protected record Tab(String key, Consumer<Rows> rows) {}

	/** One line of the list. {@code control} builds the widget at x, y, width; null for headers and notes. */
	private record Row(Component label, Component description, ControlFactory control, boolean header, boolean wide,
			java.util.function.Predicate<String> adder) {
		Row(Component label, Component description, ControlFactory control, boolean header, boolean wide) {
			this(label, description, control, header, wide, null);
		}
	}

	@FunctionalInterface
	protected interface ControlFactory {
		AbstractWidget create(int x, int y, int width);
	}

	private final Screen parent;
	private final String prefix;
	private int tab;
	private double scroll;
	private final List<Row> rows = new ArrayList<>();
	private final List<int[]> labelPositions = new ArrayList<>();

	protected OptionsScreen(Screen parent, Component title, String prefix) {
		super(title);
		this.parent = parent;
		this.prefix = prefix;
	}

	protected abstract List<Tab> tabs();

	protected abstract void save();

	/** Builder for a tab's rows. */
	protected final class Rows {
		public void header(String key) {
			rows.add(new Row(Component.translatable(prefix + "." + key), null, null, true, false));
		}

		public void note(Component text) {
			rows.add(new Row(text, null, null, false, true));
		}

		/** A toggle with a ready-made label and description instead of translation keys. */
		public void toggle(Component label, Component description, BooleanSupplier getter, Consumer<Boolean> setter) {
			rows.add(new Row(label, description, (x, y, w) -> Button.builder(onOff(getter.getAsBoolean()), b -> {
				setter.accept(!getter.getAsBoolean());
				b.setMessage(onOff(getter.getAsBoolean()));
			}).bounds(x, y, w, 20).build(), false, false));
		}

		public void toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
			Component label = Component.translatable(prefix + "." + key);
			add(key, (x, y, w) -> Button.builder(onOff(getter.getAsBoolean()), b -> {
				setter.accept(!getter.getAsBoolean());
				b.setMessage(onOff(getter.getAsBoolean()));
			}).bounds(x, y, w, 20).build(), label);
		}

		/** An integer slider; {@code format} turns the value into its display text. */
		public void slider(String key, int min, int max, IntSupplier getter, IntConsumer setter, IntFunction<String> format) {
			add(key, (x, y, w) -> new AbstractSliderButton(x, y, w, 20, Component.empty(), (getter.getAsInt() - min) / (double) (max - min)) {
				{
					updateMessage();
				}

				private int current() {
					return min + (int) Math.round(Mth.clamp(value, 0, 1) * (max - min));
				}

				@Override
				protected void updateMessage() {
					setMessage(Component.literal(format.apply(current())));
				}

				@Override
				protected void applyValue() {
					setter.accept(current());
				}
			}, Component.translatable(prefix + "." + key));
		}

		public <E extends Enum<E>> void cycle(String key, E[] values, Supplier<E> getter, Consumer<E> setter, Function<E, Component> names) {
			add(key, (x, y, w) -> Button.builder(names.apply(getter.get()), b -> {
				E next = values[(getter.get().ordinal() + 1) % values.length];
				setter.accept(next);
				b.setMessage(names.apply(next));
			}).bounds(x, y, w, 20).build(), Component.translatable(prefix + "." + key));
		}

		public void button(String key, Runnable action) {
			add(key, (x, y, w) -> Button.builder(Component.translatable(prefix + "." + key + ".button"), b -> action.run())
					.bounds(x, y, w, 20).build(), Component.translatable(prefix + "." + key));
		}

		/** A text box plus an "Add" button; {@code add} returns false if the entry was rejected. */
		public void adder(String key, java.util.function.Predicate<String> add) {
			rows.add(new Row(Component.translatable(prefix + "." + key), description(key), null, false, false, add));
		}

		/** An entry in a list with a remove button. */
		public void removable(Component label, Runnable remove) {
			rows.add(new Row(label, null, (x, y, w) -> Button.builder(Component.translatable(prefix + ".remove"), b -> {
				remove.run();
				rebuildWidgets();
			}).bounds(x + w - 60, y, 60, 20).build(), false, false));
		}

		private void add(String key, ControlFactory control, Component label) {
			rows.add(new Row(label, description(key), control, false, false));
		}

		private Component description(String key) {
			return Component.translatable(prefix + "." + key + ".desc");
		}
	}

	protected static Component onOff(boolean on) {
		return on ? CommonComponents.OPTION_ON.copy().withStyle(s -> s.withColor(0x55FF55)) : CommonComponents.OPTION_OFF.copy().withStyle(s -> s.withColor(0xFF7070));
	}

	@Override
	protected void init() {
		List<Tab> tabs = tabs();
		tab = Math.min(tab, tabs.size() - 1);

		// Tab buttons.
		int tabWidth = Math.min(96, (width - 20) / tabs.size() - 4);
		int x = width / 2 - (tabs.size() * (tabWidth + 4) - 4) / 2;
		for (int i = 0; i < tabs.size(); i++) {
			int index = i;
			Button button = Button.builder(Component.translatable(prefix + ".tab." + tabs.get(i).key()), b -> {
				tab = index;
				scroll = 0;
				rebuildWidgets();
			}).bounds(x, 28, tabWidth, 20).build();
			button.active = i != tab;
			addRenderableWidget(button);
			x += tabWidth + 4;
		}

		rows.clear();
		tabs.get(tab).rows().accept(new Rows());
		scroll = Math.min(scroll, maxScroll());
		layout();

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(width / 2 - 100, height - 26, 200, 20).build());
	}

	private int contentLeft() {
		return width / 2 - Math.min(width - 40, 440) / 2;
	}

	private int contentRight() {
		return width / 2 + Math.min(width - 40, 440) / 2;
	}

	private int listBottom() {
		return height - 32;
	}

	/** Notes are as tall as their wrapped text; everything else is one row. */
	private int rowHeight(Row row) {
		if (!row.wide) return ROW;
		return font.split(row.label, contentRight() - contentLeft()).size() * 10 + 6;
	}

	private int contentHeight() {
		int height = 0;
		for (Row row : rows) height += rowHeight(row);
		return height;
	}

	private void layout() {
		labelPositions.clear();
		int controlX = contentRight() - CONTROL_WIDTH;
		int y = TOP - (int) scroll;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int rowY = y;
			y += rowHeight(row);
			if (rowY < TOP - 2 || rowY + Math.min(20, rowHeight(row)) > listBottom()) continue;
			labelPositions.add(new int[]{i, rowY});
			if (row.adder != null) {
				// A text box with an Add button beside it.
				EditBox field = new EditBox(font, controlX + 1, rowY + 2, CONTROL_WIDTH - 46, 16, row.label);
				field.setMaxLength(64);
				if (row.description != null) field.setTooltip(Tooltip.create(row.description));
				addRenderableWidget(field);
				addRenderableWidget(Button.builder(Component.translatable(prefix + ".add"), b -> {
					if (row.adder.test(field.getValue().trim())) rebuildWidgets();
				}).bounds(controlX + CONTROL_WIDTH - 40, rowY, 40, 20).build());
				continue;
			}
			if (row.control == null) continue;
			AbstractWidget widget = row.control.create(controlX, rowY, CONTROL_WIDTH);
			if (row.description != null) widget.setTooltip(Tooltip.create(row.description));
			addRenderableWidget(widget);
		}
	}

	private double maxScroll() {
		return Math.max(0, contentHeight() - (listBottom() - TOP));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		double before = scroll;
		scroll = Math.max(0, Math.min(maxScroll(), scroll - verticalAmount * ROW));
		scroll = Math.round(scroll);
		if (scroll != before) rebuildWidgets();
		return true;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractBackground(context, mouseX, mouseY, delta);
		context.centeredText(font, title, width / 2, 12, GOLD);
		int left = contentLeft(), right = contentRight();
		context.fill(left - 6, TOP - 4, right + 6, listBottom() + 2, 0x80000000);
		for (int[] position : labelPositions) {
			Row row = rows.get(position[0]);
			int y = position[1];
			if (row.header) {
				context.text(font, row.label, left, y + 8, GOLD);
				context.fill(left, y + 18, right, y + 19, 0x40FFC040);
			} else if (row.wide) {
				int lineY = y + 2;
				for (var line : font.split(row.label, right - left)) {
					context.text(font, line, left, lineY, GRAY);
					lineY += 10;
				}
			} else {
				int labelWidth = right - left - CONTROL_WIDTH - 8;
				context.text(font, font.plainSubstrByWidth(row.label.getString(), labelWidth), left, y + 6, WHITE);
			}
		}
		// Scrollbar.
		double max = maxScroll();
		if (max > 0) {
			int top = TOP - 4, bottom = listBottom() + 2, view = bottom - top;
			int bar = Math.max(16, (int) (view * view / (view + max)));
			int barY = top + (int) ((view - bar) * scroll / max);
			context.fill(right + 8, top, right + 10, bottom, 0x30FFFFFF);
			context.fill(right + 8, barY, right + 10, barY + bar, 0xFF40E0FF);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		super.extractRenderState(context, mouseX, mouseY, delta);
		// Hovering a label shows its description too.
		int left = contentLeft(), right = contentRight();
		for (int[] position : labelPositions) {
			Row row = rows.get(position[0]);
			if (row.description == null || row.header || row.wide) continue;
			int y = position[1];
			if (mouseX >= left && mouseX < right - CONTROL_WIDTH - 8 && mouseY >= y && mouseY < y + 20) {
				context.setTooltipForNextFrame(font, font.split(row.description, 220), mouseX, mouseY);
			}
		}
	}

	/** Shows the screen's current tab; used by the self-test to screenshot each one. */
	public void showTab(int index) {
		tab = index;
		scroll = 0;
		rebuildWidgets();
	}

	public int tabCount() {
		return tabs().size();
	}

	@Override
	public void onClose() {
		save();
		minecraft.gui.setScreen(parent);
	}
}
