package com.greysworkshop.containerpeek;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.ArrayList;
import java.util.List;

/**
 * Guides you to containers picked in the search screen: a box and a tall beam on each one in the world,
 * and an arrow with the distance to the nearest at the top of the screen. Targets clear as you reach them.
 */
public final class Locator {
	public record Target(String dimension, BlockPos pos, String label, ItemStack icon) {}

	private static final double REACHED = 3.5;
	private static final int COLOR = 0xFF40E0FF;
	private static final VoxelShape BOX = VoxelShapes.cuboid(-0.02, -0.02, -0.02, 1.02, 1.02, 1.02);
	private static final VoxelShape BEAM = VoxelShapes.cuboid(0.4, 1.02, 0.4, 0.6, 96, 0.6);
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

	private static final List<Target> targets = new ArrayList<>();
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
		boolean removed = targets.removeIf(t -> t.dimension.equals(dimension)
				&& client.player.getEyePos().distanceTo(Vec3d.ofCenter(t.pos)) <= REACHED);
		if (removed && targets.isEmpty()) foundUntil = System.currentTimeMillis() + 3000;
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
		int y = 6;

		if (targets.isEmpty()) {
			if (System.currentTimeMillis() < foundUntil) {
				drawPill(context, font, Text.translatable("containerpeek.locator.found"), centerX, y, 0xFF55FF55, ItemStack.EMPTY);
			}
			return;
		}

		Target target = nearest(client);
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
		for (Target target : targets) {
			if (!target.dimension.equals(dimension)) continue;
			double x = target.pos.getX() - camera.x, y = target.pos.getY() - camera.y, z = target.pos.getZ() - camera.z;
			VertexRendering.drawOutline(matrices, lines, BOX, x, y, z, COLOR, 3.0f);
			VertexRendering.drawOutline(matrices, lines, BEAM, x, y, z, COLOR, 2.0f);
		}
	}
}
