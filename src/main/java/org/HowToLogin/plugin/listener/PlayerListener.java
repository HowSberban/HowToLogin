package org.HowToLogin.plugin.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;

import java.util.Locale;

public final class PlayerListener implements Listener {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public PlayerListener(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    // 在玩家加入世界前拦截踢出期玩家，避免 PlayerJoinEvent 中 kick 触发 chunk loader 异常
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (authManager.isKicked(player)) {
            long remaining = authManager.getKickRemaining(player);
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    HTLogin.legacy(I18n.get("login.kicked", player, remaining)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (authManager.hasAccount(player)) {
            // 尝试 IP 免密登录：上次登录 IP 与当前一致时自动登录
            if (authManager.checkIpAutoLogin(player)) {
                authManager.loginByIp(player);
                player.sendMessage(HTLogin.legacy(I18n.get("login.ip_auto_login", player)));
                // 登录后传送回上次退出位置（坐标保护模式下生效）
                authManager.returnToLogoutLocation(player);
                return;
            }
            authManager.addPendingLogin(player);
            player.sendMessage(HTLogin.legacy(I18n.get("listener.please_login", player)));
            scheduleLoginTimeout(player);
            scheduleReminder(player, true);
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("listener.please_register", player)));
            scheduleReminder(player, false);
        }
    }

    /**
     * 周期性重发登录/注册提示，防止玩家没看到。
     * 任务自管理：玩家登录/注册成功或下线后自动取消。
     * @param needsLogin true = 发送登录提示，false = 发送注册提示
     */
    public void scheduleReminder(Player player, boolean needsLogin) {
        int interval = plugin.getConfigManager().loginRemindInterval();
        if (interval <= 0) return;
        long periodTicks = interval * 20L;
        // Paper 1.20+ 统一调度器 API，兼容 Folia
        player.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            if (!player.isOnline()) {
                scheduledTask.cancel();
                return;
            }
            if (needsLogin) {
                // 已登录则停止提醒
                if (authManager.isLoggedIn(player)) {
                    scheduledTask.cancel();
                    return;
                }
                player.sendMessage(HTLogin.legacy(I18n.get("listener.please_login", player)));
            } else {
                // 已注册则停止提醒
                if (authManager.hasAccount(player)) {
                    scheduledTask.cancel();
                    return;
                }
                player.sendMessage(HTLogin.legacy(I18n.get("listener.please_register", player)));
            }
        }, null, periodTicks, periodTicks);
    }

    /**
     * 在 JoinGamePacket 发送前修改老玩家 spawn 位置为主世界出生点周围随机位置，
     * 从根本上防止 player data 中的退出位置泄露给客户端（F3、小地图 mod 等）。
     * 新玩家不干预，保留原版出生机制（无泄露风险）。
     * 此事件在 configuration phase 触发（异步线程），玩家尚未真正加入世界。
     * 强制使用主世界，防止玩家上次退出维度（下界/末地）信息泄露。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (!plugin.getConfigManager().protectionPosEnabled()) return;

        // 新玩家不干预，保留原版出生机制
        if (event.isNewPlayer()) return;

        // 老玩家：强制主世界随机位置，防止坐标泄露
        org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
        Location safeSpawn = authManager.findSafeAuthSpawn(world);
        event.setSpawnLocation(safeSpawn);
    }

    private void scheduleLoginTimeout(Player player) {
        int timeout = plugin.getConfigManager().loginTimeout();
        if (timeout <= 0) return;

        // 20 tick = 1 秒
        long delayTicks = timeout * 20L;
        // Paper 1.20+ 统一调度器 API，兼容 Folia（在实体所在区域调度）
        player.getScheduler().runDelayed(plugin, scheduledTask -> {
            if (!authManager.isLoggedIn(player) && player.isOnline()) {
                if (plugin.getConfigManager().kickOnTimeout()) {
                    player.kick(HTLogin.legacy(I18n.get("listener.login_timeout", player)));
                }
            }
        }, null, delayTicks);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // 已登录玩家退出时保存退出位置（用于下次登录后传送回来）
        // 未登录玩家退出不更新位置，保持上次保存的位置不变
        if (authManager.isLoggedIn(player)) {
            authManager.saveLogoutLocation(player);
        }
        authManager.clearSession(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfigManager().preventMove()) return;

        Player player = event.getPlayer();
        if (authManager.isLoggedIn(player)) return;

        // Paper API 保证 getTo() 非 null（@NullMarked）
        // 使用 setTo() 而非 setCancelled(true)：
        //   1. 避免客户端与服务端位置不同步导致的画面卡顿
        //   2. 使用精确坐标比较（getX/Y/Z），避免方块坐标精度不足被绕过
        Location from = event.getFrom();
        Location to = event.getTo();
        boolean positionChanged = from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();
        boolean lookChanged = from.getYaw() != to.getYaw()
                || from.getPitch() != to.getPitch();

        boolean preventLook = plugin.getConfigManager().preventLook();
        if (preventLook) {
            // 禁止位置和视角变化：全部回滚到 from
            if (positionChanged || lookChanged) {
                event.setTo(from);
            }
        } else {
            // 仅禁止位置移动：保留 to 的视角（yaw/pitch）
            if (positionChanged) {
                event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(),
                        to.getYaw(), to.getPitch()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfigManager().preventChat()) return;

        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            event.setCancelled(true);
            player.sendMessage(HTLogin.legacy(I18n.get("listener.must_login", player)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().preventCommand()) return;

        Player player = event.getPlayer();
        if (authManager.isLoggedIn(player)) return;

        // 提取命令名（去掉前导 / 和参数），统一小写匹配
        String message = event.getMessage();
        if (message.startsWith("/")) message = message.substring(1);
        String commandName = message.split(" ", 2)[0].toLowerCase(Locale.ROOT);

        // 白名单内的命令允许执行
        if (plugin.getConfigManager().commandWhitelist().contains(commandName)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(HTLogin.legacy(I18n.get("listener.must_login", player)));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getConfigManager().preventWorldInteraction()) return;
        if (event.getEntity() instanceof Player player) {
            // 未登录玩家或传送过渡期玩家不受伤害
            if (!authManager.isLoggedIn(player) || authManager.isInvulnerablePending(player)) {
                event.setCancelled(true);
            }
        }
    }

    // 阻止怪物锁定未登录玩家（怪物不会朝玩家移动或试图攻击）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityTarget(EntityTargetEvent event) {
        if (!plugin.getConfigManager().preventWorldInteraction()) return;
        if (event.getTarget() instanceof Player player
                && !authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && event.getEntity() instanceof Player player
                && !authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
