package com.greysworkshop.containerpeek;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.List;
import java.util.Locale;

/** Container Peek settings, in tabs. */
public class ConfigScreen extends OptionsScreen {
	private static final String P = "containerpeek.config";

	public ConfigScreen(Screen parent) {
		super(parent, Text.translatable(P + ".title"), P);
	}

	@Override
	protected List<Tab> tabs() {
		ModConfig c = ModConfig.INSTANCE;
		return List.of(
				new Tab("general", r -> {
					r.toggle("enabled", () -> c.enabled, v -> c.enabled = v);
					r.header("header.remember");
					r.toggle("remember", () -> c.rememberContainers, v -> c.rememberContainers = v);
					r.toggle("forget_broken", () -> c.forgetBroken, v -> c.forgetBroken = v);
					r.header("header.show");
					r.toggle("show_names", () -> c.showNames, v -> c.showNames = v);
					r.toggle("show_contents", () -> c.showContents, v -> c.showContents = v);
					r.toggle("show_unknown_hint", () -> c.showUnknownHint, v -> c.showUnknownHint = v);
					r.header("header.tooltip");
					r.toggle("shulker_tooltip", () -> c.shulkerTooltip, v -> c.shulkerTooltip = v);
					r.toggle("shulker_shift", () -> c.shulkerTooltipShiftOnly, v -> c.shulkerTooltipShiftOnly = v);
				}),
				new Tab("label", r -> {
					r.header("header.placement");
					r.cycle("position", HudPosition.values(), () -> c.hudPosition, v -> c.hudPosition = v, HudPosition::getName);
					r.slider("y_offset", 0, 100, () -> c.yOffset, v -> c.yOffset = v, v -> v + " px");
					r.slider("scale", 50, 200, () -> c.scalePercent, v -> c.scalePercent = v / 5 * 5, v -> v / 5 * 5 + "%");
					r.header("header.items");
					r.slider("max_icons", 1, 27, () -> c.maxIcons, v -> c.maxIcons = v, String::valueOf);
					r.slider("icons_per_row", 1, 27, () -> c.iconsPerRow, v -> c.iconsPerRow = v, String::valueOf);
					r.toggle("show_more", () -> c.showMoreCount, v -> c.showMoreCount = v);
					r.cycle("count_format", ModConfig.CountFormat.values(), () -> c.countFormat, v -> c.countFormat = v,
							v -> Text.translatable(P + ".count_format." + v.name().toLowerCase(Locale.ROOT)));
					r.header("header.look");
					r.cycle("name_color", ModConfig.NameColor.values(), () -> c.nameColor, v -> c.nameColor = v,
							v -> Text.translatable(P + ".name_color." + v.name().toLowerCase(Locale.ROOT)).styled(s -> s.withColor(v.rgb)));
					r.toggle("background", () -> c.labelBackground, v -> c.labelBackground = v);
					r.slider("background_opacity", 10, 100, () -> c.backgroundOpacity, v -> c.backgroundOpacity = v, v -> v + "%");
					r.header("header.when");
					r.slider("max_distance", 2, 64, () -> c.maxDistance, v -> c.maxDistance = v,
							v -> Text.translatable(P + ".blocks", v).getString());
					r.toggle("hide_sneaking", () -> c.hideWhileSneaking, v -> c.hideWhileSneaking = v);
				}),
				new Tab("containers", r -> {
					r.note(Text.translatable(P + ".containers_note"));
					r.button("show_all", () -> {
						for (ContainerKind kind : ContainerKind.values()) c.setShown(kind, true);
						clearAndInit();
					});
					r.button("hide_all", () -> {
						for (ContainerKind kind : ContainerKind.values()) c.setShown(kind, false);
						clearAndInit();
					});
					for (ContainerKind kind : ContainerKind.values()) {
						r.toggle(kind.getName(), Text.translatable(P + ".kind.desc", kind.getName()),
								() -> c.isShown(kind), v -> c.setShown(kind, v));
					}
				}),
				new Tab("search", r -> {
					r.note(Text.translatable(P + ".search_note"));
					r.header("header.locator");
					r.toggle("locator_beam", () -> c.locatorBeam, v -> c.locatorBeam = v);
					r.slider("beam_height", 16, 256, () -> c.beamHeight, v -> c.beamHeight = v / 8 * 8,
							v -> Text.translatable(P + ".blocks", v / 8 * 8).getString());
					r.slider("locator_reach", 2, 10, () -> c.locatorReach, v -> c.locatorReach = v,
							v -> Text.translatable(P + ".blocks", v).getString());
					r.cycle("strip_position", ModConfig.StripPosition.values(), () -> c.stripPosition, v -> c.stripPosition = v,
							v -> Text.translatable(P + ".strip_position." + v.name().toLowerCase(Locale.ROOT)));
					r.toggle("found_sound", () -> c.foundSound, v -> c.foundSound = v);
				}),
				new Tab("data", r -> {
					r.note(Text.translatable(P + ".data_note"));
					r.button("forget_world", () -> confirm(Text.translatable(P + ".forget_world.confirm"), () -> {
						String world = ContainerPeekClient.worldKey(client);
						if (world != null) {
							ContainerPeekClient.INSTANCE.store().clearWorld(world);
							ContainerPeekClient.INSTANCE.store().saveIfDirty();
						}
					}));
					r.button("forget_all", () -> confirm(Text.translatable(P + ".forget_all.confirm"), () -> {
						ContainerPeekClient.INSTANCE.store().clearAll();
						ContainerPeekClient.INSTANCE.store().saveIfDirty();
					}));
					r.button("open_folder", () -> Util.getOperatingSystem().open(FabricLoader.getInstance().getConfigDir()));
				}));
	}

	private void confirm(Text message, Runnable action) {
		client.setScreen(new ConfirmScreen(yes -> {
			if (yes) action.run();
			client.setScreen(this);
		}, Text.translatable(P + ".are_you_sure"), message));
	}

	@Override
	protected void save() {
		ModConfig.save();
	}
}
