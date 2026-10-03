package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.privacy.PrivacyGuard;
import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The client brand sent to servers (and shown in crash reports), as chosen in Privacy. */
@Mixin(ClientBrandRetriever.class)
public abstract class ClientBrandRetrieverMixin {
	@Inject(method = "getClientModName", at = @At("RETURN"), cancellable = true)
	private static void mimic$brand(CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(PrivacyGuard.brand(cir.getReturnValue()));
	}
}
