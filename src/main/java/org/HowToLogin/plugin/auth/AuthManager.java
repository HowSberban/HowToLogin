package org.howtologin.plugin.auth;

import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.config.ConfigManager;
import org.howtologin.plugin.data.PlayerDataManager;
import org.howtologin.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class AuthManager {

    private final HTLogin plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    // 线程安全集合，用于 Folia 多线程区域化调度
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingLogin = ConcurrentHashMap.newKeySet();
    // 登录后传送过渡期：玩家已登录但还在传送到退出位置，期间保持无敌
    private final Set<UUID> invulnerablePending = ConcurrentHashMap.newKeySet();
    // 暴力破解防护：记录失败次数和踢出到期时间
    private final Map<UUID, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> kickUntil = new ConcurrentHashMap<>();
    // 标记待删除原版数据的玩家（unregister 后等待 PlayerQuitEvent 触发时删除 .dat）
    private final Set<UUID> pendingDatDelete = ConcurrentHashMap.newKeySet();
    // 记录最近注销的玩家时间戳：5 秒内拒绝重连，确保 .dat 删除完成
    private final Map<UUID, Long> recentUnregister = new ConcurrentHashMap<>();
    // 待升级离线账号（离线 UUID）：玩家执行升级指令后标记，下次登录时尝试正版验证
    private final Set<UUID> pendingUpgrade = ConcurrentHashMap.newKeySet();
    // 首次注册/升级正版账号的待提示明文密码（正版 UUID → 明文）：玩家 join 时发送并移除
    private final Map<UUID, String> pendingPremiumPassword = new ConcurrentHashMap<>();
    // 正版验证失败回退进入的正版玩家（正版 UUID）：本次需密码登录，不自动免密
    private final Set<UUID> premiumFallback = ConcurrentHashMap.newKeySet();
    // 登录超时任务启动时间戳：用于判断超时任务是否为最新（重启时旧任务自动失效）
    private final Map<UUID, Long> loginTimeoutStartedAt = new ConcurrentHashMap<>();
    // 缓存世界结构类型：26.1+ 采用新结构（players/data + dimensions/minecraft/overworld）
    private final boolean newWorldStructure;
    // 注销后拒绝重连时长（毫秒）
    private static final long UNREGISTER_RECONNECT_DELAY = 5000L;

    public AuthManager(HTLogin plugin, PlayerDataManager dataManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.newWorldStructure = detectNewWorldStructure();
    }

    /**
     * 检测服务端是否使用 26.1+ 的新世界文件结构。
     * Bukkit.getBukkitVersion() 返回如 "1.21.11-R0.1-SNAPSHOT" 或 "26.1.2-R0.1-SNAPSHOT"。
     * 26.1+ 主版本号 >= 26，旧版 1.x 主版本号始终为 1。
     */
    private static boolean detectNewWorldStructure() {
        String version = Bukkit.getBukkitVersion();
        int dash = version.indexOf('-');
        String nums = dash > 0 ? version.substring(0, dash) : version;
        String[] parts = nums.split("\\.");
        try {
            return Integer.parseInt(parts[0]) >= 26;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 检查同 IP 注册限制：已注册账号数 + 在线未注册玩家数。
     * 防止多个未注册玩家同时进服后注册导致超限（无状态方案：
     * 玩家退出后自动不再计入，注册成功后 hasAccount 返回 true 也不再计入）。
     * @param uuid 待检查玩家（必须无账号）
     * @param ip 玩家 IP
     * @return true 允许进入，false 已超限
     */
    public boolean checkIpRegisterLimit(UUID uuid, String ip) {
        int max = configManager.maxAccountsPerIp();
        if (max <= 0) return true;
        if (ip == null) return true;
        int registered = dataManager.findByIp(ip).size();
        int onlinePending = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(uuid)) continue; // 排除自己
            if (hasAccount(online.getUniqueId())) continue; // 已注册不计入
            var addr = online.getAddress();
            if (addr != null && ip.equals(addr.getAddress().getHostAddress())) {
                onlinePending++;
            }
        }
        return registered + onlinePending < max;
    }

    // Registration
    public boolean register(Player player, String password) {
        UUID uuid = player.getUniqueId();
        if (dataManager.hasAccount(uuid)) {
            return false;
        }
        String hash = PasswordHash.hashPassword(password, configManager.passwordHashAlgorithm());
        dataManager.createPlayer(uuid, hash, player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown");
        loggedIn.add(uuid);
        pendingLogin.remove(uuid);
        onLoginSuccess(player);
        return true;
    }

    /**
     * 强制注册：管理员绕过 IP 限制强制为玩家创建账号。
     * 已有账号时返回 false。玩家在线时记录其当前 IP，离线时记为 "unknown"（下次登录时更新）。
     * 不会自动登录，玩家需自行 /login。
     */
    public boolean forceRegister(UUID uuid, String password) {
        if (dataManager.hasAccount(uuid)) return false;
        String hash = PasswordHash.hashPassword(password, configManager.passwordHashAlgorithm());
        Player online = Bukkit.getPlayer(uuid);
        String ip = "unknown";
        if (online != null && online.getAddress() != null) {
            ip = online.getAddress().getAddress().getHostAddress();
        }
        dataManager.createPlayer(uuid, hash, ip);
        return true;
    }

    // Login
    public boolean login(Player player, String password) {
        UUID uuid = player.getUniqueId();
        // 踢出期内拒绝登录
        if (isKicked(player)) return false;

        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;

        if (PasswordHash.checkPassword(password, data.passwordHash())) {
            // 自动对齐：配置算法与存储算法不一致时，登录成功后用配置算法重新哈希
            String configured = configManager.passwordHashAlgorithm();
            boolean storedIsBcrypt = PasswordHash.isBcrypt(data.passwordHash());
            boolean configIsBcrypt = "bcrypt".equalsIgnoreCase(configured);
            if (configIsBcrypt != storedIsBcrypt) {
                String newHash = PasswordHash.hashPassword(password, configured);
                data.passwordHash(newHash);
                dataManager.updatePassword(uuid, newHash);
            }
            data.lastLogin(System.currentTimeMillis() / 1000);
            if (player.getAddress() != null) {
                data.ip(player.getAddress().getAddress().getHostAddress());
            }
            dataManager.save(uuid);

            loggedIn.add(uuid);
            pendingLogin.remove(uuid);
            // 登录成功，清零失败计数
            failedAttempts.remove(uuid);
            kickUntil.remove(uuid);
            // 正版回退玩家密码登录成功，清除回退标记（下次正版验证成功即自动免密）
            clearPremiumFallback(uuid);
            onLoginSuccess(player);
            return true;
        }

        // 登录失败，增加计数（仅在启用失败保护时）
        if (configManager.failProtectionEnabled()) {
            int attempts = failedAttempts.merge(uuid, 1, Integer::sum);
            if (attempts >= configManager.failMaxAttempts()) {
                // 达到阈值，设置踢出期
                kickUntil.put(uuid, System.currentTimeMillis() + configManager.failKickDuration() * 1000L);
                failedAttempts.remove(uuid);
            }
        }
        return false;
    }

    // ===== 管理员强制操作 =====

    /** 强制登出玩家（无需玩家在线，清除登录状态，并使 IP 自动登录失效） */
    public boolean forceLogout(UUID uuid) {
        if (!loggedIn.remove(uuid)) return false;
        pendingLogin.add(uuid);
        // 清除 lastLogin 使 IP 自动登录立即失效，下次必须用密码登录
        PlayerData data = dataManager.getPlayer(uuid);
        if (data != null) {
            data.lastLogin(0);
            dataManager.save(uuid);
        }
        return true;
    }

    /** 强制修改玩家密码（无需验证旧密码，玩家无需在线） */
    public boolean forceChangePassword(UUID uuid, String newPassword) {
        if (!dataManager.hasAccount(uuid)) return false;
        String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm());
        dataManager.updatePassword(uuid, newHash);
        // 清除 lastLogin 使 IP 自动登录立即失效，强制下次必须用密码登录
        PlayerData data = dataManager.getPlayer(uuid);
        if (data != null) {
            data.lastLogin(0);
            dataManager.save(uuid);
        }
        return true;
    }

    /** 强制登录玩家（不管有没有账号，仅对在线玩家生效） */
    public void forceLogin(Player player) {
        UUID uuid = player.getUniqueId();
        loggedIn.add(uuid);
        pendingLogin.remove(uuid);
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
        onLoginSuccess(player);
    }

    // IP 免密登录：检查上次登录 IP 与当前 IP 是否一致，且未超过失效时间
    public boolean checkIpAutoLogin(Player player) {
        if (player.getAddress() == null) return false;
        return checkIpAutoLogin(player.getUniqueId(), player.getAddress().getAddress().getHostAddress());
    }

    /** IP 免密登录检查（无需 Player 对象，用于 AsyncPlayerSpawnLocationEvent） */
    public boolean checkIpAutoLogin(UUID uuid, String ip) {
        if (!configManager.ipAutoLoginEnabled()) return false;
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        if (ip == null) return false;
        String storedIp = data.ip();
        if (storedIp == null || storedIp.isEmpty()) return false;
        if (!ip.equals(storedIp)) return false;
        // 检查失效时间：0 表示永不失效
        int expireMinutes = configManager.ipAutoLoginExpireMinutes();
        if (expireMinutes <= 0) return true;
        long lastLogin = data.lastLogin();
        if (lastLogin <= 0) return false;
        long expireMillis = expireMinutes * 60L * 1000L;
        return System.currentTimeMillis() - lastLogin * 1000L < expireMillis;
    }

    // 通过 IP 免密登录：跳过密码验证，直接标记为已登录
    public void loginByIp(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return;
        data.lastLogin(System.currentTimeMillis() / 1000);
        dataManager.save(uuid);
        loggedIn.add(uuid);
        pendingLogin.remove(uuid);
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
        onLoginSuccess(player);
    }

    // Logout
    public void logout(Player player) {
        UUID uuid = player.getUniqueId();
        loggedIn.remove(uuid);
        pendingLogin.add(uuid);
        // 清除 lastLogin 使 IP 自动登录立即失效，下次必须用密码登录
        PlayerData data = dataManager.getPlayer(uuid);
        if (data != null) {
            data.lastLogin(0);
            dataManager.save(uuid);
        }
    }

    // Change password
    public boolean changePassword(Player player, String oldPassword, String newPassword) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;

        // 正版账号免密登录，密码哈希为随机占位、玩家未知，跳过旧密码校验，直接设置新密码
        if (!data.premium() && !PasswordHash.checkPassword(oldPassword, data.passwordHash())) {
            return false;
        }
        String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm());
        dataManager.updatePassword(uuid, newHash);
        return true;
    }

    // Unregister
    public boolean unregister(UUID uuid) {
        if (!dataManager.hasAccount(uuid)) return false;
        dataManager.removePlayer(uuid);
        loggedIn.remove(uuid);
        pendingLogin.remove(uuid);
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
        invulnerablePending.remove(uuid);
        // 根据配置决定是否删除 Minecraft 原版玩家数据（player.dat）
        if (configManager.realUnreg()) {
            // 记录注销时间，5 秒内拒绝重连，确保 .dat 删除完成
            recentUnregister.put(uuid, System.currentTimeMillis());
            if (Bukkit.getPlayer(uuid) != null) {
                // 玩家在线：标记后由 PlayerQuitEvent 删除（避免文件锁冲突）
                pendingDatDelete.add(uuid);
            } else {
                // 玩家离线：无文件锁，直接删除
                deletePlayerDataWithRetry(uuid);
            }
            // 顺手清理已过期的踢出记录、失败计数和注销拒绝重连记录，防止批量注销时累积
            cleanupExpiredStates();
        }
        return true;
    }

    /** 检查玩家是否在注销后的拒绝重连期内（5 秒） */
    public boolean isRecentlyUnregistered(UUID uuid) {
        Long time = recentUnregister.get(uuid);
        if (time == null) return false;
        if (System.currentTimeMillis() - time >= UNREGISTER_RECONNECT_DELAY) {
            recentUnregister.remove(uuid);
            return false;
        }
        return true;
    }

    /** 获取拒绝重连剩余秒数 */
    public long getRecentUnregisterRemaining(UUID uuid) {
        Long time = recentUnregister.get(uuid);
        if (time == null) return 0;
        long remaining = UNREGISTER_RECONNECT_DELAY - (System.currentTimeMillis() - time);
        return remaining > 0 ? (remaining + 999) / 1000 : 0;
    }

    /**
     * 在 PlayerQuitEvent 中调用：异步重试删除玩家 .dat 文件。
     * PlayerQuitEvent 触发时服务器尚未保存 .dat，直接删除会被后续保存覆盖。
     * 采用重试机制：初始延迟 500ms 后尝试删除，若文件仍存在则每 300ms 重试一次，
     * 5 秒内持续尝试（约 15 次），确保服务器完成保存后能可靠删除。
     */
    public void tryDeletePlayerDataOnQuit(UUID uuid) {
        if (!pendingDatDelete.remove(uuid)) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> deletePlayerDataWithRetry(uuid));
    }

    /** 重试删除玩家数据，5 秒内持续尝试（首次 500ms，后续每 300ms） */
    @SuppressWarnings("BusyWait")
    private void deletePlayerDataWithRetry(UUID uuid) {
        long elapsed = 0;
        while (true) {
            try {
                Thread.sleep(elapsed == 0 ? 500 : 300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            elapsed += elapsed == 0 ? 500 : 300;
            if (deletePlayerData(uuid)) return;
            if (elapsed >= 5000) {
                plugin.getLogger().warning(I18n.get("log.delete_player_data_failed", uuid));
                return;
            }
        }
    }

    /**
     * 删除 Minecraft 原版玩家数据（player.dat、advancements、stats）。
     * 目录结构兼容（通过服务端版本判断，构造时缓存）：
     *   - 旧版（< 26.1）：world/playerdata、world/advancements、world/stats
     *   - 26.1+：world/players/data、world/players/advancements、world/players/stats
     *     （worldDir 是维度目录 world/dimensions/minecraft/overworld，玩家数据在其上级 3 层的世界根目录下）
     * 由 tryDeletePlayerDataOnQuit 异步重试调用（服务器保存 .dat 后再删除）。
     * @return true 表示文件已删除或不存在（成功）；false 表示文件仍存在（需重试）
     */
    private boolean deletePlayerData(UUID uuid) {
        World world = Bukkit.getWorlds().getFirst();
        File worldRoot = worldRoot(world);
        if (worldRoot == null) {
            plugin.getLogger().warning(I18n.get("log.player_data_dir_not_found", world.getWorldFolder().getAbsolutePath()));
            return true; // 目录不存在视为无需删除，停止重试
        }

        String[] dirs = playerDataDirs();
        // 删除 .dat_old（备份文件，失败仅告警，不影响重试）
        File datOldFile = new File(worldRoot, dirs[0] + "/" + uuid + ".dat_old");
        if (datOldFile.exists() && !datOldFile.delete()) {
            plugin.getLogger().warning(I18n.get("log.delete_player_data_backup_failed", datOldFile.getAbsolutePath()));
        }

        // 删除 .dat、advancements/.json、stats/.json，任一失败则重试
        return deletePlayerFile(new File(worldRoot, dirs[0]), uuid, ".dat")
                && deletePlayerFile(new File(worldRoot, dirs[1]), uuid, ".json")
                && deletePlayerFile(new File(worldRoot, dirs[2]), uuid, ".json");
    }

    /**
     * 将离线账号的原版玩家数据（player.dat、advancements、stats）迁移到正版 UUID。
     * 升级后 UUID 变化，若不迁移这些文件，玩家的背包/成就/统计会丢失。
     * 异步执行文件重命名（阻塞文件 IO，调用方无需关心线程）。
     */
    public void migratePlayerData(UUID fromUuid, UUID toUuid) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            World world = Bukkit.getWorlds().getFirst();
            File worldRoot = worldRoot(world);
            if (worldRoot == null) {
                plugin.getLogger().warning(I18n.get("log.player_data_dir_not_found", world.getWorldFolder().getAbsolutePath()));
                return;
            }
            String[] dirs = playerDataDirs();
            renamePlayerFile(new File(worldRoot, dirs[0]), fromUuid, toUuid, ".dat");
            renamePlayerFile(new File(worldRoot, dirs[1]), fromUuid, toUuid, ".json");
            renamePlayerFile(new File(worldRoot, dirs[2]), fromUuid, toUuid, ".json");
        });
    }

    /** 将玩家文件 &lt;from&gt;.&lt;ext&gt; 重命名为 &lt;to&gt;.&lt;ext&gt;（目标已存在则先删除旧目标） */
    private void renamePlayerFile(File dir, UUID from, UUID to, String ext) {
        if (!dir.isDirectory()) return;
        File src = new File(dir, from + ext);
        if (!src.exists()) return;
        File dst = new File(dir, to + ext);
        if (dst.exists() && !dst.delete()) {
            plugin.getLogger().warning(I18n.get("log.migrate_failed", src.getAbsolutePath()));
            return;
        }
        if (!src.renameTo(dst)) {
            plugin.getLogger().warning(I18n.get("log.migrate_failed", src.getAbsolutePath()));
        }
    }

    /**
     * 计算世界根目录（玩家数据所在目录）。
     * 26.1+：世界文件夹是维度目录 world/dimensions/minecraft/overworld，
     *   玩家数据在其上级 3 层的世界根目录下；旧版：世界文件夹即根目录。
     */
    private File worldRoot(World world) {
        // Paper 的 getWorldFolder() @NotNull，无需判空；26.1+ 向上 3 层到世界根目录
        File worldDir = world.getWorldFolder();
        if (!newWorldStructure) return worldDir;
        // 26.1+：向上 3 层到世界根目录
        File root = worldDir.getParentFile(); // minecraft
        if (root != null) root = root.getParentFile(); // dimensions
        if (root != null) root = root.getParentFile(); // world 根
        return root;
    }

    /**
     * 玩家数据三个子目录（data/advancements/stats），兼容新旧世界结构。
     * 26.1+ 在 players/ 下，旧版在根目录下（playerdata 名称也不同）。
     */
    private String[] playerDataDirs() {
        if (newWorldStructure) {
            return new String[]{"players/data", "players/advancements", "players/stats"};
        }
        return new String[]{"playerdata", "advancements", "stats"};
    }

    /**
     * 删除指定目录下的玩家文件（<uuid>.<ext>）。
     * @return true 表示文件已删除或目录/文件不存在；false 表示文件仍存在（需重试）
     */
    private boolean deletePlayerFile(File dir, UUID uuid, String ext) {
        if (!dir.isDirectory()) return true;
        File file = new File(dir, uuid + ext);
        if (!file.exists()) return true;
        return file.delete();
    }

    // 玩家退出时调用 — 清理会话状态
    public void clearSession(Player player) {
        UUID uuid = player.getUniqueId();
        loggedIn.remove(uuid);
        pendingLogin.remove(uuid);
        // 清除传送过渡期标记，防止下次登录时错误无敌
        invulnerablePending.remove(uuid);
        // 清除超时任务标记（玩家已下线，旧任务无意义）
        loginTimeoutStartedAt.remove(uuid);
        // 清理失败计数和踢出记录（玩家已离线，保留无意义）
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
    }

    // Status checks
    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedIn.contains(uuid);
    }

    public boolean hasAccount(Player player) {
        return hasAccount(player.getUniqueId());
    }

    public boolean hasAccount(UUID uuid) {
        return dataManager.hasAccount(uuid);
    }

    public void addPendingLogin(Player player) {
        pendingLogin.add(player.getUniqueId());
    }

    /** 记录登录超时任务启动时间，返回当前时间戳（用于触发时判断是否为最新任务） */
    public long markLoginTimeoutStart(UUID uuid) {
        long now = System.currentTimeMillis();
        loginTimeoutStartedAt.put(uuid, now);
        return now;
    }

    /** 判断指定时间戳是否为最新的超时任务启动时间（旧任务自动失效） */
    public boolean isLatestLoginTimeout(UUID uuid, long startedAt) {
        Long latest = loginTimeoutStartedAt.get(uuid);
        return latest != null && latest == startedAt;
    }

    // 暴力破解防护：检查是否处于踢出期
    public boolean isKicked(Player player) {
        return isKicked(player.getUniqueId());
    }

    public boolean isKicked(UUID uuid) {
        if (!configManager.failProtectionEnabled()) return false;
        Long until = kickUntil.get(uuid);
        return until != null && until > System.currentTimeMillis();
    }

    // 获取剩余踢出时间（秒）
    public long getKickRemaining(Player player) {
        return getKickRemaining(player.getUniqueId());
    }

    public long getKickRemaining(UUID uuid) {
        Long until = kickUntil.get(uuid);
        if (until == null) return 0;
        long remaining = until - System.currentTimeMillis();
        return remaining > 0 ? remaining / 1000 : 0;
    }

    /** 清理已过期的踢出记录、失败计数和注销拒绝重连记录（由周期任务每分钟调用，reload 时也会调用） */
    public void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        kickUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        // 失败计数未达阈值的条目也应清理（玩家可能已离线）
        failedAttempts.entrySet().removeIf(entry -> entry.getValue() < configManager.failMaxAttempts());
        // 清理已过期的注销拒绝重连记录
        recentUnregister.entrySet().removeIf(entry -> now - entry.getValue() >= UNREGISTER_RECONNECT_DELAY);
    }

    // ===== 坐标保护相关 =====

    /**
     * 判断位置是否悬空（下方无固体方块支撑）。
     * 此方法会阻塞等待区块加载，应在异步线程中调用（如 AsyncPlayerSpawnLocationEvent）。
     */
    public boolean isLocationFloating(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = loc.getBlockY();
        if (y - 1 < world.getMinHeight()) return true;
        int blockX = x & 15;
        int blockZ = z & 15;
        org.bukkit.Chunk chunk = world.getChunkAtAsyncUrgently(x >> 4, z >> 4).join();
        org.bukkit.ChunkSnapshot snapshot = chunk.getChunkSnapshot();
        return !snapshot.getBlockData(blockX, y - 1, blockZ).getMaterial().isSolid();
    }

    /**
     * 保存玩家当前退出位置（仅已登录玩家退出时调用）。
     * 未登录玩家退出不会更新位置，保持上次保存的位置不变。
     */
    public void saveLogoutLocation(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data != null) {
            data.logoutLocation(PlayerDataManager.serializeLocation(player.getLocation()));
            dataManager.save(player.getUniqueId());
        }
    }

    /**
     * 仅更新内存缓存中的退出位置，不落库。
     * 用于 onDisable：插件禁用后无法注册异步任务，改为更新缓存后由 saveSync 统一落库。
     */
    public void updateLogoutLocationCache(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data != null) {
            data.logoutLocation(PlayerDataManager.serializeLocation(player.getLocation()));
        }
    }

    /**
     * 登录/注册成功后，传送回上次退出位置。
     * 如果没有保存的位置（新玩家），不传送（留在世界出生点）。
     * 使用 teleportAsync 以兼容 Folia（Folia 禁止同步 teleport）。
     * 调用时机：玩家已在世界中（密码登录/注册/forcelogin），非 PlayerJoinEvent 期间。
     */
    public void returnToLogoutLocation(Player player) {
        Location loc = getLogoutLocation(player);
        if (loc == null) {
            // 新玩家没有保存的位置，留在世界出生点
            return;
        }
        // 标记传送过渡期，保持无敌
        invulnerablePending.add(player.getUniqueId());
        // 直接异步传送，传送完成后移除无敌状态
        player.teleportAsync(loc).thenAccept(success ->
                invulnerablePending.remove(player.getUniqueId()));
    }

    /** 玩家是否处于传送过渡期（已登录但还在传送，应保持无敌） */
    public boolean isInvulnerablePending(Player player) {
        return invulnerablePending.contains(player.getUniqueId());
    }

    /** 是否为正版账号（premium=1），用于免密登录判断 */
    public boolean isPremium(Player player) {
        return isPremium(player.getUniqueId());
    }

    public boolean isPremium(UUID uuid) {
        return dataManager.isPremium(uuid);
    }

    // ===== 离线账号升级为正版 =====

    /** 标记离线账号待升级为正版：下次登录时尝试正版验证，成功则迁移，失败则回退 */
    public boolean markUpgradePending(UUID offlineUuid) {
        return pendingUpgrade.add(offlineUuid);
    }

    /** 检查离线账号是否有升级标记 */
    public boolean hasPendingUpgrade(UUID offlineUuid) {
        return pendingUpgrade.contains(offlineUuid);
    }

    /** 清除升级标记（验证成功或失败回退时调用） */
    public void clearUpgradePending(UUID offlineUuid) {
        pendingUpgrade.remove(offlineUuid);
    }

    /** 暂存首次注册/升级正版账号的明文密码，供玩家 join 时提示（正版验证在握手阶段完成，尚无 Player 对象） */
    public void stagePremiumPassword(UUID premiumUuid, String plainPassword) {
        if (plainPassword != null) {
            pendingPremiumPassword.put(premiumUuid, plainPassword);
        }
    }

    /** 获取并移除待提示的明文密码（玩家 join 时调用），无则返回 null */
    public String pollPremiumPassword(UUID premiumUuid) {
        return pendingPremiumPassword.remove(premiumUuid);
    }

    /** 标记正版玩家本次为正版验证失败回退进入（需密码登录） */
    public void markPremiumFallback(UUID premiumUuid) {
        premiumFallback.add(premiumUuid);
    }

    /** 清除正版回退标记（密码登录成功或下次正版验证成功时调用） */
    public void clearPremiumFallback(UUID premiumUuid) {
        premiumFallback.remove(premiumUuid);
    }

    /** 正版玩家是否为验证失败回退进入（本次需密码登录） */
    public boolean isPremiumFallback(UUID premiumUuid) {
        return premiumFallback.contains(premiumUuid);
    }

    /**
     * 正版玩家免密登录：更新登录时间和 IP，标记为已登录。
     * 与 loginByIp 类似但不检查 IP 一致性（正版玩家始终免密）。
     */
    public void loginByPremium(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data != null) {
            data.lastLogin(System.currentTimeMillis() / 1000);
            if (player.getAddress() != null) {
                data.ip(player.getAddress().getAddress().getHostAddress());
            }
            dataManager.save(uuid);
        }
        loggedIn.add(uuid);
        pendingLogin.remove(uuid);
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
        onLoginSuccess(player);
    }

    /**
     * 在主世界出生点周围寻找能立足的随机位置（老玩家专用）。
     * 安全标准放宽：只需"下方固体方块"（能站立）。
     * 因为未登录期间 onDamage 取消伤害，玩家不会因悬空/水中/岩浆受伤；
     * 登录后立即传送到上次退出位置，离开临时位置。
     * 默认尝试 10 次，全部失败则回退到世界出生点（玩家无敌，出生点不安全也不会死）。
     * 此方法会阻塞等待区块加载，应在异步线程中调用。
     * 若配置为固定坐标模式，直接返回配置的固定位置。
     */
    public Location findSafeAuthSpawn(World world) {
        // 固定坐标模式：直接使用配置的坐标
        if ("fixed".equals(configManager.protectionPosMode())) {
            return new Location(world,
                    configManager.protectionPosFixedX(),
                    configManager.protectionPosFixedY(),
                    configManager.protectionPosFixedZ(),
                    configManager.protectionPosFixedYaw(),
                    configManager.protectionPosFixedPitch());
        }

        Location spawn = world.getSpawnLocation();
        int radius = configManager.protectionPosSpawnRadius();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 多次重试，模仿原版 MC 寻找安全出生点的机制
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = (int) (spawn.getX() + (random.nextDouble() * 2 - 1) * radius);
            int z = (int) (spawn.getZ() + (random.nextDouble() * 2 - 1) * radius);
            // 阻塞等待区块加载（调用方应在异步线程）
            org.bukkit.Chunk chunk = world.getChunkAtAsyncUrgently(x >> 4, z >> 4).join();
            int y = findSafeSpawnY(chunk.getChunkSnapshot(), x & 15, z & 15, world);
            if (y != Integer.MIN_VALUE) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        // 全部失败：回退到世界出生点（玩家无敌期间不会受伤）
        return spawn;
    }

    /**
     * 从最高方块上方开始向下找能立足的 y 坐标。
     * 安全标准：下方是固体方块（能站立）。
     * 找不到时返回 Integer.MIN_VALUE，由调用方重试或回退。
     */
    private static int findSafeSpawnY(org.bukkit.ChunkSnapshot snapshot, int x, int z, World world) {
        int highestY = snapshot.getHighestBlockYAt(x, z);
        // 最高方块上方即视为可立足（下方=最高方块，只需检查它是否固体）
        if (highestY > world.getMinHeight()) {
            BlockData below = snapshot.getBlockData(x, highestY, z);
            if (below.getMaterial().isSolid()) {
                return highestY + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 获取玩家上次退出位置（无保存位置返回 null） */
    public Location getLogoutLocation(Player player) {
        return getLogoutLocation(player.getUniqueId());
    }

    /** 获取玩家上次退出位置（无保存位置返回 null） */
    public Location getLogoutLocation(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return null;
        return PlayerDataManager.deserializeLocation(data.logoutLocation());
    }

    /**
     * 登录/注册成功后的物品状态恢复：
     * 未登录期间数据包监听器清空了该玩家的背包和装备（仅本人视角，他人不受影响）。
     * 登录后调用 updateInventory 让服务器重发真实背包内容（含装备槽）。
     * <p>
     * 使用玩家调度器执行，保证 Folia 下在玩家区域线程调用（updateInventory 非线程安全）。
     */
    public void onLoginSuccess(Player player) {
        player.getScheduler().run(plugin, task -> player.updateInventory(), null);
    }
}
