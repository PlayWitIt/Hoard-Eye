package com.greysworkshop.containerpeek;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class ConfigScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int ROW_HEIGHT = 24;

	private final Screen parent;
	private int row;
	private int column;

	public ConfigScreen(Screen parent) {
		super(Text.translatable("containerpeek.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig c = ModConfig.INSTANCE;
		row = 0;
		column = 0;

		toggle("enabled", () -> c.enabled, v -> c.enabled = v);
		addDrawableChild(ButtonWidget.builder(Text.translatable("containerpeek.config.containers"),
				b -> client.setScreen(new ContainersScreen(this)))
				.dimensions(nextX(), nextY(), BUTTON_WIDTH, 20).build());
		advance();
		toggle("show_names", () -> c.showNames, v -> c.showNames = v);
		toggle("show_contents", () -> c.showContents, v -> c.showContents = v);
		toggle("show_unknown_hint", () -> c.showUnknownHint, v -> c.showUnknownHint = v);
		Text positionLabel = Text.translatable("containerpeek.config.position");
		if (c.hudPosition == null) c.hudPosition = HudPosition.BELOW_CROSSHAIR;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.composeGenericOptionText(positionLabel, c.hudPosition.getName()), b -> {
			c.hudPosition = c.hudPosition.next();
			b.setMessage(ScreenTexts.composeGenericOptionText(positionLabel, c.hudPosition.getName()));
		}).dimensions(nextX(), nextY(), BUTTON_WIDTH, 20).build());
		advance();
		slider("max_icons", "", 1, 27, () -> c.maxIcons, v -> c.maxIcons = v);
		slider("scale", "%", 50, 200, () -> c.scalePercent, v -> c.scalePercent = v);
		slider("y_offset", "", 0, 100, () -> c.yOffset, v -> c.yOffset = v);

		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(width / 2 - 100, height - 28, 200, 20).build());
	}

	private int nextX() {
		return width / 2 + (column == 0 ? -BUTTON_WIDTH - 5 : 5);
	}

	private int nextY() {
		return 36 + row * ROW_HEIGHT;
	}

	private void advance() {
		if (++column == 2) {
			column = 0;
			row++;
		}
	}

	private void toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
		Text label = Text.translatable("containerpeek.config." + key);
		addDrawableChild(ButtonWidget.builder(ScreenTexts.composeToggleText(label, getter.getAsBoolean()), b -> {
			setter.accept(!getter.getAsBoolean());
			b.setMessage(ScreenTexts.composeToggleText(label, getter.getAsBoolean()));
		}).dimensions(nextX(), nextY(), BUTTON_WIDTH, 20).build());
		advance();
	}

	private void slider(String key, String suffix, int min, int max, IntSupplier getter, IntConsumer setter) {
		Text label = Text.translatable("containerpeek.config." + key);
		double start = (getter.getAsInt() - min) / (double) (max - min);
		SliderWidget slider = new SliderWidget(nextX(), nextY(), BUTTON_WIDTH, 20, Text.empty(), start) {
			{
				updateMessage();
			}

			private int current() {
				return min + (int) Math.round(MathHelper.clamp(value, 0, 1) * (max - min));
			}

			@Override
			protected void updateMessage() {
				setMessage(Text.empty().append(label).append(": " + current() + suffix));
			}

			@Override
			protected void applyValue() {
				setter.accept(current());
			}
		};
		addDrawableChild(slider);
		advance();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 15, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		ModConfig.save();
		client.setScreen(parent);
	}
}
