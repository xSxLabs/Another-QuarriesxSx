package net.unfamily.another_quarries.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.unfamily.another_quarries.config.ModConfig;
import net.unfamily.another_quarries.util.QuarryAreaLogic;
import net.unfamily.another_quarries.util.QuarryDiggingMode;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer-first mining queue. Volume mode clears one full horizontal layer at a time across the area,
 * then continues below. Chunk mode does the same per chunk before advancing to the next chunk.
 */
public final class QuarryBlockQueue {
    public enum Phase {
        CLEAR_VOLUME,
        BELOW
    }

    private final BlockPos quarryPos;
    private final Direction facing;
    private final int sizeLeft;
    private final int sizeRight;
    private final int sizeHeight;
    private final int sizeDepth;
    private final QuarryDiggingMode mode;
    private final LongArrayList areaChunks;

    private Phase phase;
    private int chunkIndex;
    private int volumeSliceChunkIndex;
    private int activeChunkX;
    private int activeChunkZ;
    /** Current volume layer index (sizeHeight = top, 0 = base row). */
    private int volumeDy;
    /** Layers below the volume base; 0 = first row under the box. */
    private int belowLayer;
    private int layerCursor;
    private boolean airSkipCursorActive;
    private BlockPos airSkipCursor = BlockPos.ZERO;
    private int airSkipScanTopY = Integer.MIN_VALUE;
    private long cachedAirSkipBoundsKey = Long.MIN_VALUE;
    private QuarryAreaLogic.LayerFootprintBounds cachedAirSkipBounds;
    private long airSkipInteriorCacheKey = Long.MIN_VALUE;
    private LongOpenHashSet airSkipInteriorPositions;
    private long cachedCurrentLayerKey = Long.MIN_VALUE;
    private List<BlockPos> currentLayerBlocks = List.of();
    private final Deque<BlockPos> regenQueue = new ArrayDeque<>();

    private enum RegenScanPhase {
        VOLUME,
        BELOW
    }

    private boolean regenScanActive;
    private RegenScanPhase regenScanPhase;
    private int regenScanVolumeDy;
    private int regenScanMinVolumeDy;
    private int regenScanMaxVolumeDy;
    private int regenScanBelowLayer;
    private int regenScanMaxBelowLayer;
    private int regenScanLayerIndex;
    private int regenScanBelowY;
    private final Set<BlockPos> regenScanSeen = new HashSet<>();

    private static final class LayerScanResult {
        BlockPos mineable;
        boolean unloaded;
        boolean layerComplete;
        int nextCursor;
        int checksUsed;
    }

    public static QuarryBlockQueue empty() {
        return new QuarryBlockQueue(
                BlockPos.ZERO,
                Direction.NORTH,
                0,
                0,
                0,
                0,
                QuarryDiggingMode.VOLUME,
                new LongArrayList(),
                Phase.CLEAR_VOLUME,
                0,
                0,
                0,
                0,
                0,
                false,
                BlockPos.ZERO);
    }

    /** True for the in-memory placeholder used before the first build or after NBT load. */
    public boolean isPlaceholder() {
        return quarryPos.equals(BlockPos.ZERO);
    }

    private QuarryBlockQueue(
            BlockPos quarryPos,
            Direction facing,
            int sizeLeft,
            int sizeRight,
            int sizeHeight,
            int sizeDepth,
            QuarryDiggingMode mode,
            LongArrayList areaChunks,
            Phase phase,
            int chunkIndex,
            int volumeSliceChunkIndex,
            int volumeDy,
            int belowLayer,
            int layerCursor,
            boolean airSkipCursorActive,
            BlockPos airSkipCursor) {
        this.quarryPos = quarryPos;
        this.facing = facing;
        this.sizeLeft = sizeLeft;
        this.sizeRight = sizeRight;
        this.sizeHeight = sizeHeight;
        this.sizeDepth = sizeDepth;
        this.mode = mode;
        this.areaChunks = areaChunks;
        this.phase = phase;
        this.chunkIndex = chunkIndex;
        this.volumeSliceChunkIndex = volumeSliceChunkIndex;
        this.volumeDy = volumeDy;
        this.belowLayer = belowLayer;
        this.layerCursor = layerCursor;
        this.airSkipCursorActive = airSkipCursorActive;
        this.airSkipCursor = airSkipCursor != null ? airSkipCursor : BlockPos.ZERO;
        refreshActiveChunkCoords();
        refreshCurrentLayer();
    }

    public static QuarryBlockQueue build(
            BlockPos quarryPos,
            Direction facing,
            int sizeLeft,
            int sizeRight,
            int sizeHeight,
            int sizeDepth,
            QuarryDiggingMode mode,
            int chunkIndex,
            int volumeSliceChunkIndex,
            Phase phase,
            int volumeDy,
            int belowLayer,
            int layerCursor) {
        return build(
                quarryPos,
                facing,
                sizeLeft,
                sizeRight,
                sizeHeight,
                sizeDepth,
                mode,
                chunkIndex,
                volumeSliceChunkIndex,
                phase,
                volumeDy,
                belowLayer,
                layerCursor,
                false,
                BlockPos.ZERO);
    }

    public static QuarryBlockQueue build(
            BlockPos quarryPos,
            Direction facing,
            int sizeLeft,
            int sizeRight,
            int sizeHeight,
            int sizeDepth,
            QuarryDiggingMode mode,
            int chunkIndex,
            int volumeSliceChunkIndex,
            Phase phase,
            int volumeDy,
            int belowLayer,
            int layerCursor,
            boolean airSkipCursorActive,
            BlockPos airSkipCursor) {
        if (volumeDy < 0 && phase == Phase.CLEAR_VOLUME) {
            volumeDy = sizeHeight;
        }
        if (phase == Phase.CLEAR_VOLUME && volumeDy > sizeHeight) {
            volumeDy = sizeHeight;
        }
        LongArrayList chunks = QuarryAreaLogic.enumerateAreaChunks(
                quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth);
        return new QuarryBlockQueue(
                quarryPos,
                facing,
                sizeLeft,
                sizeRight,
                sizeHeight,
                sizeDepth,
                mode,
                chunks,
                phase,
                chunkIndex,
                volumeSliceChunkIndex,
                volumeDy,
                belowLayer,
                layerCursor,
                airSkipCursorActive,
                airSkipCursor);
    }

    public static int chunkCount(
            BlockPos quarryPos,
            Direction facing,
            int sizeLeft,
            int sizeRight,
            int sizeHeight,
            int sizeDepth) {
        return QuarryAreaLogic.enumerateAreaChunks(
                quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth).size();
    }

    /**
     * High-throughput air skip using a chunk-aligned world cursor (called once per quarry per tick).
     * Cheap cursor steps skip out-of-footprint cells without world reads; block checks are capped separately.
     */
    public void advanceThroughEmptyLayers(Level level, int maxMiningLevel, int maxLayerAdvances) {
        if (isPlaceholder() || maxLayerAdvances <= 0) {
            return;
        }

        int stepsRemaining = ModConfig.airSkipCursorStepsPerTick();
        int blockChecksRemaining = ModConfig.airSkipBlockChecksPerTick();
        int[] layersAdvancedHolder = new int[1];
        int minWorldY = airSkipMinWorldY(level);

        while (stepsRemaining > 0 && blockChecksRemaining > 0 && layersAdvancedHolder[0] < maxLayerAdvances) {
            QuarryAreaLogic.LayerFootprintBounds bounds = getAirSkipBounds();
            if (bounds.isEmpty()) {
                if (!advanceLayer(level)) {
                    return;
                }
                invalidateAirSkipCaches();
                layersAdvancedHolder[0]++;
                continue;
            }

            int scanTopY = airSkipScanTopY;
            if (!airSkipCursorActive) {
                scanTopY = airSkipMaxWorldY();
                BlockPos start = QuarryAreaLogic.initialAirSkipPosition(bounds, scanTopY);
                if (start == null) {
                    if (!advanceLayer(level)) {
                        return;
                    }
                    invalidateAirSkipCaches();
                    layersAdvancedHolder[0]++;
                    continue;
                }
                airSkipCursor = start;
                airSkipScanTopY = scanTopY;
                airSkipCursorActive = true;
            }

            BlockPos cursor = airSkipCursor;
            ensureAirSkipInteriorCache(cursor);

            if (!isInCachedInterior(cursor)) {
                BlockPos next = QuarryAreaLogic.nextAirSkipPosition(cursor, bounds, minWorldY, airSkipScanTopY);
                stepsRemaining--;
                if (handleAirSkipCursorStep(level, cursor, next, maxLayerAdvances, layersAdvancedHolder)) {
                    return;
                }
                if (next == null) {
                    if (!completeLayerSliceOrLayer(level)) {
                        return;
                    }
                    invalidateAirSkipCaches();
                    layerCursor = 0;
                    cachedCurrentLayerKey = Long.MIN_VALUE;
                    if (!usesVolumeChunkSlice() || volumeSliceChunkIndex == 0) {
                        layersAdvancedHolder[0]++;
                    }
                    minWorldY = airSkipMinWorldY(level);
                }
                continue;
            }

            if (!isMineableChunkLoaded(level, cursor)) {
                return;
            }

            blockChecksRemaining--;
            if (QuarryMiningFilters.isMineable(level, cursor, maxMiningLevel)) {
                syncLayerStateFromCursor(cursor);
                return;
            }

            BlockPos next = QuarryAreaLogic.nextAirSkipPosition(cursor, bounds, minWorldY, airSkipScanTopY);
            stepsRemaining--;
            if (handleAirSkipCursorStep(level, cursor, next, maxLayerAdvances, layersAdvancedHolder)) {
                return;
            }
            if (next == null) {
                if (!completeLayerSliceOrLayer(level)) {
                    return;
                }
                invalidateAirSkipCaches();
                layerCursor = 0;
                cachedCurrentLayerKey = Long.MIN_VALUE;
                if (!usesVolumeChunkSlice() || volumeSliceChunkIndex == 0) {
                    layersAdvancedHolder[0]++;
                }
                minWorldY = airSkipMinWorldY(level);
            }
        }
    }

    /**
     * Applies a cursor step. Returns true when {@code maxLayerAdvances} Y-descent budget is exhausted.
     */
    private boolean handleAirSkipCursorStep(
            Level level,
            BlockPos cursor,
            BlockPos next,
            int maxLayerAdvances,
            int[] layersAdvanced) {
        if (next == null) {
            return false;
        }
        if (next.getY() < cursor.getY()) {
            syncMiningStateFromWorldY(next.getY(), level);
            layersAdvanced[0]++;
            if (layersAdvanced[0] >= maxLayerAdvances) {
                airSkipCursor = next;
                return true;
            }
        }
        airSkipCursor = next;
        return false;
    }

    private QuarryAreaLogic.LayerFootprintBounds getAirSkipBounds() {
        long key = computeAirSkipBoundsKey();
        if (key != cachedAirSkipBoundsKey) {
            cachedAirSkipBoundsKey = key;
            cachedAirSkipBounds = computeAirSkipBounds();
        }
        return cachedAirSkipBounds;
    }

    private long computeAirSkipBoundsKey() {
        return (((long) phase.ordinal()) << 48)
                | (((long) volumeSliceChunkIndex & 0xFFFFL) << 32)
                | (((long) chunkIndex & 0xFFFFL) << 16)
                | (mode == QuarryDiggingMode.CHUNK ? 1L : 0L);
    }

    private QuarryAreaLogic.LayerFootprintBounds computeAirSkipBounds() {
        int worldY = currentLayerWorldY();
        if (mode == QuarryDiggingMode.CHUNK) {
            return QuarryAreaLogic.interiorLayerBoundsAtWorldY(
                    quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth,
                    worldY, activeChunkX, activeChunkZ, true);
        }
        if (usesVolumeChunkSlice()) {
            return QuarryAreaLogic.interiorLayerBoundsAtWorldY(
                    quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth,
                    worldY, volumeSliceChunkX(), volumeSliceChunkZ(), true);
        }
        return QuarryAreaLogic.interiorLayerBoundsAtWorldY(
                quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth,
                worldY, 0, 0, false);
    }

    private int airSkipMinWorldY(Level level) {
        if (phase == Phase.CLEAR_VOLUME) {
            return QuarryAreaLogic.miningBase(quarryPos, facing).getY();
        }
        return level.getMinY();
    }

    private int airSkipMaxWorldY() {
        return currentLayerWorldY();
    }

    private void ensureAirSkipInteriorCache(BlockPos cursor) {
        int chunkX = cursor.getX() >> 4;
        int chunkZ = cursor.getZ() >> 4;
        int worldY = cursor.getY();
        long key = (((long) chunkX & 0x3FFFFFFL) << 38)
                | (((long) chunkZ & 0x3FFFFFFL) << 12)
                | ((long) worldY & 0xFFFL);
        if (key == airSkipInteriorCacheKey && airSkipInteriorPositions != null) {
            return;
        }
        airSkipInteriorCacheKey = key;
        airSkipInteriorPositions = new LongOpenHashSet();
        List<BlockPos> positions = enumerateInteriorPositionsInChunkAtY(worldY, chunkX, chunkZ);
        for (BlockPos pos : positions) {
            airSkipInteriorPositions.add(pos.asLong());
        }
    }

    private List<BlockPos> enumerateInteriorPositionsInChunkAtY(int worldY, int chunkX, int chunkZ) {
        BlockPos base = QuarryAreaLogic.miningBase(quarryPos, facing);
        int dy = worldY - base.getY();
        if (phase == Phase.CLEAR_VOLUME && dy >= 0 && dy <= sizeHeight) {
            if (mode == QuarryDiggingMode.CHUNK) {
                return QuarryAreaLogic.enumerateVolumeLayerAtDyInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth,
                        dy, activeChunkX, activeChunkZ);
            }
            if (usesVolumeChunkSlice()) {
                return QuarryAreaLogic.enumerateVolumeLayerAtDyInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth,
                        dy, volumeSliceChunkX(), volumeSliceChunkZ());
            }
            List<BlockPos> filtered = new ArrayList<>();
            for (BlockPos pos : QuarryAreaLogic.enumerateVolumeLayerAtDy(
                    quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth, dy)) {
                if ((pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ) {
                    filtered.add(pos);
                }
            }
            return filtered;
        }
        if (phase == Phase.BELOW) {
            if (mode == QuarryDiggingMode.CHUNK) {
                return QuarryAreaLogic.enumerateBelowLayerAtYInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeDepth,
                        worldY, activeChunkX, activeChunkZ);
            }
            if (usesVolumeChunkSlice()) {
                return QuarryAreaLogic.enumerateBelowLayerAtYInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeDepth,
                        worldY, volumeSliceChunkX(), volumeSliceChunkZ());
            }
            List<BlockPos> filtered = new ArrayList<>();
            for (BlockPos pos : QuarryAreaLogic.enumerateBelowLayerAtY(
                    quarryPos, facing, sizeLeft, sizeRight, sizeDepth, worldY)) {
                if ((pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ) {
                    filtered.add(pos);
                }
            }
            return filtered;
        }
        return List.of();
    }

    private boolean isInCachedInterior(BlockPos pos) {
        return airSkipInteriorPositions != null && airSkipInteriorPositions.contains(pos.asLong());
    }

    private void syncMiningStateFromWorldY(int worldY, Level level) {
        BlockPos base = QuarryAreaLogic.miningBase(quarryPos, facing);
        int dy = worldY - base.getY();
        if (phase == Phase.CLEAR_VOLUME && dy >= 0 && dy <= sizeHeight && dy != volumeDy) {
            volumeDy = dy;
            airSkipInteriorCacheKey = Long.MIN_VALUE;
            airSkipInteriorPositions = null;
            cachedCurrentLayerKey = Long.MIN_VALUE;
        } else if (phase == Phase.BELOW) {
            int newBelowLayer = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - worldY;
            if (newBelowLayer >= 0 && newBelowLayer != belowLayer) {
                belowLayer = newBelowLayer;
                airSkipInteriorCacheKey = Long.MIN_VALUE;
                airSkipInteriorPositions = null;
                cachedCurrentLayerKey = Long.MIN_VALUE;
            }
        }
    }

    private long currentLayerKey() {
        return (((long) phase.ordinal()) << 48)
                | (((long) volumeDy & 0xFFFFL) << 32)
                | (((long) belowLayer & 0xFFFFL) << 16)
                | (((long) volumeSliceChunkIndex & 0xFFL) << 8)
                | (chunkIndex & 0xFFL);
    }

    private void invalidateAirSkipCaches() {
        cachedAirSkipBoundsKey = Long.MIN_VALUE;
        cachedAirSkipBounds = null;
        invalidateAirSkipCursor();
    }

    private void invalidateAirSkipCursor() {
        airSkipCursorActive = false;
        airSkipCursor = BlockPos.ZERO;
        airSkipScanTopY = Integer.MIN_VALUE;
        airSkipInteriorCacheKey = Long.MIN_VALUE;
        airSkipInteriorPositions = null;
    }

    private void syncLayerStateFromCursor(BlockPos cursor) {
        BlockPos base = QuarryAreaLogic.miningBase(quarryPos, facing);
        int dy = cursor.getY() - base.getY();
        if (dy >= 0 && dy <= sizeHeight) {
            phase = Phase.CLEAR_VOLUME;
            volumeDy = dy;
            belowLayer = 0;
        } else {
            phase = Phase.BELOW;
            belowLayer = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - cursor.getY();
        }

        if (usesVolumeChunkSlice()) {
            volumeSliceChunkIndex = sliceIndexForChunk(cursor.getX() >> 4, cursor.getZ() >> 4);
        }

        long layerKey = currentLayerKey();
        if (layerKey != cachedCurrentLayerKey) {
            refreshCurrentLayer();
            cachedCurrentLayerKey = layerKey;
        }
        layerCursor = indexOfPositionInCurrentLayer(cursor);
    }

    private int sliceIndexForChunk(int chunkX, int chunkZ) {
        long packed = ChunkPos.pack(chunkX, chunkZ);
        for (int i = 0; i < areaChunks.size(); i++) {
            if (areaChunks.getLong(i) == packed) {
                return i;
            }
        }
        return Math.min(Math.max(volumeSliceChunkIndex, 0), Math.max(0, areaChunks.size() - 1));
    }

    private int indexOfPositionInCurrentLayer(BlockPos pos) {
        for (int i = 0; i < currentLayerBlocks.size(); i++) {
            if (currentLayerBlocks.get(i).equals(pos)) {
                return i;
            }
        }
        return 0;
    }

    /** @deprecated Legacy signature; cursor steps come from {@link ModConfig#airSkipCursorStepsPerTick()}. */
    public void advanceThroughEmptyLayers(Level level, int maxMiningLevel, int blockBudget, int maxLayerAdvances) {
        advanceThroughEmptyLayers(level, maxMiningLevel, maxLayerAdvances);
    }

    public BlockPos takeNextMineable(Level level) {
        return takeNextMineable(level, Set.of());
    }

    public BlockPos takeNextMineable(Level level, Collection<BlockPos> reserved) {
        return takeNextMineable(level, reserved, QuarryMiningLevels.TIER_MODDED);
    }

    public BlockPos takeNextMineable(Level level, Collection<BlockPos> reserved, int maxMiningLevel) {
        Set<BlockPos> blocked = reserved.isEmpty() ? Set.of() : new HashSet<>(reserved);

        BlockPos regen = takeNextRegenMineable(level, blocked, maxMiningLevel);
        if (regen != null) {
            return regen;
        }

        int remainingInLayer = Math.max(0, currentLayerBlocks.size() - layerCursor);
        if (remainingInLayer <= 0) {
            return null;
        }

        LayerScanResult scan = scanLayerFromCursor(level, blocked, maxMiningLevel, remainingInLayer);
        layerCursor = scan.nextCursor;
        if (scan.unloaded) {
            return null;
        }
        return scan.mineable;
    }

    private LayerScanResult scanLayerFromCursor(
            Level level,
            Set<BlockPos> reserved,
            int maxMiningLevel,
            int blockBudget) {
        LayerScanResult result = new LayerScanResult();
        result.nextCursor = layerCursor;

        if (blockBudget <= 0) {
            return result;
        }

        int size = currentLayerBlocks.size();
        int index = layerCursor;
        int checks = 0;

        while (checks < blockBudget && index < size) {
            BlockPos pos = currentLayerBlocks.get(index);
            index++;
            checks++;

            if (reserved.contains(pos)) {
                continue;
            }
            if (!isMineableChunkLoaded(level, pos)) {
                result.unloaded = true;
                result.nextCursor = index - 1;
                result.checksUsed = checks;
                return result;
            }
            if (QuarryMiningFilters.isMineable(level, pos, maxMiningLevel)) {
                result.mineable = pos;
                result.nextCursor = index;
                result.checksUsed = checks;
                return result;
            }
        }

        result.nextCursor = index;
        result.checksUsed = checks;
        result.layerComplete = index >= size;
        return result;
    }

    private boolean isCurrentLayerComplete(Level level, Set<BlockPos> reserved, int maxMiningLevel) {
        int savedCursor = layerCursor;
        try {
            layerCursor = 0;
            LayerScanResult scan = scanLayerFromCursor(
                    level, reserved, maxMiningLevel, currentLayerBlocks.size());
            if (scan.unloaded) {
                return false;
            }
            if (scan.mineable != null) {
                return false;
            }
            return scan.layerComplete;
        } finally {
            layerCursor = savedCursor;
        }
    }

    private boolean isMineableChunkLoaded(Level level, BlockPos pos) {
        return level.isLoaded(pos);
    }

    private boolean advanceLayer(Level level) {
        volumeSliceChunkIndex = 0;
        invalidateAirSkipCaches();
        cachedCurrentLayerKey = Long.MIN_VALUE;
        if (phase == Phase.CLEAR_VOLUME) {
            volumeDy--;
            if (volumeDy >= 0) {
                refreshCurrentLayer();
                return true;
            }
            phase = Phase.BELOW;
            belowLayer = 0;
            refreshCurrentLayer();
            return true;
        }

        belowLayer++;
        int minY = level.getMinY();
        int y = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - belowLayer;
        if (y >= minY) {
            refreshCurrentLayer();
            return true;
        }
        return false;
    }

    private boolean completeLayerSliceOrLayer(Level level) {
        if (usesVolumeChunkSlice() && hasMoreVolumeSlices()) {
            advanceVolumeSlice();
            return true;
        }
        return advanceLayer(level);
    }

    private void advanceVolumeSlice() {
        volumeSliceChunkIndex++;
        layerCursor = 0;
        invalidateAirSkipCaches();
        cachedCurrentLayerKey = Long.MIN_VALUE;
        refreshCurrentLayer();
    }

    private boolean hasMoreVolumeSlices() {
        return volumeSliceChunkIndex + 1 < areaChunks.size();
    }

    private boolean usesVolumeChunkSlice() {
        if (mode != QuarryDiggingMode.VOLUME) {
            return false;
        }
        int threshold = ModConfig.airSkipChunkSliceMinInteriorBlocks();
        if (threshold <= 0) {
            return false;
        }
        return QuarryAreaLogic.interiorColumnsCount(sizeLeft, sizeRight, sizeDepth) >= threshold;
    }

    private int currentLayerWorldY() {
        if (phase == Phase.CLEAR_VOLUME) {
            return QuarryAreaLogic.volumeLayerWorldY(quarryPos, facing, volumeDy, sizeHeight);
        }
        return QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - belowLayer;
    }

    private int volumeSliceChunkX() {
        if (areaChunks.isEmpty()) {
            return 0;
        }
        int index = Math.min(Math.max(volumeSliceChunkIndex, 0), areaChunks.size() - 1);
        return ChunkPos.unpack(areaChunks.getLong(index)).x();
    }

    private int volumeSliceChunkZ() {
        if (areaChunks.isEmpty()) {
            return 0;
        }
        int index = Math.min(Math.max(volumeSliceChunkIndex, 0), areaChunks.size() - 1);
        return ChunkPos.unpack(areaChunks.getLong(index)).z();
    }

    private void refreshActiveChunkCoords() {
        if (mode != QuarryDiggingMode.CHUNK || areaChunks.isEmpty()) {
            activeChunkX = 0;
            activeChunkZ = 0;
            return;
        }
        int index = Math.min(Math.max(chunkIndex, 0), areaChunks.size() - 1);
        ChunkPos chunk = ChunkPos.unpack(areaChunks.getLong(index));
        activeChunkX = chunk.x();
        activeChunkZ = chunk.z();
    }

    private void refreshCurrentLayer() {
        if (mode == QuarryDiggingMode.CHUNK) {
            if (phase == Phase.CLEAR_VOLUME) {
                currentLayerBlocks = QuarryAreaLogic.enumerateVolumeLayerAtDyInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth,
                        volumeDy, activeChunkX, activeChunkZ);
            } else {
                int y = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - belowLayer;
                currentLayerBlocks = QuarryAreaLogic.enumerateBelowLayerAtYInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeDepth, y, activeChunkX, activeChunkZ);
            }
            return;
        }

        if (usesVolumeChunkSlice()) {
            int chunkX = volumeSliceChunkX();
            int chunkZ = volumeSliceChunkZ();
            if (phase == Phase.CLEAR_VOLUME) {
                currentLayerBlocks = QuarryAreaLogic.enumerateVolumeLayerAtDyInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth,
                        volumeDy, chunkX, chunkZ);
            } else {
                int y = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - belowLayer;
                currentLayerBlocks = QuarryAreaLogic.enumerateBelowLayerAtYInChunk(
                        quarryPos, facing, sizeLeft, sizeRight, sizeDepth, y, chunkX, chunkZ);
            }
            return;
        }

        if (phase == Phase.CLEAR_VOLUME) {
            currentLayerBlocks = QuarryAreaLogic.enumerateVolumeLayerAtDy(
                    quarryPos, facing, sizeLeft, sizeRight, sizeHeight, sizeDepth, volumeDy);
        } else {
            int y = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - belowLayer;
            currentLayerBlocks = QuarryAreaLogic.enumerateBelowLayerAtY(
                    quarryPos, facing, sizeLeft, sizeRight, sizeDepth, y);
        }
    }

    public boolean isRegenScanActive() {
        return regenScanActive;
    }

    public void beginRegenScan(int layerDepth) {
        regenScanActive = true;
        regenScanLayerIndex = 0;
        regenScanSeen.clear();
        regenScanSeen.addAll(regenQueue);

        int depth = Math.max(1, layerDepth);
        if (phase == Phase.CLEAR_VOLUME) {
            regenScanPhase = RegenScanPhase.VOLUME;
            regenScanMinVolumeDy = volumeDy;
            regenScanMaxVolumeDy = Math.min(sizeHeight, volumeDy + depth);
            regenScanVolumeDy = regenScanMaxVolumeDy;
            regenScanMaxBelowLayer = -1;
        } else {
            regenScanPhase = RegenScanPhase.BELOW;
            regenScanMaxBelowLayer = belowLayer;
            regenScanBelowLayer = Math.max(0, belowLayer - depth);
            regenScanBelowY = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing) - regenScanBelowLayer;
            regenScanMinVolumeDy = -1;
            regenScanMaxVolumeDy = -1;
            regenScanVolumeDy = -1;
        }
    }

    /** Checks up to {@code budget} positions near the mining front; appends regen targets to the queue. */
    public void advanceRegenScan(Level level, int maxMiningLevel, int budget) {
        if (!regenScanActive || budget <= 0) {
            return;
        }

        int remaining = budget;
        boolean chunkFilter = mode == QuarryDiggingMode.CHUNK;

        while (remaining > 0 && regenScanActive) {
            if (regenScanPhase == RegenScanPhase.VOLUME) {
                if (regenScanVolumeDy < regenScanMinVolumeDy) {
                    regenScanActive = false;
                    return;
                }

                int before = regenScanLayerIndex;
                regenScanLayerIndex = QuarryAreaLogic.forEachInteriorVolumeAtDySlice(
                        quarryPos,
                        facing,
                        sizeLeft,
                        sizeRight,
                        sizeHeight,
                        sizeDepth,
                        regenScanVolumeDy,
                        activeChunkX,
                        activeChunkZ,
                        chunkFilter,
                        regenScanLayerIndex,
                        remaining,
                        pos -> considerRegenCandidate(level, pos, maxMiningLevel));

                remaining -= regenScanLayerIndex - before;
                if (regenScanLayerIndex <= before) {
                    regenScanVolumeDy--;
                    regenScanLayerIndex = 0;
                }
                continue;
            }

            if (regenScanBelowLayer > regenScanMaxBelowLayer) {
                regenScanActive = false;
                return;
            }

            if (regenScanBelowY < level.getMinY()) {
                regenScanActive = false;
                return;
            }

            int before = regenScanLayerIndex;
            regenScanLayerIndex = QuarryAreaLogic.forEachInteriorBelowAtYSlice(
                    quarryPos,
                    facing,
                    sizeLeft,
                    sizeRight,
                    sizeDepth,
                    regenScanBelowY,
                    activeChunkX,
                    activeChunkZ,
                    chunkFilter,
                    regenScanLayerIndex,
                    remaining,
                    pos -> considerRegenCandidate(level, pos, maxMiningLevel));

            remaining -= regenScanLayerIndex - before;
            if (regenScanLayerIndex <= before) {
                regenScanBelowLayer++;
                regenScanBelowY--;
                regenScanLayerIndex = 0;
            }
        }
    }

    private void considerRegenCandidate(Level level, BlockPos pos, int maxMiningLevel) {
        if (regenQueue.size() >= ModConfig.regenQueueMaxSize()) {
            return;
        }
        if (!isMineableChunkLoaded(level, pos)) {
            return;
        }
        if (level.isEmptyBlock(pos)) {
            return;
        }
        if (QuarryMiningFilters.isMineable(level, pos, maxMiningLevel) && regenScanSeen.add(pos)) {
            regenQueue.addLast(pos);
        }
    }

    public void setRegenQueue(List<BlockPos> blocks) {
        regenQueue.clear();
        int max = ModConfig.regenQueueMaxSize();
        for (BlockPos pos : blocks) {
            if (regenQueue.size() >= max) {
                break;
            }
            regenQueue.addLast(pos);
        }
    }

    public List<BlockPos> getRegenQueue() {
        return new ArrayList<>(regenQueue);
    }

    private BlockPos takeNextRegenMineable(Level level, Set<BlockPos> reserved, int maxMiningLevel) {
        while (!regenQueue.isEmpty()) {
            BlockPos pos = regenQueue.removeFirst();
            if (reserved.contains(pos)) {
                continue;
            }
            if (mode == QuarryDiggingMode.CHUNK && !isInActiveChunk(pos)) {
                continue;
            }
            if (!isMineableChunkLoaded(level, pos)) {
                continue;
            }
            if (QuarryMiningFilters.isMineable(level, pos, maxMiningLevel)) {
                return pos;
            }
        }
        return null;
    }

    private boolean isInActiveChunk(BlockPos pos) {
        if (mode != QuarryDiggingMode.CHUNK) {
            return true;
        }
        return (pos.getX() >> 4) == activeChunkX && (pos.getZ() >> 4) == activeChunkZ;
    }

    public boolean hasRemainingChunks() {
        return mode == QuarryDiggingMode.CHUNK && !areaChunks.isEmpty() && chunkIndex + 1 < areaChunks.size();
    }

    public boolean isCurrentChunkComplete(Level level) {
        return isCurrentChunkComplete(level, QuarryMiningLevels.TIER_MODDED);
    }

    public boolean isCurrentChunkComplete(Level level, int maxMiningLevel) {
        if (phase != Phase.BELOW) {
            return false;
        }
        return isBelowExhausted(level, maxMiningLevel);
    }

    public boolean isBelowExhausted(Level level) {
        return isBelowExhausted(level, QuarryMiningLevels.TIER_MODDED);
    }

    public boolean isBelowExhausted(Level level, int maxMiningLevel) {
        if (phase != Phase.BELOW) {
            return false;
        }
        int minY = level.getMinY();
        int startY = QuarryAreaLogic.belowVolumeStartY(quarryPos, facing);
        return startY - belowLayer < minY && isCurrentLayerComplete(level, Set.of(), maxMiningLevel);
    }

    public void advanceToNextChunk() {
        chunkIndex++;
        phase = Phase.CLEAR_VOLUME;
        volumeDy = sizeHeight;
        belowLayer = 0;
        layerCursor = 0;
        volumeSliceChunkIndex = 0;
        invalidateAirSkipCursor();
        refreshActiveChunkCoords();
        refreshCurrentLayer();
    }

    public boolean isExhausted(Level level) {
        if (mode == QuarryDiggingMode.CHUNK) {
            return isBelowExhausted(level) && !hasRemainingChunks();
        }
        return isBelowExhausted(level);
    }

    /** True while the queue can still advance (e.g. skipping air layers) or has regen work pending. */
    public boolean hasPendingMiningWork(Level level) {
        if (isRegenScanActive() || !regenQueue.isEmpty()) {
            return true;
        }
        return !isExhausted(level);
    }

    public static boolean isMineable(Level level, BlockPos pos) {
        return QuarryMiningFilters.isMineable(level, pos);
    }

    public static boolean isMineable(Level level, BlockPos pos, int maxMiningLevel) {
        return QuarryMiningFilters.isMineable(level, pos, maxMiningLevel);
    }

    /**
     * Returns the next queue position whose chunk must be loaded before target selection can continue.
     * This is intentionally available even when no worker currently has a target: the queue stops at
     * unloaded chunks, so ticketing worker targets alone can otherwise deadlock forever.
     */
    public BlockPos getCurrentScanPosition() {
        if (airSkipCursorActive && !airSkipCursor.equals(BlockPos.ZERO)) {
            return airSkipCursor;
        }
        if (!currentLayerBlocks.isEmpty()) {
            int index = Math.min(Math.max(layerCursor, 0), currentLayerBlocks.size() - 1);
            return currentLayerBlocks.get(index);
        }
        if (mode == QuarryDiggingMode.CHUNK && !areaChunks.isEmpty()) {
            return new BlockPos((activeChunkX << 4) + 8, currentLayerWorldY(), (activeChunkZ << 4) + 8);
        }
        if (usesVolumeChunkSlice() && !areaChunks.isEmpty()) {
            return new BlockPos((volumeSliceChunkX() << 4) + 8, currentLayerWorldY(), (volumeSliceChunkZ() << 4) + 8);
        }
        return null;
    }

    public int getAreaChunkCount() {
        return areaChunks.size();
    }

    /** Chunks fully processed before the current slice; equals {@link #getAreaChunkCount()} when exhausted. */
    public int getProcessedChunkCount(Level level) {
        int total = areaChunks.size();
        if (total == 0) {
            return 0;
        }
        if (isExhausted(level)) {
            return total;
        }
        if (mode == QuarryDiggingMode.CHUNK) {
            return Math.min(chunkIndex, total);
        }
        if (usesVolumeChunkSlice()) {
            return Math.min(volumeSliceChunkIndex, total);
        }
        return 0;
    }

    public Phase getPhase() {
        return phase;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getVolumeSliceChunkIndex() {
        return volumeSliceChunkIndex;
    }

    public boolean isAirSkipCursorActive() {
        return airSkipCursorActive;
    }

    public BlockPos getAirSkipCursor() {
        return airSkipCursor;
    }

    public int getVolumeDy() {
        return volumeDy;
    }

    public int getBelowLayer() {
        return belowLayer;
    }

    public int getCursor() {
        return layerCursor;
    }

    public void setCursor(int cursor) {
        this.layerCursor = Math.max(0, cursor);
    }

    public int getBelowColumnIndex() {
        return layerCursor;
    }

    public int size() {
        return currentLayerBlocks.size();
    }
}
