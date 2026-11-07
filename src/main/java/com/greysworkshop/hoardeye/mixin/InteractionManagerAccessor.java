package com.greysworkshop.hoardeye.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerAccessor {
	@Accessor("blockBreakingCooldown")
	void hoardeye$setBreakCooldown(int ticks);

	@Accessor("currentBreakingProgress")
	float hoardeye$getBreakingProgress();

	@Accessor("currentBreakingProgress")
	void hoardeye$setBreakingProgress(float progress);
}
