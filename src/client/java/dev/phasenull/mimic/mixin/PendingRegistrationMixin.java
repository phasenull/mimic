package dev.phasenull.mimic.mixin;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Decoder;
import dev.phasenull.mimic.bypass.RegistryStandIns;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
public abstract class PendingRegistrationMixin {
	@Inject(method = "loadFromNetwork", at = @At("RETURN"))
	private static void mimic$recordResult(Decoder<?> decoder, RegistryOps<Tag> ops, ResourceKey<?> key, Tag tag,
			CallbackInfoReturnable<Either<?, Exception>> cir) {
		RegistryStandIns.onDecoded(decoder, ops, key, tag, cir.getReturnValue());
	}
}
