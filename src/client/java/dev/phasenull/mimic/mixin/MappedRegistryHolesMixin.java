package dev.phasenull.mimic.mixin;

import com.google.common.collect.Iterators;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import it.unimi.dsi.fastutil.objects.ObjectList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * After a Fabric registry sync with server-only entries left out, a registry's id table has holes (the
 * server ids of those entries). Iteration skips holes, and Fabric's remap (also used to restore the
 * client's ids on disconnect) sees a hole as a dummy entry no id map contains, instead of failing on it.
 * Priority above Fabric API's mixin so its added remap method can be targeted.
 */
@Mixin(value = MappedRegistry.class, priority = 1500)
public abstract class MappedRegistryHolesMixin<T> {
	@Shadow
	@Final
	private ObjectList<Holder.Reference<T>> byId;

	@Inject(method = "iterator", at = @At("HEAD"), cancellable = true)
	private void mimic$skipHoles(CallbackInfoReturnable<Iterator<T>> cir) {
		cir.setReturnValue(Iterators.transform(Iterators.filter(byId.iterator(), Objects::nonNull), Holder::value));
	}

	@Inject(method = "listElements", at = @At("HEAD"), cancellable = true)
	private void mimic$skipHolesInStream(CallbackInfoReturnable<Stream<Holder.Reference<T>>> cir) {
		cir.setReturnValue(byId.stream().filter(Objects::nonNull));
	}

	@SuppressWarnings("unchecked")
	@WrapOperation(method = "remap", remap = false, require = 0, at = @At(value = "INVOKE",
		target = "Lit/unimi/dsi/fastutil/objects/ObjectList;get(I)Ljava/lang/Object;"))
	private Object mimic$holeAsDummy(ObjectList<?> list, int index, Operation<Object> original) {
		Object value = original.call(list, index);
		if (value != null || list != byId) {
			return value;
		}
		ResourceKey<? extends Registry<T>> registry = ((Registry<T>) (Object) this).key();
		return Holder.Reference.createStandAlone((HolderOwner<T>) (Object) this,
			ResourceKey.create(registry, Identifier.fromNamespaceAndPath("mimic", "hole_" + index)));
	}
}
