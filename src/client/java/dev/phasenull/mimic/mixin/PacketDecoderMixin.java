package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.debug.ConnectionDebug;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
	@Unique
	private int mimic$frameBytes;

	@Inject(method = "decode", at = @At("HEAD"))
	private void mimic$measure(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
		mimic$frameBytes = in.readableBytes();
	}

	@Inject(method = "decode", at = @At("RETURN"))
	private void mimic$count(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
		if (ConnectionDebug.isTrackedChannel(ctx.channel()) && !out.isEmpty() && out.getLast() instanceof Packet<?> packet
				&& packet.type().flow() == PacketFlow.CLIENTBOUND) {
			ConnectionDebug.countBytes(packet, mimic$frameBytes);
		}
	}
}
