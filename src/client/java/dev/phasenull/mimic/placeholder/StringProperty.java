package dev.phasenull.mimic.placeholder;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Optional;

/** A block property with arbitrary named values, for mod properties that have no vanilla equivalent. */
final class StringProperty extends Property<String> {
	private final List<String> values;

	StringProperty(String name, List<String> values) {
		super(name, String.class);
		this.values = List.copyOf(values);
	}

	@Override
	public List<String> getPossibleValues() {
		return values;
	}

	@Override
	public String getName(String value) {
		return value;
	}

	@Override
	public Optional<String> getValue(String name) {
		return values.contains(name) ? Optional.of(name) : Optional.empty();
	}

	@Override
	public int getInternalIndex(String value) {
		return values.indexOf(value);
	}
}
