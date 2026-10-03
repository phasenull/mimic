package dev.phasenull.mimic.bypass.forge;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.bypass.ViaBridge;
import dev.phasenull.mimic.debug.JoinStatus;
import dev.phasenull.mimic.placeholder.Placeholders;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A legacy Forge server's numbering of its mods' blocks and items, from the registry snapshots it sends
 * during login. Forge numbers vanilla entries exactly like vanilla and puts mods' after them, so ViaVersion
 * translates the vanilla ids fine but has no idea about the rest (it drops them). This maps those ids to
 * Mimic's placeholders: a block-state id past the server version's vanilla states is walked through the mod
 * blocks in server order (state counts as guessed for the placeholders), an item id past vanilla's items
 * straight to its placeholder.
 * <p>
 * Applied at the first ViaVersion step only (the server's own version); later steps see ids already in
 * this client's numbering, past their tables, and leave them alone.
 */
public final class ForgeTable {
	/** Server registry -> (entry -> server id). */
	private static final Map<Identifier, Map<Identifier, Integer>> REGISTRIES = new LinkedHashMap<>();

	private record Run(int first, int count, Block block) {}

	private static volatile boolean active;
	private static volatile Object firstMapping;
	private static volatile int vanillaStates;
	private static volatile int vanillaItems;
	private static volatile List<Run> runs = List.of();
	private static volatile Map<Integer, Integer> items = Map.of();
	private static final Map<Object, int[]> SIZES = new IdentityHashMap<>();

	private ForgeTable() {}

	public static synchronized void reset() {
		REGISTRIES.clear();
		active = false;
		firstMapping = null;
		runs = List.of();
		items = Map.of();
		synchronized (SIZES) {
			SIZES.clear();
		}
	}

	/** One registry snapshot from the server: placeholders for what this client lacks. Runs on the client thread. */
	public static synchronized void registry(Identifier name, Map<Identifier, Integer> ids) {
		REGISTRIES.put(name, ids);
		Placeholders.ensureUnknownBlock();
		Registry<?> local = BuiltInRegistries.REGISTRY.getValue(name);
		if (local == null) {
			return;
		}
		int added = 0;
		for (Identifier id : ids.keySet()) {
			if (!local.containsKey(id) && Placeholders.ensure(local, id)) {
				added++;
			}
		}
		if (added > 0) {
			JoinStatus.info("[Forge] {}: {} server-only entries got placeholders", name, added);
		}
		if (name.getPath().equals("block") || name.getPath().equals("item")) {
			build();
		}
	}

	private static void build() {
		try {
			Object first = ViaBridge.firstMappingData();
			if (first == null) {
				return;
			}
			int[] size = sizes(first);
			vanillaStates = size[0];
			vanillaItems = size[1];
			Map<Identifier, Integer> blocks = REGISTRIES.getOrDefault(Identifier.withDefaultNamespace("block"), Map.of());
			List<Map.Entry<Identifier, Integer>> modBlocks = new ArrayList<>(blocks.entrySet().stream()
				.filter(e -> !e.getKey().getNamespace().equals("minecraft")).toList());
			modBlocks.sort(Comparator.comparingInt(Map.Entry::getValue));
			List<Run> table = new ArrayList<>();
			int next = vanillaStates;
			for (Map.Entry<Identifier, Integer> e : modBlocks) {
				if (!BuiltInRegistries.BLOCK.containsKey(e.getKey())) {
					continue;
				}
				Block block = BuiltInRegistries.BLOCK.getValue(e.getKey());
				int count = block.getStateDefinition().getPossibleStates().size();
				table.add(new Run(next, count, block));
				next += count;
			}
			runs = List.copyOf(table);
			Map<Integer, Integer> itemMap = new HashMap<>();
			REGISTRIES.getOrDefault(Identifier.withDefaultNamespace("item"), Map.of()).forEach((id, serverId) -> {
				if (serverId >= vanillaItems && BuiltInRegistries.ITEM.containsKey(id)) {
					itemMap.put(serverId, BuiltInRegistries.ITEM.getId(BuiltInRegistries.ITEM.getValue(id)));
				}
			});
			items = Map.copyOf(itemMap);
			firstMapping = first;
			active = true;
			JoinStatus.info("[Forge] Mod id table: {} mod blocks ({} states after vanilla's {}), {} mod items", table.size(),
				next - vanillaStates, vanillaStates, itemMap.size());
		} catch (ReflectiveOperationException | RuntimeException e) {
			MimicClient.LOGGER.warn("[Forge] Could not build the mod id table", e);
		}
	}

	/** Block-state and item table sizes of a ViaVersion mapping step. */
	private static int[] sizes(Object mapping) throws ReflectiveOperationException {
		synchronized (SIZES) {
			int[] cached = SIZES.get(mapping);
			if (cached == null) {
				cached = new int[] {ViaBridge.size(mapping, "getBlockStateMappings"), ViaBridge.size(mapping, "getItemMappings")};
				SIZES.put(mapping, cached);
			}
			return cached;
		}
	}

	/** This client's id for a server block-state id (null: let ViaVersion map it). */
	public static Integer state(Object mapping, int id) {
		if (!active) {
			return null;
		}
		try {
			if (mapping != firstMapping) {
				return id >= sizes(mapping)[0] ? id : null;
			}
			if (id < vanillaStates) {
				return null;
			}
			for (Run run : runs) {
				if (id < run.first() + run.count()) {
					BlockState state = run.block().getStateDefinition().getPossibleStates().get(id - run.first());
					return Block.getId(state);
				}
			}
			BlockState unknown = Placeholders.unknownState();
			return unknown == null ? 0 : Block.getId(unknown);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** This client's id for a server item id (null: let ViaVersion map it). */
	public static Integer item(Object mapping, int id) {
		if (!active) {
			return null;
		}
		try {
			if (mapping != firstMapping) {
				return id >= sizes(mapping)[1] ? id : null;
			}
			return id < vanillaItems ? null : items.getOrDefault(id, 0);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
