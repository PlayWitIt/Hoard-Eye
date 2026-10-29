package com.greysworkshop.hoardeye.mixin;

import com.greysworkshop.hoardeye.Sight;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.world.level.block.LevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server telling us a block really broke, or blew up: what a chest hider never sends. (At the end: on the game thread.) */
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
	@Inject(method = "handleLevelEvent", at = @At("TAIL"))
	private void hoardeye$blockBroken(ClientboundLevelEventPacket packet, CallbackInfo ci) {
		if (packet.getType() == LevelEvent.PARTICLES_DESTROY_BLOCK) Sight.broken(packet.getPos());
	}

	@Inject(method = "handleExplosion", at = @At("TAIL"))
	private void hoardeye$explosion(ClientboundExplodePacket packet, CallbackInfo ci) {
		Sight.exploded(packet.center(), packet.radius());
	}
}
