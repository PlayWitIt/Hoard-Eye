package com.greysworkshop.shulkernames;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;

/** The container groups that can be switched on and off in the config. */
public enum ContainerKind {
	SHULKER_BOX,
	/** Chests, trapped chests and copper chests. */
	CHEST,
	BARREL,
	ENDER_CHEST,
	/** Hoppers, furnaces, dispensers, droppers, brewing stands, crafters, etc. */
	OTHER;

	/** Returns the kind of container {@code be} is, or null if it isn't one we track. */
	public static ContainerKind of(BlockEntity be) {
		if (be instanceof ShulkerBoxBlockEntity) return SHULKER_BOX;
		if (be instanceof ChestBlockEntity) return CHEST;
		if (be instanceof BarrelBlockEntity) return BARREL;
		if (be instanceof EnderChestBlockEntity) return ENDER_CHEST;
		if (be instanceof LockableContainerBlockEntity) return OTHER;
		return null;
	}
}
