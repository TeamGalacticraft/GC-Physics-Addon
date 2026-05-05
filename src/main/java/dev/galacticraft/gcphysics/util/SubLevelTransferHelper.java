package dev.galacticraft.gcphysics.util;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class SubLevelTransferHelper {

    private static final Logger log = LoggerFactory.getLogger("GCPhysics");

    private SubLevelTransferHelper() {}

    public static void transferToDimension(
            final ServerSubLevel subLevel,
            final ServerLevel sourceLevel,
            final ServerLevel targetLevel,
            final BlockPos targetPos,
            final Consumer<ServerSubLevel> onComplete
    ) {
        final LevelPlot plot = subLevel.getPlot();

        final SubLevelContainer targetContainer = SubLevelContainer.getContainer(targetLevel);
        if (targetContainer == null) {
            log.error("[Transfer] Target dimension {} has no SubLevelContainer.", targetLevel.dimension().location());
            onComplete.accept(null);
            return;
        }

        final BlockPos plotAnchor = plot.getCenterBlock();
        final Vec3 logicalPos = toVec3(subLevel.logicalPose().position());
        log.info("[Transfer] {} -> {} | plotAnchor={} logicalPos={} targetPos={}",
                sourceLevel.dimension().location(), targetLevel.dimension().location(),
                plotAnchor, logicalPos, targetPos);

        // Collect plot blocks and build a set of the plot's chunk positions simultaneously.
        // The chunk key set is used later to distinguish genuine plot entities from world
        // entities that Sable's query mixin includes in the plotgrid AABB search.
        final ObjectArrayList<BlockPos> plotBlocks = new ObjectArrayList<>();
        final Set<Long> plotChunkKeys = new HashSet<>();
        for (final PlotChunkHolder chunk : plot.getLoadedChunks()) {
            plotChunkKeys.add(chunk.getPos().toLong());
            final BoundingBox3ic localChunkBounds = chunk.getBoundingBox();
            if (localChunkBounds == null || localChunkBounds == BoundingBox3i.EMPTY) continue;
            for (int x = localChunkBounds.minX(); x <= localChunkBounds.maxX(); x++) {
                for (int y = localChunkBounds.minY(); y <= localChunkBounds.maxY(); y++) {
                    for (int z = localChunkBounds.minZ(); z <= localChunkBounds.maxZ(); z++) {
                        final BlockPos pos = new BlockPos(
                                x + chunk.getPos().getMinBlockX(),
                                y,
                                z + chunk.getPos().getMinBlockZ()
                        );
                        if (!sourceLevel.getBlockState(pos).isAir()) {
                            plotBlocks.add(pos);
                        }
                    }
                }
            }
        }

        log.info("[Transfer] Collected {} plot blocks across {} plot chunks.", plotBlocks.size(), plotChunkKeys.size());
        if (plotBlocks.isEmpty()) {
            log.warn("[Transfer] No blocks found in plot — aborting.");
            onComplete.accept(null);
            return;
        }

        final SubLevelAssemblyHelper.AssemblyTransform transform =
                new SubLevelAssemblyHelper.AssemblyTransform(plotAnchor, targetPos, 0, Rotation.NONE, targetLevel);

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        final ObjectArrayList<BlockPos> targetBlocks = new ObjectArrayList<>(plotBlocks.size());
        for (final BlockPos plotBlock : plotBlocks) {
            final BlockPos targetBlock = transform.apply(plotBlock);
            targetBlocks.add(targetBlock);
            minX = Math.min(minX, targetBlock.getX()); minY = Math.min(minY, targetBlock.getY()); minZ = Math.min(minZ, targetBlock.getZ());
            maxX = Math.max(maxX, targetBlock.getX()); maxY = Math.max(maxY, targetBlock.getY()); maxZ = Math.max(maxZ, targetBlock.getZ());
        }
        final BoundingBox3i targetBounds = new BoundingBox3i(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);

        // Force-load target chunks so moveBlocks can write block entity NBT correctly.
        final Set<Long> forcedChunks = new HashSet<>();
        for (final BlockPos targetBlock : targetBlocks) {
            final int cx = SectionPos.blockToSectionCoord(targetBlock.getX());
            final int cz = SectionPos.blockToSectionCoord(targetBlock.getZ());
            final long key = ChunkPos.asLong(cx, cz);
            if (forcedChunks.add(key)) {
                final ChunkPos chunkPos = new ChunkPos(cx, cz);
                targetLevel.getChunkSource().updateChunkForced(chunkPos, true);
                targetLevel.getChunk(cx, cz);
            }
        }
        log.info("[Transfer] Force-loaded {} target chunks.", forcedChunks.size());

        try {
            executeTransfer(
                    subLevel, plot, plotAnchor, logicalPos, plotChunkKeys,
                    sourceLevel, targetLevel, targetPos,
                    plotBlocks, targetBlocks, targetBounds, transform,
                    onComplete
            );
        } catch (final Exception e) {
            log.error("[Transfer] Transfer task threw an unexpected exception.", e);
            onComplete.accept(null);
        } finally {
            // Always release forced tickets — without this the server hangs on save if anything throws.
            for (final long key : forcedChunks) {
                final ChunkPos chunkPos = new ChunkPos((int) (key & 0xFFFFFFFFL), (int) (key >> 32));
                targetLevel.getChunkSource().updateChunkForced(chunkPos, false);
            }
            log.info("[Transfer] Forced chunk tickets released.");
        }
    }

    private static void executeTransfer(
            final ServerSubLevel subLevel,
            final LevelPlot plot,
            final BlockPos plotAnchor,
            final Vec3 logicalPos,
            final Set<Long> plotChunkKeys,
            final ServerLevel sourceLevel,
            final ServerLevel targetLevel,
            final BlockPos targetPos,
            final ObjectArrayList<BlockPos> plotBlocks,
            final ObjectArrayList<BlockPos> targetBlocks,
            final BoundingBox3i targetBounds,
            final SubLevelAssemblyHelper.AssemblyTransform transform,
            final Consumer<ServerSubLevel> onComplete
    ) {
        final AABB trackingAabb = AABB.ofSize(logicalPos, 64, 64, 64);

        // ----------------------------------------------------------------
        // PLOT ENTITY SNAPSHOT
        // Entities physically inside the plotgrid (item frames, paintings, etc.).
        //
        // The plotAabb search can return world entities near the ship's logical
        // position due to Sable's entity query mixin remapping plotgrid searches.
        // We guard against this by verifying the entity's chunk is one of the
        // plot's actual plotgrid chunks. Static plot entities (item frames) have
        // their real plotgrid chunk returned from chunkPosition(), while world
        // entities near the ship's logical position have world chunks that won't
        // match.
        //
        // We also exclude tracking entities — they live in world space and would
        // have their positions corrupted by the plotgrid offset math.
        // ----------------------------------------------------------------
        final BoundingBox3ic plotBb = plot.getBoundingBox();
        final AABB plotAabb = new AABB(
                plotBb.minX(), plotBb.minY(), plotBb.minZ(),
                plotBb.maxX() + 1.0, plotBb.maxY() + 1.0, plotBb.maxZ() + 1.0
        ).inflate(1.0);

        final List<Entity> plotEntities = sourceLevel.getEntitiesOfClass(
                Entity.class, plotAabb,
                e -> !(e instanceof Player)
                        && Sable.HELPER.getTrackingSubLevel(e) != subLevel
                        && plotChunkKeys.contains(e.chunkPosition().toLong())
        );
        log.info("[Entity/Plot] Found {} plot entities.", plotEntities.size());

        final List<CompoundTag> plotEntitySnapshots = new ArrayList<>(plotEntities.size());
        for (final Entity entity : plotEntities) {
            final CompoundTag tag = new CompoundTag();
            if (entity.save(tag)) {
                log.info("[Entity/Plot]   Snapshotted {} at {}", entity.getType().toShortString(), entity.position());
                plotEntitySnapshots.add(tag);
            }
        }

        // ----------------------------------------------------------------
        // TRACKING ENTITY SNAPSHOT
        // Non-player entities on top of / around the ship in world space.
        // ----------------------------------------------------------------
        final List<Entity> trackingEntities = sourceLevel.getEntitiesOfClass(
                Entity.class, trackingAabb,
                e -> !(e instanceof Player) && Sable.HELPER.getTrackingSubLevel(e) == subLevel
        );
        log.info("[Entity/Tracking] Found {} tracking entities.", trackingEntities.size());

        final List<CompoundTag> trackingEntitySnapshots = new ArrayList<>(trackingEntities.size());
        final List<Vec3> trackingEntityOffsets = new ArrayList<>(trackingEntities.size());
        for (final Entity entity : trackingEntities) {
            final Vec3 offset = entity.position().subtract(logicalPos);
            final CompoundTag tag = new CompoundTag();
            if (entity.save(tag)) {
                trackingEntitySnapshots.add(tag);
                trackingEntityOffsets.add(offset);
                log.info("[Entity/Tracking]   Snapshotted {} offset={}", entity.getType().toShortString(), offset);
            }
        }

        // ----------------------------------------------------------------
        // PLAYER SNAPSHOT
        // Players tracking the sub-level. Captured before kicking so we know
        // their offset relative to the ship. Teleported after assembly.
        // ----------------------------------------------------------------
        final List<ServerPlayer> trackingPlayers = sourceLevel.getEntitiesOfClass(
                ServerPlayer.class, trackingAabb,
                e -> Sable.HELPER.getTrackingSubLevel(e) == subLevel
        );
        log.info("[Entity/Player] Found {} tracking players.", trackingPlayers.size());

        final List<Vec3> playerOffsets = new ArrayList<>(trackingPlayers.size());
        final List<Float> playerYRots = new ArrayList<>(trackingPlayers.size());
        final List<Float> playerXRots = new ArrayList<>(trackingPlayers.size());
        for (final ServerPlayer player : trackingPlayers) {
            playerOffsets.add(player.position().subtract(logicalPos));
            playerYRots.add(player.getYRot());
            playerXRots.add(player.getXRot());
            log.info("[Entity/Player]   {} offset={}", player.getGameProfile().getName(),
                    player.position().subtract(logicalPos));
        }

        // Kick and discard all non-player entities — recreated after assembly.
        // Players are NOT discarded — they stay alive and are teleported below.
        ((ServerLevelPlot) plot).kickAllEntities();
        for (final Entity entity : plotEntities) {
            entity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
        }
        for (final Entity entity : trackingEntities) {
            entity.remove(Entity.RemovalReason.CHANGED_DIMENSION);
        }

        // ----------------------------------------------------------------
        // BLOCK TRANSFER
        // ----------------------------------------------------------------
        try {
            SubLevelAssemblyHelper.moveBlocks(sourceLevel, transform, plotBlocks);
        } catch (final UnsupportedOperationException e) {
            if (e.getMessage() != null && e.getMessage().contains("nonexistent plot holder")) {
                log.warn("[Transfer] moveBlocks cross-dimension mixin error (expected) — notifying source clients.");
                for (final BlockPos sourceBlock : plotBlocks) {
                    sourceLevel.getChunkSource().blockChanged(sourceBlock);
                }
            } else {
                throw e;
            }
        }

        SubLevelAssemblyHelper.moveTrackingPoints(sourceLevel, plot.getBoundingBox(), null, transform);

        final SubLevelContainer sourceContainer = SubLevelContainer.getContainer(sourceLevel);
        if (sourceContainer != null) {
            final Vector2i origin = sourceContainer.getOrigin();
            sourceContainer.removeSubLevel(
                    plot.plotPos.x - origin.x,
                    plot.plotPos.z - origin.y,
                    SubLevelRemovalReason.REMOVED
            );
        }

        // ----------------------------------------------------------------
        // ASSEMBLY
        // ----------------------------------------------------------------
        final ServerSubLevel result = SubLevelAssemblyHelper.assembleBlocks(
                targetLevel, targetPos, targetBlocks, targetBounds
        );
        log.info("[Transfer] assembleBlocks returned: {}", result);

        if (result == null) {
            log.error("[Transfer] Assembly failed — skipping entity and player recreation.");
            onComplete.accept(null);
            return;
        }

        final Vec3 newLogicalPos = toVec3(result.logicalPose().position());
        final BlockPos newPlotAnchor = result.getPlot().getCenterBlock();

        // ----------------------------------------------------------------
        // PLOT ENTITY RECREATION
        // Remapped into the new plotgrid so they move with the ship.
        // ----------------------------------------------------------------
        if (!plotEntitySnapshots.isEmpty()) {
            log.info("[Entity/Plot] Recreating {} entities. newPlotAnchor={}", plotEntitySnapshots.size(), newPlotAnchor);
            for (final CompoundTag tag : plotEntitySnapshots) {
                final ListTag posList = tag.getList("Pos", Tag.TAG_DOUBLE);
                if (posList.size() == 3) {
                    tag.put("Pos", newDoubleList(
                            newPlotAnchor.getX() + (posList.getDouble(0) - plotAnchor.getX()),
                            newPlotAnchor.getY() + (posList.getDouble(1) - plotAnchor.getY()),
                            newPlotAnchor.getZ() + (posList.getDouble(2) - plotAnchor.getZ())
                    ));
                }
                if (tag.contains("TileX", Tag.TAG_INT)) {
                    tag.putInt("TileX", newPlotAnchor.getX() + (tag.getInt("TileX") - plotAnchor.getX()));
                    tag.putInt("TileY", newPlotAnchor.getY() + (tag.getInt("TileY") - plotAnchor.getY()));
                    tag.putInt("TileZ", newPlotAnchor.getZ() + (tag.getInt("TileZ") - plotAnchor.getZ()));
                }
                final Entity newEntity = EntityType.loadEntityRecursive(tag, targetLevel, e -> e);
                if (newEntity != null) {
                    targetLevel.addFreshEntity(newEntity);
                    log.info("[Entity/Plot]   Spawned {} at {}", newEntity.getType().toShortString(), newEntity.position());
                } else {
                    log.error("[Entity/Plot]   loadEntityRecursive returned null for '{}'", tag.getString("id"));
                }
            }
        }

        // ----------------------------------------------------------------
        // TRACKING ENTITY RECREATION
        // Spawned in world space in the target dimension at newLogicalPos + offset.
        // Sable picks them up as tracking entities once they land on the ship.
        // ----------------------------------------------------------------
        if (!trackingEntitySnapshots.isEmpty()) {
            log.info("[Entity/Tracking] Recreating {} entities. newLogicalPos={}", trackingEntitySnapshots.size(), newLogicalPos);
            for (int i = 0; i < trackingEntitySnapshots.size(); i++) {
                final CompoundTag tag = trackingEntitySnapshots.get(i);
                final Vec3 newPos = newLogicalPos.add(trackingEntityOffsets.get(i));
                tag.put("Pos", newDoubleList(newPos.x, newPos.y, newPos.z));
                final Entity newEntity = EntityType.loadEntityRecursive(tag, targetLevel, e -> e);
                if (newEntity != null) {
                    targetLevel.addFreshEntity(newEntity);
                    log.info("[Entity/Tracking]   Spawned {} at {}", newEntity.getType().toShortString(), newEntity.position());
                } else {
                    log.error("[Entity/Tracking]   loadEntityRecursive returned null for '{}'", tag.getString("id"));
                }
            }
        }

        log.info("[Transfer] Transfer complete.");
        onComplete.accept(result);

        // ----------------------------------------------------------------
        // PLAYER TELEPORTATION
        // Deferred by two tick after assembly so Sable's networking has a full
        // tick to process and register the new sub-level before the client resets
        // its known sub-levels on dimension change. Firing this in the same tick
        // as assembly causes a Sable client-side sync issue (movement packets
        // arriving for an unregistered sub-level).
        // ----------------------------------------------------------------
        if (!trackingPlayers.isEmpty()) {
            DelayedServerTasks.runLater(sourceLevel.getServer(), 2, () -> {
                log.info("[Entity/Player] Teleporting {} players. newLogicalPos={}", trackingPlayers.size(), newLogicalPos);

                for (int i = 0; i < trackingPlayers.size(); i++) {
                    final ServerPlayer player = trackingPlayers.get(i);
                    final Vec3 newPos = newLogicalPos.add(playerOffsets.get(i));

                    log.info("[Entity/Player]   Teleporting {} to {}", player.getGameProfile().getName(), newPos);

                    player.teleportTo(
                            targetLevel,
                            newPos.x,
                            newPos.y,
                            newPos.z,
                            playerYRots.get(i),
                            playerXRots.get(i)
                    );
                }
            });
        }
    }

    private static Vec3 toVec3(final Vector3d v) {
        return new Vec3(v.x, v.y, v.z);
    }

    private static ListTag newDoubleList(final double x, final double y, final double z) {
        final ListTag tag = new ListTag();
        tag.add(DoubleTag.valueOf(x));
        tag.add(DoubleTag.valueOf(y));
        tag.add(DoubleTag.valueOf(z));
        return tag;
    }
}