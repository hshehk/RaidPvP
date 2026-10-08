package me.tsukieru.raidpvp.util;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class SchedulerUtil {
    private SchedulerUtil() {}

    public static boolean run(Entity entity, Plugin plugin, Runnable task) {
        return entity.getScheduler().run(plugin, scheduledTask -> task.run(), null) != null;
    }
}
