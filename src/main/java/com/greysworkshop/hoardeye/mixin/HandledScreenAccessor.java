package com.greysworkshop.hoardeye.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's panel is drawn, and the slot under the mouse. */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("x")
	int hoardeye$getX();

	@Accessor("y")
	int hoardeye$getY();

	@Accessor("focusedSlot")
	Slot hoardeye$getFocusedSlot();
}
