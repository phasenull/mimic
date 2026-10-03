package dev.phasenull.mimic.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.phasenull.mimic.cache.ModCache;
import dev.phasenull.mimic.texture.TextureStore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;

public final class MimicCommand {
	private MimicCommand() {}

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
