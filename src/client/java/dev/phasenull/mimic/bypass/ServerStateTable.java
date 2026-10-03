package dev.phasenull.mimic.bypass;

import dev.phasenull.mimic.MimicClient;
import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.debug.JoinStatus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
import net.minecraft.SharedConstants;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block-state ids as an older Fabric server numbers them, when ViaFabricPlus translates (with block ids
 * passed through, see {@link ViaPassthrough}). Fabric numbers states by walking the server's blocks with
 * this client's state definitions, which is wrong wherever a vanilla block's states changed between the
 * server's version and this one; everything after it then shifts (server-only blocks show up as random
 * vanilla ones).
 * <p>
 * This table walks the server's blocks in the server's order too, but gives vanilla blocks of the server's
 * version their exact states from ViaVersion's mapping data (each old state's equivalent in this version),
 * and every other block (mods' placeholders, backported blocks) the client's own states. Read through
 * reflection, so nothing happens without ViaFabricPlus.
 */
public final class ServerStateTable {
	/** This client's vanilla states, by vanilla id (taken at startup, before any server renumbers them). */
	private static List<BlockState> vanillaStates = List.of();
	/** Blocks the server listed in its registry sync. */
	private static final Set<Identifier> SERVER_BLOCKS = Collections.synchronizedSet(new HashSet<>());
	private static volatile boolean applying;

	private ServerStateTable() {}

	public static void register() {
		List<BlockState> states = new ArrayList<>();
		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			states.add(state);
		}
		vanillaStates = List.copyOf(states);
		// Registered after Fabric's own state tracker, so this runs once Fabric has renumbered.
		RegistryIdRemapCallback.event(BuiltInRegistries.BLOCK).register(state -> {
			if (applying) {
				rebuild();
			}
		});
		// Applied by the time play starts; Fabric's renumbering on disconnect must stay its own.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> applying = false);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> applying = false);
	}

	/** Called around a server's registry ids being applied. */
	public static void applying(boolean now, Set<Identifier> serverBlocks) {
		applying = now;
		if (now) {
			SERVER_BLOCKS.clear();
			SERVER_BLOCKS.addAll(serverBlocks);
		}
	}

	private static void rebuild() {
		if (!ViaPassthrough.active("minecraft:block")) {
			return;
		}
		try {
			List<Object> protocols = clientboundProtocols();
			if (protocols.isEmpty()) {
				return;
			}
			Map<String, List<BlockState>> vanilla = serverVanillaStates(protocols);
			install(vanilla);
		} catch (ReflectiveOperationException | RuntimeException e) {
			MimicClient.LOGGER.warn("[States] Could not build the server's block-state table", e);
		}
	}

	/** Via protocols from the server's version to this one, in the order a clientbound packet goes through. */
	private static List<Object> clientboundProtocols() throws ReflectiveOperationException {
		Object connection = ConnectionDebug.trackedConnection();
		if (connection == null) {
			return List.of();
		}
		Method targetGetter;
		try {
			targetGetter = connection.getClass().getMethod("viaFabricPlus$getTargetVersion");
		} catch (NoSuchMethodException e) {
			return List.of();
		}
		Object target = targetGetter.invoke(connection);
		if (target == null) {
			return List.of();
		}
		ClassLoader loader = target.getClass().getClassLoader();
		Class<?> versionClass = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion", false, loader);
		Object client = versionClass.getMethod("getProtocol", int.class).invoke(null, SharedConstants.getProtocolVersion());
		if ((int) versionClass.getMethod("getVersion").invoke(target) == (int) versionClass.getMethod("getVersion").invoke(client)) {
			return List.of();
		}
		Object manager = Class.forName("com.viaversion.viaversion.api.Via", false, loader).getMethod("getManager").invoke(null);
		Object protocolManager = manager.getClass().getMethod("getProtocolManager").invoke(manager);
		Method pathMethod = Class.forName("com.viaversion.viaversion.api.protocol.ProtocolManager", false, loader)
			.getMethod("getProtocolPath", versionClass, versionClass);
		List<?> path = (List<?>) pathMethod.invoke(protocolManager, client, target);
		if (path == null) {
			return List.of();
		}
		Method protocolOf = Class.forName("com.viaversion.viaversion.api.protocol.ProtocolPathEntry", false, loader).getMethod("protocol");
		List<Object> protocols = new ArrayList<>();
		for (Object entry : path) {
			protocols.add(protocolOf.invoke(entry));
		}
		// The path runs from the client towards the server; clientbound data goes the other way.
		Collections.reverse(protocols);
		return protocols;
	}

	/**
	 * Each vanilla block of the server's version (by name) -> this client's states for its states, in the
	 * server's order. Old states are taken in order: a block's run is the states whose equivalent belongs to
	 * the same-named block here (or, for a renamed or removed block, to one block here).
	 */
	private static Map<String, List<BlockState>> serverVanillaStates(List<Object> protocols) throws ReflectiveOperationException {
		Object first = mappingData(protocols.getFirst());
		Object stateMappings = method(first, "getBlockStateMappings").invoke(first);
		Object blockMappings = method(first, "getFullBlockMappings").invoke(first);
		int stateCount = (int) method(stateMappings, "size").invoke(stateMappings);
		int blockCount = (int) method(blockMappings, "size").invoke(blockMappings);
		Method identifier = method(blockMappings, "identifier", int.class);

		int[] mapped = new int[stateCount];
		for (int i = 0; i < stateCount; i++) {
			mapped[i] = i;
		}
		for (Object protocol : protocols) {
			Object data = mappingData(protocol);
			Object mappings = data == null ? null : method(data, "getBlockStateMappings").invoke(data);
			if (mappings == null) {
				continue;
			}
			Method newId = method(mappings, "getNewId", int.class);
			for (int i = 0; i < stateCount; i++) {
				if (mapped[i] >= 0) {
					mapped[i] = (int) newId.invoke(mappings, mapped[i]);
				}
			}
		}

		Map<String, List<BlockState>> byBlock = new HashMap<>();
		int i = 0;
		for (int b = 0; b < blockCount && i < stateCount; b++) {
			String name = normalize((String) identifier.invoke(blockMappings, b));
			int start = i;
			while (i < stateCount && name.equals(blockName(target(mapped[i])))) {
				i++;
			}
			if (i == start) {
				Block block = target(mapped[i]).getBlock();
				while (i < stateCount && target(mapped[i]).getBlock() == block) {
					i++;
				}
			}
			List<BlockState> states = new ArrayList<>(i - start);
			for (int k = start; k < i; k++) {
				states.add(target(mapped[k]));
			}
			byBlock.put(name, states);
		}
		if (i != stateCount) {
			MimicClient.LOGGER.warn("[States] Server version's vanilla states didn't line up ({} of {} placed)", i, stateCount);
		}
		return byBlock;
	}

	private static void install(Map<String, List<BlockState>> vanilla) throws ReflectiveOperationException {
		List<Block> serverOrder = new ArrayList<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (SERVER_BLOCKS.contains(BuiltInRegistries.BLOCK.getKey(block))) {
				serverOrder.add(block);
			}
		}
		serverOrder.sort((a, b) -> Integer.compare(BuiltInRegistries.BLOCK.getId(a), BuiltInRegistries.BLOCK.getId(b)));

		IdMapper<BlockState> registry = Block.BLOCK_STATE_REGISTRY;
		registry.getClass().getMethod("fabric_clear").invoke(registry);
		Set<BlockState> placed = new HashSet<>();
		int id = 0;
		int fromVia = 0;
		for (Block block : serverOrder) {
			List<BlockState> states = vanilla.get(BuiltInRegistries.BLOCK.getKey(block).toString());
			if (states != null) {
				fromVia++;
			} else {
				states = block.getStateDefinition().getPossibleStates();
			}
			for (BlockState state : states) {
				registry.addMapping(state, id++);
				placed.add(state);
			}
		}
		int serverStates = id;
		// The client's other states (blocks the server doesn't have) after the server's.
		for (Block block : BuiltInRegistries.BLOCK) {
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				if (placed.add(state)) {
					registry.addMapping(state, id++);
				}
			}
		}
		JoinStatus.info("[States] Server block-state table: {} states for {} blocks ({} vanilla blocks from ViaVersion's data)",
			serverStates, serverOrder.size(), fromVia);
	}

	private static Object mappingData(Object protocol) throws ReflectiveOperationException {
		return method(protocol, "getMappingData").invoke(protocol);
	}

	private static BlockState target(int vanillaId) {
		return vanillaId >= 0 && vanillaId < vanillaStates.size() ? vanillaStates.get(vanillaId) : Blocks.AIR.defaultBlockState();
	}

	private static String blockName(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static String normalize(String name) {
		return name.contains(":") ? name : "minecraft:" + name;
	}

	/** A public method by name, looked up on the object's interfaces too (Via's implementations are internal). */
	private static Method method(Object target, String name, Class<?>... params) throws NoSuchMethodException {
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			for (Class<?> iface : type.getInterfaces()) {
				try {
					return iface.getMethod(name, params);
				} catch (NoSuchMethodException ignored) {
					// Keep looking.
				}
			}
		}
		Method m = target.getClass().getMethod(name, params);
		m.setAccessible(true);
		return m;
	}
}
