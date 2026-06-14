package com.francis.voxulkan.chunk;

import com.francis.voxulkan.VoxUlkanMod;
import com.francis.voxulkan.lod.LodBuilder;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listens for chunk load/unload events and feeds chunk data
 * into the LoD builder pipeline on a background thread.
 */
public class ChunkCaptureManager {

    // Background thread pool for LoD generation (doesn't block the game thread)
    private final ExecutorService lodBuildPool = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "voxulkan-lod-builder");
                t.setDaemon(true);
                return t;
            }
    );

    /**
     * Called by MixinChunkLoadEvent when a chunk finishes loading.
     * Submits async LoD build job for that chunk.
     */
    public void onChunkLoaded(LevelChunk chunk) {
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;

        // Snapshot the chunk data we need (block states per section)
        // We do this on the game thread immediately, then build LoD async
        CapturedChunk captured = CapturedChunk.snapshot(chunk);

        lodBuildPool.submit(() -> {
            try {
                LodBuilder.buildLod(captured, cx, cz);
            } catch (Exception e) {
                VoxUlkanMod.LOGGER.error("[VoxUlkan] Failed to build LoD for chunk {},{}: {}", cx, cz, e.getMessage());
            }
        });
    }

    /**
     * Called when a chunk is unloaded. We keep the LoD data around
     * (that's the whole point), so this just marks the chunk as unloaded
     * in the active set.
     */
    public void onChunkUnloaded(int cx, int cz) {
        // LoD mesh stays alive — it's what we render when the full chunk is gone
        VoxUlkanMod.lodMeshManager.markChunkUnloaded(cx, cz);
    }

    public void cleanup() {
        lodBuildPool.shutdownNow();
    }
}
