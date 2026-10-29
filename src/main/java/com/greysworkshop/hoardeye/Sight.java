package com.greysworkshop.hoardeye;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * When a container that seems gone really is. Servers that hide chests (anti-ESP) send air in place of every
 * container you can't see, and anti-xray sends stone in place of sealed-in ones. Neither touches one in plain view
 * close by, and neither sends the "block broken" effect or an explosion. So a container only counts as gone when
 * its spot is in plain view, close by, or the server said it broke.
 */
public final class Sight {
	/** Hiders always show containers this close, in sight. */
	public static final double RANGE = 16;
	/** The server's word that a block broke counts for this long. */
	private static final long PROOF_MS = 60_000;

	private static final Map<Long, Long> broken = new HashMap<>();
	private record Explosion(Vec3 center, double reach, long at) {}
	private static final ArrayDeque<Explosion> explosions = new ArrayDeque<>();

	private Sight() {}

	/** Close by and in clear line of sight from your eyes, or the server said it was broken. */
	public static boolean certain(ClientLevel world, Entity viewer, BlockPos pos) {
		return proven(pos) || inPlainView(world, viewer, pos);
	}

	public static boolean inPlainView(ClientLevel world, Entity viewer, BlockPos pos) {
		if (viewer == null) return false;
		Vec3 eye = viewer.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		if (eye.distanceTo(center) > RANGE) return false;
		if (clear(world, viewer, eye, center, pos)) return true;
		for (Direction d : Direction.values()) {
			Vec3 face = center.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
			// Only the sides that face you can be seen.
			if (face.subtract(center).dot(eye.subtract(center)) <= 0) continue;
			if (clear(world, viewer, eye, face, pos)) return true;
		}
		return false;
	}

	private static boolean clear(ClientLevel world, Entity viewer, Vec3 eye, Vec3 to, BlockPos target) {
		BlockHitResult hit = world.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
	}

	/** The server's "block broken" effect: sent to players nearby when a block really breaks (before the block update). */
	public static void broken(BlockPos pos) {
		long now = System.currentTimeMillis();
		broken.put(pos.asLong(), now);
		if (broken.size() > 4096) broken.values().removeIf(t -> now - t > PROOF_MS);
	}

	public static void exploded(Vec3 center, float radius) {
		long now = System.currentTimeMillis();
		// An explosion breaks blocks out to about twice its power from the centre.
		explosions.addLast(new Explosion(center, radius * 2.0 + 1, now));
		while (!explosions.isEmpty() && (explosions.size() > 256 || now - explosions.peekFirst().at() > PROOF_MS)) explosions.removeFirst();
	}

	public static boolean proven(BlockPos pos) {
		long now = System.currentTimeMillis();
		Long at = broken.get(pos.asLong());
		if (at != null && now - at <= PROOF_MS) return true;
		Vec3 c = Vec3.atCenterOf(pos);
		for (Explosion e : explosions) {
			if (now - e.at() <= PROOF_MS && e.center().distanceTo(c) <= e.reach()) return true;
		}
		return false;
	}

	public static void clear() {
		broken.clear();
		explosions.clear();
	}
}
