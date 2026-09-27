package net.unfamily.another_quarries;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.unfamily.another_quarries.registry.ModBlockEntities;
import net.unfamily.another_quarries.registry.ModBlocks;
import net.unfamily.another_quarries.registry.ModCreativeModeTabs;
import net.unfamily.another_quarries.registry.ModItems;
import net.unfamily.another_quarries.registry.ModDataComponents;
import net.unfamily.another_quarries.registry.ModMenuTypes;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.unfamily.another_quarries.block.structure.StructureQuarryBreakCascade;
import net.unfamily.another_quarries.block.entity.QuarryBlockEntity;
import net.unfamily.another_quarries.mining.QuarryChunkTickets;

@Mod(AnotherQuarries.MOD_ID)
public final class AnotherQuarries {
    public static final String MOD_ID = "another_quarries";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AnotherQuarries(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.debug("Loading {}", MOD_ID);
        modEventBus.addListener(AnotherQuarries::onRegister);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, net.unfamily.another_quarries.config.ModConfig.SPEC);
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModDataComponents.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(AnotherQuarries::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(AnotherQuarries::onLevelLoad);
    }

    private static void onRegister(RegisterEvent event) {
        QuarryChunkTickets.registerTicketTypes();
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel server) {
            QuarryBlockEntity.bootstrapLoadedQuarries(server);
        }
    }

    private static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel server) {
            StructureQuarryBreakCascade.tick(server);
            QuarryBlockEntity.fallbackServerTick(server);
        }
    }

}