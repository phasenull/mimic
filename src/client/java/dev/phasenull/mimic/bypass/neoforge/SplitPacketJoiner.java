package dev.phasenull.mimic.bypass.neoforge;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.JoinStatus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import java.nio.charset.StandardCharsets;

/**
 * Joins NeoForge's split packets ({@code neoforge:split}). A NeoForge server cuts any packet over 8 MB
 * (e.g. a big modpack's recipe list) into parts, and only for clients that claimed that channel; without
 * it the server fails to send the packet and drops the connection with no message.
 * <p>
 * Sits right after decompression and before ViaFabricPlus: the parts hold the packet in the server's
 * protocol, so the joined frame is passed on as if it had arrived whole, and Via translates it as usual.
 */
public final class SplitPacketJoiner extends ChannelInboundHandlerAdapter {
	public static final String NAME = "mimic-split-joiner";
	private static final byte[] CHANNEL = "neoforge:split".getBytes(StandardCharsets.US_ASCII);
	private static final byte STATE_FIRST = 1;
	private static final byte STATE_LAST = 2;

	private CompositeByteBuf parts;
	private int count;

	/** Adds the joiner to a connection's pipeline, before Via's decoder (or the vanilla one without Via). */
	public static void install(Channel channel) {
		ChannelPipeline pipeline = channel.pipeline();
		if (pipeline.get(NAME) != null) {
			return;
		}
		String before = pipeline.get("via-decoder") != null ? "via-decoder" : "inbound_config";
		if (pipeline.get(before) == null) {
			MimicClient.LOGGER.warn("[NeoForge] Can't install the split packet joiner: no {} in {}", before, pipeline.names());
			return;
		}
		pipeline.addBefore(before, NAME, new SplitPacketJoiner());
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		if (!(msg instanceof ByteBuf buf) || !isSplitPart(buf)) {
			ctx.fireChannelRead(msg);
			return;
		}
		try {
			int length = readVarInt(buf);
			byte state = buf.readByte();
			if (state == STATE_FIRST) {
				reset();
			}
			if (parts == null) {
				parts = ctx.alloc().compositeBuffer(Integer.MAX_VALUE);
			}
			parts.addComponent(true, buf.readRetainedSlice(length - 1));
			count++;
			if (state == STATE_LAST) {
				CompositeByteBuf whole = parts;
				int pieces = count;
				parts = null;
				count = 0;
				ConnectionDebug.note("SPLIT", "joined " + pieces + " parts into " + whole.readableBytes() + " bytes");
				JoinStatus.info("[NeoForge] Joined a split packet ({} parts, {} KB)", pieces, whole.readableBytes() / 1024);
				ctx.fireChannelRead(whole);
			}
		} finally {
			buf.release();
		}
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) {
		reset();
	}

	private void reset() {
		if (parts != null) {
			parts.release();
			parts = null;
		}
		count = 0;
	}

	/**
	 * A custom payload frame on {@code neoforge:split}: packet id, then the channel id as a string. On a
	 * match the reader is left at the payload; otherwise it's untouched.
	 */
	private static boolean isSplitPart(ByteBuf buf) {
		int start = buf.readerIndex();
		try {
			if (!skipVarInt(buf) || !buf.isReadable() || buf.readByte() != CHANNEL.length || buf.readableBytes() < CHANNEL.length) {
				buf.readerIndex(start);
				return false;
			}
			for (byte b : CHANNEL) {
				if (buf.readByte() != b) {
					buf.readerIndex(start);
					return false;
				}
			}
			return true;
		} catch (RuntimeException e) {
			buf.readerIndex(start);
			return false;
		}
	}

	private static boolean skipVarInt(ByteBuf buf) {
		for (int i = 0; i < 5; i++) {
			if (!buf.isReadable()) {
				return false;
			}
			if ((buf.readByte() & 0x80) == 0) {
				return true;
			}
		}
		return false;
	}

	private static int readVarInt(ByteBuf buf) {
		int value = 0;
		for (int i = 0; i < 5; i++) {
			byte b = buf.readByte();
			value |= (b & 0x7F) << (7 * i);
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new IllegalStateException("VarInt too big");
	}
}
