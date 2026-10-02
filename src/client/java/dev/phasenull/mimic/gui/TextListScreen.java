package dev.phasenull.mimic.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A scrollable list of text rows; rows with an action open another screen when clicked. */
public class TextListScreen extends Screen {
	/**
	 * One row. {@code open} (a screen to show) or {@code action} (anything else) make it clickable; both may
	 * be null for plain text. {@code header} rows are drawn in yellow.
	 */
	public record Row(String text, String detail, boolean header, Supplier<Screen> open, Runnable action) {
		public static Row header(String text) {
			return new Row(text, null, true, null, null);
		}

		public static Row text(String text) {
			return new Row(text, null, false, null, null);
		}

		public static Row text(String text, String detail) {
			return new Row(text, detail, false, null, null);
		}

		public static Row link(String text, String detail, Supplier<Screen> open) {
			return new Row(text, detail, false, open, null);
		}

		public static Row action(String text, String detail, Runnable action) {
			return new Row(text, detail, false, null, action);
		}

		boolean clickable() {
			return open != null || action != null;
		}
	}

	private static final int HEADER_COLOR = 0xFFFFFF55;
	private static final int LINK_COLOR = 0xFF55FFFF;

	private final Screen parent;
	private final List<Row> rows;
	private Consumer<List<Path>> onDrop;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private RowList list;

	public TextListScreen(Screen parent, Component title, List<Row> rows) {
		super(title);
		this.parent = parent;
		this.rows = rows;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		layout.addToFooter(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(200).build());
		layout.visitWidgets(w -> addRenderableWidget(w));
		list = addRenderableWidget(new RowList(minecraft));
		rows.forEach(r -> list.add(new RowEntry(r)));
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		list.updateSize(width, layout);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** Files dragged onto the window go to {@code handler}. */
	public TextListScreen onFilesDropped(Consumer<List<Path>> handler) {
		this.onDrop = handler;
		return this;
	}

	@Override
	public void onFilesDrop(List<Path> files) {
		if (onDrop != null) {
			onDrop.accept(files);
		}
	}

	private class RowList extends ObjectSelectionList<RowEntry> {
		RowList(Minecraft minecraft) {
			super(minecraft, TextListScreen.this.width, TextListScreen.this.height, 0, font.lineHeight * 2 + 4);
		}

		void add(RowEntry entry) {
			addEntry(entry);
		}

		@Override
		public int getRowWidth() {
			return Math.min(400, getWidth() - 20);
		}
	}

	private class RowEntry extends ObjectSelectionList.Entry<RowEntry> {
		private final Row row;

		RowEntry(Row row) {
			this.row = row;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
			int w = getContentWidth();
			int color = row.header() ? HEADER_COLOR : row.clickable() ? LINK_COLOR : CommonColors.WHITE;
			String text = (row.clickable() ? "> " : "") + row.text();
			g.text(font, font.plainSubstrByWidth(text, w), getContentX(), getContentY(), color);
			if (row.detail() != null) {
				g.text(font, font.plainSubstrByWidth(row.detail(), w), getContentX(), getContentY() + font.lineHeight + 1, CommonColors.GRAY);
			}
		}

		@Override
		public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
			if (row.open() != null) {
				minecraft.gui.setScreen(row.open().get());
				return true;
			}
			if (row.action() != null) {
				row.action().run();
				return true;
			}
			return super.mouseClicked(event, doubleClick);
		}

		@Override
		public Component getNarration() {
			return Component.literal(row.text());
		}
	}
}
