package com.greysworkshop.containerpeek;

import net.minecraft.text.Text;

/** Where on screen the label is drawn. */
public enum HudPosition {
	BELOW_CROSSHAIR("below_crosshair", Align.CENTER),
	ABOVE_CROSSHAIR("above_crosshair", Align.CENTER),
	RIGHT_OF_CROSSHAIR("right_of_crosshair", Align.LEFT),
	TOP_LEFT("top_left", Align.LEFT),
	TOP_CENTER("top_center", Align.CENTER),
	TOP_RIGHT("top_right", Align.RIGHT),
	BOTTOM_LEFT("bottom_left", Align.LEFT),
	BOTTOM_RIGHT("bottom_right", Align.RIGHT);

	private enum Align { LEFT, CENTER, RIGHT }

	private final String key;
	private final Align align;

	HudPosition(String key, Align align) {
		this.key = key;
		this.align = align;
	}

	public Text getName() {
		return Text.translatable("containerpeek.config.position." + key);
	}

	public HudPosition next() {
		HudPosition[] values = values();
		return values[(ordinal() + 1) % values.length];
	}

	/** X offset of a line {@code lineWidth} wide inside a label {@code width} wide. */
	public int align(int width, int lineWidth) {
		return switch (align) {
			case LEFT -> 0;
			case CENTER -> (width - lineWidth) / 2;
			case RIGHT -> width - lineWidth;
		};
	}
}
