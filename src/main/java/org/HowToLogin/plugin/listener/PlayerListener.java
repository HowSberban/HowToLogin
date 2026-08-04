package org.howtologin.plugin.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.InventoryHolder;

import java.util.Locale;

// AsyncPlayerSpawnLocationEvent 等 Paper API 标记为 @ApiStatus.Experimental，实际已稳定可用
@SuppressWarnings("UnstableApiUsage")
public final class PlayerListener implements Listener {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public PlayerListener(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    // 在玩家加入世界前拦截：踢出期玩家、同一 IP 账号数量超限
    // 使用 AsyncPlayerPreLoginEvent 替代已弃用的 PlayerLoginEvent（1.21.6+）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        var uuid = event.getUniqueId();

        // 踢出期内拒绝进入
        if (authManager.isKicked(uuid)) {
            long remaining = authManager.getKickRemaining(uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    HTLogin.legacy(I18n.get("login.kicked", remaining)));
            return;
        }

        // 注销后 5 秒内拒绝重连，确保 .dat 删除完成
        if (authManager.isRecentlyUnregistered(uuid)) {
            long remaining = authManager.getRecentUnregisterRemaining(uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    HTLogin.legacy(I18n.get("unregister.recently_deleted", remaining)));
            return;
        }

        // 同一 IP 账号数量限制：仅对新玩家（无账号）检查
        // 统计已注册账号 + 在线未注册玩家，防止多人同时进服后注册超限
        int maxAccounts = plugin.getConfigManager().maxAccountsPerIp();
        if (maxAccounts > 0 && !authManager.hasAccount(uuid)) {
            String ip = event.getAddress().getHostAddress();
            if (!authManager.checkIpRegisterLimit(uuid, ip)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        HTLogin.legacy(I18n.get("register.ip_limit", maxAccounts)));
            }
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
                // 退出位置已在 onSpawnLocation 中设置为出生点，无需传送
                return;
            }
            authManager.addPendingLogin(player);
            player.sendMessage(HTLogin.legacy(I18n.get("listener.please_login", player)));
            scheduleLoginTimeout(player);
            scheduleReminder(player, true);
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("listener.please_register", player)));
            scheduleLoginTimeout(player);
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
     * 在 JoinGamePacket 发送前调整老玩家 spawn 位置。
     * - IP 自动登录的玩家：直接在退出位置出生，避免后续传送。
     * - 启用坐标保护：强制主世界随机位置，防止坐标泄露（F3、小地图 mod 等）。
     * - 未启用坐标保护：检查原位置是否悬空，悬空则改用随机出生点（与 pos 保护相同逻辑），
     *   防止反作弊误踢，同时不修正到正下方地面（避免玩家利用逃避摔落伤害）。
     * 新玩家不干预，保留原版出生机制。
     * 此事件在 configuration phase 触发（异步线程），玩家尚未真正加入世界。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        // 新玩家不干预，保留原版出生机制
        if (event.isNewPlayer()) return;

        var conn = event.getConnection();
        java.util.UUID uuid = conn.getProfile().getId();
        String ip = conn.getClientAddress().getAddress().getHostAddress();

        // IP 自动登录的玩家直接在退出位置出生，避免后续传送
        if (uuid != null && authManager.checkIpAutoLogin(uuid, ip)) {
            Location logoutLoc = authManager.getLogoutLocation(uuid);
            if (logoutLoc != null) {
                event.setSpawnLocation(logoutLoc);
                return;
            }
        }

        if (plugin.getConfigManager().protectionPosEnabled()) {
            // 启用坐标保护：强制主世界随机位置，防止坐标泄露
            org.bukkit.World world = org.bukkit.Bukkit.getWorlds().getFirst();
            Location safeSpawn = authManager.findSafeAuthSpawn(world);
            event.setSpawnLocation(safeSpawn);
        } else {
            // 未启用坐标保护：悬空时改用随机出生点，防止反作弊误踢
            if (authManager.isLocationFloating(event.getSpawnLocation())) {
                org.bukkit.World world = org.bukkit.Bukkit.getWorlds().getFirst();
                event.setSpawnLocation(authManager.findSafeAuthSpawn(world));
            }
        }
    }

    /** 启动登录超时踢出任务（onJoin 和 forceRegister 共用，重复调用会自动作废旧任务） */
    public void scheduleLoginTimeout(Player player) {
        int timeout = plugin.getConfigManager().loginTimeout();
        if (timeout <= 0) return;

        // 记录启动时间，触发时校验是否为最新任务（forceRegister 重启超时后旧任务自动失效）
        long startedAt = authManager.markLoginTimeoutStart(player.getUniqueId());
        // 20 tick = 1 秒
        long delayTicks = timeout * 20L;
        // Paper 1.20+ 统一调度器 API，兼容 Folia（在实体所在区域调度）
        player.getScheduler().runDelayed(plugin, scheduledTask -> {
            // 非最新任务直接放弃（forceRegister 已重启超时计时）
            if (!authManager.isLatestLoginTimeout(player.getUniqueId(), startedAt)) return;
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
        // 注销玩家退出时删除原版 .dat（服务器已保存并释放文件锁）
        authManager.tryDeletePlayerDataOnQuit(player.getUniqueId());
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
        int space = message.indexOf(' ');
        String commandName = (space > 0 ? message.substring(0, space) : message).toLowerCase(Locale.ROOT);

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

    // ===== 以下为新增事件监听 =====

    // 背包保护：阻止查看未登录玩家的背包或末影箱
    // InventoryHolder 为 Player 时表示打开的是某玩家的背包或末影箱
    // target == viewer 的情况（玩家打开自己的背包/末影箱）由 prevent.inventory 控制，此处不干预
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.getConfigManager().protectionInventoryEnabled()) return;
        if (!(event.getPlayer() instanceof Player viewer)) return;

        InventoryHolder holder = event.getInventory().getHolder();
        // holder 为目标玩家时，检查该玩家是否未登录
        if (holder instanceof Player target && !target.equals(viewer)
                && !authManager.isLoggedIn(target)) {
            event.setCancelled(true);
            viewer.sendMessage(HTLogin.legacy(I18n.get("listener.inventory_protected", viewer, target.getName())));
        }
    }

    // 容器点击（含创造模式）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (event.getWhoClicked() instanceof Player player
                && !authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    // 容器拖拽
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (event.getWhoClicked() instanceof Player player
                && !authManager.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    // 传送门
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(PlayerPortalEvent event) {
        if (!plugin.getConfigManager().preventWorldInteraction()) return;
        if (!authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 物品消耗（进食、喝药水等）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (!authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 副手切换
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (!authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
