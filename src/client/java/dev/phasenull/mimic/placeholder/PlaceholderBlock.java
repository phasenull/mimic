package dev.phasenull.mimic.placeholder;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;

/**
 * Stands in for a block only the server has, registered under the server's id. Its properties are chosen
 * so it has as many states as the server's block (see {@link StateGuess}): block-state ids are assigned in
 * registry order, so a wrong count shifts every later server-only block.
 */
public class PlaceholderBlock extends Block {
	/** Properties for the block being constructed (the state definition is built inside Block's constructor). */
	static final ThreadLocal<List<Property<?>>> PENDING = new ThreadLocal<>();

	private final StateGuess guess;

	PlaceholderBlock(Properties properties, StateGuess guess) {
		super(properties);
		this.guess = guess;
	}

	public StateGuess guess() {
		return guess;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		List<Property<?>> properties = PENDING.get();
		if (properties != null && !properties.isEmpty()) {
			builder.add(properties.toArray(new Property<?>[0]));
		}
	}
}
