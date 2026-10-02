package dev.phasenull.mimic.mixin;

import dev.phasenull.mimic.bypass.ViaPassthrough;
import dev.phasenull.mimic.placeholder.Placeholders;
import dev.phasenull.mimic.debug.JoinSession;
import dev.phasenull.mimic.debug.JoinStatus;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler;
import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fabric servers send their full registry id map (fabric:registry/sync) and Fabric API aborts the join if
 * the client lacks a registry or entry. Unknown registries and entries are dropped first, so everything
 * this client does have still gets the server's ids.
 */
@Mixin(value = ClientRegistrySyncHandler.class, remap = false)
public abstract class ClientRegistrySyncHandlerMixin {
	@ModifyVariable(method = "apply", at = @At("HEAD"), argsOnly = true)
	private static RegistrySyncPayload mimic$dropUnknown(RegistrySyncPayload payload) {
		Map<Identifier, Object2IntMap<Identifier>> kept = new LinkedHashMap<>();
		int droppedRegistries = 0;
		int droppedEntries = 0;
		int placeholders = 0;
		JoinSession.kind("Fabric");
		for (Map.Entry<Identifier, Object2IntMap<Identifier>> registry : payload.registryMap().entrySet()) {
			Registry<?> local = BuiltInRegistries.REGISTRY.getValue(registry.getKey());
			String registryId = registry.getKey().toString();
			registry.getValue().keySet().forEach(id -> JoinSession.entry(registryId, id.toString(), local != null && Placeholders.hasReal(local, id)));
			if (local == null) {
				droppedRegistries++;
				continue;
			}
			Object2IntMap<Identifier> entries = new Object2IntLinkedOpenHashMap<>();
			for (Object2IntMap.Entry<Identifier> entry : registry.getValue().object2IntEntrySet()) {
				if (local.containsKey(entry.getKey())) {
					entries.put(entry.getKey(), entry.getIntValue());
				} else if (Placeholders.ensure(local, entry.getKey())) {
					// Server-only block/item/entity type: a placeholder now holds its id.
					entries.put(entry.getKey(), entry.getIntValue());
					placeholders++;
				} else {
					droppedEntries++;
				}
			}
			kept.put(registry.getKey(), entries);
			ViaPassthrough.synced(registryId);
		}
		if (placeholders > 0) {
			JoinStatus.info("[Fabric] Registry sync: {} server-only blocks/items/entities got placeholders", placeholders);
		}
		if (droppedRegistries == 0 && droppedEntries == 0 && placeholders == 0) {
			return payload;
		}
		JoinStatus.info("[Fabric] Registry sync: skipped {} unknown registries and {} unknown entries", droppedRegistries, droppedEntries);
		return new RegistrySyncPayload(kept, payload.registryAttributes());
	}
}
