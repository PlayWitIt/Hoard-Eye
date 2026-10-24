package com.greysworkshop.hoardeye.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's panel is drawn, so fetch-route items can be highlighted in their slots. */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
	@Accessor("leftPos")
	int hoardeye$getX();

	@Accessor("topPos")
	int hoardeye$getY();
}
