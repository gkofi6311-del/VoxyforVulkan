package com.francis.voxulkan.mixin;

import com.francis.voxulkan.VoxUlkanMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinWorldRenderer {

    /**
     * Inject after solid terrain is rendered so our LoD geometry
     * blends in depth-correctly with the terrain.
     *
     * The WorldRenderEvents.AFTER_TRANSLUCENT in VoxUlkanMod handles
     * the actual render call — this mixin is a backup hook for any
     * per-frame state we need to capture (MVP matrix, etc).
     */
    @Inject(
        method = "renderChunkLayer",
        at = @At("TAIL")
    )
    private void voxulkan_afterRenderLayer(RenderType renderType, PoseStack poseStack,
                                           double camX, double camY, double camZ,
                                           Matrix4f projectionMatrix, CallbackInfo ci) {
        // After the solid pass: capture the view-projection matrix for our Vulkan pipeline
        if (!renderType.equals(RenderType.solid())) return;
        if (VoxUlkanMod.vulkanRenderer == null || !VoxUlkanMod.vulkanRenderer.isReady()) return;

        // Pass the current MVP matrix to the renderer
        // (stored for use in push constants during the LoD draw pass)
        VoxUlkanMod.vulkanRenderer.updateViewProjection(poseStack.last().pose(), projectionMatrix,
                (float)camX, (float)camY, (float)camZ);
    }
}
