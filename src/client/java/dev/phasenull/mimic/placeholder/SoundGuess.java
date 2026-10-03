package dev.phasenull.mimic.placeholder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;

import java.util.List;
import java.util.Map;

/**
 * Break, step and place sounds for a placeholder block, from a vanilla block with a similar name: the same
 * name ("mymod:oak_log" -> oak_log), else the longest name ending ("mymod:maple_log" -> a vanilla "_log"),
 * else a few material words; stone otherwise.
 */
public final class SoundGuess {
	private static final Map<String, SoundType> WORDS = Map.ofEntries(
		Map.entry("wood", SoundType.WOOD), Map.entry("plank", SoundType.WOOD), Map.entry("log", SoundType.WOOD),
		Map.entry("crate", SoundType.WOOD), Map.entry("barrel", SoundType.WOOD), Map.entry("chest", SoundType.WOOD),
		Map.entry("table", SoundType.WOOD), Map.entry("shelf", SoundType.WOOD), Map.entry("easel", SoundType.WOOD),
		Map.entry("canvas", SoundType.WOOL), Map.entry("wool", SoundType.WOOL), Map.entry("carpet", SoundType.WOOL),
		Map.entry("cloth", SoundType.WOOL), Map.entry("leaves", SoundType.GRASS), Map.entry("leaf", SoundType.GRASS),
		Map.entry("grass", SoundType.GRASS), Map.entry("plant", SoundType.GRASS), Map.entry("flower", SoundType.GRASS),
		Map.entry("sapling", SoundType.GRASS), Map.entry("glass", SoundType.GLASS), Map.entry("lamp", SoundType.GLASS),
		Map.entry("sand", SoundType.SAND), Map.entry("gravel", SoundType.GRAVEL), Map.entry("dirt", SoundType.GRAVEL),
		Map.entry("snow", SoundType.SNOW), Map.entry("metal", SoundType.METAL), Map.entry("iron", SoundType.METAL),
		Map.entry("steel", SoundType.METAL), Map.entry("copper", SoundType.COPPER), Map.entry("pipe", SoundType.METAL),
		Map.entry("cable", SoundType.METAL), Map.entry("wire", SoundType.METAL), Map.entry("machine", SoundType.METAL),
		Map.entry("modem", SoundType.METAL), Map.entry("chain", SoundType.CHAIN), Map.entry("lantern", SoundType.LANTERN),
		Map.entry("slime", SoundType.SLIME_BLOCK), Map.entry("honey", SoundType.HONEY_BLOCK), Map.entry("bone", SoundType.BONE_BLOCK),
		Map.entry("amethyst", SoundType.AMETHYST), Map.entry("deepslate", SoundType.DEEPSLATE), Map.entry("netherrack", SoundType.NETHERRACK),
		Map.entry("scaffold", SoundType.SCAFFOLDING), Map.entry("ladder", SoundType.LADDER), Map.entry("candle", SoundType.CANDLE),
		Map.entry("vine", SoundType.VINE), Map.entry("moss", SoundType.MOSS), Map.entry("mud", SoundType.MUD));

	private SoundGuess() {}

	public static SoundType of(Identifier id) {
		String path = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
		List<String> parts = List.of(path.split("_"));
		for (int k = 0; k < parts.size(); k++) {
			String ending = String.join("_", parts.subList(k, parts.size()));
			SoundType sound = vanilla(ending);
			if (sound != null) {
				return sound;
			}
		}
		for (int k = parts.size() - 1; k >= 0; k--) {
			String word = parts.get(k);
			for (Map.Entry<String, SoundType> e : WORDS.entrySet()) {
				if (word.startsWith(e.getKey())) {
					return e.getValue();
				}
			}
		}
		return SoundType.STONE;
	}

	/** The sound of minecraft:{@code name}, or of the first vanilla block whose name ends in "_{@code name}". */
	private static SoundType vanilla(String name) {
		Identifier exact = Identifier.withDefaultNamespace(name);
		if (BuiltInRegistries.BLOCK.containsKey(exact) && !Placeholders.isPlaceholder(BuiltInRegistries.BLOCK, exact)) {
			return BuiltInRegistries.BLOCK.getValue(exact).defaultBlockState().getSoundType();
		}
		String ending = "_" + name;
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier key = BuiltInRegistries.BLOCK.getKey(block);
			if (key.getNamespace().equals("minecraft") && key.getPath().endsWith(ending)) {
				return block.defaultBlockState().getSoundType();
			}
		}
		return null;
	}
}
