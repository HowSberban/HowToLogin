package org.howtologin.plugin.auth;

import org.howtologin.plugin.HTLogin;
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
import java.util.concurrent.TimeUnit;

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
    // 缓存世界结构类型：26.1+ 采用新结构（players/data + dimensions/minecraft/overworld）
    private final boolean newWorldStructure;

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

        if (PasswordHash.checkPassword(oldPassword, data.passwordHash())) {
            String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm());
            dataManager.updatePassword(uuid, newHash);
            return true;
        }
        return false;
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
            if (Bukkit.getPlayer(uuid) != null) {
                // 玩家在线：标记后由 PlayerQuitEvent 删除（避免文件锁冲突）
                pendingDatDelete.add(uuid);
            } else {
                // 玩家离线：无文件锁，直接删除
                deletePlayerData(uuid);
            }
        }
        return true;
    }

    /**
     * 在 PlayerQuitEvent 中调用：延迟异步删除玩家 .dat 文件。
     * PlayerQuitEvent 触发时服务器尚未保存 .dat，直接删除会被后续保存覆盖，
     * 因此延迟 1 秒，等服务器完成保存后再删除。
     */
    public void tryDeletePlayerDataOnQuit(UUID uuid) {
        if (!pendingDatDelete.remove(uuid)) return;
        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> deletePlayerData(uuid), 1, TimeUnit.SECONDS);
    }

    /**
     * 删除 Minecraft 原版玩家数据文件（player.dat 及其备份）。
     * 目录结构兼容（通过服务端版本判断，构造时缓存）：
     *   - 旧版（< 26.1）：world/playerdata（worldDir 即世界根目录）
     *   - 26.1+：world/players/data（worldDir 是维度目录 world/dimensions/minecraft/overworld，
     *     玩家数据在其上级 3 层的世界根目录下）
     * 由 tryDeletePlayerDataOnQuit 延迟调用（服务器保存 .dat 后再删除）。
     */
    private void deletePlayerData(UUID uuid) {
        World world = Bukkit.getWorlds().getFirst();
        File worldDir = world.getWorldFolder();

        File playerDataDir;
        if (newWorldStructure) {
            // 26.1+：worldDir 是维度目录，向上 3 层到世界根目录
            File worldRoot = worldDir.getParentFile(); // minecraft
            if (worldRoot != null) worldRoot = worldRoot.getParentFile(); // dimensions
            if (worldRoot != null) worldRoot = worldRoot.getParentFile(); // world 根
            playerDataDir = worldRoot != null ? resolvePlayerDataDir(worldRoot) : null;
        } else {
            // 旧版：worldDir 即世界根目录
            playerDataDir = resolvePlayerDataDir(worldDir);
        }

        if (playerDataDir == null) {
            plugin.getLogger().warning("无法找到玩家数据目录，起始查找路径: " + worldDir.getAbsolutePath());
            return;
        }
        File datFile = new File(playerDataDir, uuid + ".dat");
        File datOldFile = new File(playerDataDir, uuid + ".dat_old");
        if (datFile.exists() && !datFile.delete()) {
            plugin.getLogger().warning("无法删除玩家数据文件: " + datFile.getAbsolutePath());
        }
        if (datOldFile.exists() && !datOldFile.delete()) {
            plugin.getLogger().warning("无法删除玩家数据备份文件: " + datOldFile.getAbsolutePath());
        }
    }

    /**
     * 检查目录下是否存在玩家数据目录（优先 26.1+ 的 players/data，其次旧版 playerdata）。
     * 不存在返回 null。
     */
    private File resolvePlayerDataDir(File dir) {
        File newDir = new File(dir, "players/data");
        if (newDir.isDirectory()) return newDir;
        File oldDir = new File(dir, "playerdata");
        return oldDir.isDirectory() ? oldDir : null;
    }

    // 玩家退出时调用 — 清理会话状态
    public void clearSession(Player player) {
        UUID uuid = player.getUniqueId();
        loggedIn.remove(uuid);
        pendingLogin.remove(uuid);
        // 清除传送过渡期标记，防止下次登录时错误无敌
        invulnerablePending.remove(uuid);
    }

    // Status checks
    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
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

    /** 清理已过期的踢出记录和失败计数（reload 时调用，防止内存泄漏） */
    public void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        kickUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
        // 失败计数未达阈值的条目也应清理（玩家可能已离线）
        failedAttempts.entrySet().removeIf(entry -> entry.getValue() < configManager.failMaxAttempts());
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

    /**
     * 在主世界出生点周围寻找能立足的随机位置（老玩家专用）。
     * 安全标准放宽：只需"下方固体方块"（能站立）。
     * 因为未登录期间 onDamage 取消伤害，玩家不会因悬空/水中/岩浆受伤；
     * 登录后立即传送到上次退出位置，离开临时位置。
     * 默认尝试 10 次，全部失败则回退到世界出生点（玩家无敌，出生点不安全也不会死）。
     * 此方法会阻塞等待区块加载，应在异步线程中调用。
     */
    public Location findSafeAuthSpawn(World world) {
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
}
