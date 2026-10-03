package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.debug.PacketFeed;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With the packet feed on, lists every sound this client starts, and whether the engine played it. */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play", at = @At("RETURN"))
	private void mimic$feed(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (PacketFeed.enabled()) {
			PacketFeed.sound(sound.getIdentifier() + " " + cir.getReturnValue());
		}
	}
}
