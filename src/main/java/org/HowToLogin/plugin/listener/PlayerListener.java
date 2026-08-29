package org.howtologin.plugin.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.dialog.PreJoinAuthListener;
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
import org.bukkit.event.player.*;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// AsyncPlayerSpawnLocationEvent 等 Paper API 标记为 @ApiStatus.Experimental，实际已稳定可用
@SuppressWarnings("UnstableApiUsage")
public final class PlayerListener implements Listener {

    private final HTLogin plugin;
    private final AuthManager authManager;
    // 活跃的提醒 BossBar：登录成功/玩家退出时立即隐藏（不等下一个任务周期）
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> reminderBars = new ConcurrentHashMap<>();
    // 活跃的提醒任务：重新挂起（reload）时取消旧任务，避免新旧任务并行重复提醒
    private final Map<UUID, ScheduledTask> reminderTasks = new ConcurrentHashMap<>();

    public PlayerListener(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    // 在玩家加入世界前拦截：踢出期玩家、同一 IP 账号数量超限
    // 使用 AsyncPlayerPreLoginEvent 替代已弃用的 PlayerLoginEvent（1.21.6+）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        var uuid = event.getUniqueId();

        // 数据库加载失败（fail-closed）：缓存为空会把所有玩家误判为未注册，拒绝进入直至恢复
        if (plugin.getPlayerDataManager().isLoadFailed()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("login.db_unavailable"));
            return;
        }

        // 踢出期内拒绝进入
        if (authManager.isKicked(uuid)) {
            long remaining = authManager.getKickRemaining(uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    I18n.msg("login.kicked", remaining));
            return;
        }

        // 注销后 5 秒内拒绝重连，确保 .dat 删除完成
        if (authManager.isRecentlyUnregistered(uuid)) {
            long remaining = authManager.getRecentUnregisterRemaining(uuid);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("unregister.recently_deleted", remaining));
            return;
        }

        // 同 IP 已达上限时仅在连接层拦截无账号新玩家，避免名额已满的 IP 涌入未注册玩家；
        // max-accounts-per-ip.reject-join 关闭时放行进服，由注册动作精确判定（共享 IP 环境友好）
        // 已达上限判定内部已处理 max<=0，无需在此重复判断
        if (plugin.getConfigManager().ipLimitRejectJoin()
                && !authManager.hasAccount(uuid)
                && authManager.isIpAccountLimitReached(event.getAddress().getHostAddress())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("register.ip_limit",
                            plugin.getConfigManager().maxAccountsPerIp()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 更新活跃时间（有账号即更新，用于不活跃清理；未注册玩家不写库）
        authManager.touchActive(player);

        // Pre-join Dialog 已在配置阶段完成登录/注册：收尾后直接进入世界（无需挂起）
        PreJoinAuthListener preJoin = plugin.getPreJoinAuthListener();
        if (preJoin != null) {
            PreJoinAuthListener.AuthOutcome outcome = preJoin.consume(player);
            if (outcome != null) {
                boolean login = outcome == PreJoinAuthListener.AuthOutcome.LOGIN;
                if (login ? authManager.finishPreJoinLogin(player) : authManager.finishPreJoinRegister(player)) {
                    player.sendMessage(I18n.msg(login ? "login.success" : "register.success", player));
                    return;
                }
                // 账号在配置阶段认证后被删除（竞态）：走正常挂起流程
            }
        }

        // 正版玩家免密登录：跳过密码验证，直接标记为已登录
        if (authManager.isPremium(player)) {
            // 正版验证失败回退进入的玩家：本次需密码登录，不自动免密
            if (authManager.isPremiumFallback(player.getUniqueId())) {
                beginAuthFlow(player);
                return;
            }
            // 先检查会话是否命中（决定是否需要传送）
            // 命中时 onSpawnLocation 已将出生点设为退出位置，无需传送
            // 未命中时需传送到退出位置
            boolean sessionHit = authManager.hasSession(player);
            authManager.autoLogin(player);
            // 已绑定 2FA（pre-join 弹窗未覆盖时的回退）：等待验证码，登录收尾与传送延迟到 /2fa 验证完成
            boolean pending2fa = authManager.isPending2fa(player.getUniqueId());
            if (pending2fa) {
                // 未通过 2FA 不算登录成功：与挂起流程一致（旁观保护 + 周期提醒 + 超时）
                suspend(player, "login.need_2fa", true);
            } else {
                player.sendMessage(I18n.msg("login.premium_auto_login", player));
            }
            if (!sessionHit && !pending2fa) {
                authManager.returnToLogoutLocation(player);
            }
            return;
        }

        if (authManager.hasAccount(player)) {
            // 会话命中：上次登录 IP 与当前一致且未过期，免输密码直接登录
            if (authManager.hasSession(player)) {
                authManager.autoLogin(player);
                if (authManager.isPending2fa(player.getUniqueId())) {
                    // 已绑定 2FA（pre-join 弹窗未覆盖时的回退）：等待验证码，传送由 /2fa 验证完成流程处理
                    suspend(player, "login.need_2fa", true);
                } else {
                    // 退出位置已在 onSpawnLocation 中设置为出生点，无需传送
                    player.sendMessage(I18n.msg("login.ip_auto_login", player));
                }
                return;
            }
        }
        beginAuthFlow(player);
    }

    /**
     * 通用挂起：旁观者保护 + 消息提示 + 周期提醒 + 超时踢出。
     * 所有等待登录/2FA 的挂起路径强制复用本方法，防止各分支手工复制漏项。
     * @param messageKey 挂起时发送的聊天提示 key
     * @param needsLogin true = 登录流程提示（含 2FA），false = 注册流程提示
     */
    public void suspend(Player player, String messageKey, boolean needsLogin) {
        authManager.setSpectator(player);
        player.sendMessage(I18n.msg(messageKey, player));
        scheduleReminder(player, needsLogin);
        scheduleLoginTimeout(player, needsLogin);
    }

    /**
     * 挂起玩家等待登录/注册：待登录状态、旁观模式、聊天提示、超时与周期提醒。
     * join 与 /reload 重挂起共用；有账号走登录流程，无账号走注册流程。
     * 配置阶段 Dialog（pre-join）未能覆盖的玩家（旧客户端/旧服务端/超时放行）在此以聊天栏提示挂起。
     */
    public void beginAuthFlow(Player player) {
        boolean hasAccount = authManager.hasAccount(player);
        // 无密码账户：验证码是唯一登录因素，直接进入待验证状态
        boolean passwordless = hasAccount && authManager.isPasswordless(player.getUniqueId());
        if (hasAccount) {
            authManager.addPendingLogin(player);
        }
        if (passwordless) {
            String ip = AuthManager.clientIp(player);
            if (!authManager.requires2faAtLogin(player.getUniqueId(), ip)) {
                // 2FA 会话命中：免验证码直接登录（Dialog 未覆盖时的回退路径）
                // 走到这里说明 login.session 未命中，出生点在保护位置，登录后须传送回退出位置
                authManager.autoLogin(player);
                player.sendMessage(I18n.msg("login.success", player));
                authManager.returnToLogoutLocation(player);
                return;
            }
            authManager.addPending2fa(player.getUniqueId());
        }
        suspend(player,
                passwordless ? "login.passwordless_prompt"
                        : hasAccount ? "listener.please_login" : "listener.please_register",
                hasAccount);
    }

    /**
     * 周期性重发登录/注册提示，防止玩家没看到。
     * 任务自管理：玩家登录/注册成功或下线后自动取消。
     * 提示方式由 login.remind-method 配置：chat / title / actionbar / bossbar。
     * @param needsLogin true = 发送登录提示，false = 发送注册提示
     */
    public void scheduleReminder(Player player, boolean needsLogin) {
        int interval = plugin.getConfigManager().loginRemindInterval();
        if (interval <= 0) return;
        long periodTicks = interval * 20L;
        UUID uuid = player.getUniqueId();
        // 取消旧提醒任务（refreshPendingPlayers 重新挂起时避免新旧任务并行重复提醒）
        ScheduledTask old = reminderTasks.remove(uuid);
        if (old != null) old.cancel();
        // Paper 1.20+ 统一调度器 API，兼容 Folia
        // 玩家调度器已退休（退出瞬间与 reload 挂起竞态）时返回 null，null 不允许入 Map
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            if (!player.isOnline()) {
                reminderTasks.remove(uuid);
                hideReminderBar(player);
                scheduledTask.cancel();
                return;
            }
            boolean done = needsLogin ? authManager.isLoggedIn(player) : authManager.hasAccount(player);
            if (done) {
                reminderTasks.remove(uuid);
                hideReminderBar(player);
                scheduledTask.cancel();
                return;
            }
            sendReminder(player, needsLogin);
        }, null, periodTicks, periodTicks);
        if (task != null) {
            reminderTasks.put(uuid, task);
        }
    }

    /** 按配置方式发送登录/注册提醒（bossbar 引用统一由 reminderBars 持有） */
    private void sendReminder(Player player, boolean needsLogin) {
        // 无密码账户提醒输入验证码而非密码；已过密码待 2FA 的提醒输入验证码
        String key = !needsLogin ? "listener.please_register"
                : authManager.isPending2fa(player.getUniqueId()) ? "login.need_2fa"
                : authManager.isPasswordless(player.getUniqueId()) ? "login.passwordless_prompt"
                : "listener.please_login";
        String method = plugin.getConfigManager().loginRemindMethod();
        switch (method) {
            case "title" -> player.showTitle(net.kyori.adventure.title.Title.title(
                    I18n.msg(key, player),
                    net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(500),
                            java.time.Duration.ofMillis(2000),
                            java.time.Duration.ofMillis(500))));
            case "actionbar" -> player.sendActionBar(I18n.msg(key, player));
            case "bossbar" -> {
                net.kyori.adventure.bossbar.BossBar bar = reminderBars.get(player.getUniqueId());
                if (bar == null) {
                    net.kyori.adventure.text.Component text = I18n.msg(key, player);
                    bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                            text, 1.0f,
                            net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
                            net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
                    player.showBossBar(bar);
                    reminderBars.put(player.getUniqueId(), bar);
                } else {
                    bar.name(I18n.msg(key, player));
                }
            }
            default -> player.sendMessage(I18n.msg(key, player));
        }
    }

    /** 隐藏并移除提醒 BossBar（登录成功、玩家退出、任务自检清理时调用；非 bossbar 方式时为空操作） */
    public void hideReminderBar(Player player) {
        net.kyori.adventure.bossbar.BossBar bar = reminderBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** 清理所有提醒 BossBar（refreshPendingPlayers 重新挂起前调用，防止旧 BossBar 悬挂到玩家登录才消失） */
    private void clearReminderBars() {
        reminderBars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bar);
        });
        reminderBars.clear();
    }

    /**
     * 配置热重载后重新挂起未登录玩家：清理旧提醒，按新配置重新展示。
     * 逐玩家切回其区域线程执行（Folia：管理员与目标玩家可能不在同一区域线程）。
     */
    public void refreshPendingPlayers() {
        clearReminderBars();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (authManager.isLoggedIn(player)) continue;
            player.getScheduler().run(plugin, task -> beginAuthFlow(player), null);
        }
    }

    /**
     * 在 JoinGamePacket 发送前调整老玩家 spawn 位置。
     * - 会话命中的玩家：直接在退出位置出生，避免后续传送。
     * - 启用坐标保护：强制主世界随机位置，防止坐标泄露（F3、小地图 mod 等）。
     * 新玩家不干预，保留原版出生机制。
     * 此事件在 configuration phase 触发（异步线程），玩家尚未真正加入世界。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        // 新玩家不干预，保留原版出生机制
        if (event.isNewPlayer()) return;

        var conn = event.getConnection();
        java.util.UUID uuid = conn.getProfile().getId();
        // 代理协议下地址可能未解析（getAddress() 返回 null），判空避免 NPE
        var clientAddr = conn.getClientAddress().getAddress();
        String ip = clientAddr != null ? clientAddr.getHostAddress() : null;

        // 会话命中（免输密码）的玩家直接在退出位置出生，避免后续传送
        // 登录需 2FA 的除外：验证完成前不放行到退出位置（/2fa 验证后再传送）；
        // 2FA 会话命中（同 IP 且未过期）视同已完成验证
        if (uuid != null && authManager.hasSession(uuid, ip) && !authManager.requires2faAtLogin(uuid, ip)) {
            Location logoutLoc = authManager.getLogoutLocation(uuid);
            if (logoutLoc != null) {
                event.setSpawnLocation(logoutLoc);
                return;
            }
        }

        // Pre-join 已认证玩家：与会话命中一致，直接在退出位置出生，避免随机出生后再传送
        PreJoinAuthListener preJoin = plugin.getPreJoinAuthListener();
        if (uuid != null && preJoin != null && preJoin.hasCompleted(uuid)) {
            Location logoutLoc = authManager.getLogoutLocation(uuid);
            if (logoutLoc != null) {
                event.setSpawnLocation(logoutLoc);
            }
            // 已认证：登录前未接收任何世界信息，无需坐标保护
            return;
        }

        // 启用坐标保护：强制主世界随机位置，防止坐标泄露
        if (plugin.getConfigManager().protectionPosEnabled()) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorlds().getFirst();
            Location safeSpawn = authManager.findSafeAuthSpawn(world);
            event.setSpawnLocation(safeSpawn);
        }
    }

    /** 启动登录/注册超时踢出任务（onJoin 和 forceRegister 共用，重复调用会自动作废旧任务）
     *  @param needsLogin true = 登录超时（login.timeout），false = 注册超时（register.timeout） */
    public void scheduleLoginTimeout(Player player, boolean needsLogin) {
        int timeout = plugin.getConfigManager().loginTimeout();
        if (!needsLogin) timeout = plugin.getConfigManager().registerTimeout();
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
                    player.kick(I18n.msg("listener.login_timeout", player));
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
        // 立即清理提醒 BossBar：玩家调度器随退出 retired，任务内的清理分支不再执行
        hideReminderBar(player);
        // 清理提醒任务引用（任务随玩家调度器 retired 不再执行，防止 Map 残留）
        reminderTasks.remove(player.getUniqueId());
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
            // 仅禁止位置移动：直接改 to 的坐标（复用对象，避免热路径逐次分配），保留其视角（yaw/pitch）
            if (positionChanged) {
                to.setX(from.getX());
                to.setY(from.getY());
                to.setZ(from.getZ());
                event.setTo(to);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfigManager().preventChat()) return;

        Player player = event.getPlayer();
        if (!authManager.isLoggedIn(player)) {
            event.setCancelled(true);
            player.sendMessage(I18n.msg("listener.must_login", player));
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
        player.sendMessage(I18n.msg("listener.must_login", player));
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

    // 实体交互（右键实体：村民交易、上马、喂食等）：未登录玩家保持原游戏模式时可打开交易界面窥视
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 禁止未登录的旁观玩家附身实体：附身后镜头跟随目标实体移动，可窥视他人位置（绕过坐标保护）。
    // Paper 1.21.11 已移除 PlayerSpectateEntityEvent，附身改由 cause=SPECTATE 的传送事件表达
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (plugin.getConfigManager().preventWorldInteraction()
                && !authManager.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
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
