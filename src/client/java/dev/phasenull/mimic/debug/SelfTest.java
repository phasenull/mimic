package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.bypass.ServerStateTable;
import dev.phasenull.mimic.placeholder.PlaceholderBlock;
import dev.phasenull.mimic.placeholder.Placeholders;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the blocks around the player shortly after joining: blocks that never generate naturally
 * (command blocks, barriers...), placeholders of blocks this server doesn't have, or the unknown block
 * showing up in the terrain mean the block-state ids are shifted. If so, says where the shift starts and
 * which guessed block before it most likely has the wrong number of states (see /mimic verify).
 */
public final class SelfTest {
	private static final int DELAY_TICKS = 160;
	private static final int RADIUS = 24;
	private static final int BELOW = 32;
	private static final int ABOVE = 8;
	/** Odd blocks needed, absolute and per non-air block, before warning without being asked. */
	private static final int MIN_ODD = 12;
	private static final double MIN_ODD_SHARE = 0.002;

	/** Vanilla blocks a survival world doesn't generate around players. */
	private static final Set<Block> NEVER_NATURAL = Set.of(Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
		Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK, Blocks.STRUCTURE_VOID, Blocks.JIGSAW, Blocks.BARRIER,
		Blocks.LIGHT, Blocks.MOVING_PISTON, Blocks.END_GATEWAY, Blocks.END_PORTAL, Blocks.REINFORCED_DEEPSLATE,
		Blocks.TEST_BLOCK, Blocks.TEST_INSTANCE_BLOCK, Blocks.PETRIFIED_OAK_SLAB, Blocks.BUBBLE_COLUMN);

	private static int countdown = -1;

	private SelfTest() {}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> countdown = DELAY_TICKS);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> countdown = -1);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (countdown > 0 && --countdown == 0 && client.player != null) {
				Result result = run(client);
				if (result.worrying()) {
					result.lines().forEach(line -> client.player.sendSystemMessage(line));
				}
				log(result);
			}
		});
	}

	private static void log(Result result) {
		result.lines().forEach(line -> dev.phasenull.mimic.MimicClient.LOGGER.info("[SelfTest] {}", line.getString()));
	}

	/** One check's outcome, as chat lines. */
	public record Result(boolean worrying, List<Component> lines) {}

	public static Result run(Minecraft client) {
		BlockPos center = client.player.blockPosition();
		Map<Block, Integer> odd = new LinkedHashMap<>();
		Map<BlockState, Integer> oddStates = new HashMap<>();
		int solid = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = -RADIUS; x <= RADIUS; x++) {
			for (int z = -RADIUS; z <= RADIUS; z++) {
				int cx = center.getX() + x;
				int cz = center.getZ() + z;
				if (!client.level.hasChunk(cx >> 4, cz >> 4)) {
					continue;
				}
				for (int y = center.getY() - BELOW; y <= center.getY() + ABOVE; y++) {
					BlockState state = client.level.getBlockState(pos.set(cx, y, cz));
					if (state.isAir()) {
						continue;
					}
					solid++;
					if (isOdd(state)) {
						odd.merge(state.getBlock(), 1, Integer::sum);
						oddStates.merge(state, 1, Integer::sum);
					}
				}
			}
		}
		int oddCount = odd.values().stream().mapToInt(Integer::intValue).sum();
		List<Component> lines = new ArrayList<>();
		boolean worrying = oddCount >= MIN_ODD && oddCount > solid * MIN_ODD_SHARE;
		if (oddCount == 0) {
			lines.add(Component.literal("[Mimic] Self-test: the " + solid + " blocks around you look right.").withStyle(ChatFormatting.GREEN));
			return new Result(false, lines);
		}
		StringBuilder top = new StringBuilder();
		odd.entrySet().stream().sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(4).forEach(e -> {
			if (!top.isEmpty()) {
				top.append(", ");
			}
			top.append(e.getValue()).append(' ').append(name(e.getKey()));
		});
		lines.add(Component.literal("[Mimic] Self-test: " + oddCount + " of " + solid + " blocks around you look out of place (" + top + ")"
			+ (worrying ? "; block ids are probably shifted." : "; probably fine (built by players?).")).withStyle(worrying ? ChatFormatting.GOLD : ChatFormatting.GRAY));
		if (worrying) {
			suggestion(oddStates).ifPresent(lines::add);
		}
		return new Result(worrying, lines);
	}

	private static boolean isOdd(BlockState state) {
		if (state == Placeholders.unknownState()) {
			return true;
		}
		Block block = state.getBlock();
		if (block instanceof PlaceholderBlock) {
			// A placeholder for a block this server doesn't even have (left from another server).
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			return ServerStateTable.serverStates() >= 0 && !ServerStateTable.serverHas(id);
		}
		return NEVER_NATURAL.contains(block);
	}

	/** Where the shift starts and the guessed block before it that most likely miscounts. */
	private static java.util.Optional<Component> suggestion(Map<BlockState, Integer> oddStates) {
		List<ServerStateTable.Entry> entries = ServerStateTable.entries();
		if (entries.isEmpty()) {
			return java.util.Optional.of(Component.literal("Import the server's mod jars (Server mods page) so Mimic knows their blocks' states.")
				.withStyle(ChatFormatting.GRAY));
		}
		int first = Integer.MAX_VALUE;
		boolean pastEnd = false;
		for (BlockState state : oddStates.keySet()) {
			if (state == Placeholders.unknownState() || ServerStateTable.serverId(state) >= ServerStateTable.serverStates()) {
				pastEnd = true;
			} else {
				first = Math.min(first, ServerStateTable.serverId(state));
			}
		}
		int from = first != Integer.MAX_VALUE ? first : ServerStateTable.serverStates();
		ServerStateTable.Entry suspect = null;
		for (ServerStateTable.Entry entry : entries) {
			if (entry.first() >= from) {
				break;
			}
			if (entry.guessed()) {
				suspect = entry;
			}
		}
		String where = first != Integer.MAX_VALUE ? "Ids from about #" + first + " look shifted" : "Some ids are past the end of the server's table";
		if (pastEnd && first != Integer.MAX_VALUE) {
			where += " (and some are past the end)";
		}
		if (suspect == null) {
			return java.util.Optional.of(Component.literal(where + ". Look at a block you know and use /mimic verify on it.").withStyle(ChatFormatting.GRAY));
		}
		return java.util.Optional.of(Component.literal(where + ". Most likely miscounted: " + suspect.block() + " ("
			+ (suspect.last() - suspect.first() + 1) + " states, " + suspect.source() + "). Import " + suspect.block().getNamespace()
			+ "'s jar, or /mimic verify a block you know that comes after it. Details: /mimic selftest").withStyle(ChatFormatting.GRAY));
	}

	private static String name(Block block) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}
}
