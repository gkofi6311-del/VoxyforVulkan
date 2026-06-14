package com.francis.voxulkan;

import com.francis.voxulkan.chunk.ChunkCaptureManager;
import com.francis.voxulkan.lod.LodMeshManager;
import com.francis.voxulkan.render.VulkanLodRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxUlkanMod implements ClientModInitializer {

    public static final String MOD_ID = "voxulkan";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Singletons accessible globally
    public static ChunkCaptureManager chunkCapture;
    public static LodMeshManager lodMeshManager;
    public static VulkanLodRenderer vulkanRenderer;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[VoxUlkan] Initializing...");

        // Set up subsystems
        chunkCapture = new ChunkCaptureManager();
        lodMeshManager = new LodMeshManager();
        vulkanRenderer = new VulkanLodRenderer();

        // Initialize Vulkan renderer once the client is fully ready
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            LOGGER.info("[VoxUlkan] Client started, initializing Vulkan LoD renderer...");
            vulkanRenderer.init();
        });

        // Hook into world render to draw LoD meshes after terrain
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            if (vulkanRenderer.isReady()) {
                vulkanRenderer.renderLods(context);
            }
        });

        // Clean up on client shutdown
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("[VoxUlkan] Shutting down...");
            vulkanRenderer.cleanup();
            lodMeshManager.cleanup();
            chunkCapture.cleanup();
        });

        LOGGER.info("[VoxUlkan] Initialized.");
    }
}
