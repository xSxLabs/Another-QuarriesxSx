package net.unfamily.another_quarries.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.unfamily.another_quarries.AnotherQuarries;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Keeps the quarry itself and all active worker target chunks fully ticking.
 *
 * NeoForge's BlockPos ticket overload only controls natural spawning. The UUID
 * overload has a real "ticking" flag, so these chunks continue to receive full
 * chunk ticks even with no player nearby.
 */
public final class QuarryChunkTickets {
    public static final TicketController CONTROLLER = new TicketController(
            Identifier.fromNamespaceAndPath(AnotherQuarries.MOD_ID, "quarry_mining"));

    private QuarryChunkTickets() {}

    private static UUID ownerId(ServerLevel level, BlockPos owner) {
        String key = level.dimension().identifier() + ":" + owner.getX() + ":" + owner.getY() + ":" + owner.getZ();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public static void sync(ServerLevel level, BlockPos owner, Iterable<BlockPos> activeTargets, LongOpenHashSet forcedChunks) {
        UUID ticketOwner = ownerId(level, owner);

        // The quarry's own chunk must be FULLY ticking too. Otherwise its
        // BlockEntity ticker can stop and it can no longer maintain worker tickets.
        long ownerChunk = ChunkPos.pack(owner);
        LongOpenHashSet needed = new LongOpenHashSet();
        needed.add(ownerChunk);

        for (BlockPos target : activeTargets) {
            needed.add(ChunkPos.pack(target));
        }

        LongOpenHashSet toRemove = new LongOpenHashSet(forcedChunks);
        toRemove.removeAll(needed);
        for (long chunk : toRemove) {
            ChunkPos chunkPos = ChunkPos.unpack(chunk);
            CONTROLLER.forceChunk(level, ticketOwner, chunkPos.x(), chunkPos.z(), false, true);
            forcedChunks.remove(chunk);
        }

        for (long chunk : needed) {
            if (forcedChunks.contains(chunk)) {
                continue;
            }
            ChunkPos chunkPos = ChunkPos.unpack(chunk);
            if (CONTROLLER.forceChunk(level, ticketOwner, chunkPos.x(), chunkPos.z(), true, true)) {
                forcedChunks.add(chunk);
            }
        }
    }

    public static void releaseAll(ServerLevel level, BlockPos owner, LongOpenHashSet forcedChunks) {
        UUID ticketOwner = ownerId(level, owner);
        for (long chunk : forcedChunks.toLongArray()) {
            ChunkPos chunkPos = ChunkPos.unpack(chunk);
            CONTROLLER.forceChunk(level, ticketOwner, chunkPos.x(), chunkPos.z(), false, true);
        }
        forcedChunks.clear();
    }
}
