package dev.phasenull.mimic;

import dev.phasenull.mimic.bypass.LoginQueryAnswers;
import dev.phasenull.mimic.bypass.RetryLoop;
import dev.phasenull.mimic.bypass.neoforge.NeoForgeBypass;
import dev.phasenull.mimic.command.MimicCommand;
import dev.phasenull.mimic.debug.ConnectionOverlay;
import dev.phasenull.mimic.debug.CopyLogsButton;
import dev.phasenull.mimic.debug.HotReloadNotifier;
import dev.phasenull.mimic.gui.JoinScreenExtras;
import dev.phasenull.mimic.gui.TitleScreenModsButton;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MimicClient implements ClientModInitializer {
	public static final String MOD_ID = "mimic";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		MimicCommand.register();
		TitleScreenModsButton.register();
		ConnectionOverlay.register();
		CopyLogsButton.register();
		JoinScreenExtras.register();
		RetryLoop.register();
		HotReloadNotifier.start();
		NeoForgeBypass.register();
		LoginQueryAnswers.registerReconnect();
		LOGGER.info("Mimic loaded");
	}
}
