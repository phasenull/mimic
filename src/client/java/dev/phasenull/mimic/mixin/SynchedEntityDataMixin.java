package dev.phasenull.mimic.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phasenull.mimic.debug.ConnectionDebug;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Server mods add entity data fields (or change their types) on vanilla entities. A value whose type
 * doesn't match this client's field is skipped, so the rest of the packet still applies.
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {
	@WrapMethod(method = "assignValue")
	private void mimic$skipMismatched(SynchedEntityData.DataItem<?> item, SynchedEntityData.DataValue<?> value, Operation<Void> original) {
		try {
			original.call(item, value);
		} catch (IllegalStateException | ClassCastException e) {
			ConnectionDebug.note("ENTITY-DATA", "skipped field " + value.id() + ": " + e.getMessage());
		}
	}
}
