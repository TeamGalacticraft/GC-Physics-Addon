package dev.galacticraft.gcphysics.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.galacticraft.gcphysics.util.SubLevelTransferHelper;
import dev.ryanhcode.sable.api.command.SubLevelArgumentType;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public final class GCPhysicsCommands {

    private GCPhysicsCommands() {}

    /**
     * Registers all GC Physics Addon commands under /gcphysics.
     */
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext buildContext) {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("gcphysics")
                .requires(src -> src.hasPermission(2));

        root.then(Commands.literal("ship")
                .then(Commands.literal("teleport")
                        .then(Commands.argument("sub_level", SubLevelArgumentType.singleSubLevel())
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(GCPhysicsCommands::executeShipTeleport))))));

        dispatcher.register(root);
    }

    private static int executeShipTeleport(final CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        final ServerSubLevel subLevel = SubLevelArgumentType.getSingleSubLevel(ctx, "sub_level");
        final ServerLevel targetLevel = DimensionArgument.getDimension(ctx, "dimension");
        final BlockPos targetPos = BlockPosArgument.getBlockPos(ctx, "pos");
        final ServerLevel sourceLevel = ctx.getSource().getLevel();

        if (sourceLevel == targetLevel) {
            ctx.getSource().sendFailure(Component.literal("[GCPhysics] Source and target dimension are the same."));
            return 0;
        }

        final SubLevelContainer targetContainer = SubLevelContainer.getContainer(targetLevel);
        if (targetContainer == null) {
            ctx.getSource().sendFailure(Component.literal("[GCPhysics] Target dimension has no Sable SubLevelContainer."));
            return 0;
        }

        ctx.getSource().sendSystemMessage(Component.literal("[GCPhysics] Transferring ship, please wait..."));

        SubLevelTransferHelper.transferToDimension(subLevel, sourceLevel, targetLevel, targetPos, result -> {
            if (result == null) {
                ctx.getSource().sendFailure(Component.literal("[GCPhysics] Transfer failed: assembly returned null."));
                return;
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[GCPhysics] Ship assembled in " + targetLevel.dimension().location() +
                            " at " + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ()
            ), true);
        });

        return 1;
    }
}