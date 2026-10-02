package dev.phasenull.mimic.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.JoinSession;
import dev.phasenull.mimic.debug.JoinStatus;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
	@Unique
	private static final int MAX_SKIP_MESSAGES = 20;

	@Unique
	private static int mimic$skipped;

	@Shadow
	@Final
	private ProtocolInfo<?> protocolInfo;

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

	/**
	 * On the joining connection, a play packet this client can't decode (it references server-only mod
	 * content) is skipped instead of disconnecting. Each call holds exactly one framed packet, so skipping
	 * its remaining bytes can't misalign the stream.
	 * <p>
	 * In configuration only "unknown packet id" is skipped: when a proxy switches servers it can still send
	 * play packets after start_configuration, which then arrive once the client reads configuration.
	 */
	@WrapMethod(method = "decode")
	private void mimic$skipUndecodable(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, Operation<Void> original) {
		ConnectionProtocol protocol = protocolInfo.id();
		if (protocolInfo.flow() != PacketFlow.CLIENTBOUND
				|| (protocol != ConnectionProtocol.PLAY && protocol != ConnectionProtocol.CONFIGURATION)
				|| !ConnectionDebug.isTrackedChannel(ctx.channel())) {
			original.call(ctx, in, out);
			return;
		}
		int size = in.readableBytes();
		try {
			original.call(ctx, in, out);
		} catch (Exception e) {
			String what = e.getMessage() == null ? e.toString() : e.getMessage();
			if (protocol == ConnectionProtocol.CONFIGURATION && !what.contains("unknown packet id")) {
				throw e;
			}
			in.skipBytes(in.readableBytes());
			ConnectionDebug.note("SKIP", size + " bytes: " + what);
			int quote = what.indexOf('\'');
			int end = quote >= 0 ? what.indexOf('\'', quote + 1) : -1;
			JoinSession.skipped(end > quote ? what.substring(quote + 1, end) : what.substring(0, Math.min(what.length(), 80)));
			if (++mimic$skipped <= MAX_SKIP_MESSAGES) {
				JoinStatus.info("[Play] Skipped a packet this client can't read: {}", what);
			}
		}
	}
}
