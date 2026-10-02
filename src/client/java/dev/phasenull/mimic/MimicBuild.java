package dev.phasenull.mimic;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;

/** Build info stamped into fabric.mod.json by Gradle's processResources. */
public final class MimicBuild {
	private static final String COMMIT = readCommit();

	private MimicBuild() {}

	/** Short git commit the running build came from, e.g. "6a9df2f" or "6a9df2f-dirty"; "dev" if not built by Gradle. */
	public static String commit() {
		return COMMIT;
	}

	/** e.g. "Mimic 6a9df2f". */
	public static String label() {
		return "Mimic " + COMMIT;
	}

	private static String readCommit() {
		return FabricLoader.getInstance().getModContainer(MimicClient.MOD_ID)
			.map(mod -> mod.getMetadata().getCustomValue("mimic:commit"))
			.filter(value -> value.getType() == CustomValue.CvType.STRING)
			.map(CustomValue::getAsString)
			.filter(commit -> !commit.isBlank() && !commit.contains("${"))
			.orElse("dev");
	}
}
