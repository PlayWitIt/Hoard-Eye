package com.greysworkshop.hoardeye;

import net.minecraft.block.CopperChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.text.Text;

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

	public Text getName() {
		return Text.translatable(translationKey);
	}

	/** Returns the kind of container {@code be} is, or null if it isn't one we track. */
	public static ContainerKind of(BlockEntity be) {
		// Subclasses before their parents: trapped chest extends chest, dropper extends dispenser.
		if (be instanceof ShulkerBoxBlockEntity) return SHULKER_BOX;
		if (be instanceof TrappedChestBlockEntity) return TRAPPED_CHEST;
		if (be instanceof ChestBlockEntity) {
			return be.getCachedState().getBlock() instanceof CopperChestBlock ? COPPER_CHEST : CHEST;
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
		if (be instanceof LockableContainerBlockEntity) return OTHER;
		return null;
	}
}
