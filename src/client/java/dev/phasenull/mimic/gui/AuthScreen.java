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

/** Dev-only account switcher: Microsoft browser sign-in, saved login, or an offline name. */
public class AuthScreen extends Screen {
	private static final int W = 200;
	private static final int ROW = 24;
	private static final int ERROR_COLOR = 0xFFFF5555;

	private enum Mode { MENU, PASTE, WORKING }

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	private volatile Mode mode = Mode.MENU;
	private volatile Component status = Component.empty();
	private volatile boolean statusIsError;
	private String loginUrl;

	private Button useSaved;
	private Button signIn;
	private EditBox offlineName;
	private Button useOffline;
	private Button forget;

	private EditBox redirectUrl;
	private Button finish;
	private Button reopen;
	private Button back;

	public AuthScreen(Screen parent) {
		super(Component.translatable("mimic.auth.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(W).build());
		layout.visitWidgets(w -> addRenderableWidget(w));

		useSaved = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.use_saved"), b -> runSaved()).width(W).build());
		signIn = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.sign_in"), b -> startBrowserSignIn()).width(W).build());
		offlineName = addRenderableWidget(new EditBox(font, 0, 0, W - 84, 20, Component.translatable("mimic.auth.offline_name")));
		offlineName.setMaxLength(16);
		offlineName.setValue(DevAuth.isMicrosoftSession() ? "Player" : minecraft.getUser().getName());
		useOffline = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.offline"), b -> goOffline()).width(80).build());
		forget = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.forget"), b -> forgetLogin()).width(W).build());

		redirectUrl = addRenderableWidget(new EditBox(font, 0, 0, W, 20, Component.translatable("mimic.auth.paste_hint")));
		redirectUrl.setMaxLength(8192);
		redirectUrl.setHint(Component.translatable("mimic.auth.paste_hint"));
		finish = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.finish"), b -> runFinish()).width(W / 2 - 2).build());
		reopen = addRenderableWidget(Button.builder(Component.translatable("mimic.auth.open_link"), b -> openLogin()).width(W / 2 - 2).build());
		back = addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> {
			mode = Mode.MENU;
			setStatus(Component.empty(), false);
		}).width(W).build());

		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		int x = (width - W) / 2;
		int y = layout.getHeaderHeight() + 34;
		useSaved.setPosition(x, y);
		signIn.setPosition(x, y + ROW);
		offlineName.setPosition(x, y + ROW * 2);
		useOffline.setPosition(x + W - 80, y + ROW * 2);
		forget.setPosition(x, y + ROW * 3);

		redirectUrl.setPosition(x, y + ROW);
		finish.setPosition(x, y + ROW * 2);
		reopen.setPosition(x + W / 2 + 2, y + ROW * 2);
		back.setPosition(x, y + ROW * 3);
	}

	@Override
	public void tick() {
		Mode m = mode;
		boolean saved = DevAuth.hasSavedLogin();
		useSaved.visible = signIn.visible = offlineName.visible = useOffline.visible = forget.visible = m == Mode.MENU;
		useSaved.active = forget.active = saved;
		useOffline.active = !offlineName.getValue().isBlank();
		redirectUrl.visible = finish.visible = reopen.visible = back.visible = m == Mode.PASTE;
		finish.active = redirectUrl.getValue().contains("code=") || redirectUrl.getValue().contains("error=");
	}

	private void startBrowserSignIn() {
		loginUrl = DevAuth.authorizationUrl(true);
		redirectUrl.setValue("");
		mode = Mode.PASTE;
		setStatus(Component.empty(), false);
		setFocused(redirectUrl);
		openLogin();
	}

	private void openLogin() {
		if (loginUrl != null) {
			Blaze3D.openUri(URI.create(loginUrl));
		}
	}

	private void runFinish() {
		String url = redirectUrl.getValue();
		runInBackground(() -> DevAuth.finishSignIn(url, this::setStatusText));
	}

	private void runSaved() {
		runInBackground(() -> DevAuth.signInSaved(this::setStatusText));
	}

	private interface SessionTask {
		MicrosoftAuth.McSession run() throws Exception;
	}

	private void runInBackground(SessionTask task) {
		Mode previous = mode;
		mode = Mode.WORKING;
		setStatus(Component.translatable("mimic.auth.working"), false);
		Thread thread = new Thread(() -> {
			try {
				MicrosoftAuth.McSession session = task.run();
				minecraft.execute(() -> {
					SessionSwitcher.useMicrosoft(minecraft, session);
					setStatus(Component.translatable("mimic.auth.signed_in", session.name()), false);
					mode = Mode.MENU;
				});
			} catch (Exception e) {
				setStatus(Component.literal(e.getMessage() == null ? e.toString() : e.getMessage()), true);
				mode = previous;
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

	private void setStatusText(String message) {
		setStatus(Component.literal(message), false);
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

		if (mode == Mode.PASTE) {
			int top = layout.getHeaderHeight() + 34;
			g.centeredText(font, Component.translatable("mimic.auth.paste_step1"), cx, top, CommonColors.LIGHT_GRAY);
			g.centeredText(font, Component.translatable("mimic.auth.paste_step2"), cx, top + font.lineHeight + 2, CommonColors.LIGHT_GRAY);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
