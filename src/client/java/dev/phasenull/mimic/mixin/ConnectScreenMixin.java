package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.bypass.neoforge.NeoForgeBypass;
import dev.phasenull.mimic.debug.ConnectionDebug;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
	@ModifyArg(method = "extractRenderState", index = 1, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private Component mimic$appendBytes(Component status) {
		String received = ConnectionDebug.receivedSummary();
		return received == null ? status : Component.empty().append(status).append(" " + received);
	}

	@Inject(method = "startConnecting", at = @At("HEAD"))
	private static void mimic$rememberServer(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data,
			boolean quickPlay, TransferState transfer, CallbackInfo ci) {
		NeoForgeBypass.onConnecting(address, data);
	}
}
