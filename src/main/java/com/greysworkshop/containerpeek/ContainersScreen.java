package com.greysworkshop.containerpeek;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.EnumMap;
import java.util.Map;

/** Lets the player pick which container types get labels. */
public class ContainersScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int ROW_HEIGHT = 22;
	private static final int TOP = 32;

	private final Screen parent;
	private final Map<ContainerKind, ButtonWidget> buttons = new EnumMap<>(ContainerKind.class);

	public ContainersScreen(Screen parent) {
		super(Text.translatable("containerpeek.config.containers_title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		buttons.clear();
		ContainerKind[] kinds = ContainerKind.values();
		for (int i = 0; i < kinds.length; i++) {
			ContainerKind kind = kinds[i];
			int x = width / 2 + (i % 2 == 0 ? -BUTTON_WIDTH - 5 : 5);
			int y = TOP + (i / 2) * ROW_HEIGHT;
			ButtonWidget button = ButtonWidget.builder(label(kind), b -> {
				ModConfig.INSTANCE.setShown(kind, !ModConfig.INSTANCE.isShown(kind));
				b.setMessage(label(kind));
			}).dimensions(x, y, BUTTON_WIDTH, 20).build();
			buttons.put(kind, addDrawableChild(button));
		}

		int y = height - 28;
		addDrawableChild(ButtonWidget.builder(Text.translatable("containerpeek.config.show_all"), b -> setAll(true))
				.dimensions(width / 2 - 155, y, 100, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("containerpeek.config.hide_all"), b -> setAll(false))
				.dimensions(width / 2 - 50, y, 100, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(width / 2 + 55, y, 100, 20).build());
	}

	private static Text label(ContainerKind kind) {
		return ScreenTexts.composeToggleText(kind.getName(), ModConfig.INSTANCE.isShown(kind));
	}

	private void setAll(boolean shown) {
		for (ContainerKind kind : ContainerKind.values()) {
			ModConfig.INSTANCE.setShown(kind, shown);
			buttons.get(kind).setMessage(label(kind));
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		ModConfig.save();
		client.setScreen(parent);
	}
}
