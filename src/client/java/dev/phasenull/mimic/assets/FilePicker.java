package dev.phasenull.mimic.assets;

import dev.phasenull.mimic.MimicClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

/** The system's "open file" dialog (via SDL, which the game window already uses). One dialog at a time. */
public final class FilePicker {
	private static SDL_DialogFileCallback callback;
	private static SDL_DialogFileFilter.Buffer filters;
	private static ByteBuffer filterName;
	private static ByteBuffer filterPattern;

	private FilePicker() {}

	public static boolean open() {
		return callback != null;
	}

	/** Shows the dialog; {@code onPicked} runs on the render thread with the chosen file (not on cancel). */
	public static void pick(String description, String extensions, Consumer<Path> onPicked) {
		Minecraft client = Minecraft.getInstance();
		if (callback != null) {
			return;
		}
		filterName = MemoryUtil.memUTF8(description);
		filterPattern = MemoryUtil.memUTF8(extensions);
		filters = SDL_DialogFileFilter.malloc(1);
		filters.get(0).name(filterName).pattern(filterPattern);
		callback = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
			String picked = null;
			if (fileList != 0) {
				long first = MemoryUtil.memGetAddress(fileList);
				if (first != 0) {
					picked = MemoryUtil.memUTF8(first);
				}
			}
			String result = picked;
			client.execute(() -> {
				free();
				if (result != null) {
					onPicked.accept(Path.of(result));
				}
			});
		});
		try {
			SDLDialog.SDL_ShowOpenFileDialog(callback, 0, client.getWindow().handle(), filters, (CharSequence) null, false);
		} catch (RuntimeException | LinkageError e) {
			MimicClient.LOGGER.warn("Could not open the file dialog", e);
			free();
		}
	}

	private static void free() {
		if (callback != null) {
			callback.free();
			callback = null;
		}
		if (filters != null) {
			filters.free();
			filters = null;
		}
		MemoryUtil.memFree(filterName);
		MemoryUtil.memFree(filterPattern);
		filterName = null;
		filterPattern = null;
	}
}
