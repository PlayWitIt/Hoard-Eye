package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.BoxPicker;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keys and letters for the box manager, ahead of anything else on the screen, like JEI's search box. */
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
	@Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
	private void hoardeye$key(long window, int action, KeyInput input, CallbackInfo ci) {
		if (BoxPicker.rawKey(window, action, input)) ci.cancel();
	}

	@Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
	private void hoardeye$typeInBoxManager(long window, CharInput input, CallbackInfo ci) {
		if (window == MinecraftClient.getInstance().getWindow().getHandle() && BoxPicker.isOpen() && input.isValidChar() && BoxPicker.typeChar(input.asString())) ci.cancel();
	}
}
