package com.greysworkshop.hoardeye;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;

/** The container types that can each be switched on and off in the config. */
public enum ContainerKind {
	SHULKER_BOX("block.minecraft.shulker_box"),
	CHEST("block.minecraft.chest"),
	TRAPPED_CHEST("block.minecraft.trapped_chest"),
	COPPER_CHEST("block.minecraft.copper_chest"),
	BARREL("block.minecraft.barrel"),
	ENDER_CHEST("block.minecraft.ender_chest"),
	HOPPER("block.minecraft.hopper"),
	DROPPER("block.minecraft.dropper"),
	DISPENSER("block.minecraft.dispenser"),
	FURNACE("block.minecraft.furnace"),
	SMOKER("block.minecraft.smoker"),
	BLAST_FURNACE("block.minecraft.blast_furnace"),
	BREWING_STAND("block.minecraft.brewing_stand"),
	CRAFTER("block.minecraft.crafter"),
	/** Any other container, e.g. from other mods. */
	OTHER("hoardeye.config.kind.other");

	private final String translationKey;

	ContainerKind(String translationKey) {
		this.translationKey = translationKey;
	}

	public Component getName() {
		return Component.translatable(translationKey);
	}

	/** Returns the kind of container {@code be} is, or null if it isn't one we track. */
	public static ContainerKind of(BlockEntity be) {
		// Subclasses before their parents: trapped chest extends chest, dropper extends dispenser.
		if (be instanceof ShulkerBoxBlockEntity) return SHULKER_BOX;
		if (be instanceof TrappedChestBlockEntity) return TRAPPED_CHEST;
		if (be instanceof ChestBlockEntity) {
			return be.getBlockState().getBlock() instanceof CopperChestBlock ? COPPER_CHEST : CHEST;
		}
		if (be instanceof BarrelBlockEntity) return BARREL;
		if (be instanceof EnderChestBlockEntity) return ENDER_CHEST;
		if (be instanceof HopperBlockEntity) return HOPPER;
		if (be instanceof DropperBlockEntity) return DROPPER;
		if (be instanceof DispenserBlockEntity) return DISPENSER;
		if (be instanceof FurnaceBlockEntity) return FURNACE;
		if (be instanceof SmokerBlockEntity) return SMOKER;
		if (be instanceof BlastFurnaceBlockEntity) return BLAST_FURNACE;
		if (be instanceof BrewingStandBlockEntity) return BREWING_STAND;
		if (be instanceof CrafterBlockEntity) return CRAFTER;
		if (be instanceof BaseContainerBlockEntity) return OTHER;
		return null;
	}
}
