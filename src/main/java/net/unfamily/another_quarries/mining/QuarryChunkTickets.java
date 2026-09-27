package net.unfamily.another_quarries.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Keeps the quarry and its active target chunks at BLOCK_TICKING status.
 *
 * This deliberately mirrors Logistics' working quarry implementation: it uses
 * vanilla ServerChunkCache tickets at ChunkLevel.byStatus(BLOCK_TICKING)
 * instead of NeoForge forced-chunk tickets.
 */
public final class QuarryChunkTickets {
    public static final TicketType TICKET_TYPE = new TicketType(40L, false);

    private QuarryChunkTickets() {}

    private static Ticket ticket() {
        return new Ticket(TICKET_TYPE, net.minecraft.server.level.ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING));
    }

    public static void sync(ServerLevel level, BlockPos owner, Iterable<BlockPos> activeTargets, LongOpenHashSet forcedChunks) {
        LongOpenHashSet needed = new LongOpenHashSet();
        needed.add(ChunkPos.pack(owner));
        for (BlockPos target : activeTargets) {
            needed.add(ChunkPos.pack(target));
        }

        // Refresh every needed ticket every machine tick, exactly like Logistics.
        for (long chunk : needed) {
            level.getChunkSource().addTicket(ticket(), ChunkPos.unpack(chunk));
        }

        LongOpenHashSet toRemove = new LongOpenHashSet(forcedChunks);
        toRemove.removeAll(needed);
        for (long chunk : toRemove) {
            level.getChunkSource().removeTicketWithRadius(TICKET_TYPE, ChunkPos.unpack(chunk), 2);
            forcedChunks.remove(chunk);
        }

        forcedChunks.addAll(needed);
    }

    public static void releaseAll(ServerLevel level, BlockPos owner, LongOpenHashSet forcedChunks) {
        for (long chunk : forcedChunks.toLongArray()) {
            level.getChunkSource().removeTicketWithRadius(TICKET_TYPE, ChunkPos.unpack(chunk), 2);
        }
        forcedChunks.clear();
    }
}
