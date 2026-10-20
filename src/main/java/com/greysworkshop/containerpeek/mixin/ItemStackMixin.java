package com.greysworkshop.containerpeek.mixin;

import com.greysworkshop.containerpeek.ShulkerPreview;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipAppender;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Consumer;

/** Swaps a shulker box's text list of contents for the icon grid from {@link ShulkerPreview}. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "getTooltipData", at = @At("HEAD"), cancellable = true)
	private void containerpeek$shulkerPreview(CallbackInfoReturnable<Optional<TooltipData>> cir) {
		Optional<TooltipData> data = ShulkerPreview.data((ItemStack) (Object) this);
		if (data.isPresent()) cir.setReturnValue(data);
	}

	@Inject(method = "appendComponentTooltip", at = @At("HEAD"), cancellable = true)
	private <T extends TooltipAppender> void containerpeek$hideContentsText(ComponentType<T> type, Item.TooltipContext context,
			TooltipDisplayComponent display, Consumer<Text> tooltip, TooltipType tooltipType, CallbackInfo ci) {
		if (type == DataComponentTypes.CONTAINER && ShulkerPreview.applies((ItemStack) (Object) this)) ci.cancel();
	}
}
