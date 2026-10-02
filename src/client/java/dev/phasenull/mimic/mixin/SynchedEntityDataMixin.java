package dev.phasenull.mimic.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phasenull.mimic.debug.ConnectionDebug;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Server mods add entity data fields (or change their types) on vanilla entities, and placeholder entities
 * have none of a modded entity's fields. Values for fields this client doesn't have, or of another type,
 * are skipped so the rest of the packet still applies.
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {
	@Shadow
	@Final
	private SynchedEntityData.DataItem<?>[] itemsById;

	@WrapMethod(method = "assignValues")
	private void mimic$skipUnknownFields(List<SynchedEntityData.DataValue<?>> values, Operation<Void> original) {
		List<SynchedEntityData.DataValue<?>> known = values.stream()
			.filter(v -> v.id() >= 0 && v.id() < itemsById.length && itemsById[v.id()] != null).toList();
		original.call(known.size() == values.size() ? values : known);
	}

	@WrapMethod(method = "assignValue")
	private void mimic$skipMismatched(SynchedEntityData.DataItem<?> item, SynchedEntityData.DataValue<?> value, Operation<Void> original) {
		try {
			original.call(item, value);
		} catch (IllegalStateException | ClassCastException e) {
			ConnectionDebug.note("ENTITY-DATA", "skipped field " + value.id() + ": " + e.getMessage());
		}
	}
}
