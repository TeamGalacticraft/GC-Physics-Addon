package dev.galacticraft.gcphysics;

import dev.galacticraft.gcphysics.block.GcBlocks;
import dev.galacticraft.gcphysics.block.entity.GcBlockEntities;
import dev.galacticraft.gcphysics.command.GCPhysicsCommands;
import dev.galacticraft.gcphysics.item.GcItems;
import dev.galacticraft.gcphysics.menu.GcMenuTypes;
import dev.galacticraft.gcphysics.util.DelayedServerTasks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GcPhysicsMod implements ModInitializer {
    public static final String MOD_ID = "gc-physics";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing GC Physics");

        GcBlocks.init();
        GcItems.init();
        GcBlockEntities.init();
        GcMenuTypes.init();
        GcServerEvents.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            GCPhysicsCommands.register(dispatcher, registryAccess);
        });

        ServerTickEvents.END_SERVER_TICK.register(DelayedServerTasks::tick);

        LOGGER.info("Initialized GC Physics");
    }
}