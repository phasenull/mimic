package dev.phasenull.mimic.debug;

import dev.phasenull.mimic.MimicClient;
import org.slf4j.helpers.MessageFormatter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Recent Mimic messages shown on the joining screens; also written to the game log. */
public final class JoinStatus {
	private static final int KEEP = 4;
	private static final Deque<String> MESSAGES = new ArrayDeque<>();

	private JoinStatus() {}

	/** Logs at info level (slf4j-style {} placeholders) and shows the message on the joining screen. */
	public static void info(String format, Object... args) {
		String message = MessageFormatter.arrayFormat(format, args).getMessage();
		MimicClient.LOGGER.info(message);
		post(message);
	}

	public static synchronized void post(String message) {
		MESSAGES.addFirst(message);
		while (MESSAGES.size() > KEEP) {
			MESSAGES.removeLast();
		}
	}

	public static synchronized void clear() {
		MESSAGES.clear();
	}

	/** Newest first. */
	public static synchronized List<String> recent() {
		return List.copyOf(MESSAGES);
	}
}
