package com.francis.voxulkan.lod;

/**
 * Holds the Vulkan GPU buffer handles for a single chunk's LoD mesh.
 * Created by VulkanLodRenderer.uploadMesh(), destroyed when the mesh
 * is replaced or the mod shuts down.
 */
public class LodMesh {

    public final int chunkX;
    public final int chunkZ;
    public final int indexCount;

    // Vulkan buffer handles (longs = VkBuffer/VmaAllocation handles)
    public final long vertexBuffer;
    public final long vertexAllocation;
    public final long indexBuffer;
    public final long indexAllocation;

    // Reference to renderer so we can call its destroy methods
    private final com.francis.voxulkan.render.VulkanLodRenderer renderer;

    public LodMesh(int chunkX, int chunkZ, int indexCount,
                   long vertexBuffer, long vertexAllocation,
                   long indexBuffer, long indexAllocation,
                   com.francis.voxulkan.render.VulkanLodRenderer renderer) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.indexCount = indexCount;
        this.vertexBuffer = vertexBuffer;
        this.vertexAllocation = vertexAllocation;
        this.indexBuffer = indexBuffer;
        this.indexAllocation = indexAllocation;
        this.renderer = renderer;
    }

    public void destroy() {
        renderer.destroyMesh(this);
    }
}
