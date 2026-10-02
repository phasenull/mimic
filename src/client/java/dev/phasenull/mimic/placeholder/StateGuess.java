package dev.phasenull.mimic.placeholder;

import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;

/**
 * The properties a placeholder block gets, i.e. how many block states it takes up. Without the mod's own
 * data, the vanilla shape a block id suggests ("_stairs", "_slab"...) is the best guess; anything else gets
 * a single state.
 */
public record StateGuess(List<Property<?>> properties, String source) {
	private static final StateGuess SINGLE = new StateGuess(List.of(), "no properties (unknown shape)");

	public int states() {
		int count = 1;
		for (Property<?> property : properties) {
			count *= property.getPossibleValues().size();
		}
		return count;
	}

	public static StateGuess fromName(String path) {
		if (path.endsWith("_stairs")) {
			return named("stairs", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.HALF, BlockStateProperties.STAIRS_SHAPE,
				BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_slab")) {
			return named("slab", BlockStateProperties.SLAB_TYPE, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_wall")) {
			return named("wall", BlockStateProperties.UP, BlockStateProperties.EAST_WALL, BlockStateProperties.NORTH_WALL,
				BlockStateProperties.SOUTH_WALL, BlockStateProperties.WEST_WALL, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_fence_gate")) {
			return named("fence gate", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.IN_WALL, BlockStateProperties.OPEN,
				BlockStateProperties.POWERED);
		}
		if (path.endsWith("_fence") || path.endsWith("_pane") || path.endsWith("_bars")) {
			return named("fence/pane", BlockStateProperties.EAST, BlockStateProperties.NORTH, BlockStateProperties.SOUTH,
				BlockStateProperties.WEST, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_trapdoor")) {
			return named("trapdoor", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.HALF, BlockStateProperties.OPEN,
				BlockStateProperties.POWERED, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_door")) {
			return named("door", BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.DOUBLE_BLOCK_HALF, BlockStateProperties.DOOR_HINGE,
				BlockStateProperties.OPEN, BlockStateProperties.POWERED);
		}
		if (path.endsWith("_button")) {
			return named("button", BlockStateProperties.ATTACH_FACE, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.POWERED);
		}
		if (path.endsWith("_pressure_plate")) {
			return named("pressure plate", BlockStateProperties.POWERED);
		}
		if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae") || path.endsWith("_pillar")) {
			return named("log/pillar", BlockStateProperties.AXIS);
		}
		if (path.endsWith("_leaves")) {
			return named("leaves", BlockStateProperties.DISTANCE, BlockStateProperties.PERSISTENT, BlockStateProperties.WATERLOGGED);
		}
		if (path.endsWith("_sapling")) {
			return named("sapling", BlockStateProperties.STAGE);
		}
		return SINGLE;
	}

	private static StateGuess named(String shape, Property<?>... properties) {
		return new StateGuess(List.of(properties), "guessed from the name (" + shape + ")");
	}
}
