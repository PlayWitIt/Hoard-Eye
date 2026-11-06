package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.BoxPicker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No tooltip from the chest behind the box picker. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenTooltipMixin {
	@Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
	private void hoardeye$hideBehindPicker(DrawContext context, int x, int y, CallbackInfo ci) {
		if (BoxPicker.isOpenOn((HandledScreen<?>) (Object) this)) ci.cancel();
	}
}
