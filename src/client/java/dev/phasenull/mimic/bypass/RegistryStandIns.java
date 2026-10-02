package dev.phasenull.mimic.bypass;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Decoder;
import dev.phasenull.mimic.debug.JoinSession;
import dev.phasenull.mimic.debug.JoinStatus;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Server-sent registry entries that fail to parse (they reference server-only mod content) are kept in
 * place, so network ids stay aligned, with a stand-in: a fresh decode of an entry of the same registry
 * that did parse (registries reject the same value object twice). Works on
 * RegistryLoadTask.PendingRegistration reflectively because that type is protected.
 */
public final class RegistryStandIns {
	private record Retry(Decoder<?> decoder, RegistryOps<Tag> ops) {}

	private static final Map<ResourceKey<?>, Retry> FAILED = new ConcurrentHashMap<>();
	private static final Map<Decoder<?>, Tag> PARSED_SAMPLE = new ConcurrentHashMap<>();

	private static final Constructor<?> CREATE;
	private static final Method KEY;
	private static final Method VALUE;
	private static final Method INFO;

	static {
		try {
			Class<?> type = Class.forName("net.minecraft.resources.RegistryLoadTask$PendingRegistration");
			CREATE = type.getDeclaredConstructor(ResourceKey.class, Either.class, RegistrationInfo.class);
			KEY = type.getDeclaredMethod("key");
			VALUE = type.getDeclaredMethod("value");
			INFO = type.getDeclaredMethod("registrationInfo");
			for (var member : List.of(CREATE, KEY, VALUE, INFO)) {
				member.setAccessible(true);
			}
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private RegistryStandIns() {}

	public static void reset() {
		FAILED.clear();
		PARSED_SAMPLE.clear();
	}

	/** Called with every network registry entry decode result. */
	public static void onDecoded(Decoder<?> decoder, RegistryOps<Tag> ops, ResourceKey<?> key, Tag tag, Either<?, Exception> result) {
		if (result.left().isPresent()) {
			PARSED_SAMPLE.putIfAbsent(decoder, tag);
		} else {
			FAILED.put(key, new Retry(decoder, ops));
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Stream apply(Stream elements, String registry) {
		List<Object> list = elements.toList();
		return list.stream().map(p -> {
			try {
				if (((Either) VALUE.invoke(p)).right().isEmpty()) {
					return p;
				}
				ResourceKey<?> key = (ResourceKey<?>) KEY.invoke(p);
				Object standIn = standIn(key);
				if (standIn == null) {
					return p;
				}
				JoinStatus.info("[Registry] Stand-in for {} in {}", key.identifier(), registry);
				JoinSession.standIn(registry, key.identifier().toString());
				return CREATE.newInstance(key, Either.left(standIn), INFO.invoke(p));
			} catch (ReflectiveOperationException e) {
				return p;
			}
		});
	}

	private static Object standIn(ResourceKey<?> key) {
		Retry retry = FAILED.remove(key);
		if (retry == null) {
			return null;
		}
		Tag sample = PARSED_SAMPLE.get(retry.decoder());
		return sample == null ? null : retry.decoder().parse(retry.ops(), sample).result().orElse(null);
	}
}
