package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.ShulkerPreview;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TooltipProvider;

/** Swaps a shulker box's text list of contents for the icon grid from {@link ShulkerPreview}. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "getTooltipImage", at = @At("HEAD"), cancellable = true)
	private void hoardeye$shulkerPreview(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		Optional<TooltipComponent> data = ShulkerPreview.data((ItemStack) (Object) this);
		if (data.isPresent()) cir.setReturnValue(data);
	}

	@Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
	private <T extends TooltipProvider> void hoardeye$hideContentsText(DataComponentType<T> type, Item.TooltipContext context,
			TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag tooltipType, CallbackInfo ci) {
		if (type == DataComponents.CONTAINER && ShulkerPreview.applies((ItemStack) (Object) this)) ci.cancel();
	}
}
