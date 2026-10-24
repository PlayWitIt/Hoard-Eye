package com.greysworkshop.hoardeye;

import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/** Shows a shulker box item's contents in its tooltip as a 9×3 grid of icons, laid out like the box itself. */
public final class ShulkerPreview {
	private static final int SLOTS = 27;
	private static final int COLUMNS = 9;
	private static final int CELL = 18;

	/** Tooltip payload: the box's slots in order, and its dye colour (or -1 for an undyed box). */
	public record Data(List<ItemStack> slots, int color) implements TooltipComponent {}

	private ShulkerPreview() {}

	/** True for a non-empty shulker box item, when the preview is turned on. */
	public static boolean applies(ItemStack stack) {
		ModConfig config = ModConfig.INSTANCE;
		if (!config.enabled || !config.shulkerTooltip) return false;
		if (config.shulkerTooltipShiftOnly && !net.minecraft.client.Minecraft.getInstance().hasShiftDown()) return false;
		if (!(Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock)) return false;
		ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
		return contents != null && contents.nonEmptyItemCopyStream().findAny().isPresent();
	}

	public static Optional<TooltipComponent> data(ItemStack stack) {
		if (!applies(stack)) return Optional.empty();
		NonNullList<ItemStack> slots = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
		stack.get(DataComponents.CONTAINER).copyInto(slots);
		DyeColor dye = ((ShulkerBoxBlock) Block.byItem(stack.getItem())).getColor();
		return Optional.of(new Data(slots, dye == null ? -1 : dye.getTextureDiffuseColor()));
	}

	public record Component(Data data) implements ClientTooltipComponent {
		@Override
		public int getHeight(Font textRenderer) {
			return rows() * CELL + 2;
		}

		@Override
		public int getWidth(Font textRenderer) {
			return COLUMNS * CELL;
		}

		private int rows() {
			return (data.slots().size() + COLUMNS - 1) / COLUMNS;
		}

		@Override
		public void extractImage(Font textRenderer, int x, int y, int width, int height, GuiGraphicsExtractor context) {
			// Slot backgrounds, tinted with the box's colour when it's dyed.
			int slotColor = data.color() == -1 ? 0x60000000 : 0x60000000 | (data.color() & 0xFFFFFF);
			List<ItemStack> slots = data.slots();
			for (int i = 0; i < slots.size(); i++) {
				int cx = x + (i % COLUMNS) * CELL;
				int cy = y + (i / COLUMNS) * CELL;
				context.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, slotColor);
				ItemStack stack = slots.get(i);
				if (stack.isEmpty()) continue;
				context.item(stack, cx, cy);
				context.itemDecorations(textRenderer, stack, cx, cy);
			}
		}
	}
}
