package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.JoinSession;
import dev.phasenull.mimic.debug.JoinStatus;
import dev.phasenull.mimic.sniffer.PacketSniffer;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
	@Unique
	private static final int MAX_MESSAGES = 20;

	@Unique
	private static int mimic$handleErrors;

	@Shadow
	@Final
	protected Connection connection;

	@Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"))
	private void mimic$sniff(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
		PacketSniffer.onPayload(packet.payload());
	}

	/**
	 * In game, a packet that fails while being handled (e.g. entity data a server mod added) is logged and
	 * skipped instead of disconnecting. Joining/respawning failures still disconnect: nothing works after them.
	 */
	@Inject(method = "onPacketError", at = @At("HEAD"), cancellable = true)
	private void mimic$keepPlaying(Packet<?> packet, Exception error, CallbackInfo ci) {
		if (!((Object) this instanceof ClientPacketListener) || !ConnectionDebug.isTracked(connection)
				|| packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
			return;
		}
		String what = packet.type().id() + ": " + (error.getMessage() == null ? error.toString() : error.getMessage());
		ConnectionDebug.note("HANDLE-SKIP", what);
		JoinSession.skipped("handling " + packet.type().id());
		if (++mimic$handleErrors <= MAX_MESSAGES) {
			JoinStatus.info("[Play] Ignored a packet that failed to apply: {}", what);
		}
		ci.cancel();
	}
}
