package dev.phasenull.mimic.debug;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Names server-sent sounds for the packet feed ("#412 minecraft:block.wood.break") and logs each distinct
 * one once per connection, so a join report shows which ids arrived and what this client played for them.
 */
public final class SoundLog {
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private SoundLog() {}

	public static void reset() {
		LOGGED.clear();
	}

	public static String describe(Holder<SoundEvent> holder) {
		SoundEvent sound = holder.value();
		String text;
		if (holder instanceof Holder.Reference<SoundEvent>) {
			text = "#" + BuiltInRegistries.SOUND_EVENT.getId(sound) + " " + sound.location();
		} else {
			text = "inline " + sound.location();
		}
		if (LOGGED.add(text)) {
			dev.phasenull.mimic.MimicClient.LOGGER.info("[Sound] {}", text);
		}
		return text;
	}
}
