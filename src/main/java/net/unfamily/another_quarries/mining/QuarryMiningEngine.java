package net.unfamily.another_quarries.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.unfamily.another_quarries.AnotherQuarries;
import net.unfamily.another_quarries.block.QuarryBlock;
import net.unfamily.another_quarries.block.entity.QuarryBlockEntity;
import net.unfamily.another_quarries.item.QuarryEquipmentSlots;
import net.unfamily.another_quarries.config.ModConfig;
import net.unfamily.another_quarries.util.QuarryAreaLogic;
import net.unfamily.another_quarries.util.QuarryDiggingMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class QuarryMiningEngine {
    private final QuarryBlockEntity quarry;
    private final QuarryFrameController frameController = new QuarryFrameController();
    private QuarryBlockQueue queue = QuarryBlockQueue.empty();
    private final List<WorkerState> workers = new ArrayList<>();
    private boolean queueBuilt;
    private int queueSignature;
    private int activeChunkIndex;
    private int volumeSliceChunkIndex;
    private QuarryBlockQueue.Phase miningPhase = QuarryBlockQueue.Phase.CLEAR_VOLUME;
    private int volumeDy = -1;
    private int belowLayer;
    private int layerCursor;
    private boolean airSkipCursorActive;
    private BlockPos airSkipCursor = BlockPos.ZERO;
    private int regenScanCooldown;
    private long diagnosticLastMiningReport;
    private long diagnosticSuccessfulBreaks;
    private long diagnosticFailedBreaks;
    private long diagnosticNoTargetTicks;

    public QuarryMiningEngine(QuarryBlockEntity quarry) {
        this.quarry = quarry;
        int interval = ModConfig.regenScanIntervalTicks();
        this.regenScanCooldown = interval > 1
                ? Math.floorMod(quarry.getBlockPos().hashCode(), interval)
                : interval;
    }

    public boolean tick(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        ItemStackHandler equipment = quarry.getEquipmentHandler();
        int logicalWorkers = QuarryEquipmentSlots.effectiveWorkerCount(equipment);
        int activeWorkers = QuarryDrillAssigner.activeWorkerCount(equipment);
        QuarryDrillType drill = QuarryDrillAssigner.resolveDrill(equipment);
        if (activeWorkers <= 0) {
            workers.clear();
            setQuarryVisual(level, QuarryBlock.QuarryState.OFF);
            return false;
        }

        BlockState state = level.getBlockState(quarry.getBlockPos());
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        int signature = hashSignature(facing);

        frameController.ensureSignature(signature);
        if (frameController.shouldScanBorder()) {
            frameController.scanBorder(serverLevel, quarry, facing);
        }

        syncWorkers(activeWorkers);

        ItemStackHandler buffer = quarry.getBufferHandler();
        if (!QuarryOutputHandler.hasBufferSpace(buffer)) {
            setQuarryVisual(level, frameController.isFrameWorkActive()
                    ? QuarryBlock.QuarryState.BOOT
                    : QuarryBlock.QuarryState.OFF);
            syncQueueState();
            return frameController.isFrameWorkActive();
        }

        int blocksPerCompletion = ModConfig.blocksPerWorkerCompletion(logicalWorkers, activeWorkers);
        MiningTickContext tickCtx = new MiningTickContext(
                equipment, drill, logicalWorkers, activeWorkers, blocksPerCompletion, buffer);

        if (!frameController.isReady()) {
            return tickFrameWork(serverLevel, facing, tickCtx);
        }

        return tickMining(serverLevel, facing, tickCtx);
    }

    private boolean tickFrameWork(ServerLevel level, Direction facing, MiningTickContext tickCtx) {
        boolean progressed = false;
        int rfPerBlock = quarry.estimatedRfPerBlock();
        QuarryWorkContext ctx = buildContext(tickCtx.equipment(), tickCtx.drill(), rfPerBlock, level);
        Set<BlockPos> reserved = buildReservedTargets();

        if (frameController.getPhase() == QuarryFrameController.Phase.CLEARING) {
            progressed = tickFrameClear(level, tickCtx, ctx, reserved, rfPerBlock);
            frameController.pruneCompletedClearTargets(level);
            if (frameController.getClearQueue().isEmpty()) {
                frameController.onClearQueueDrained();
            }
        } else if (frameController.getPhase() == QuarryFrameController.Phase.PLACING) {
            progressed = tickFramePlace(level, reserved);
            frameController.pruneCompletedPlaceTargets(level);
            if (frameController.getPlaceQueue().isEmpty()) {
                frameController.onPlaceFinished();
            }
        }

        boolean active = progressed || workers.stream().anyMatch(w -> w.target != null)
                || frameController.isFrameWorkActive();
        if (frameController.isReady()) {
            resetWorkerTargets();
        } else {
            setQuarryVisual(level, QuarryBlock.QuarryState.BOOT);
        }
        return active && frameController.isFrameWorkActive();
    }

    private boolean tickFrameClear(
            ServerLevel level,
            MiningTickContext tickCtx,
            QuarryWorkContext ctx,
            Set<BlockPos> reserved,
            int rfPerBlock) {
        boolean progressed = false;
        boolean changed = false;
        QuarryDrillType drill = tickCtx.drill();
        int breaksRemaining = ModConfig.maxBlockBreaksPerTick();

        for (WorkerState worker : workers) {
            if (breaksRemaining <= 0) {
                break;
            }

            if (worker.target == null) {
                worker.target = takeNextFrameClearTarget(reserved, worker);
                worker.progress = 0;
                worker.requiredTicks = worker.target != null
                        ? (level.getBlockState(worker.target).is(net.unfamily.another_quarries.registry.ModBlocks.STRUCTURE_QUARRY.get())
                                ? 1
                                : QuarryBlockBreaker.breakTicksForBlock(level, worker.target, ctx))
                        : 0;
            } else {
                BlockState currentTarget = level.getBlockState(worker.target);
                boolean frameRemoval = currentTarget.is(net.unfamily.another_quarries.registry.ModBlocks.STRUCTURE_QUARRY.get());
                if (!frameRemoval && !QuarryBlockBreaker.canBreak(level, worker.target, drill)) {
                    releaseReservedTarget(reserved, worker);
                    worker.target = takeNextFrameClearTarget(reserved, worker);
                    worker.progress = 0;
                    worker.requiredTicks = worker.target != null
                            ? QuarryBlockBreaker.breakTicksForBlock(level, worker.target, ctx)
                            : 0;
                }
            }

            if (worker.target == null) {
                continue;
            }

            BlockState targetState = level.getBlockState(worker.target);
            if (targetState.is(net.unfamily.another_quarries.registry.ModBlocks.STRUCTURE_QUARRY.get())) {
                worker.progress++;
                if (worker.progress < 1) {
                    progressed = true;
                    continue;
                }
                if (frameController.removeFrameBlock(level, worker.target)) {
                    frameController.notifyFrameCleared(level, worker.target);
                    releaseReservedTarget(reserved, worker);
                    worker.target = null;
                    worker.progress = 0;
                    changed = true;
                    progressed = true;
                } else {
                    releaseReservedTarget(reserved, worker);
                    worker.target = takeNextFrameClearTarget(reserved, worker);
                    worker.progress = 0;
                }
                continue;
            }

            worker.progress++;
            if (worker.progress < worker.requiredTicks) {
                progressed = true;
                continue;
            }

            if (quarry.getEnergyStorage().getEnergyStored() < rfPerBlock) {
                worker.progress = worker.requiredTicks - 1;
                progressed = true;
                continue;
            }

            if (!QuarryOutputHandler.hasBufferSpace(tickCtx.buffer())) {
                worker.progress = worker.requiredTicks - 1;
                progressed = true;
                continue;
            }

            if (QuarryBlockBreaker.breakBlock(level, worker.target, ctx, tickCtx.buffer())) {
                BlockPos cleared = worker.target;
                quarry.getEnergyStorage().extractEnergy(rfPerBlock, false);
                frameController.notifyFrameCleared(level, cleared);
                releaseReservedTarget(reserved, worker);
                worker.target = null;
                worker.progress = 0;
                changed = true;
                breaksRemaining--;
                progressed = true;
            } else {
                releaseReservedTarget(reserved, worker);
                worker.target = takeNextFrameClearTarget(reserved, worker);
                worker.progress = 0;
                worker.requiredTicks = worker.target != null
                        ? QuarryBlockBreaker.breakTicksForBlock(level, worker.target, ctx)
                        : 0;
            }
        }

        if (changed) {
            quarry.setChanged();
        }
        return progressed;
    }

    private boolean tickFramePlace(ServerLevel level, Set<BlockPos> reserved) {
        boolean progressed = false;
        boolean changed = false;

        for (WorkerState worker : workers) {
            if (worker.target == null || !level.getBlockState(worker.target).isAir()) {
                releaseReservedTarget(reserved, worker);
                worker.target = takeNextFramePlaceTarget(reserved, worker);
                worker.progress = 0;
                worker.requiredTicks = worker.target != null ? 1 : 0;
            }

            if (worker.target == null) {
                continue;
            }

            worker.progress++;
            if (worker.progress < worker.requiredTicks) {
                progressed = true;
                continue;
            }

            if (frameController.placeFrameBlock(level, worker.target)) {
                releaseReservedTarget(reserved, worker);
                worker.target = null;
                worker.progress = 0;
                changed = true;
                progressed = true;
            } else {
                releaseReservedTarget(reserved, worker);
                worker.target = takeNextFramePlaceTarget(reserved, worker);
                worker.progress = 0;
                worker.requiredTicks = worker.target != null ? 1 : 0;
            }
        }

        if (changed) {
            quarry.setChanged();
        }
        return progressed;
    }

    private BlockPos takeNextFrameClearTarget(Set<BlockPos> reserved, WorkerState forWorker) {
        releaseReservedTarget(reserved, forWorker);
        BlockPos next = frameController.takeNextClearTarget(quarry.getLevel(), reserved);
        if (next != null) {
            reserved.add(next);
        }
        return next;
    }

    private BlockPos takeNextFramePlaceTarget(Set<BlockPos> reserved, WorkerState forWorker) {
        releaseReservedTarget(reserved, forWorker);
        BlockPos next = frameController.takeNextPlaceTarget(quarry.getLevel(), reserved);
        if (next != null) {
            reserved.add(next);
        }
        return next;
    }

    private boolean tickMining(ServerLevel level, Direction facing, MiningTickContext tickCtx) {
        ensureQueue(level);
        if (queueBuilt && workers.stream().noneMatch(w -> w.target != null)) {
            int maxMiningLevel = tickCtx.drill().maxMiningLevel();
            queue.advanceThroughEmptyLayers(
                    level,
                    maxMiningLevel,
                    ModConfig.airSkipMaxLayersPerTick());
        }
        scanRegeneratedBlocksIfDue(level);

        boolean progressed = false;
        int rfPerBlock = quarry.estimatedRfPerBlock();
        QuarryWorkContext ctx = buildContext(tickCtx.equipment(), tickCtx.drill(), rfPerBlock, level);
        Set<BlockPos> reserved = buildReservedTargets();
        int breaksRemaining = ModConfig.maxBlockBreaksPerTick();
        boolean changed = false;
        QuarryDrillType drill = tickCtx.drill();

        for (WorkerState worker : workers) {
            if (breaksRemaining <= 0) {
                break;
            }

            if (worker.target == null || !QuarryBlockBreaker.canBreak(level, worker.target, drill)) {
                releaseReservedTarget(reserved, worker);
                worker.target = takeNextMiningTarget(level, reserved, drill);
                worker.progress = 0;
                worker.requiredTicks = worker.target != null
                        ? QuarryBlockBreaker.breakTicksForBlock(level, worker.target, ctx)
                        : 0;
            }

            if (worker.target == null) {
                continue;
            }

            worker.progress++;
            if (worker.progress < worker.requiredTicks) {
                progressed = true;
                continue;
            }

            int blocksToBreak = Math.min(tickCtx.blocksPerCompletion(), breaksRemaining);
            int broken = completeMiningBreaks(
                    level, worker, ctx, tickCtx.buffer(), rfPerBlock, drill, reserved, blocksToBreak);
            if (broken > 0) {
                changed = true;
                breaksRemaining -= broken;
                progressed = true;
            } else if (worker.target != null) {
                worker.progress = worker.requiredTicks - 1;
                progressed = true;
            }
        }

        if (changed) {
            quarry.setChanged();
        }

        boolean active = progressed
                || workers.stream().anyMatch(w -> w.target != null)
                || (queueBuilt && queue.hasPendingMiningWork(level));
        setQuarryVisual(level, active ? QuarryBlock.QuarryState.ON : QuarryBlock.QuarryState.OFF);
        syncQueueState();

        if (workers.stream().noneMatch(w -> w.target != null)) {
            diagnosticNoTargetTicks++;
        }
        diagnosticMiningReport(level, active, progressed, tickCtx, drill);

        return active;
    }

    private int completeMiningBreaks(
            ServerLevel level,
            WorkerState worker,
            QuarryWorkContext ctx,
            ItemStackHandler buffer,
            int rfPerBlock,
            QuarryDrillType drill,
            Set<BlockPos> reserved,
            int blocksToBreak) {
        int broken = 0;
        for (int i = 0; i < blocksToBreak; i++) {
            if (worker.target == null || !QuarryBlockBreaker.canBreak(level, worker.target, drill)) {
                releaseReservedTarget(reserved, worker);
                worker.target = takeNextMiningTarget(level, reserved, drill);
                if (worker.target == null) {
                    worker.progress = 0;
                    worker.requiredTicks = 0;
                    break;
                }
                worker.requiredTicks = QuarryBlockBreaker.breakTicksForBlock(level, worker.target, ctx);
                worker.progress = worker.requiredTicks;
            }

            if (quarry.getEnergyStorage().getEnergyStored() < rfPerBlock) {
                break;
            }
            if (!QuarryOutputHandler.hasBufferSpace(buffer)) {
                break;
            }

            BlockPos attemptedTarget = worker.target;
            boolean targetLoaded = level.isLoaded(attemptedTarget);
            boolean targetChunkLoaded = level.hasChunkAt(attemptedTarget);
            BlockState attemptedState = level.getBlockState(attemptedTarget);
            if (QuarryBlockBreaker.breakBlock(level, attemptedTarget, ctx, buffer)) {
                diagnosticSuccessfulBreaks++;
                quarry.getEnergyStorage().extractEnergy(rfPerBlock, false);
                releaseReservedTarget(reserved, worker);
                worker.target = null;
                worker.progress = 0;
                broken++;
                AnotherQuarries.LOGGER.info("[AQ-BREAK] quarry={} result=SUCCESS target={} block={} loaded={} chunkLoaded={} totalSuccess={} energyAfter={}",
                        quarry.getBlockPos(), attemptedTarget, attemptedState, targetLoaded, targetChunkLoaded,
                        diagnosticSuccessfulBreaks, quarry.getEnergyStorage().getEnergyStored());
            } else {
                diagnosticFailedBreaks++;
                AnotherQuarries.LOGGER.warn("[AQ-BREAK] quarry={} result=FAILED target={} block={} loaded={} chunkLoaded={} canBreak={} progress={}/{} totalFailed={} energy={}",
                        quarry.getBlockPos(), attemptedTarget, attemptedState, targetLoaded, targetChunkLoaded,
                        QuarryBlockBreaker.canBreak(level, attemptedTarget, drill), worker.progress, worker.requiredTicks,
                        diagnosticFailedBreaks, quarry.getEnergyStorage().getEnergyStored());
                releaseReservedTarget(reserved, worker);
                worker.target = null;
                worker.progress = 0;
                break;
            }
        }
        return broken;
    }

    private void diagnosticMiningReport(ServerLevel level, boolean active, boolean progressed,
            MiningTickContext tickCtx, QuarryDrillType drill) {
        long now = level.getGameTime();
        if (now - diagnosticLastMiningReport < 200L) {
            return;
        }
        diagnosticLastMiningReport = now;

        StringBuilder workerInfo = new StringBuilder();
        for (int i = 0; i < workers.size(); i++) {
            WorkerState w = workers.get(i);
            if (i > 0) workerInfo.append(" | ");
            workerInfo.append('#').append(i)
                    .append("{target=").append(w.target)
                    .append(",progress=").append(w.progress).append('/').append(w.requiredTicks);
            if (w.target != null) {
                workerInfo.append(",loaded=").append(level.isLoaded(w.target))
                        .append(",chunkLoaded=").append(level.hasChunkAt(w.target))
                        .append(",block=").append(level.getBlockState(w.target))
                        .append(",canBreak=").append(QuarryBlockBreaker.canBreak(level, w.target, drill))
                        .append(",chunk=").append(w.target.getX() >> 4).append(',').append(w.target.getZ() >> 4);
            }
            workerInfo.append('}');
        }

        AnotherQuarries.LOGGER.info(
                "[AQ-MINING] quarry={} gameTime={} mode={} active={} progressed={} queueBuilt={} phase={} volumeDy={} belowLayer={} cursor={} activeChunkIndex={} volumeSliceChunkIndex={} pending={} workers={}/{} workerState=[{}] successes={} failures={} noTargetTicks={} energy={}/{}",
                quarry.getBlockPos(), now, quarry.getDiggingMode(), active, progressed, queueBuilt, miningPhase,
                volumeDy, belowLayer, layerCursor, activeChunkIndex, volumeSliceChunkIndex,
                queueBuilt && queue.hasPendingMiningWork(level), workers.size(), tickCtx.activeWorkers(),
                workerInfo, diagnosticSuccessfulBreaks, diagnosticFailedBreaks, diagnosticNoTargetTicks,
                quarry.getEnergyStorage().getEnergyStored(), quarry.getEnergyStorage().getMaxEnergyStored());
    }

    public void onPowerEnabled() {
        frameController.onPowerEnabled();
        resetWorkerTargets();
    }

    public void invalidateFrame() {
        frameController.requestValidation();
        resetWorkerTargets();
    }

    public void requestFullReboot() {
        invalidateQueue();
        frameController.requestFullReboot();
    }

    public void setIdleVisual(Level level) {
        setQuarryVisual(level, QuarryBlock.QuarryState.OFF);
    }

    public List<BlockPos> getChunkTicketPositions(Level level) {
        // Keep both actual worker targets and the queue's prospective scan chunk ticking.
        // The queue deliberately refuses to inspect an unloaded chunk. If every worker is
        // targetless at that boundary, target-only tickets create a deadlock: no target ->
        // no ticket -> chunk stays unloaded -> no target. Ticket the scan front as well.
        List<BlockPos> positions = new ArrayList<>(getActiveTargetPositions());
        if (queueBuilt && !queue.isPlaceholder()) {
            BlockPos scanPosition = queue.getCurrentScanPosition();
            if (scanPosition != null && !positions.contains(scanPosition)) {
                positions.add(scanPosition);
            }
        }
        return positions;
    }

    public int getTotalAreaChunkCount() {
        Level level = quarry.getLevel();
        if (level == null) {
            return 0;
        }
        Direction facing = level.getBlockState(quarry.getBlockPos()).getValue(HorizontalDirectionalBlock.FACING);
        return QuarryBlockQueue.chunkCount(
                quarry.getBlockPos(),
                facing,
                quarry.getSizeLeft(),
                quarry.getSizeRight(),
                quarry.getSizeHeight(),
                quarry.getSizeDepth());
    }

    public int getProcessedAreaChunkCount() {
        Level level = quarry.getLevel();
        if (level == null) {
            return 0;
        }
        if (level instanceof ServerLevel serverLevel) {
            ensureQueue(serverLevel);
            syncQueueState();
            if (queueBuilt && !queue.isPlaceholder()) {
                return queue.getProcessedChunkCount(level);
            }
        }
        int total = getTotalAreaChunkCount();
        if (total == 0) {
            return 0;
        }
        if (quarry.getDiggingMode() == QuarryDiggingMode.CHUNK) {
            return Math.min(activeChunkIndex, total);
        }
        if (usesVolumeChunkSlice()) {
            return Math.min(volumeSliceChunkIndex, total);
        }
        return 0;
    }

    private boolean usesVolumeChunkSlice() {
        if (quarry.getDiggingMode() != QuarryDiggingMode.VOLUME) {
            return false;
        }
        int threshold = ModConfig.airSkipChunkSliceMinInteriorBlocks();
        if (threshold <= 0) {
            return false;
        }
        return QuarryAreaLogic.interiorColumnsCount(
                quarry.getSizeLeft(), quarry.getSizeRight(), quarry.getSizeDepth()) >= threshold;
    }

    private void syncQueueState() {
        if (queueBuilt) {
            miningPhase = queue.getPhase();
            volumeDy = queue.getVolumeDy();
            belowLayer = queue.getBelowLayer();
            layerCursor = queue.getCursor();
            activeChunkIndex = queue.getChunkIndex();
            volumeSliceChunkIndex = queue.getVolumeSliceChunkIndex();
            airSkipCursorActive = queue.isAirSkipCursorActive();
            airSkipCursor = queue.getAirSkipCursor();
        }
    }

    private QuarryWorkContext buildContext(
            ItemStackHandler equipment, QuarryDrillType drill, int rfPerBlock, ServerLevel level) {
        BlockState state = level.getBlockState(quarry.getBlockPos());
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        QuarryWorkContext base = new QuarryWorkContext(
                drill,
                QuarryEquipmentSlots.diggerModuleCount(equipment),
                QuarryEquipmentSlots.speedModuleCount(equipment),
                QuarryEquipmentSlots.fortuneLevel(equipment, level.registryAccess()),
                QuarryEquipmentSlots.hasSilkTouch(equipment, level.registryAccess()),
                rfPerBlock,
                facing,
                quarry.getDiggingMode(),
                ItemStack.EMPTY,
                quarry.isFilterModuleActive(),
                quarry.getActiveItemDenyFilters());
        ItemStack breakTool = QuarryBlockBreaker.buildBreakTool(base, level);
        return new QuarryWorkContext(
                base.drill(),
                base.diggerModules(),
                base.speedModules(),
                base.fortuneLevel(),
                base.silkTouch(),
                base.rfPerBlock(),
                base.facing(),
                base.diggingMode(),
                breakTool,
                base.voidFilteredDrops(),
                base.itemDenyFilters());
    }

    private void ensureQueue(Level level) {
        BlockState state = level.getBlockState(quarry.getBlockPos());
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        int signature = hashSignature(facing);

        if (queueBuilt && signature == queueSignature && !queue.isPlaceholder()) {
            int maxMiningLevel = QuarryDrillAssigner.resolveDrill(quarry.getEquipmentHandler()).maxMiningLevel();
            if (quarry.getDiggingMode() == QuarryDiggingMode.CHUNK
                    && queue.isCurrentChunkComplete(level, maxMiningLevel)
                    && queue.hasRemainingChunks()) {
                queue.advanceToNextChunk();
                activeChunkIndex = queue.getChunkIndex();
                resetWorkerTargets();
            }
            return;
        }

        if (signature != queueSignature) {
            activeChunkIndex = 0;
            volumeSliceChunkIndex = 0;
            miningPhase = QuarryBlockQueue.Phase.CLEAR_VOLUME;
            volumeDy = -1;
            belowLayer = 0;
            layerCursor = 0;
            airSkipCursorActive = false;
            airSkipCursor = BlockPos.ZERO;
        }

        List<BlockPos> pendingRegen = queueBuilt && signature == queueSignature
                ? new ArrayList<>(queue.getRegenQueue())
                : List.of();
        int dy = volumeDy >= 0 ? volumeDy : quarry.getSizeHeight();
        queue = QuarryBlockQueue.build(
                quarry.getBlockPos(),
                facing,
                quarry.getSizeLeft(),
                quarry.getSizeRight(),
                quarry.getSizeHeight(),
                quarry.getSizeDepth(),
                quarry.getDiggingMode(),
                activeChunkIndex,
                volumeSliceChunkIndex,
                miningPhase,
                dy,
                belowLayer,
                layerCursor,
                airSkipCursorActive,
                airSkipCursor);
        queue.setRegenQueue(pendingRegen);
        queueBuilt = true;
        queueSignature = signature;
        resetWorkerTargets();
    }

    private Set<BlockPos> buildReservedTargets() {
        Set<BlockPos> reserved = new HashSet<>(workers.size());
        for (WorkerState worker : workers) {
            if (worker.target != null) {
                reserved.add(worker.target);
            }
        }
        return reserved;
    }

    private BlockPos takeNextMiningTarget(Level level, Set<BlockPos> reserved, QuarryDrillType drill) {
        BlockPos next = queue.takeNextMineable(level, reserved, drill.maxMiningLevel());
        if (next != null) {
            reserved.add(next);
        }
        return next;
    }

    private static void releaseReservedTarget(Set<BlockPos> reserved, WorkerState worker) {
        if (worker.target != null) {
            reserved.remove(worker.target);
        }
    }

    private void scanRegeneratedBlocksIfDue(Level level) {
        if (!queueBuilt) {
            return;
        }

        int maxMiningLevel = QuarryDrillAssigner.resolveDrill(quarry.getEquipmentHandler()).maxMiningLevel();
        int budget = ModConfig.regenScanBlocksPerTick();

        if (queue.isRegenScanActive()) {
            queue.advanceRegenScan(level, maxMiningLevel, budget);
            return;
        }

        if (--regenScanCooldown > 0) {
            return;
        }
        regenScanCooldown = ModConfig.regenScanIntervalTicks();
        queue.beginRegenScan(ModConfig.regenScanLayerDepth());
        queue.advanceRegenScan(level, maxMiningLevel, budget);
    }

    private void resetWorkerTargets() {
        for (WorkerState worker : workers) {
            worker.target = null;
            worker.progress = 0;
            worker.requiredTicks = 0;
        }
    }

    private int hashSignature(Direction facing) {
        return facing.get2DDataValue()
                ^ (quarry.getSizeLeft() << 4)
                ^ (quarry.getSizeRight() << 8)
                ^ (quarry.getSizeHeight() << 12)
                ^ (quarry.getSizeDepth() << 16)
                ^ (quarry.getDiggingMode().getId() << 20);
    }

    private void syncWorkers(int count) {
        while (workers.size() < count) {
            workers.add(new WorkerState(workers.size()));
        }
        while (workers.size() > count) {
            workers.remove(workers.size() - 1);
        }
    }

    public void invalidateQueue() {
        queueBuilt = false;
        activeChunkIndex = 0;
        volumeSliceChunkIndex = 0;
        airSkipCursorActive = false;
        airSkipCursor = BlockPos.ZERO;
        miningPhase = QuarryBlockQueue.Phase.CLEAR_VOLUME;
        volumeDy = -1;
        belowLayer = 0;
        layerCursor = 0;
        regenScanCooldown = ModConfig.regenScanIntervalTicks();
        resetWorkerTargets();
    }

    public java.util.List<BlockPos> getActiveTargetPositions() {
        java.util.List<BlockPos> targets = new java.util.ArrayList<>();
        for (WorkerState worker : workers) {
            if (worker.target != null) {
                targets.add(worker.target);
            }
        }
        return targets;
    }

    public void save(net.minecraft.world.level.storage.ValueOutput output) {
        output.putInt("QueueCursor", queueBuilt ? queue.getCursor() : layerCursor);
        output.putInt("VolumeDy", queueBuilt ? queue.getVolumeDy() : volumeDy);
        output.putInt("QueueSignature", queueSignature);
        output.putBoolean("QueueBuilt", queueBuilt);
        output.putInt("ActiveChunkIndex", activeChunkIndex);
        output.putInt("VolumeSliceChunkIndex", queueBuilt ? queue.getVolumeSliceChunkIndex() : volumeSliceChunkIndex);
        output.putBoolean("AirSkipCursorActive", queueBuilt ? queue.isAirSkipCursorActive() : airSkipCursorActive);
        BlockPos savedCursor = queueBuilt ? queue.getAirSkipCursor() : airSkipCursor;
        output.putInt("AirSkipCursorX", savedCursor.getX());
        output.putInt("AirSkipCursorY", savedCursor.getY());
        output.putInt("AirSkipCursorZ", savedCursor.getZ());
        output.putString("MiningPhase", miningPhase.name());
        output.putInt("BelowLayer", belowLayer);
        output.putInt("RegenScanCooldown", regenScanCooldown);
        frameController.save(output);
        var list = output.list("MiningWorkers", WorkerState.CODEC);
        for (WorkerState worker : workers) {
            list.add(worker);
        }
        if (list.isEmpty()) {
            output.discard("MiningWorkers");
        }
    }

    public void load(net.minecraft.world.level.storage.ValueInput input) {
        queueSignature = input.getIntOr("QueueSignature", 0);
        queueBuilt = input.getBooleanOr("QueueBuilt", false);
        activeChunkIndex = input.getIntOr("ActiveChunkIndex", 0);
        volumeSliceChunkIndex = input.getIntOr("VolumeSliceChunkIndex", 0);
        airSkipCursorActive = input.getBooleanOr("AirSkipCursorActive", false);
        airSkipCursor = new BlockPos(
                input.getIntOr("AirSkipCursorX", 0),
                input.getIntOr("AirSkipCursorY", 0),
                input.getIntOr("AirSkipCursorZ", 0));
        try {
            miningPhase = QuarryBlockQueue.Phase.valueOf(
                    input.getStringOr("MiningPhase", QuarryBlockQueue.Phase.CLEAR_VOLUME.name()));
        } catch (IllegalArgumentException ignored) {
            miningPhase = QuarryBlockQueue.Phase.CLEAR_VOLUME;
        }
        volumeDy = input.getIntOr("VolumeDy", -1);
        belowLayer = input.getIntOr("BelowLayer", 0);
        layerCursor = input.getIntOr("QueueCursor", 0);
        regenScanCooldown = input.getIntOr("RegenScanCooldown", ModConfig.regenScanIntervalTicks());
        int interval = ModConfig.regenScanIntervalTicks();
        if (interval > 1) {
            regenScanCooldown = Math.max(
                    regenScanCooldown,
                    Math.floorMod(quarry.getBlockPos().hashCode(), interval));
        }
        frameController.load(input);
        queue = QuarryBlockQueue.empty();
        workers.clear();
        for (WorkerState worker : input.listOrEmpty("MiningWorkers", WorkerState.CODEC)) {
            workers.add(worker);
        }
    }

    private void setQuarryVisual(Level level, QuarryBlock.QuarryState target) {
        BlockState state = level.getBlockState(quarry.getBlockPos());
        if (state.getValue(QuarryBlock.STATE) != target) {
            level.setBlock(quarry.getBlockPos(), state.setValue(QuarryBlock.STATE, target), 3);
        }
    }

    private record MiningTickContext(
            ItemStackHandler equipment,
            QuarryDrillType drill,
            int logicalWorkers,
            int activeWorkers,
            int blocksPerCompletion,
            ItemStackHandler buffer) {}

    public static final class WorkerState {
        public static final com.mojang.serialization.Codec<WorkerState> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(instance ->
                instance.group(
                        com.mojang.serialization.Codec.INT.fieldOf("DrillIndex").forGetter(w -> w.drillIndex),
                        BlockPos.CODEC.optionalFieldOf("Target").forGetter(w -> java.util.Optional.ofNullable(w.target)),
                        com.mojang.serialization.Codec.INT.fieldOf("Progress").forGetter(w -> w.progress),
                        com.mojang.serialization.Codec.INT.fieldOf("RequiredTicks").forGetter(w -> w.requiredTicks)
                ).apply(instance, (drillIndex, target, progress, requiredTicks) ->
                        new WorkerState(drillIndex, target.orElse(null), progress, requiredTicks)));

        int drillIndex;
        BlockPos target;
        int progress;
        int requiredTicks;

        WorkerState(int drillIndex) {
            this(drillIndex, null, 0, 0);
        }

        WorkerState(int drillIndex, BlockPos target, int progress, int requiredTicks) {
            this.drillIndex = drillIndex;
            this.target = target;
            this.progress = progress;
            this.requiredTicks = requiredTicks;
        }
    }
}
