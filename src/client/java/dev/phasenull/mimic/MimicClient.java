package dev.phasenull.mimic;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MimicClient implements ClientModInitializer {
	public static final String MOD_ID = "mimic";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		LOGGER.info("Mimic loaded");
	}
}
