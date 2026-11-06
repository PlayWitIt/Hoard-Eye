package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.BoxPicker;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right at the start: other mods' overlays on the same screen, like JEI's, would otherwise take clicks meant for the box manager. */
@Mixin(Mouse.class)
public abstract class MouseMixin {
	@Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
	private void hoardeye$button(long window, MouseInput input, int action, CallbackInfo ci) {
		if (BoxPicker.rawMouseButton(window, input, action)) ci.cancel();
	}

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void hoardeye$scroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		if (BoxPicker.rawMouseScroll(window, vertical)) ci.cancel();
	}
}
