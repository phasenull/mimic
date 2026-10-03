package dev.phasenull.mimic.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.phasenull.mimic.bypass.StateAnchors;
import dev.phasenull.mimic.cache.ModCache;
import dev.phasenull.mimic.texture.TextureStore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public final class MimicCommand {
	private MimicCommand() {}

	/**
	 * Pins the looked-at block. Without an id it's verified as shown (its states start where they start now);
	 * with an id, the looked-at state is taken to be that block's default state, so its states start there
	 * (use nudge if the variant is off).
	 */
	private static int verify(FabricClientCommandSource source, String claimed) {
		Minecraft client = Minecraft.getInstance();
		if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || client.level == null) {
			source.sendError(Component.literal("Look at a block first"));
			return 0;
		}
		BlockState shown = client.level.getBlockState(hit.getBlockPos());
		int id = Block.getId(shown);
		Block block = shown.getBlock();
		int first;
		if (claimed == null) {
			first = id - block.getStateDefinition().getPossibleStates().indexOf(shown);
		} else {
			Identifier claimedId = Identifier.tryParse(claimed.trim());
			if (claimedId == null || !BuiltInRegistries.BLOCK.containsKey(claimedId)) {
				source.sendError(Component.literal("Unknown block " + claimed));
				return 0;
			}
			block = BuiltInRegistries.BLOCK.getValue(claimedId);
			first = id - block.getStateDefinition().getPossibleStates().indexOf(block.defaultBlockState());
		}
		String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
		StateAnchors.set(blockId, first);
		int states = block.getStateDefinition().getPossibleStates().size();
		source.sendFeedback(Component.literal("Verified " + blockId + " at server states #" + first + "-#" + (first + states - 1)
			+ ". Rejoin to apply."));
		return 1;
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
			ClientCommands.literal("mimic")
				.then(ClientCommands.literal("mods").executes(ctx -> {
					ModCache cache = ModCache.get();
					synchronized (cache) {
						// Mods only seen by a network channel have nothing to list.
						var listed = cache.mods.entrySet().stream()
							.filter(e -> !e.getValue().items.isEmpty() || !e.getValue().blocks.isEmpty() || !e.getValue().entities.isEmpty())
							.toList();
						if (listed.isEmpty()) {
							ctx.getSource().sendFeedback(Component.literal("No mod items, blocks or entities seen yet."));
						}
						listed.forEach(e -> ctx.getSource().sendFeedback(Component.literal(e.getKey() + "  items=" + e.getValue().items.size()
							+ " blocks=" + e.getValue().blocks.size() + " entities=" + e.getValue().entities.size())));
						int hidden = cache.mods.size() - listed.size();
						if (hidden > 0) {
							ctx.getSource().sendFeedback(Component.literal(hidden + " more seen with nothing to list (channels only)"));
						}
					}
					return 1;
				}))
				// Anchors: pin a block to its real place in the server's block-state numbering.
				.then(ClientCommands.literal("verify").executes(ctx -> verify(ctx.getSource(), null))
					.then(ClientCommands.argument("block", StringArgumentType.greedyString())
						.executes(ctx -> verify(ctx.getSource(), StringArgumentType.getString(ctx, "block")))))
				.then(ClientCommands.literal("nudge")
					.then(ClientCommands.argument("block", StringArgumentType.string())
						.then(ClientCommands.argument("by", IntegerArgumentType.integer()).executes(ctx -> {
							String block = StringArgumentType.getString(ctx, "block");
							int by = IntegerArgumentType.getInteger(ctx, "by");
							if (!StateAnchors.nudge(block, by)) {
								ctx.getSource().sendError(Component.literal("No anchor for " + block + " on this server"));
								return 0;
							}
							ctx.getSource().sendFeedback(Component.literal("Moved " + block + " by " + by + " states. Rejoin to apply."));
							return 1;
						}))))
				.then(ClientCommands.literal("anchors").executes(ctx -> {
					Map<String, Integer> anchors = StateAnchors.current();
					if (anchors.isEmpty()) {
						ctx.getSource().sendFeedback(Component.literal("No anchors on this server. Look at a block and use /mimic verify."));
					}
					anchors.forEach((block, first) -> ctx.getSource().sendFeedback(Component.literal(block + " starts at state #" + first)));
					return 1;
				}).then(ClientCommands.literal("clear").executes(ctx -> {
					StateAnchors.clear();
					ctx.getSource().sendFeedback(Component.literal("Anchors cleared for this server. Rejoin to apply."));
					return 1;
				})))
				.then(ClientCommands.literal("texture")
					.then(ClientCommands.argument("id", StringArgumentType.string())
						.then(ClientCommands.argument("png", StringArgumentType.greedyString()).executes(ctx -> {
							String id = StringArgumentType.getString(ctx, "id");
							String png = StringArgumentType.getString(ctx, "png");
							int colon = id.indexOf(':');
							if (colon <= 0 || colon == id.length() - 1) {
								ctx.getSource().sendError(Component.literal("Use <modid:name>, e.g. magic:wand"));
								return 0;
							}
							String modId = id.substring(0, colon);
							String path = id.substring(colon + 1);
							try {
								String stored = TextureStore.importPng(Path.of(png), modId, path);
								ModCache cache = ModCache.get();
								cache.item(modId, ModCache.UNKNOWN_VERSION, path).texture = stored;
								cache.save();
								ctx.getSource().sendFeedback(Component.literal("Texture attached to " + id));
								return 1;
							} catch (IOException e) {
								ctx.getSource().sendError(Component.literal(e.getMessage()));
								return 0;
							}
						}))))
		));
	}
}
