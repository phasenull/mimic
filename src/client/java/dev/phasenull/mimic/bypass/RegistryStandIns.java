package dev.phasenull.mimic.bypass;

import com.mojang.datafixers.util.Either;
import dev.phasenull.mimic.debug.JoinStatus;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

/**
 * Replaces server-sent registry entries that failed to parse with a stand-in value from the same
 * registry, keeping their position so network ids stay aligned. Works on
 * RegistryLoadTask.PendingRegistration reflectively because that type is protected.
 */
public final class RegistryStandIns {
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

	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Stream apply(Stream elements, String registry) {
		List<Object> list = elements.toList();
		Object standIn = list.stream().map(RegistryStandIns::value).flatMap(v -> v.left().stream()).findFirst().orElse(null);
		if (standIn == null) {
			return list.stream();
		}
		return list.stream().map(p -> {
			Either value = value(p);
			if (value.right().isEmpty()) {
				return p;
			}
			try {
				ResourceKey<?> key = (ResourceKey<?>) KEY.invoke(p);
				JoinStatus.info("[Registry] Stand-in for {} in {}", key.identifier(), registry);
				return CREATE.newInstance(key, Either.left(standIn), INFO.invoke(p));
			} catch (ReflectiveOperationException e) {
				return p;
			}
		});
	}

	private static Either<?, ?> value(Object pending) {
		try {
			return (Either<?, ?>) VALUE.invoke(pending);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
