package dev.galacticraft.gcphysics.compat.sable;

import dev.galacticraft.gcphysics.mixin.sable.DimensionPhysicsDataAccessor;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.Optional;

public final class GalacticraftSableDimensionPhysics {
    private GalacticraftSableDimensionPhysics() {}

    public static void registerSpaceStation(ResourceKey<Level> dimensionKey) {
        DimensionPhysics physics = new DimensionPhysics(
                dimensionKey.location(),
                1001,
                Optional.of(0.0f),                    // universal_drag
                Optional.of(new Vector3f(0, 0, 0)),   // base_gravity
                Optional.of(0.0),                     // base_pressure
                Optional.empty(),                     // pressure_function
                Optional.of(new Vector3f(0, 0, 0))    // magnetic_north
        );

        DimensionPhysicsDataAccessor
                .galacticraft$getDimensionPhysicsData()
                .put(dimensionKey, physics);
    }
}