package dev.phasenull.mimic.placeholder;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Stands in for a block only the server has, registered under the server's id. Its properties are chosen
 * so it has as many states as the server's block (see {@link StateGuess}): block-state ids are assigned in
 * registry order, so a wrong count shifts every later server-only block.
 */
public class PlaceholderBlock extends Block {
	/** Properties for the block being constructed (the state definition is built inside Block's constructor). */
	static final ThreadLocal<List<Property<?>>> PENDING = new ThreadLocal<>();

	private static final Field STATE_DEFINITION;

	static {
		try {
			STATE_DEFINITION = Block.class.getDeclaredField("stateDefinition");
			STATE_DEFINITION.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private volatile StateGuess guess;

	PlaceholderBlock(Properties properties, StateGuess guess) {
		super(properties);
		this.guess = guess;
	}

	public StateGuess guess() {
		return guess;
	}

	// Shapes from the imported jar's models (see ModelShapes); a full block without them.

	@Override
	protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
			net.minecraft.core.BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
		return ModelShapes.outline(state);
	}

	@Override
	protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state, net.minecraft.world.level.BlockGetter level,
			net.minecraft.core.BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
		return ModelShapes.collision(state);
	}

	@Override
	protected net.minecraft.world.phys.shapes.VoxelShape getOcclusionShape(BlockState state) {
		return ModelShapes.full(state) ? net.minecraft.world.phys.shapes.Shapes.block() : net.minecraft.world.phys.shapes.Shapes.empty();
	}

	/**
	 * Gives this block a new set of states, e.g. after the mod's jar was imported, so the counts are right
	 * without restarting. Only call it before the server's ids are applied (Fabric then renumbers all states).
	 * Returns true if the states changed.
	 */
	boolean reshape(StateGuess newGuess) {
		if (newGuess.properties().equals(guess.properties())) {
			return false;
		}
		StateDefinition.Builder<Block, BlockState> builder = new StateDefinition.Builder<>(this);
		if (!newGuess.properties().isEmpty()) {
			builder.add(newGuess.properties().toArray(new Property<?>[0]));
		}
		StateDefinition<Block, BlockState> definition = builder.create(Block::defaultBlockState, BlockState::new);
		try {
			STATE_DEFINITION.set(this, definition);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
		registerDefaultState(definition.any());
		for (BlockState state : definition.getPossibleStates()) {
			state.initCache();
		}
		guess = newGuess;
		return true;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		List<Property<?>> properties = PENDING.get();
		if (properties != null && !properties.isEmpty()) {
			builder.add(properties.toArray(new Property<?>[0]));
		}
	}
}
