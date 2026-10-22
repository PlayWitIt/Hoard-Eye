package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.HoardEyeClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block the server says changed, seen before the change so what was there can still be read. */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
	@Inject(method = "handleBlockUpdate", at = @At("HEAD"))
	private void hoardeye$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		HoardEyeClient mod = HoardEyeClient.INSTANCE;
		if (mod == null) return;
		try {
			mod.blockChanged((ClientWorld) (Object) this, pos, state);
		} catch (RuntimeException e) {
			HoardEyeClient.LOGGER.error("Could not handle a block change", e);
		}
	}
}
