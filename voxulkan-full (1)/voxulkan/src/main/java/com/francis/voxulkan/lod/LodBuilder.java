package com.francis.voxulkan.lod;

import com.francis.voxulkan.VoxUlkanMod;
import com.francis.voxulkan.chunk.CapturedChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a CapturedChunk into a simplified LoD mesh.
 *
 * Strategy: 4x4x4 "macro-blocks" — for each 4x4x4 region of blocks,
 * emit a single colored cube if the majority of blocks in it are solid.
 * This gives us ~64x fewer vertices than full geometry while preserving
 * the shape of terrain from a distance.
 *
 * This runs entirely on background threads (see ChunkCaptureManager).
 */
public class LodBuilder {

    // Size of each LoD "super-block" in game blocks
    public static final int LOD_CELL_SIZE = 4;

    public static void buildLod(CapturedChunk chunk, int cx, int cz) {
        List<float[]> vertices = new ArrayList<>(); // x,y,z,r,g,b per vertex
        List<Integer> indices = new ArrayList<>();

        int totalSections = chunk.sectionCount;

        for (int s = 0; s < totalSections; s++) {
            int baseY = chunk.worldY(s, 0);

            // Step through the section in LOD_CELL_SIZE steps
            for (int ly = 0; ly < CapturedChunk.SECTION_HEIGHT; ly += LOD_CELL_SIZE) {
                for (int lz = 0; lz < CapturedChunk.CHUNK_WIDTH; lz += LOD_CELL_SIZE) {
                    for (int lx = 0; lx < CapturedChunk.CHUNK_WIDTH; lx += LOD_CELL_SIZE) {

                        // Count solid blocks in this cell
                        int solidCount = 0;
                        float r = 0, g = 0, b = 0;

                        for (int dy = 0; dy < LOD_CELL_SIZE; dy++) {
                            for (int dz = 0; dz < LOD_CELL_SIZE; dz++) {
                                for (int dx = 0; dx < LOD_CELL_SIZE; dx++) {
                                    int ty = ly + dy, tz = lz + dz, tx = lx + dx;
                                    if (ty >= CapturedChunk.SECTION_HEIGHT) continue;
                                    if (chunk.isSolid(s, ty, tz, tx)) {
                                        solidCount++;
                                        // Average color from block state (placeholder — real impl queries block color map)
                                        float[] color = getBlockColor(chunk.blockStates[s][ty][tz][tx]);
                                        r += color[0]; g += color[1]; b += color[2];
                                    }
                                }
                            }
                        }

                        // Only emit geometry if more than half the cell is solid
                        int threshold = (LOD_CELL_SIZE * LOD_CELL_SIZE * LOD_CELL_SIZE) / 2;
                        if (solidCount >= threshold) {
                            r /= solidCount; g /= solidCount; b /= solidCount;

                            float wx = (cx * 16f) + lx;
                            float wy = baseY + ly;
                            float wz = (cz * 16f) + lz;
                            float sz = LOD_CELL_SIZE;

                            emitCube(vertices, indices, wx, wy, wz, sz, r, g, b);
                        }
                    }
                }
            }
        }

        // Convert to flat float arrays for GPU upload
        float[] vertexArray = flattenVertices(vertices);
        int[] indexArray = indices.stream().mapToInt(i -> i).toArray();

        if (vertexArray.length > 0) {
            // Hand off to LodMeshManager to be uploaded to the GPU
            VoxUlkanMod.lodMeshManager.submitMesh(cx, cz, vertexArray, indexArray);
        }
    }

    /**
     * Emits 8 vertices + 36 indices for a cube at (wx,wy,wz) with size sz.
     * Vertex format: x, y, z, r, g, b  (6 floats each)
     */
    private static void emitCube(List<float[]> verts, List<Integer> indices,
                                  float wx, float wy, float wz, float sz,
                                  float r, float g, float b) {
        int base = verts.size();

        // 8 corners
        verts.add(new float[]{wx,      wy,      wz,      r, g, b});
        verts.add(new float[]{wx + sz, wy,      wz,      r, g, b});
        verts.add(new float[]{wx + sz, wy,      wz + sz, r, g, b});
        verts.add(new float[]{wx,      wy,      wz + sz, r, g, b});
        verts.add(new float[]{wx,      wy + sz, wz,      r, g, b});
        verts.add(new float[]{wx + sz, wy + sz, wz,      r, g, b});
        verts.add(new float[]{wx + sz, wy + sz, wz + sz, r, g, b});
        verts.add(new float[]{wx,      wy + sz, wz + sz, r, g, b});

        // 6 faces × 2 triangles × 3 indices = 36
        int[] cubeIndices = {
            0,1,2, 0,2,3, // bottom
            4,5,6, 4,6,7, // top
            0,1,5, 0,5,4, // front
            2,3,7, 2,7,6, // back
            1,2,6, 1,6,5, // right
            3,0,4, 3,4,7  // left
        };
        for (int i : cubeIndices) indices.add(base + i);
    }

    private static float[] flattenVertices(List<float[]> verts) {
        float[] out = new float[verts.size() * 6];
        int i = 0;
        for (float[] v : verts) {
            out[i++] = v[0]; out[i++] = v[1]; out[i++] = v[2];
            out[i++] = v[3]; out[i++] = v[4]; out[i++] = v[5];
        }
        return out;
    }

    /**
     * Placeholder color lookup per block state.
     * TODO: replace with a proper block color atlas sampled from block textures.
     */
    private static float[] getBlockColor(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) return new float[]{0.5f, 0.5f, 0.5f};
        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();

        if (name.contains("grass")) return new float[]{0.3f, 0.7f, 0.2f};
        if (name.contains("dirt"))  return new float[]{0.5f, 0.35f, 0.2f};
        if (name.contains("stone")) return new float[]{0.5f, 0.5f, 0.5f};
        if (name.contains("sand"))  return new float[]{0.85f, 0.82f, 0.55f};
        if (name.contains("water")) return new float[]{0.2f, 0.4f, 0.8f};
        if (name.contains("log") || name.contains("wood")) return new float[]{0.45f, 0.3f, 0.15f};
        if (name.contains("leaves")) return new float[]{0.2f, 0.55f, 0.15f};
        if (name.contains("snow"))  return new float[]{0.9f, 0.95f, 1.0f};

        return new float[]{0.5f, 0.5f, 0.5f}; // default grey
    }
}
