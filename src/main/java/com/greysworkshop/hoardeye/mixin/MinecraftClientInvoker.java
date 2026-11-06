package com.greysworkshop.hoardeye.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The game's own right-click, exactly as pressing the key does it. */
@Mixin(MinecraftClient.class)
public interface MinecraftClientInvoker {
	@Invoker("doItemUse")
	void hoardeye$doItemUse();
}
