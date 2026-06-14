package com.francis.voxulkan.mixin;

import com.francis.voxulkan.VoxUlkanMod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class MixinChunkLoadEvent {

    /**
     * Hook: called when the client finishes loading a chunk.
     * We forward it to ChunkCaptureManager for LoD building.
     */
    @Inject(
        method = "onChunkLoaded",
        at = @At("TAIL")
    )
    private void voxulkan_onChunkLoaded(int cx, int cz, CallbackInfo ci) {
        if (VoxUlkanMod.chunkCapture == null) return;

        ClientLevel self = (ClientLevel)(Object)this;
        LevelChunk chunk = self.getChunk(cx, cz);
        if (chunk != null) {
            VoxUlkanMod.chunkCapture.onChunkLoaded(chunk);
            VoxUlkanMod.lodMeshManager.markChunkLoaded(cx, cz);
        }
    }

    /**
     * Hook: called when a chunk is unloaded.
     * LoD mesh stays alive — that's the whole point.
     */
    @Inject(
        method = "unload",
        at = @At("HEAD")
    )
    private void voxulkan_onChunkUnloaded(LevelChunk chunk, CallbackInfo ci) {
        if (VoxUlkanMod.chunkCapture == null) return;
        VoxUlkanMod.chunkCapture.onChunkUnloaded(chunk.getPos().x, chunk.getPos().z);
    }
}
