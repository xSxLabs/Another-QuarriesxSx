package net.unfamily.another_quarries.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Chunk ticking copied from the working Logistics quarry pattern for MC 26.1:
 * registered 20-tick TicketTypes, refreshed every machine tick, with the ticket
 * level derived from FullChunkStatus.BLOCK_TICKING.
 */
public final class QuarryChunkTickets {
    private static TicketType QUARRY;
    private static TicketType QUARRY_BOUNDARY;

    private QuarryChunkTickets() {}

    public static void registerTicketTypes() {
        if (QUARRY != null) {
            return;
        }
        QUARRY = Registry.register(
                BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath("another_quarries", "quarry_block_ticking"),
                new TicketType(20L, 15));
        QUARRY_BOUNDARY = Registry.register(
                BuiltInRegistries.TICKET_TYPE,
                Identifier.fromNamespaceAndPath("another_quarries", "quarry_boundary_block_ticking"),
                new TicketType(20L, 14));
    }

    public static void keepOwnerTicking(ServerLevel level, BlockPos owner) {
        if (QUARRY == null) return;
        int ticketLevel = ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING);
        ChunkPos ownerChunk = new ChunkPos(owner.getX() >> 4, owner.getZ() >> 4);
        level.getChunkSource().addTicket(new Ticket(QUARRY, ticketLevel), ownerChunk);
    }

    public static void sync(ServerLevel level, BlockPos owner, Iterable<BlockPos> activeTargets, LongOpenHashSet forcedChunks) {
        if (QUARRY == null) {
            return;
        }

        int ticketLevel = ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING);
        ChunkPos ownerChunk = new ChunkPos(owner.getX() >> 4, owner.getZ() >> 4);
        level.getChunkSource().addTicket(new Ticket(QUARRY, ticketLevel), ownerChunk);

        LongOpenHashSet needed = new LongOpenHashSet();
        needed.add(ChunkPos.pack(ownerChunk.x(), ownerChunk.z()));
        for (BlockPos target : activeTargets) {
            ChunkPos targetChunk = new ChunkPos(target.getX() >> 4, target.getZ() >> 4);
            needed.add(ChunkPos.pack(targetChunk.x(), targetChunk.z()));
            level.getChunkSource().addTicket(new Ticket(QUARRY_BOUNDARY, ticketLevel), targetChunk);
        }

        // Logistics relies on the 20-tick timeout instead of explicit removal.
        // Any ticket not refreshed naturally disappears after the machine stops
        // needing that chunk.
        forcedChunks.clear();
        forcedChunks.addAll(needed);
    }

    public static void releaseAll(ServerLevel level, BlockPos owner, LongOpenHashSet forcedChunks) {
        // Intentionally no explicit removal: these tickets expire after 20 ticks,
        // matching the working Logistics implementation.
        forcedChunks.clear();
    }
}
