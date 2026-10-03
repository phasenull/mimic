package dev.phasenull.mimic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phasenull.mimic.bypass.forge.ForgeBypass;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lets {@link ForgeBypass} add the Forge client marker to the handshake host when logging in. */
@Mixin(Connection.class)
public abstract class ConnectionHandshakeMixin {
	@WrapOperation(method = "lambda$initiateServerboundConnection$0", at = @At(value = "NEW",
		target = "(ILjava/lang/String;ILnet/minecraft/network/protocol/handshake/ClientIntent;)Lnet/minecraft/network/protocol/handshake/ClientIntentionPacket;"))
	private ClientIntentionPacket mimic$forgeMarker(int protocol, String host, int port, ClientIntent intent, Operation<ClientIntentionPacket> original) {
		String marked = intent == ClientIntent.LOGIN ? ForgeBypass.handshakeHost(host) : host;
		return original.call(protocol, marked, port, intent);
	}
}
