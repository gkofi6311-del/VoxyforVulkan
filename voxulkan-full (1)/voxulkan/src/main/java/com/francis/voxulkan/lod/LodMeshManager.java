package com.francis.voxulkan.lod;

import com.francis.voxulkan.VoxUlkanMod;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stores all LoD meshes (one per chunk column) and manages their lifecycle.
 *
 * Background threads submit built meshes here via submitMesh().
 * The render thread drains pendingUploads each frame and uploads them to the GPU.
 */
public class LodMeshManager {

    // Key: encoded chunk pos (cx << 32 | cz as long)
    private final Map<Long, LodMesh> meshes = new ConcurrentHashMap<>();

    // Meshes built on background threads waiting for GPU upload on render thread
    private final Queue<PendingMesh> pendingUploads = new ConcurrentLinkedQueue<>();

    // Chunks currently loaded by the game (we skip rendering LoD for these —
    // the game renders them at full detail)
    private final Set<Long> loadedChunks = ConcurrentHashMap.newKeySet();

    /**
     * Called from background thread after LodBuilder finishes a mesh.
     */
    public void submitMesh(int cx, int cz, float[] vertices, int[] indices) {
        pendingUploads.add(new PendingMesh(cx, cz, vertices, indices));
    }

    /**
     * Called from the render thread each frame.
     * Uploads any pending meshes to the GPU (Vulkan buffers).
     * Returns the list of meshes ready for rendering.
     */
    public void processPendingUploads(com.francis.voxulkan.render.VulkanLodRenderer renderer) {
        PendingMesh pending;
        int uploadsThisFrame = 0;
        // Cap uploads per frame to avoid stutter
        while ((pending = pendingUploads.poll()) != null && uploadsThisFrame < 4) {
            long key = chunkKey(pending.cx, pending.cz);
            LodMesh existing = meshes.get(key);
            if (existing != null) existing.destroy(); // free old GPU buffers

            LodMesh mesh = renderer.uploadMesh(pending.cx, pending.cz, pending.vertices, pending.indices);
            if (mesh != null) {
                meshes.put(key, mesh);
                uploadsThisFrame++;
            }
        }
    }

    public Map<Long, LodMesh> getMeshes() {
        return meshes;
    }

    public boolean isChunkLoaded(int cx, int cz) {
        return loadedChunks.contains(chunkKey(cx, cz));
    }

    public void markChunkLoaded(int cx, int cz) {
        loadedChunks.add(chunkKey(cx, cz));
    }

    public void markChunkUnloaded(int cx, int cz) {
        loadedChunks.remove(chunkKey(cx, cz));
    }

    public void cleanup() {
        for (LodMesh mesh : meshes.values()) mesh.destroy();
        meshes.clear();
    }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // Lightweight data carrier from builder thread → render thread
    public record PendingMesh(int cx, int cz, float[] vertices, int[] indices) {}
}
