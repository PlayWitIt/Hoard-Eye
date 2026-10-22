package com.greysworkshop.hoardeye;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds a config button to this mod's entry in Mod Menu. Only loaded when Mod Menu is installed. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return ConfigScreen::new;
	}
}
