package com.francis.voxulkan.render;

import com.francis.voxulkan.VoxUlkanMod;
import com.francis.voxulkan.lod.LodMesh;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the Vulkan pipeline for LoD mesh rendering.
 *
 * Uses VulkanMod's public static accessors:
 *   - net.vulkanmod.vulkan.Vulkan.getVkDevice()     → VkDevice
 *   - net.vulkanmod.vulkan.Vulkan.getAllocator()     → VMA allocator (long)
 *   - net.vulkanmod.vulkan.device.DeviceManager.vkDevice          → VkDevice (static field)
 *   - net.vulkanmod.vulkan.device.DeviceManager.physicalDevice    → VkPhysicalDevice (static field)
 *   - net.vulkanmod.vulkan.Renderer.getInstance().getGraphicsQueue()  → VkQueue
 *
 * Vertex format: vec3 pos + vec3 color = 6 floats = 24 bytes/vertex
 * Push constants: mat4 viewProj (64 bytes)
 */
public class VulkanLodRenderer {

    private boolean ready = false;

    // Vulkan objects
    private VkDevice device;
    private VkPhysicalDevice physicalDevice;
    private long commandPool;
    private VkQueue graphicsQueue;

    // Pipeline
    private long renderPass;
    private long pipelineLayout;
    private long graphicsPipeline;

    // Current frame view-projection matrix (updated each frame by mixin)
    private final float[] vpMatrix = new float[16];
    // Camera position for world-space offset in push constants
    private float camX, camY, camZ;

    // Per-frame command buffer for LoD draws
    private long lodCommandBuffer;

    // ---- Vertex attribute stride: 6 floats (pos xyz + color rgb) ----
    private static final int VERTEX_STRIDE = 6 * Float.BYTES; // 24 bytes

    public void init() {
        try {
            acquireVulkanContext();
            createCommandPool();
            createRenderPass();
            createPipelineLayout();
            createGraphicsPipeline();
            allocateCommandBuffer();
            ready = true;
            VoxUlkanMod.LOGGER.info("[VoxUlkan] Vulkan LoD renderer ready.");
        } catch (Exception e) {
            VoxUlkanMod.LOGGER.error("[VoxUlkan] Failed to init Vulkan renderer: {}", e.getMessage(), e);
            ready = false;
        }
    }

    // -----------------------------------------------------------------------
    // Context acquisition — uses VulkanMod's public static API
    // -----------------------------------------------------------------------

    private void acquireVulkanContext() throws Exception {
        // VulkanMod exposes Vulkan.getVkDevice() as a public static method
        Class<?> vulkanClass = Class.forName("net.vulkanmod.vulkan.Vulkan");
        java.lang.reflect.Method getDevice = vulkanClass.getMethod("getVkDevice");
        this.device = (VkDevice) getDevice.invoke(null);

        // DeviceManager.physicalDevice is a public static field
        Class<?> dmClass = Class.forName("net.vulkanmod.vulkan.device.DeviceManager");
        java.lang.reflect.Field physField = dmClass.getField("physicalDevice");
        this.physicalDevice = (VkPhysicalDevice) physField.get(null);

        // Graphics queue via Renderer.getInstance().getGraphicsQueue()
        Class<?> rendererClass = Class.forName("net.vulkanmod.vulkan.Renderer");
        java.lang.reflect.Method getInstance = rendererClass.getMethod("getInstance");
        Object renderer = getInstance.invoke(null);
        java.lang.reflect.Method getGQ = rendererClass.getMethod("getGraphicsQueue");
        this.graphicsQueue = (VkQueue) getGQ.invoke(renderer);

        VoxUlkanMod.LOGGER.info("[VoxUlkan] Acquired Vulkan context. Device={}", device.address());
    }

    // -----------------------------------------------------------------------
    // Command pool
    // -----------------------------------------------------------------------

    private void createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Find graphics queue family from DeviceManager
            int queueFamily = getGraphicsQueueFamily();

            VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamily);

            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device, info, null, pPool), "create command pool");
            commandPool = pPool.get(0);
        }
    }

    private int getGraphicsQueueFamily() throws RuntimeException {
        try {
            Class<?> dmClass = Class.forName("net.vulkanmod.vulkan.device.DeviceManager");
            java.lang.reflect.Field f = dmClass.getField("graphicsFamily");
            return f.getInt(null);
        } catch (Exception e) {
            // Fallback: find graphics queue family ourselves
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer count = stack.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
                VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, props);
                for (int i = 0; i < props.capacity(); i++) {
                    if ((props.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) return i;
                }
            }
            throw new RuntimeException("No graphics queue family found");
        }
    }

    // -----------------------------------------------------------------------
    // Render pass — compatible with VulkanMod's swapchain format
    // -----------------------------------------------------------------------

    private void createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // We use LOAD_OP_LOAD to draw on top of VulkanMod's existing frame
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);

            // Color attachment
            attachments.get(0)
                    .format(getSwapchainFormat())
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)      // keep existing pixels
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            // Depth attachment
            attachments.get(1)
                    .format(getDepthFormat())
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)      // share depth buffer with terrain
                    .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference colorRef = VkAttachmentReference.calloc(stack)
                    .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(VkAttachmentReference.calloc(1, stack).put(0, colorRef))
                    .pDepthStencilAttachment(depthRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
            dependency.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(0)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo rpInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRP = stack.mallocLong(1);
            check(vkCreateRenderPass(device, rpInfo, null, pRP), "create render pass");
            renderPass = pRP.get(0);
        }
    }

    // -----------------------------------------------------------------------
    // Pipeline layout — push constants only (no descriptor sets needed)
    // -----------------------------------------------------------------------

    private void createPipelineLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Push constant: mat4 viewProj = 64 bytes
            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .offset(0)
                    .size(64);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pPushConstantRanges(pcRange);

            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, layoutInfo, null, pLayout), "create pipeline layout");
            pipelineLayout = pLayout.get(0);
        }
    }

    // -----------------------------------------------------------------------
    // Graphics pipeline
    // -----------------------------------------------------------------------

    private void createGraphicsPipeline() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Load compiled SPIR-V shaders from resources
            ByteBuffer vertSpv = loadSpirv("/assets/voxulkan/shaders/lod.vert.spv");
            ByteBuffer fragSpv = loadSpirv("/assets/voxulkan/shaders/lod.frag.spv");

            long vertModule = createShaderModule(vertSpv);
            long fragModule = createShaderModule(fragSpv);

            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(entryPoint);
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entryPoint);

            // Vertex binding: interleaved [x,y,z, r,g,b] per vertex
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
            binding.get(0).binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

            // Attribute 0: position (vec3, offset 0)
            // Attribute 1: color    (vec3, offset 12)
            VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(2, stack);
            attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * Float.BYTES);

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(binding)
                    .pVertexAttributeDescriptions(attrs);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                    .primitiveRestartEnable(false);

            // Dynamic viewport/scissor so we don't need to recreate on resize
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_BACK_BIT)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .depthBiasEnable(false);

            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .sampleShadingEnable(false)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // Depth test: read + write, LESS_OR_EQUAL to blend with terrain edge
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true)
                    .depthWriteEnable(true)
                    .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(false);

            // Color blend: opaque (no blending)
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            colorBlendAttachment.get(0)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                    VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .logicOpEnable(false)
                    .pAttachments(colorBlendAttachment);

            // Dynamic state: viewport + scissor (set per-frame)
            IntBuffer dynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(dynamicStates);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisampling)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamicState)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0)
                    .basePipelineHandle(VK_NULL_HANDLE);

            LongBuffer pPipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "create graphics pipeline");
            graphicsPipeline = pPipeline.get(0);

            // Shader modules no longer needed after pipeline creation
            vkDestroyShaderModule(device, vertModule, null);
            vkDestroyShaderModule(device, fragModule, null);

            VoxUlkanMod.LOGGER.info("[VoxUlkan] Graphics pipeline created.");
        }
    }

    // -----------------------------------------------------------------------
    // Command buffer
    // -----------------------------------------------------------------------

    private void allocateCommandBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pCB = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, allocInfo, pCB), "allocate command buffer");
            lodCommandBuffer = pCB.get(0);
        }
    }

    // -----------------------------------------------------------------------
    // Per-frame render
    // -----------------------------------------------------------------------

    public void renderLods(WorldRenderContext context) {
        // Upload pending meshes (max 4 per frame to avoid stutter)
        VoxUlkanMod.lodMeshManager.processPendingUploads(this);

        // Collect visible LoD meshes (outside loaded chunk range)
        var meshes = VoxUlkanMod.lodMeshManager.getMeshes();
        if (meshes.isEmpty()) return;

        var camera = context.camera();
        int camCX = (int) Math.floor(camera.getPosition().x / 16);
        int camCZ = (int) Math.floor(camera.getPosition().z / 16);
        int lodRadius = 512; // configurable later

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Get the active framebuffer and extent from VulkanMod's Renderer
            long framebuffer = getActiveFramebuffer();
            int width = getSwapchainWidth();
            int height = getSwapchainHeight();

            // Begin command buffer
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkCommandBuffer cb = new VkCommandBuffer(lodCommandBuffer, device);
            vkResetCommandBuffer(cb, 0);
            check(vkBeginCommandBuffer(cb, beginInfo), "begin command buffer");

            // Begin render pass
            VkRenderPassBeginInfo rpBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer)
                    .renderArea(a -> a.offset(o -> o.set(0, 0)).extent(e -> e.set(width, height)));
            // No clear values — LOAD_OP_LOAD keeps existing content
            vkCmdBeginRenderPass(cb, rpBegin, VK_SUBPASS_CONTENTS_INLINE);

            // Bind pipeline
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline);

            // Set dynamic viewport and scissor
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                    .x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset(o -> o.set(0, 0)).extent(e -> e.set(width, height));
            vkCmdSetViewport(cb, 0, viewport);
            vkCmdSetScissor(cb, 0, scissor);

            // Push view-projection matrix as push constants
            FloatBuffer vpBuf = stack.mallocFloat(16);
            vpBuf.put(vpMatrix).flip();
            vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, vpBuf);

            // Draw each visible LoD mesh
            LongBuffer pVertexBuffer = stack.mallocLong(1);
            LongBuffer pOffset = stack.mallocLong(1).put(0).flip();

            for (var entry : meshes.entrySet()) {
                LodMesh mesh = entry.getValue();

                // Skip if game is rendering this chunk at full detail
                if (VoxUlkanMod.lodMeshManager.isChunkLoaded(mesh.chunkX, mesh.chunkZ)) continue;

                // Distance cull
                int dx = mesh.chunkX - camCX;
                int dz = mesh.chunkZ - camCZ;
                if (dx * dx + dz * dz > lodRadius * lodRadius) continue;

                // Bind this mesh's vertex + index buffers
                pVertexBuffer.put(0, mesh.vertexBuffer);
                vkCmdBindVertexBuffers(cb, 0, pVertexBuffer, pOffset);
                vkCmdBindIndexBuffer(cb, mesh.indexBuffer, 0, VK_INDEX_TYPE_UINT32);

                // Draw!
                vkCmdDrawIndexed(cb, mesh.indexCount, 1, 0, 0, 0);
            }

            vkCmdEndRenderPass(cb);
            check(vkEndCommandBuffer(cb), "end command buffer");

            // Submit to graphics queue
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(lodCommandBuffer));

            check(vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE), "queue submit");
        }
    }

    // -----------------------------------------------------------------------
    // Buffer upload (called from render thread by LodMeshManager)
    // -----------------------------------------------------------------------

    public LodMesh uploadMesh(int cx, int cz, float[] vertices, int[] indices) {
        if (device == null) return null;

        long vbSize = (long) vertices.length * Float.BYTES;
        long ibSize = (long) indices.length * Integer.BYTES;

        long[] vb = allocateAndFill(vbSize, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, vertices);
        long[] ib = allocateAndFill(ibSize, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, indices);

        if (vb == null || ib == null) return null;

        return new LodMesh(cx, cz, indices.length, vb[0], vb[1], ib[0], ib[1], this);
    }

    /** Returns [buffer, memory] or null on failure. */
    private long[] allocateAndFill(long size, int usage, float[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] buf = createBuffer(stack, size, usage,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            org.lwjgl.PointerBuffer ppData = stack.mallocPointer(1);
            vkMapMemory(device, buf[1], 0, size, 0, ppData);
            ppData.getFloatBuffer(0, data.length).put(data);
            vkUnmapMemory(device, buf[1]);
            return buf;
        } catch (Exception e) {
            VoxUlkanMod.LOGGER.error("[VoxUlkan] Buffer alloc failed: {}", e.getMessage());
            return null;
        }
    }

    private long[] allocateAndFill(long size, int usage, int[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] buf = createBuffer(stack, size, usage,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            org.lwjgl.PointerBuffer ppData = stack.mallocPointer(1);
            vkMapMemory(device, buf[1], 0, size, 0, ppData);
            ppData.getIntBuffer(0, data.length).put(data);
            vkUnmapMemory(device, buf[1]);
            return buf;
        } catch (Exception e) {
            VoxUlkanMod.LOGGER.error("[VoxUlkan] Buffer alloc failed: {}", e.getMessage());
            return null;
        }
    }

    private long[] createBuffer(MemoryStack stack, long size, int usage, int memProps) {
        VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);

        LongBuffer pBuf = stack.mallocLong(1);
        check(vkCreateBuffer(device, bufInfo, null, pBuf), "create buffer");
        long buffer = pBuf.get(0);

        VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
        vkGetBufferMemoryRequirements(device, buffer, reqs);

        VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(reqs.size())
                .memoryTypeIndex(findMemoryType(stack, reqs.memoryTypeBits(), memProps));

        LongBuffer pMem = stack.mallocLong(1);
        check(vkAllocateMemory(device, allocInfo, null, pMem), "allocate memory");
        long memory = pMem.get(0);

        vkBindBufferMemory(device, buffer, memory, 0);
        return new long[]{buffer, memory};
    }

    private int findMemoryType(MemoryStack stack, int typeFilter, int properties) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0 &&
                (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        throw new RuntimeException("No suitable memory type");
    }

    // -----------------------------------------------------------------------
    // VulkanMod swapchain/framebuffer accessors (via reflection)
    // -----------------------------------------------------------------------

    private long getActiveFramebuffer() {
        try {
            Class<?> rendererClass = Class.forName("net.vulkanmod.vulkan.Renderer");
            Object renderer = rendererClass.getMethod("getInstance").invoke(null);
            return (long) rendererClass.getMethod("getActiveFramebuffer").invoke(renderer);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get framebuffer from VulkanMod Renderer", e);
        }
    }

    private int getSwapchainWidth() {
        try {
            Class<?> scClass = Class.forName("net.vulkanmod.vulkan.framebuffer.SwapChain");
            return (int) scClass.getMethod("getWidth").invoke(null);
        } catch (Exception e) { return 1920; } // safe fallback
    }

    private int getSwapchainHeight() {
        try {
            Class<?> scClass = Class.forName("net.vulkanmod.vulkan.framebuffer.SwapChain");
            return (int) scClass.getMethod("getHeight").invoke(null);
        } catch (Exception e) { return 1080; }
    }

    private int getSwapchainFormat() {
        try {
            Class<?> scClass = Class.forName("net.vulkanmod.vulkan.framebuffer.SwapChain");
            return (int) scClass.getMethod("getFormat").invoke(null);
        } catch (Exception e) { return VK_FORMAT_B8G8R8A8_UNORM; }
    }

    private int getDepthFormat() {
        try {
            Class<?> vulkanClass = Class.forName("net.vulkanmod.vulkan.Vulkan");
            return (int) vulkanClass.getMethod("getDefaultDepthFormat").invoke(null);
        } catch (Exception e) { return VK_FORMAT_D32_SFLOAT; }
    }

    // -----------------------------------------------------------------------
    // Shader loading
    // -----------------------------------------------------------------------

    private ByteBuffer loadSpirv(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("SPIR-V not found: " + path);
            byte[] bytes = is.readAllBytes();
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            return buf;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load SPIR-V: " + path, e);
        }
    }

    private long createShaderModule(ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            check(vkCreateShaderModule(device, createInfo, null, pModule), "create shader module");
            MemoryUtil.memFree(spirv);
            return pModule.get(0);
        }
    }

    // -----------------------------------------------------------------------
    // Called by MixinWorldRenderer each frame with current MVP
    // -----------------------------------------------------------------------

    public void updateViewProjection(org.joml.Matrix4f viewMatrix, org.joml.Matrix4f projMatrix,
                                     float cx, float cy, float cz) {
        // Combined VP matrix
        org.joml.Matrix4f vp = new org.joml.Matrix4f(projMatrix).mul(viewMatrix);
        vp.get(vpMatrix, 0);
        this.camX = cx; this.camY = cy; this.camZ = cz;
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    public void destroyMesh(LodMesh mesh) {
        if (device == null) return;
        vkDestroyBuffer(device, mesh.vertexBuffer, null);
        vkFreeMemory(device, mesh.vertexAllocation, null);
        vkDestroyBuffer(device, mesh.indexBuffer, null);
        vkFreeMemory(device, mesh.indexAllocation, null);
    }

    public void cleanup() {
        if (device == null) return;
        vkDeviceWaitIdle(device);
        if (graphicsPipeline != 0) vkDestroyPipeline(device, graphicsPipeline, null);
        if (pipelineLayout != 0)   vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (renderPass != 0)       vkDestroyRenderPass(device, renderPass, null);
        if (commandPool != 0)      vkDestroyCommandPool(device, commandPool, null);
    }

    public boolean isReady() { return ready; }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private static void check(int result, String op) {
        if (result != VK_SUCCESS)
            throw new RuntimeException("[VoxUlkan] Vulkan error in " + op + ": " + result);
    }

    // LWJGL PointerBuffer alias
    private static org.lwjgl.PointerBuffer PointerBuffer(MemoryStack stack, long value) {
        return stack.pointers(value);
    }
}
