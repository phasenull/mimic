package dev.phasenull.mimic.bypass;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A custom payload kept as raw bytes, for channels whose format we read and write by hand. */
public record RawPayload(Type<RawPayload> type, byte[] data) implements CustomPacketPayload {
	public static Type<RawPayload> type(String namespace, String path) {
		return new Type<>(Identifier.fromNamespaceAndPath(namespace, path));
	}

	public static StreamCodec<FriendlyByteBuf, RawPayload> codec(Type<RawPayload> type) {
		return StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data()),
			buf -> {
				byte[] data = new byte[buf.readableBytes()];
				buf.readBytes(data);
				return new RawPayload(type, data);
			});
	}
}
