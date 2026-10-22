package com.greysworkshop.hoardeye.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's panel is drawn, so fetch-route items can be highlighted in their slots. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("x")
	int hoardeye$getX();

	@Accessor("y")
	int hoardeye$getY();
}
