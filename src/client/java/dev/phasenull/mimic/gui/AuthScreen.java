package dev.phasenull.mimic.gui;

import com.mojang.blaze3d.Blaze3D;
import dev.phasenull.mimic.devauth.DevAuth;
import dev.phasenull.mimic.devauth.MicrosoftAuth;
import dev.phasenull.mimic.devauth.SessionSwitcher;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.net.URI;

/** Dev-only account switcher: Microsoft sign-in (device code) or an offline name. */
public class AuthScreen extends Screen {
	private static final int W = 200;
	private static final int ROW = 24;
	private static final int ERROR_COLOR = 0xFFFF5555;
	private static final int CODE_COLOR = 0xFFFFFF55;

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	private volatile boolean busy;
	private volatile boolean cancelled;
	private volatile MicrosoftAuth.DeviceCode deviceCode;
	private volatile Component status = Component.empty();
	private volatile boolean statusIsError;

	private EditBox offlineName;
	private Button signIn;
	private Button switchAccount;
	private Button useOffline;
	private Button forget;
	private Button copyCode;
	private Button openLink;
	private Button cancel;

	public AuthScreen(Screen parent) {
		super(Component.translatable("mimic.auth.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(W).build());
		layout.visitWidgets(w -> addRenderableWidget(w));

		boolean msAvailable = DevAuth.clientId() != null;
		signIn = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.sign_in"), b -> startSignIn(false)).width(W).build());
		switchAccount = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.switch"), b -> startSignIn(true)).width(W).build());
		offlineName = addRenderableWidget(new EditBox(font, 0, 0, W - 84, 20, Component.translatable("mimic.auth.offline_name")));
		offlineName.setMaxLength(16);
		offlineName.setValue(DevAuth.isMicrosoftSession() ? "Player" : minecraft.getUser().getName());
		useOffline = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.offline"), b -> goOffline()).width(80).build());
		forget = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.forget"), b -> forgetLogin()).width(W).build());

		copyCode = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.copy_code"), b -> {
			MicrosoftAuth.DeviceCode code = deviceCode;
			if (code != null) {
				minecraft.keyboardHandler.setClipboard(code.userCode());
			}
		}).width(W / 2 - 2).build());
		openLink = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.open_link"), b -> {
			MicrosoftAuth.DeviceCode code = deviceCode;
			if (code != null) {
				Blaze3D.openUri(URI.create(code.verificationUri()));
			}
		}).width(W / 2 - 2).build());
		cancel = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> cancelled = true).width(W).build());

		if (!msAvailable) {
			setStatus(Component.translatable("mimic.auth.no_client_id"), true);
		}
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		int x = (width - W) / 2;
		int y = layout.getHeaderHeight() + 34;
		signIn.setPosition(x, y);
		switchAccount.setPosition(x, y + ROW);
		offlineName.setPosition(x, y + ROW * 2);
		useOffline.setPosition(x + W - 80, y + ROW * 2);
		forget.setPosition(x, y + ROW * 3);

		copyCode.setPosition(x, y + ROW * 2);
		openLink.setPosition(x + W / 2 + 2, y + ROW * 2);
		cancel.setPosition(x, y + ROW * 3);
	}

	@Override
	public void tick() {
		boolean msAvailable = DevAuth.clientId() != null;
		boolean showCode = busy && deviceCode != null;
		signIn.visible = switchAccount.visible = offlineName.visible = useOffline.visible = forget.visible = !busy;
		signIn.active = switchAccount.active = msAvailable;
		forget.active = DevAuth.hasSavedLogin();
		copyCode.visible = openLink.visible = showCode;
		cancel.visible = busy;
		useOffline.active = !offlineName.getValue().isBlank();
	}

	private void startSignIn(boolean forceNew) {
		busy = true;
		cancelled = false;
		deviceCode = null;
		setStatus(Component.translatable("mimic.auth.working"), false);
		Thread thread = new Thread(() -> {
			try {
				MicrosoftAuth.McSession session = DevAuth.signIn(forceNew,
					code -> {
						deviceCode = code;
						minecraft.execute(() -> Blaze3D.openUri(URI.create(code.verificationUri())));
					},
					msg -> setStatus(Component.literal(msg), false),
					() -> cancelled);
				minecraft.execute(() -> {
					SessionSwitcher.useMicrosoft(minecraft, session);
					setStatus(Component.translatable("mimic.auth.signed_in", session.name()), false);
					busy = false;
					deviceCode = null;
				});
			} catch (Exception e) {
				setStatus(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage()), true);
				busy = false;
				deviceCode = null;
			}
		}, "Mimic dev sign-in");
		thread.setDaemon(true);
		thread.start();
	}

	private void goOffline() {
		String name = offlineName.getValue().trim();
		SessionSwitcher.useOffline(minecraft, name);
		setStatus(Component.translatable("mimic.auth.offline_now", name), false);
	}

	private void forgetLogin() {
		DevAuth.forgetSavedLogin();
		setStatus(Component.translatable("mimic.auth.forgotten"), false);
	}

	private void setStatus(Component message, boolean error) {
		status = message;
		statusIsError = error;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		int cx = width / 2;
		int y = layout.getHeaderHeight() + 6;
		Component kind = Component.translatable(DevAuth.isMicrosoftSession() ? "mimic.auth.kind.microsoft" : "mimic.auth.kind.offline");
		g.centeredText(font, Component.translatable("mimic.auth.current", minecraft.getUser().getName(), kind), cx, y, CommonColors.WHITE);
		g.centeredText(font, status, cx, y + font.lineHeight + 4, statusIsError ? ERROR_COLOR : CommonColors.GRAY);

		MicrosoftAuth.DeviceCode code = deviceCode;
		if (busy && code != null) {
			int top = layout.getHeaderHeight() + 34;
			g.centeredText(font, Component.translatable("mimic.auth.go_to", code.verificationUri()), cx, top + 2, CommonColors.WHITE);
			g.centeredText(font, Component.literal(code.userCode()).withStyle(s -> s.withBold(true)), cx, top + ROW + 2, CODE_COLOR);
		}
	}

	@Override
	public void onClose() {
		cancelled = true;
		minecraft.gui.setScreen(parent);
	}
}
