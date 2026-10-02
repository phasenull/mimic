package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.sniffer.PacketSniffer;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
	@Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"))
	private void mimic$sniff(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
		PacketSniffer.onPayload(packet.payload());
	}
}
