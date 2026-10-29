package com.greysworkshop.hoardeye;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
	private record Explosion(Vec3d center, double reach, long at) {}
	private static final ArrayDeque<Explosion> explosions = new ArrayDeque<>();

	private Sight() {}

	/** Close by and in clear line of sight from your eyes, or the server said it was broken. */
	public static boolean certain(ClientWorld world, Entity viewer, BlockPos pos) {
		return proven(pos) || inPlainView(world, viewer, pos);
	}

	public static boolean inPlainView(ClientWorld world, Entity viewer, BlockPos pos) {
		if (viewer == null) return false;
		Vec3d eye = viewer.getEyePos();
		Vec3d center = Vec3d.ofCenter(pos);
		if (eye.distanceTo(center) > RANGE) return false;
		if (clear(world, viewer, eye, center, pos)) return true;
		for (Direction d : Direction.values()) {
			Vec3d face = center.add(d.getOffsetX() * 0.45, d.getOffsetY() * 0.45, d.getOffsetZ() * 0.45);
			// Only the sides that face you can be seen.
			if (face.subtract(center).dotProduct(eye.subtract(center)) <= 0) continue;
			if (clear(world, viewer, eye, face, pos)) return true;
		}
		return false;
	}

	private static boolean clear(ClientWorld world, Entity viewer, Vec3d eye, Vec3d to, BlockPos target) {
		BlockHitResult hit = world.raycast(new RaycastContext(eye, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, viewer));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
	}

	/** The server's "block broken" effect: sent to players nearby when a block really breaks (before the block update). */
	public static void broken(BlockPos pos) {
		long now = System.currentTimeMillis();
		broken.put(pos.asLong(), now);
		if (broken.size() > 4096) broken.values().removeIf(t -> now - t > PROOF_MS);
	}

	public static void exploded(Vec3d center, float radius) {
		long now = System.currentTimeMillis();
		// An explosion breaks blocks out to about twice its power from the centre.
		explosions.addLast(new Explosion(center, radius * 2.0 + 1, now));
		while (!explosions.isEmpty() && (explosions.size() > 256 || now - explosions.peekFirst().at() > PROOF_MS)) explosions.removeFirst();
	}

	public static boolean proven(BlockPos pos) {
		long now = System.currentTimeMillis();
		Long at = broken.get(pos.asLong());
		if (at != null && now - at <= PROOF_MS) return true;
		Vec3d c = Vec3d.ofCenter(pos);
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
