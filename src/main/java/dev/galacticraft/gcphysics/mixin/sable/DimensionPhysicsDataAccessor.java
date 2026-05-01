package dev.galacticraft.gcphysics.mixin.sable;

import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysics;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(DimensionPhysicsData.class)
public interface DimensionPhysicsDataAccessor {
    @Accessor("DIMENSION_PHYSICS_DATA")
    static Map<ResourceKey<Level>, DimensionPhysics> galacticraft$getDimensionPhysicsData() {
        throw new AssertionError();
    }
}