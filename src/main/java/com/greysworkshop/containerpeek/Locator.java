package com.greysworkshop.containerpeek;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
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
	private static final VoxelShape BOX = VoxelShapes.cuboid(-0.02, -0.02, -0.02, 1.02, 1.02, 1.02);
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	private static final List<Target> targets = new ArrayList<>();
	/** Where the last marker was drawn; for the self-test log. */
	public static String lastMarker = "";
	private static String what = "";
	private static long foundUntil;

	private Locator() {}

	/** Starts guiding to these containers; {@code what} describes what you're looking for. */
	public static void guide(List<Target> list, String what) {
		targets.clear();
		targets.addAll(list);
		Locator.what = what;
		foundUntil = 0;
	}

	public static void stop() {
		targets.clear();
	}

	public static boolean active() {
		return !targets.isEmpty();
	}

	public static int count() {
		return targets.size();
	}

	public static void tick(MinecraftClient client) {
		if (targets.isEmpty() || client.player == null || client.world == null) return;
		String dimension = ContainerPeekClient.dimensionKey(client.world);
		double reach = ModConfig.INSTANCE.locatorReach + 0.5;
		boolean removed = targets.removeIf(t -> t.dimension.equals(dimension)
				&& client.player.getEyePos().distanceTo(Vec3d.ofCenter(t.pos)) <= reach);
		if (!removed) return;
		if (targets.isEmpty()) foundUntil = System.currentTimeMillis() + 3000;
		if (ModConfig.INSTANCE.foundSound) {
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, targets.isEmpty() ? 1.0f : 1.5f));
		}
	}

	private static Target nearest(MinecraftClient client) {
		String dimension = ContainerPeekClient.dimensionKey(client.world);
		Target best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Target target : targets) {
			if (!target.dimension.equals(dimension)) continue;
			double distance = client.player.squaredDistanceTo(Vec3d.ofCenter(target.pos));
			if (distance < bestDistance) {
				best = target;
				bestDistance = distance;
			}
		}
		return best;
	}

	public static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.options.hudHidden) return;
		TextRenderer font = client.textRenderer;
		int centerX = context.getScaledWindowWidth() / 2;
		int y = ModConfig.INSTANCE.stripPosition == ModConfig.StripPosition.TOP ? 6 : context.getScaledWindowHeight() - 62;

		if (targets.isEmpty()) {
			if (System.currentTimeMillis() < foundUntil) {
				drawPill(context, font, Text.translatable("containerpeek.locator.found"), centerX, y, 0xFF55FF55, ItemStack.EMPTY);
			}
			return;
		}

		Target target = nearest(client);
		if (ModConfig.INSTANCE.locatorMarkers) drawMarkers(context, client, font, target);
		if (target == null) {
			String where = targets.get(0).dimension.replace("minecraft:", "").replace('_', ' ');
			drawPill(context, font, Text.translatable("containerpeek.locator.other_dimension", what, where), centerX, y, 0xFFFFAA00, targets.get(0).icon);
			return;
		}

		Vec3d to = Vec3d.ofCenter(target.pos).subtract(client.player.getEyePos());
		int distance = (int) Math.round(to.length());
		float targetYaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		float relative = MathHelper.wrapDegrees(targetYaw - client.player.getYaw());
		String arrow = ARROWS[Math.floorMod(Math.round(relative / 45f), 8)];
		String vertical = to.y > 3 ? "  ▲" + (int) to.y : to.y < -3 ? "  ▼" + (int) -to.y : "";
		String more = targets.size() > 1 ? "  (+" + (targets.size() - 1) + ")" : "";

		Text text = Text.literal(arrow + "  ").append(Text.literal(what).styled(s -> s.withColor(0xFFFFFF)))
				.append(Text.literal("  " + target.label + "  " + distance + "m" + vertical + more).styled(s -> s.withColor(0xBBBBBB)));
		drawPill(context, font, text, centerX, y, COLOR, target.icon);
	}

	/**
	 * Waypoint markers: each target's icon and distance drawn on screen where the container is, on top of
	 * everything, so it shows through walls. Targets off-screen or behind you are pinned to the screen edge
	 * with an arrow pointing towards them.
	 */
	private static void drawMarkers(DrawContext context, MinecraftClient client, TextRenderer font, Target nearest) {
		Camera camera = client.gameRenderer.getCamera();
		Vec3d eye = camera.getCameraPos();
		double yaw = Math.toRadians(camera.getYaw()), pitch = Math.toRadians(camera.getPitch());
		// Camera axes in world space: forward, right and up.
		Vec3d forward = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
		Vec3d up = right.crossProduct(forward);
		double tanHalfFov = Math.tan(Math.toRadians(client.options.getFov().getValue()) / 2);
		int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
		double halfW = w / 2.0, halfH = h / 2.0;
		String dimension = ContainerPeekClient.dimensionKey(client.world);
		int margin = 18;

		for (Target target : targets) {
			if (!target.dimension.equals(dimension)) continue;
			Vec3d d = Vec3d.ofCenter(target.pos).subtract(eye);
			double cx = d.dotProduct(right), cy = d.dotProduct(up), cz = d.dotProduct(forward);
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
				sy = MathHelper.clamp(halfH + dy * scale, 40, h - 56);
			}
			boolean main = target == nearest;
			int x = (int) Math.round(sx), y = (int) Math.round(sy);
			int ring = main ? COLOR : 0xA040E0FF;
			context.fill(x - 10, y - 10, x + 10, y + 10, main ? 0xB0101018 : 0x80101018);
			context.drawStrokedRectangle(x - 10, y - 10, 20, 20, ring);
			context.drawItem(target.icon, x - 8, y - 8);
			if (main) {
				// Only the nearest gets a distance, so markers close together stay readable.
				String distance = (int) Math.round(d.length()) + "m";
				int tw = font.getWidth(distance);
				int labelY = y + 12;
				if (labelY + 9 > h) labelY = y - 21;
				context.fill(x - tw / 2 - 2, labelY - 1, x + tw / 2 + 2, labelY + 9, 0xA0000000);
				context.drawTextWithShadow(font, distance, x - tw / 2, labelY, 0xFFFFFFFF);
			}
			lastMarker = (onScreen ? "on-screen" : "edge") + " at " + x + "," + y + " (camera yaw " + (int) camera.getYaw() + ")";
			if (!onScreen) {
				// An arrow just inside the marker, pointing off-screen towards the container.
				Matrix3x2fStack matrices = context.getMatrices();
				matrices.pushMatrix();
				matrices.translate((float) (x - Math.cos(angle) * 16), (float) (y - Math.sin(angle) * 16));
				matrices.rotate((float) (angle + Math.PI / 2));
				context.drawText(font, "▲", -font.getWidth("▲") / 2, -4, ring, true);
				matrices.popMatrix();
			}
		}
	}

	/** A rounded-looking dark strip with an icon and text, centred on {@code centerX}. */
	private static void drawPill(DrawContext context, TextRenderer font, Text text, int centerX, int y, int accent, ItemStack icon) {
		int iconSpace = icon.isEmpty() ? 0 : 18;
		int width = font.getWidth(text) + iconSpace + 12;
		int x0 = centerX - width / 2, x1 = x0 + width, y1 = y + 20;
		context.fill(x0 + 1, y, x1 - 1, y1, 0xC0101018);
		context.fill(x0, y + 1, x1, y1 - 1, 0xC0101018);
		context.fill(x0 + 1, y1 - 1, x1 - 1, y1, accent);
		int x = x0 + 6;
		if (!icon.isEmpty()) {
			context.drawItem(icon, x, y + 2);
			x += iconSpace;
		}
		context.drawTextWithShadow(font, text, x, y + 6, accent);
	}

	public static void renderWorld(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (targets.isEmpty() || client.world == null) return;
		String dimension = ContainerPeekClient.dimensionKey(client.world);
		Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
		MatrixStack matrices = context.matrices();
		VertexConsumer lines = context.consumers().getBuffer(RenderLayers.lines());
		VoxelShape beam = VoxelShapes.cuboid(0.4, 1.02, 0.4, 0.6, ModConfig.INSTANCE.beamHeight, 0.6);
		for (Target target : targets) {
			if (!target.dimension.equals(dimension)) continue;
			double x = target.pos.getX() - camera.x, y = target.pos.getY() - camera.y, z = target.pos.getZ() - camera.z;
			VertexRendering.drawOutline(matrices, lines, BOX, x, y, z, COLOR, 3.0f);
			if (ModConfig.INSTANCE.locatorBeam) VertexRendering.drawOutline(matrices, lines, beam, x, y, z, COLOR, 2.0f);
		}
	}
}
