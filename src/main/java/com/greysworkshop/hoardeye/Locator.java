package com.greysworkshop.hoardeye;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import java.util.ArrayList;
import java.util.List;

/**
 * Guides you to containers picked in the search screen: a box and a tall beam on each one in the world,
 * and an arrow with the distance to the nearest at the top of the screen. Targets clear as you reach them.
 */
public final class Locator {
	public record Target(String dimension, BlockPos pos, String label, ItemStack icon) {}

	private static final int COLOR = 0xFF40E0FF;
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	private static final List<Target> targets = new ArrayList<>();
	/** Where the last marker was drawn; for the self-test log. */
	public static String lastMarker = "";
	private static String what = "";
	private static long foundUntil;
	/** A fetch route being followed (null if not): stops advance when you open their container. */
	private static List<Fetch.Stop> route;
	private static int routeIndex;

	private Locator() {}

	/** Starts guiding to these containers; {@code what} describes what you're looking for. */
	public static void guide(List<Target> list, String what) {
		route = null;
		targets.clear();
		targets.addAll(list);
		Locator.what = what;
		foundUntil = 0;
	}

	/** Starts a fetch route: guides to each stop in turn. */
	public static void guideRoute(List<Fetch.Stop> stops) {
		route = new ArrayList<>(stops);
		routeIndex = 0;
		foundUntil = 0;
		showStop();
	}

	private static void showStop() {
		targets.clear();
		Fetch.Stop stop = route.get(routeIndex);
		targets.add(new Target(stop.dimension(), stop.pos(), stop.label(), stop.takes().get(0).icon()));
		String takes = stop.takes().get(0).count() + " " + stop.takes().get(0).name()
				+ (stop.takes().size() > 1 ? " +" + (stop.takes().size() - 1) + " more" : "");
		what = Component.translatable("hoardeye.fetch.stop", routeIndex + 1, route.size(), takes).getString();
	}

	/** The route stop at this container, if it's the one you're heading to. */
	public static Fetch.Stop routeStopAt(String dimension, BlockPos pos) {
		if (route == null) return null;
		Fetch.Stop stop = route.get(routeIndex);
		return stop.dimension().equals(dimension) && stop.pos().equals(pos) ? stop : null;
	}

	/** You opened the current stop's container: move on to the next stop, or finish. */
	public static void advanceRoute() {
		if (route == null) return;
		Minecraft client = Minecraft.getInstance();
		if (++routeIndex >= route.size()) {
			route = null;
			targets.clear();
			foundUntil = System.currentTimeMillis() + 3000;
			Fetch.clear();
		} else {
			showStop();
		}
		if (ModConfig.INSTANCE.foundSound) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, route == null ? 1.0f : 1.5f));
		}
	}

	/** Which stop you're heading to (0-based); for the self-test. */
	public static int routeIndex() {
		return route == null ? -1 : routeIndex;
	}

	public static Fetch.Stop currentStop() {
		return route == null ? null : route.get(routeIndex);
	}

	public static boolean onRoute() {
		return route != null;
	}

	public static void stop() {
		route = null;
		targets.clear();
	}

	public static boolean active() {
		return !targets.isEmpty();
	}

	public static int count() {
		return targets.size();
	}

	public static void tick(Minecraft client) {
		// On a route, stops are done when you open them, not when you walk past.
		if (targets.isEmpty() || route != null || client.player == null || client.level == null) return;
		String dimension = HoardEyeClient.dimensionKey(client.level);
		double reach = ModConfig.INSTANCE.locatorReach + 0.5;
		boolean removed = targets.removeIf(t -> t.dimension.equals(dimension)
				&& client.player.getEyePosition().distanceTo(Vec3.atCenterOf(t.pos)) <= reach);
		if (!removed) return;
		if (targets.isEmpty()) foundUntil = System.currentTimeMillis() + 3000;
		if (ModConfig.INSTANCE.foundSound) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, targets.isEmpty() ? 1.0f : 1.5f));
		}
	}

	private static Target nearest(Minecraft client) {
		String dimension = HoardEyeClient.dimensionKey(client.level);
		Target best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Target target : targets) {
			if (!target.dimension.equals(dimension)) continue;
			double distance = client.player.distanceToSqr(Vec3.atCenterOf(target.pos));
			if (distance < bestDistance) {
				best = target;
				bestDistance = distance;
			}
		}
		return best;
	}

	public static void renderHud(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null || client.gui.hud.isHidden()) return;
		Font font = client.font;
		int centerX = context.guiWidth() / 2;
		int y = ModConfig.INSTANCE.stripPosition == ModConfig.StripPosition.TOP ? 6 : context.guiHeight() - 62;

		if (targets.isEmpty()) {
			if (System.currentTimeMillis() < foundUntil) {
				drawPill(context, font, Component.translatable("hoardeye.locator.found"), centerX, y, 0xFF55FF55, ItemStack.EMPTY);
			}
			return;
		}

		Target target = nearest(client);
		if (ModConfig.INSTANCE.locatorMarkers) drawMarkers(context, client, font, target);
		if (target == null) {
			String where = targets.get(0).dimension.replace("minecraft:", "").replace('_', ' ');
			drawPill(context, font, Component.translatable("hoardeye.locator.other_dimension", what, where), centerX, y, 0xFFFFAA00, targets.get(0).icon);
			return;
		}

		Vec3 to = Vec3.atCenterOf(target.pos).subtract(client.player.getEyePosition());
		int distance = (int) Math.round(to.length());
		float targetYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		float relative = Mth.wrapDegrees(targetYaw - client.player.getYRot());
		String arrow = ARROWS[Math.floorMod(Math.round(relative / 45f), 8)];
		String vertical = to.y > 3 ? "  ▲" + (int) to.y : to.y < -3 ? "  ▼" + (int) -to.y : "";
		String more = targets.size() > 1 && route == null ? "  (+" + (targets.size() - 1) + ")" : "";

		Component text = Component.literal(arrow + "  ").append(Component.literal(what).withStyle(s -> s.withColor(0xFFFFFF)))
				.append(Component.literal("  " + target.label + "  " + distance + "m" + vertical + more).withStyle(s -> s.withColor(0xBBBBBB)));
		drawPill(context, font, text, centerX, y, COLOR, target.icon);
	}

	/**
	 * Waypoint markers: each target's icon and distance drawn on screen where the container is, on top of
	 * everything, so it shows through walls. Targets off-screen or behind you are pinned to the screen edge
	 * with an arrow pointing towards them.
	 */
	private static void drawMarkers(GuiGraphicsExtractor context, Minecraft client, Font font, Target nearest) {
		Camera camera = client.gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		double yaw = Math.toRadians(camera.yRot()), pitch = Math.toRadians(camera.xRot());
		// Camera axes in world space: forward, right and up.
		Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3 up = right.cross(forward);
		double tanHalfFov = Math.tan(Math.toRadians(client.options.fov().get()) / 2);
		int w = context.guiWidth(), h = context.guiHeight();
		double halfW = w / 2.0, halfH = h / 2.0;
		String dimension = HoardEyeClient.dimensionKey(client.level);
		int margin = 18;

		for (Target target : targets) {
			if (!target.dimension.equals(dimension)) continue;
			Vec3 d = Vec3.atCenterOf(target.pos).subtract(eye);
			double cx = d.dot(right), cy = d.dot(up), cz = d.dot(forward);
			double sx, sy;
			boolean onScreen;
			if (cz > 0.05) {
				sx = halfW + cx / cz / tanHalfFov * halfH;
				sy = halfH - cy / cz / tanHalfFov * halfH;
				onScreen = sx >= margin && sx <= w - margin && sy >= margin && sy <= h - margin;
			} else {
				// Behind you: point the way it would be if you turned round.
				sx = halfW + (Math.abs(cx) + Math.abs(cy) < 1e-3 ? 0 : cx) * 1e4;
				sy = halfH + (Math.abs(cx) + Math.abs(cy) < 1e-3 ? 1 : -cy) * 1e4;
				onScreen = false;
			}
			double angle = Math.atan2(sy - halfH, sx - halfW);
			if (!onScreen) {
				double dx = sx - halfW, dy = sy - halfH;
				double scale = Math.min((halfW - margin) / Math.max(1e-6, Math.abs(dx)), (halfH - margin) / Math.max(1e-6, Math.abs(dy)));
				sx = halfW + dx * scale;
				// Keep clear of the guide strip at the top and the hotbar at the bottom.
				sy = Mth.clamp(halfH + dy * scale, 40, h - 56);
			}
			boolean main = target == nearest;
			int x = (int) Math.round(sx), y = (int) Math.round(sy);
			int ring = main ? COLOR : 0xA040E0FF;
			context.fill(x - 10, y - 10, x + 10, y + 10, main ? 0xB0101018 : 0x80101018);
			context.outline(x - 10, y - 10, 20, 20, ring);
			context.item(target.icon, x - 8, y - 8);
			if (main) {
				// Only the nearest gets a distance, so markers close together stay readable.
				String distance = (int) Math.round(d.length()) + "m";
				int tw = font.width(distance);
				int labelY = y + 12;
				if (labelY + 9 > h) labelY = y - 21;
				context.fill(x - tw / 2 - 2, labelY - 1, x + tw / 2 + 2, labelY + 9, 0xA0000000);
				context.text(font, distance, x - tw / 2, labelY, 0xFFFFFFFF);
			}
			lastMarker = (onScreen ? "on-screen" : "edge") + " at " + x + "," + y + " (camera yaw " + (int) camera.yRot() + ")";
			if (!onScreen) {
				// An arrow just inside the marker, pointing off-screen towards the container.
				Matrix3x2fStack matrices = context.pose();
				matrices.pushMatrix();
				matrices.translate((float) (x - Math.cos(angle) * 16), (float) (y - Math.sin(angle) * 16));
				matrices.rotate((float) (angle + Math.PI / 2));
				context.text(font, "▲", -font.width("▲") / 2, -4, ring, true);
				matrices.popMatrix();
			}
		}
	}

	/** A rounded-looking dark strip with an icon and text, centred on {@code centerX}. */
	private static void drawPill(GuiGraphicsExtractor context, Font font, Component text, int centerX, int y, int accent, ItemStack icon) {
		int iconSpace = icon.isEmpty() ? 0 : 18;
		int width = font.width(text) + iconSpace + 12;
		int x0 = centerX - width / 2, x1 = x0 + width, y1 = y + 20;
		context.fill(x0 + 1, y, x1 - 1, y1, 0xC0101018);
		context.fill(x0, y + 1, x1, y1 - 1, 0xC0101018);
		context.fill(x0 + 1, y1 - 1, x1 - 1, y1, accent);
		int x = x0 + 6;
		if (!icon.isEmpty()) {
			context.item(icon, x, y + 2);
			x += iconSpace;
		}
		context.text(font, text, x, y + 6, accent);
	}

	/** Called every client tick: per-tick gizmos stay on screen until the next tick replaces them. */
	public static void emitWorldMarkers(Minecraft client) {
		if (targets.isEmpty() || client.level == null) return;
		String dimension = HoardEyeClient.dimensionKey(client.level);
		GizmoStyle box = GizmoStyle.stroke(COLOR, 3.0f), beam = GizmoStyle.stroke(COLOR, 2.0f);
		for (Target target : targets) {
			if (!target.dimension.equals(dimension)) continue;
			BlockPos p = target.pos;
			Gizmos.cuboid(new AABB(p).inflate(0.02), box);
			if (ModConfig.INSTANCE.locatorBeam) Gizmos.cuboid(new AABB(p.getX() + 0.4, p.getY() + 1.02, p.getZ() + 0.4,
					p.getX() + 0.6, p.getY() + ModConfig.INSTANCE.beamHeight, p.getZ() + 0.6), beam);
		}
	}
}
