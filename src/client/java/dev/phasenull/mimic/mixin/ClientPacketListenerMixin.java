package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.bypass.ReconfigLoopGuard;
import dev.phasenull.mimic.debug.ConnectionDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	/** Leaves a proxy that keeps bouncing this client between backends (see {@link ReconfigLoopGuard}). */
	@Inject(method = "handleConfigurationStart", at = @At("HEAD"), cancellable = true)
	private void mimic$stopReconfigLoop(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
		// The handler first runs on the network thread only to reschedule itself; count the render-thread run.
		ClientCommonPacketListenerImpl self = (ClientCommonPacketListenerImpl) (Object) this;
		if (!Minecraft.getInstance().isSameThread() || !ConnectionDebug.isTracked(((ClientCommonPacketListenerImplAccessor) self).mimic$connection())) {
			return;
		}
		if (ReconfigLoopGuard.onReconfigure()) {
			((ClientCommonPacketListenerImplAccessor) self).mimic$connection().disconnect(Component.literal(ReconfigLoopGuard.reason()));
			ci.cancel();
		}
	}
}
