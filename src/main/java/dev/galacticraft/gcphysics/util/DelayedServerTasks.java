package dev.galacticraft.gcphysics.util;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

public final class DelayedServerTasks {
    private static final List<ScheduledTask> TASKS = new ArrayList<>();

    private DelayedServerTasks() {}

    public static void runLater(MinecraftServer server, int delayTicks, Runnable task) {
        TASKS.add(new ScheduledTask(server.getTickCount() + delayTicks, task));
    }

    public static void tick(MinecraftServer server) {
        int now = server.getTickCount();

        TASKS.removeIf(task -> {
            if (task.runAtTick <= now) {
                task.task.run();
                return true;
            }
            return false;
        });
    }

    private record ScheduledTask(int runAtTick, Runnable task) {}
}