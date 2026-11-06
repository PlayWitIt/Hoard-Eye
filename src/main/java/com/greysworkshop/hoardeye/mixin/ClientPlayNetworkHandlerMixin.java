package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.BoxGrab;
import com.greysworkshop.hoardeye.Sight;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.world.WorldEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server telling us a block really broke, or blew up: what a chest hider never sends. (At the end: on the game thread.) */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Inject(method = "onWorldEvent", at = @At("TAIL"))
	private void hoardeye$blockBroken(WorldEventS2CPacket packet, CallbackInfo ci) {
		if (packet.getEventId() == WorldEvents.BLOCK_BROKEN) Sight.broken(packet.getPos());
	}

	/** Called first on the network thread, which then hands over to the game thread and calls it again. */
	@Inject(method = "onOpenScreen", at = @At("HEAD"), cancellable = true)
	private void hoardeye$openScreen(OpenScreenS2CPacket packet, CallbackInfo ci) {
		if (MinecraftClient.getInstance().isOnThread() && BoxGrab.onOpenScreen(packet.getSyncId(), packet.getScreenHandlerType())) ci.cancel();
	}

	@Inject(method = "onInventory", at = @At("TAIL"))
	private void hoardeye$inventory(InventoryS2CPacket packet, CallbackInfo ci) {
		BoxGrab.onInventory(packet.syncId());
	}

	@Inject(method = "onExplosion", at = @At("TAIL"))
	private void hoardeye$explosion(ExplosionS2CPacket packet, CallbackInfo ci) {
		Sight.exploded(packet.center(), packet.radius());
	}
}
