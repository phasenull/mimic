package dev.phasenull.mimic.gui;

import dev.phasenull.mimic.debug.ConnectionDebug;
import dev.phasenull.mimic.privacy.PrivacyGuard;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** What this client sends to servers, the brand it reports, and which packets or channels it never sends. */
public final class PrivacyScreen {
	private PrivacyScreen() {}

	public static Screen create(Screen parent) {
		List<TextListScreen.Row> rows = new ArrayList<>();
		String brand = PrivacyGuard.brandSetting();
		rows.add(TextListScreen.Row.header("Client brand sent to servers: " + (brand.isEmpty() ? "real (" + ClientBrandRetriever.getClientModName() + ")" : brand)));
		rows.add(TextListScreen.Row.action(brandLabel("Real brand (fabric)", brand.isEmpty()), "What Fabric normally reports",
			() -> reopen(parent, () -> PrivacyGuard.setBrand(PrivacyGuard.BRAND_UNCHANGED))));
		rows.add(TextListScreen.Row.action(brandLabel("Vanilla", brand.equals(PrivacyGuard.BRAND_VANILLA)),
			"Look like an unmodded client to brand checks (mod channels can still give you away)",
			() -> reopen(parent, () -> PrivacyGuard.setBrand(PrivacyGuard.BRAND_VANILLA))));

		Set<String> blocked = ConnectionDebug.blocked();
		Map<String, Integer> seen = new TreeMap<>(PrivacyGuard.seenIds());
		blocked.forEach(id -> seen.putIfAbsent(id, 0));
		rows.add(TextListScreen.Row.header("Sent by this client (" + seen.size() + "): click one to stop sending it"));
		rows.add(TextListScreen.Row.text("Blocking can make a server kick you or break a feature; changes apply to the next packet."));
		seen.forEach((id, count) -> {
			boolean isBlocked = blocked.contains(id);
			String label = (isBlocked ? "[blocked] " : "") + id + (count > 0 ? "  x" + count : "");
			if (PrivacyGuard.REQUIRED.contains(id)) {
				rows.add(TextListScreen.Row.text(label, "Needed to stay connected; can't be blocked"));
			} else {
				rows.add(TextListScreen.Row.action(label, PrivacyGuard.reveals(id), () -> reopen(parent, () -> {
					Set<String> next = new HashSet<>(ConnectionDebug.blocked());
					if (!next.remove(id)) {
						next.add(id);
					}
					ConnectionDebug.setBlocked(next);
				})));
			}
		});
		if (seen.isEmpty()) {
			rows.add(TextListScreen.Row.text("Nothing sent yet. Join a server and come back."));
		}
		return new TextListScreen(parent, Component.literal("Privacy"), rows);
	}

	private static String brandLabel(String text, boolean selected) {
		return text + (selected ? "  (selected)" : "");
	}

	private static void reopen(Screen parent, Runnable change) {
		change.run();
		Minecraft.getInstance().gui.setScreen(create(parent));
	}
}
