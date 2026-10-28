package com.barbarianraids;

import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds Create track blocks in loaded chunks (one sample per 16x16x16 section, nearest first). */
public final class TrackScanner {
    private TrackScanner() {}

    public static List<BlockPos> find(ServerLevel level, BlockPos center, int radius) {
        int minCx = (center.getX() - radius) >> 4, maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4, maxCz = (center.getZ() + radius) >> 4;
        double r2 = (double) radius * radius;
        List<BlockPos> found = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> st.getBlock() instanceof TrackBlock)) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    boolean added = false;
                    for (int x = 0; x < 16 && !added; x++) {
                        for (int y = 0; y < 16 && !added; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (s.getBlockState(x, y, z).getBlock() instanceof TrackBlock) {
                                    BlockPos p = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                                    if (p.distSqr(center) <= r2) {
                                        found.add(p);
                                    }
                                    added = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
        return found;
    }
}
