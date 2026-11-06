package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.BoxGrab;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game cancels any break each tick the attack key isn't held, which would restart the box's break forever. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class InteractionManagerMixin {
	@Inject(method = "cancelBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void hoardeye$keepBreakingBox(CallbackInfo ci) {
		if (BoxGrab.holdingBreak()) ci.cancel();
	}
}
