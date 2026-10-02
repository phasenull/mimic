package dev.phasenull.mimic;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Build info: the git commit stamped into fabric.mod.json by Gradle, or asked from git in dev sessions. */
public final class MimicBuild {
	private static final String COMMIT = readCommit();

	private MimicBuild() {}

	/** Short git commit the running build came from, e.g. "6a9df2f" or "6a9df2f-dirty"; "dev" if unknown. */
	public static String commit() {
		return COMMIT;
	}

	/** e.g. "Mimic 6a9df2f". */
	public static String label() {
		return "Mimic " + COMMIT;
	}

	private static String readCommit() {
		return stamped()
			.or(() -> FabricLoader.getInstance().isDevelopmentEnvironment() ? fromGit() : Optional.empty())
			.orElse("dev");
	}

	private static Optional<String> stamped() {
		return FabricLoader.getInstance().getModContainer(MimicClient.MOD_ID)
			.map(mod -> mod.getMetadata().getCustomValue("mimic:commit"))
			.filter(value -> value.getType() == CustomValue.CvType.STRING)
			.map(CustomValue::getAsString)
			.filter(commit -> !commit.isBlank() && !commit.contains("${"));
	}

	/** IDE launches (e.g. VS Code) skip Gradle's resource processing; the dev game dir is <project>/run. */
	private static Optional<String> fromGit() {
		try {
			Path project = FabricLoader.getInstance().getGameDir().toAbsolutePath().getParent();
			Process git = new ProcessBuilder("git", "describe", "--always", "--dirty", "--abbrev=7", "--exclude=*")
				.directory(new File(project.toString()))
				.redirectErrorStream(true)
				.start();
			if (!git.waitFor(3, TimeUnit.SECONDS) || git.exitValue() != 0) {
				git.destroy();
				return Optional.empty();
			}
			String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			return out.isEmpty() ? Optional.empty() : Optional.of(out);
		} catch (Exception e) {
			return Optional.empty();
		}
	}
}
