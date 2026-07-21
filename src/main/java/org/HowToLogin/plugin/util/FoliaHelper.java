package org.HowToLogin.plugin.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.function.Consumer;

/**
 * Folia 兼容工具类。
 * 运行时自动检测 Folia 并使用对应的调度器 API。
 */
public final class FoliaHelper {

    private static final boolean FOLIA;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            // 非 Folia 环境
        }
        FOLIA = folia;
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * 在全局区域调度任务（用于非实体/非世界操作）。
     */
    public static void runGlobal(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 在实体所在区域调度任务（用于玩家相关操作）。
     */
    public static void runEntity(Plugin plugin, Player player, Consumer<Player> task) {
        if (FOLIA) {
            player.getScheduler().run(plugin, scheduledTask -> task.accept(player), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> task.accept(player));
        }
    }

    /**
     * 在实体所在区域延迟调度任务。
     */
    public static void runEntityDelayed(Plugin plugin, Player player, Consumer<Player> task, long delayTicks) {
        if (FOLIA) {
            player.getScheduler().runDelayed(plugin, scheduledTask -> task.accept(player), null, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> task.accept(player), delayTicks);
        }
    }

    /**
     * 调度异步任务（用于文件 I/O 等）。
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * 取消该插件所有已注册的任务。
     */
    public static void cancelTasks(Plugin plugin) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private FoliaHelper() {}
}