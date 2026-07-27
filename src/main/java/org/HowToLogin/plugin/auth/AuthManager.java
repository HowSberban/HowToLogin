package org.HowToLogin.plugin.auth;

import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.config.ConfigManager;
import org.HowToLogin.plugin.data.PlayerDataManager;
import org.HowToLogin.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

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

    public AuthManager(HTLogin plugin, PlayerDataManager dataManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
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

    /** 强制登出玩家（无需玩家在线，清除登录状态） */
    public boolean forceLogout(UUID uuid) {
        if (!loggedIn.remove(uuid)) return false;
        pendingLogin.add(uuid);
        return true;
    }

    /** 强制修改玩家密码（无需验证旧密码，玩家无需在线） */
    public boolean forceChangePassword(UUID uuid, String newPassword) {
        if (!dataManager.hasAccount(uuid)) return false;
        String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm());
        dataManager.updatePassword(uuid, newHash);
        // 若玩家在线，强制下线让其重新登录
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
        if (!configManager.ipAutoLoginEnabled()) return false;
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        if (player.getAddress() == null) return false;
        String storedIp = data.ip();
        if (storedIp == null || storedIp.isEmpty()) return false;
        if (!player.getAddress().getAddress().getHostAddress().equals(storedIp)) return false;
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
        return true;
    }

    // 玩家退出时调用 — 清理会话状态
    public void clearSession(Player player) {
        UUID uuid = player.getUniqueId();
        loggedIn.remove(uuid);
        pendingLogin.remove(uuid);
    }

    // Status checks
    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    public boolean hasAccount(Player player) {
        return dataManager.hasAccount(player.getUniqueId());
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

    // ===== 坐标保护相关 =====

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
     * 登录/注册成功后，传送回上次退出位置。
     * 如果没有保存的位置（新玩家），不传送（留在世界出生点）。
     * 仅在启用坐标保护时生效。
     * 使用 teleportAsync 以兼容 Folia（Folia 禁止同步 teleport）。
     * 传送后延迟 2 tick 恢复伤害，防止传送前瞬间受伤。
     */
    public void returnToLogoutLocation(Player player) {
        if (configManager.protectionPosEnabled()) {
            Location loc = getLogoutLocation(player);
            if (loc == null) {
                // 新玩家没有保存的位置，留在世界出生点
                return;
            }
            // 标记传送过渡期，保持无敌
            invulnerablePending.add(player.getUniqueId());
            player.teleportAsync(loc).thenAccept(success -> {
                if (success) {
                    // 传送成功后延迟 2 tick 移除无敌（40ms × 2 = 100ms 缓冲）
                    player.getScheduler().runDelayed(plugin, task ->
                            invulnerablePending.remove(player.getUniqueId()), null, 2L);
                } else {
                    invulnerablePending.remove(player.getUniqueId());
                }
            });
        }
    }

    /** 玩家是否处于传送过渡期（已登录但还在传送，应保持无敌） */
    public boolean isInvulnerablePending(Player player) {
        return invulnerablePending.contains(player.getUniqueId());
    }

    /**
     * 传送到主世界出生点周围随机安全位置（登出时调用）。
     * 仅在启用坐标保护时生效。
     * 强制使用主世界，防止玩家当前所在维度信息泄露。
     * 在异步线程寻找安全位置（阻塞式），完成后用 teleportAsync 传送（兼容 Folia）。
     */
    public void teleportToAuthLocation(Player player) {
        if (configManager.protectionPosEnabled()) {
            World world = Bukkit.getWorlds().getFirst();
            // 切到异步线程寻找安全位置，避免阻塞命令线程
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                Location loc = findSafeAuthSpawn(world);
                player.teleportAsync(loc);
            });
        }
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

    private Location getLogoutLocation(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return null;
        return PlayerDataManager.deserializeLocation(data.logoutLocation());
    }
}
