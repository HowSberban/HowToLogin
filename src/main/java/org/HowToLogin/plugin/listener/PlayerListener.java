package org.HowToLogin.plugin.listener;

import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.HowToLogin.plugin.util.FoliaHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;

public final class PlayerListener implements Listener {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public PlayerListener(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (authManager.hasAccount(player)) {
            authManager.addPendingLogin(player);
            player.sendMessage(HTLogin.legacy(I18n.get("listener.please_login")));
            scheduleLoginTimeout(player);
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("listener.please_register")));
        }
        player.sendMessage(HTLogin.legacy(I18n.get("listener.welcome")));
    }

    private void scheduleLoginTimeout(Player player) {
        int timeout = plugin.getConfigManager().loginTimeout();
        if (timeout <= 0) return;

        // 20 tick = 1 秒
        long delayTicks = timeout * 20L;
        FoliaHelper.runEntityDelayed(plugin, player, p -> {
            if (!authManager.isLoggedIn(p) && p.isOnline()) {
                if (plugin.getConfigManager().kickOnTimeout()) {
                    p.kick(HTLogin.legacy(I18n.get("listener.login_timeout")));
                }
            }
        }, delayTicks);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        authManager.clearSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (authManager.isLoggedIn(player)) return;

        // 未登录/未注册玩家只允许视角转动，禁止水平移动
        // Paper API 中 PlayerMoveEvent.getTo() 不会返回 null，故无需 null 检查
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            if (plugin.getConfigManager().preventChat()) {
                event.setCancelled(true);
                player.sendMessage(HTLogin.legacy(I18n.get("listener.must_login")));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (authManager.isLoggedIn(player)) return;

        // 未登录/未注册玩家只能使用登录/注册相关命令
        String msg = event.getMessage().toLowerCase();
        if (msg.startsWith("/login") || msg.startsWith("/l ") || msg.equals("/l")
                || msg.startsWith("/register") || msg.startsWith("/reg ") || msg.equals("/reg")) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(HTLogin.legacy(I18n.get("listener.must_login_cmd")));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (!authManager.isLoggedIn(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (!authManager.isLoggedIn(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }
}
