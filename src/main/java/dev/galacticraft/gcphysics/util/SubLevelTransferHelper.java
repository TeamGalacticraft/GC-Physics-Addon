package dev.galacticraft.gcphysics.util;

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
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector2i;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public final class SubLevelTransferHelper {

    private SubLevelTransferHelper() {}

    /**
     * Transfers a sub-level to another dimension asynchronously across two ticks.
     * @param onComplete called on tick N+1 with the new ServerSubLevel, or null if assembly failed.
     */
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
            onComplete.accept(null);
            return;
        }

        final BlockPos plotAnchor = plot.getCenterBlock();

        final ObjectArrayList<BlockPos> plotBlocks = new ObjectArrayList<>();
        for (final PlotChunkHolder chunk : plot.getLoadedChunks()) {
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
                        final BlockState state = sourceLevel.getBlockState(pos);
                        if (!state.isAir()) {
                            plotBlocks.add(pos);
                        }
                    }
                }
            }
        }

        if (plotBlocks.isEmpty()) {
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

        final BoundingBox3i targetBounds = new BoundingBox3i(
                minX - 1, minY - 1, minZ - 1,
                maxX + 1, maxY + 1, maxZ + 1
        );

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

        // Tick N+1: by now Sable has had a full server tick to allocate and load plot chunks.
        final int nextTick = sourceLevel.getServer().getTickCount() + 1;
        sourceLevel.getServer().tell(new TickTask(nextTick, () -> {
            ((ServerLevelPlot) plot).kickAllEntities();
            try {
                SubLevelAssemblyHelper.moveBlocks(sourceLevel, transform, plotBlocks);
            } catch (final UnsupportedOperationException e) {
                if (e.getMessage() != null && e.getMessage().contains("nonexistent plot holder")) {
                    // moveBlocks' fifth loop sends sendBlockUpdated() to resultingLevel (overworld)
                    // at the source plotgrid coordinates. Those coordinates fall in the overworld's
                    // plotgrid zone but have no PlotChunkHolder, causing Sable's mixin to throw.
                    // By this point loops 2-4 have completed: blocks copied, source cleared.
                    // We just need to notify the source level clients about the cleared positions.
                    for (final BlockPos sourceBlock : plotBlocks) {
                        sourceLevel.getChunkSource().blockChanged(sourceBlock);
                    }
                } else {
                    // Unexpected — rethrow so we don't swallow unrelated errors.
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

            final ServerSubLevel result = SubLevelAssemblyHelper.assembleBlocks(
                    targetLevel, targetPos, targetBlocks, targetBounds
            );

            // Release forced tickets — Sable's PlotChunkHolder takes over from here.
            for (final long key : forcedChunks) {
                final ChunkPos chunkPos = new ChunkPos((int) (key & 0xFFFFFFFFL), (int) (key >> 32));
                targetLevel.getChunkSource().updateChunkForced(chunkPos, false);
            }

            onComplete.accept(result);
        }));
    }
}