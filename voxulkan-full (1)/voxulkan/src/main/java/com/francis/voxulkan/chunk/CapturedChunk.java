package com.francis.voxulkan.chunk;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * A lightweight, thread-safe snapshot of a chunk's block data.
 * We copy only what we need for LoD meshing so the game thread
 * can continue without us holding a reference to the live chunk.
 */
public class CapturedChunk {

    public static final int CHUNK_WIDTH = 16;
    public static final int SECTION_HEIGHT = 16;

    public final int chunkX;
    public final int chunkZ;
    public final int minY;       // world Y of the lowest section
    public final int sectionCount;

    // Flat block state array: [section][y][z][x]
    // null entry means air
    public final BlockState[][][][] blockStates;

    private CapturedChunk(int chunkX, int chunkZ, int minY, int sectionCount, BlockState[][][][] blockStates) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.sectionCount = sectionCount;
        this.blockStates = blockStates;
    }

    /**
     * Snapshot a live chunk on the game thread. Fast — just array copies.
     */
    public static CapturedChunk snapshot(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int sectionCount = sections.length;
        int minY = chunk.getMinSectionY() * SECTION_HEIGHT;

        BlockState[][][][] states = new BlockState[sectionCount][SECTION_HEIGHT][CHUNK_WIDTH][CHUNK_WIDTH];

        for (int s = 0; s < sectionCount; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) continue;

            for (int y = 0; y < SECTION_HEIGHT; y++) {
                for (int z = 0; z < CHUNK_WIDTH; z++) {
                    for (int x = 0; x < CHUNK_WIDTH; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        // Only store non-air blocks to keep memory low
                        if (!state.isAir()) {
                            states[s][y][z][x] = state;
                        }
                    }
                }
            }
        }

        return new CapturedChunk(
                chunk.getPos().x,
                chunk.getPos().z,
                minY,
                sectionCount,
                states
        );
    }

    /** World Y for a given section + local Y offset */
    public int worldY(int section, int localY) {
        return minY + section * SECTION_HEIGHT + localY;
    }

    /** True if the block at section/localY/z/x is non-air */
    public boolean isSolid(int section, int y, int z, int x) {
        if (section < 0 || section >= sectionCount) return false;
        return blockStates[section][y][z][x] != null;
    }
}
