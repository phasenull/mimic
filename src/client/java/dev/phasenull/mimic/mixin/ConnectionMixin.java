package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.debug.ConnectionDebug;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
	@Shadow
	public abstract PacketFlow getReceiving();

	@Shadow
	public abstract String getLoggableAddress(boolean logIps);

	private boolean mimic$isClient() {
		return getReceiving() == PacketFlow.CLIENTBOUND;
	}

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void mimic$in(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		if (mimic$isClient()) {
			ConnectionDebug.received(packet);
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
	private void mimic$out(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		if (mimic$isClient()) {
			ConnectionDebug.sent(packet);
		}
	}

	@Inject(method = "setupInboundProtocol", at = @At("HEAD"))
	private void mimic$inbound(ProtocolInfo<?> info, PacketListener listener, CallbackInfo ci) {
		if (mimic$isClient()) {
			ConnectionDebug.protocol("in", name(info.id()));
		}
	}

	@Inject(method = "setupOutboundProtocol", at = @At("HEAD"))
	private void mimic$outbound(ProtocolInfo<?> info, CallbackInfo ci) {
		if (!mimic$isClient()) {
			return;
		}
		if (info.id() == ConnectionProtocol.HANDSHAKING) {
			ConnectionDebug.begin(getLoggableAddress(true));
		}
		ConnectionDebug.protocol("out", name(info.id()));
	}

	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
	private void mimic$disconnect(DisconnectionDetails details, CallbackInfo ci) {
		if (mimic$isClient()) {
			ConnectionDebug.disconnected(details.reason().getString());
		}
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"))
	private void mimic$error(ChannelHandlerContext ctx, Throwable t, CallbackInfo ci) {
		if (mimic$isClient()) {
			ConnectionDebug.error(t);
		}
	}

	private static String name(ConnectionProtocol protocol) {
		return protocol.name().toLowerCase(Locale.ROOT);
	}
}
