package com.greysworkshop.containerpeek;

import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.util.DyeColor;
import net.minecraft.util.collection.DefaultedList;

import java.util.List;
import java.util.Optional;

/** Shows a shulker box item's contents in its tooltip as a 9×3 grid of icons, laid out like the box itself. */
public final class ShulkerPreview {
	private static final int SLOTS = 27;
	private static final int COLUMNS = 9;
	private static final int CELL = 18;

	/** Tooltip payload: the box's slots in order, and its dye colour (or -1 for an undyed box). */
	public record Data(List<ItemStack> slots, int color) implements TooltipData {}

	private ShulkerPreview() {}

	/** True for a non-empty shulker box item, when the preview is turned on. */
	public static boolean applies(ItemStack stack) {
		ModConfig config = ModConfig.INSTANCE;
		if (!config.enabled || !config.shulkerTooltip) return false;
		if (config.shulkerTooltipShiftOnly && !net.minecraft.client.MinecraftClient.getInstance().isShiftPressed()) return false;
		if (!(Block.getBlockFromItem(stack.getItem()) instanceof ShulkerBoxBlock)) return false;
		ContainerComponent contents = stack.get(DataComponentTypes.CONTAINER);
		return contents != null && contents.streamNonEmpty().findAny().isPresent();
	}

	public static Optional<TooltipData> data(ItemStack stack) {
		if (!applies(stack)) return Optional.empty();
		DefaultedList<ItemStack> slots = DefaultedList.ofSize(SLOTS, ItemStack.EMPTY);
		stack.get(DataComponentTypes.CONTAINER).copyTo(slots);
		DyeColor dye = ((ShulkerBoxBlock) Block.getBlockFromItem(stack.getItem())).getColor();
		return Optional.of(new Data(slots, dye == null ? -1 : dye.getEntityColor()));
	}

	public record Component(Data data) implements TooltipComponent {
		@Override
		public int getHeight(TextRenderer textRenderer) {
			return rows() * CELL + 2;
		}

		@Override
		public int getWidth(TextRenderer textRenderer) {
			return COLUMNS * CELL;
		}

		private int rows() {
			return (data.slots().size() + COLUMNS - 1) / COLUMNS;
		}

		@Override
		public void drawItems(TextRenderer textRenderer, int x, int y, int width, int height, DrawContext context) {
			// Slot backgrounds, tinted with the box's colour when it's dyed.
			int slotColor = data.color() == -1 ? 0x60000000 : 0x60000000 | (data.color() & 0xFFFFFF);
			List<ItemStack> slots = data.slots();
			for (int i = 0; i < slots.size(); i++) {
				int cx = x + (i % COLUMNS) * CELL;
				int cy = y + (i / COLUMNS) * CELL;
				context.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, slotColor);
				ItemStack stack = slots.get(i);
				if (stack.isEmpty()) continue;
				context.drawItem(stack, cx, cy);
				context.drawStackOverlay(textRenderer, stack, cx, cy);
			}
		}
	}
}
