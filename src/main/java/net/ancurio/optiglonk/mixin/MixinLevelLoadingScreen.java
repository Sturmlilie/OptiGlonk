package net.ancurio.optiglonk.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.world.chunk.ChunkStatus;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public class MixinLevelLoadingScreen {
	@Shadow
	private static Object2IntMap<ChunkStatus> STATUS_TO_COLOR;

	// What this does: Replaces the roughly 2000 DrawableHelper.fill()
	// calls (each of which results in a quad draw) with CPU-sided
	// rendering into a NativeImage, which is then drawn via textured
	// quad.
	// An alternative would be to use GL_POINTS with pointSize = chunkSize,
	// but at this point we're splitting hairs.
	// Caching the GL resources is probably not terribly impactful,
	// but hey, why not

	private static NativeImageBackedTexture chunkMapTexture;
	private static int lastChunkMapSize = -1;

	private static VertexBuffer chunkMapQuad;
	private static BufferBuilder bufferBuilder;
	private static int lastChunkMapAbsX = -1;
	private static int lastChunkMapAbsY = -1;

	private static final VertexFormat vFormat = VertexFormats.POSITION_TEXTURE;

	private static void prepareChunkMapTexture(int size) {
		if (chunkMapTexture == null) {
			chunkMapTexture = new NativeImageBackedTexture(size, size, false);
			return;
		}

		try {
			chunkMapTexture.setImage(new NativeImage(size, size, false));
		} catch (Exception e) {
			throw new RuntimeException("Oh no! How could this happen!");
		}
	}

	private static void prepareChunkMapBuffer(int absX, int absY, int absSize) {
		if (chunkMapQuad == null) {
			chunkMapQuad = new VertexBuffer(vFormat);
			bufferBuilder = new BufferBuilder(12 + 8);
		}

		bufferBuilder.begin(GL11.GL_QUADS, vFormat);
		bufferBuilder.vertex(absX, absY, 0)                    .texture(0, 0).next();
		bufferBuilder.vertex(absX + absSize, absY, 0)          .texture(1, 0).next();
		bufferBuilder.vertex(absX + absSize, absY + absSize, 0).texture(1, 1).next();
		bufferBuilder.vertex(absX, absY + absSize, 0)          .texture(0, 1).next();
		bufferBuilder.end();

		chunkMapQuad.upload(bufferBuilder);
	}

	private static void prepareResources(int absX, int absY, int size, int absSize) {
		if (lastChunkMapAbsX != absX || lastChunkMapAbsY != absY || lastChunkMapSize != size) {
			lastChunkMapAbsX = absX;
			lastChunkMapAbsY = absY;

			prepareChunkMapBuffer(absX, absY, absSize);
		}

		if (lastChunkMapSize != size) {
			prepareChunkMapTexture(size);
			lastChunkMapSize = size;
		}
	}

	private static void drawChunkMapImage() {
		chunkMapTexture.upload();
		GlStateManager.disableBlend();
		chunkMapQuad.bind();
		vFormat.startDrawing(0);
		GlStateManager.drawArrays(GL11.GL_QUADS, 0, 4);
		vFormat.endDrawing();
		chunkMapQuad.unbind();
		GlStateManager.enableBlend();
	}

	/**
	 * @author Ancurio
	 * @reason There is no clean way to cut around that loop over fill()
	 */
	@Overwrite
	public static void drawChunkMap(WorldGenerationProgressTracker progressProvider,
	                                int centerX, int centerY, int chunkSize, int zero) {
		// Majong has these as parameters but actually never passes
		// anything else in..
		assert zero == 0;

		int size = progressProvider.getSize();
		int absSize = size * chunkSize;
		int absX = centerX - absSize / 2;
		int absY = centerY - absSize / 2;

		prepareResources(absX, absY, size, absSize);

		final NativeImage chunkMapImage = chunkMapTexture.getImage();

		for(int cx = 0; cx < size; ++cx) {
			for(int cy = 0; cy < size; ++cy) {
				ChunkStatus chunkStatus = progressProvider.getChunkStatus(cx, cy);
				int color = STATUS_TO_COLOR.getInt(chunkStatus);
				chunkMapImage.setPixelRgba(cx, cy, color);
			}
		}

		drawChunkMapImage();
	}
}
