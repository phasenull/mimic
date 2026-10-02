package dev.phasenull.mimic.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.CommonColors;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ModListScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int GAP = 12;
	private static final int SEARCH_HEIGHT = 20;

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private EditBox search;
	private ModList list;

	public ModListScreen(Screen parent) {
		super(Component.translatable("mimic.mods.title", topLevelMods().size()));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		layout.visitWidgets(w -> addRenderableWidget(w));

		search = new EditBox(font, 0, 0, 100, SEARCH_HEIGHT, Component.translatable("mimic.mods.search"));
		search.setHint(Component.translatable("mimic.mods.search"));
		list = new ModList(minecraft, 100, 100, 0);
		search.setResponder(list::filter);
		list.filter("");

		addRenderableWidget(search);
		addRenderableWidget(list);
		repositionElements();
		setInitialFocus(search);
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		int top = layout.getHeaderHeight() + 4;
		int bottom = height - layout.getFooterHeight() - 4;
		int listWidth = listWidth();

		search.setX(MARGIN);
		search.setY(top);
		search.setWidth(listWidth);
		list.updateSizeAndPosition(listWidth, bottom - top - SEARCH_HEIGHT - 4, MARGIN, top + SEARCH_HEIGHT + 4);
	}

	private int listWidth() {
		return Math.max(140, Math.min(260, width * 2 / 5));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);

		int x = MARGIN + listWidth() + GAP;
		int y = layout.getHeaderHeight() + 6;
		int w = width - x - MARGIN;
		ModEntry selected = list.getSelected();
		if (selected == null) {
			g.text(font, Component.translatable("mimic.mods.select"), x, y, CommonColors.GRAY);
			return;
		}

		ModMetadata meta = selected.mod.getMetadata();
		g.text(font, Component.literal(meta.getName()).withStyle(s -> s.withBold(true)), x, y, CommonColors.WHITE);
		y += font.lineHeight + 6;
		y = field(g, "mimic.mods.version", meta.getVersion().getFriendlyString(), x, y, w);
		y = field(g, "mimic.mods.id", meta.getId(), x, y, w);
		String authors = meta.getAuthors().stream().map(Person::getName).collect(Collectors.joining(", "));
		if (!authors.isEmpty()) {
			y = field(g, "mimic.mods.authors", authors, x, y, w);
		}
		if (!meta.getLicense().isEmpty()) {
			y = field(g, "mimic.mods.license", String.join(", ", meta.getLicense()), x, y, w);
		}
		var parentMod = selected.mod.getContainingMod();
		if (parentMod.isPresent()) {
			y = field(g, "mimic.mods.bundled_in", parentMod.get().getMetadata().getName(), x, y, w);
		}
		int nested = selected.mod.getContainedMods().size();
		if (nested > 0) {
			y = field(g, "mimic.mods.includes", Integer.toString(nested), x, y, w);
		}
		if (!meta.getDescription().isEmpty()) {
			y += 6;
			g.textWithWordWrap(font, FormattedText.of(meta.getDescription()), x, y, w, CommonColors.LIGHT_GRAY);
		}
	}

	private int field(GuiGraphicsExtractor g, String key, String value, int x, int y, int w) {
		Component line = Component.translatable(key).withStyle(s -> s.withColor(CommonColors.GRAY))
			.append(Component.literal(" " + value).withStyle(s -> s.withColor(CommonColors.WHITE)));
		g.text(font, font.split(line, w).getFirst(), x, y, CommonColors.WHITE);
		return y + font.lineHeight + 3;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private static List<ModContainer> topLevelMods() {
		return FabricLoader.getInstance().getAllMods().stream()
			.filter(m -> m.getContainingMod().isEmpty())
			.toList();
	}

	private static Comparator<ModContainer> byName() {
		return Comparator.comparing(m -> m.getMetadata().getName().toLowerCase(Locale.ROOT));
	}

	private class ModList extends ObjectSelectionList<ModEntry> {
		ModList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, font.lineHeight * 2 + 6);
		}

		void filter(String query) {
			String q = query.trim().toLowerCase(Locale.ROOT);
			List<ModContainer> mods = q.isEmpty()
				? topLevelMods()
				: FabricLoader.getInstance().getAllMods().stream().filter(m -> matches(m, q)).toList();
			replaceEntries(mods.stream().sorted(byName()).map(ModEntry::new).toList());
			setScrollAmount(0);
		}

		private static boolean matches(ModContainer mod, String q) {
			ModMetadata meta = mod.getMetadata();
			return meta.getId().toLowerCase(Locale.ROOT).contains(q) || meta.getName().toLowerCase(Locale.ROOT).contains(q);
		}

		@Override
		public int getRowWidth() {
			return getWidth() - 14;
		}
	}

	private class ModEntry extends ObjectSelectionList.Entry<ModEntry> {
		final ModContainer mod;

		ModEntry(ModContainer mod) {
			this.mod = mod;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
			ModMetadata meta = mod.getMetadata();
			int w = getContentWidth();
			g.text(font, font.plainSubstrByWidth(meta.getName(), w), getContentX(), getContentY(), CommonColors.WHITE);
			String sub = meta.getVersion().getFriendlyString() + "  " + meta.getId();
			g.text(font, font.plainSubstrByWidth(sub, w), getContentX(), getContentY() + font.lineHeight + 2, CommonColors.GRAY);
		}

		@Override
		public Component getNarration() {
			return Component.literal(mod.getMetadata().getName());
		}
	}
}
