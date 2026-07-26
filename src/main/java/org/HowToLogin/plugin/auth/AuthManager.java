package org.HowToLogin.plugin.auth;

import org.HowToLogin.plugin.config.ConfigManager;
import org.HowToLogin.plugin.data.PlayerDataManager;
import org.HowToLogin.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    // 线程安全集合，用于 Folia 多线程区域化调度
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingLogin = ConcurrentHashMap.newKeySet();
    // 暴力破解防护：记录失败次数和锁定到期时间
    private final Map<UUID, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lockUntil = new ConcurrentHashMap<>();

    public AuthManager(PlayerDataManager dataManager, ConfigManager configManager) {
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    // Registration
    public boolean register(Player player, String password) {
        UUID uuid = player.getUniqueId();
        if (dataManager.hasAccount(uuid)) {
            return false;
        }
        String hash = PasswordHash.hashPassword(password);
        dataManager.createPlayer(uuid, hash, player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown");
        loggedIn.add(uuid);
        pendingLogin.remove(uuid);
        return true;
    }

    // Login
    public boolean login(Player player, String password) {
        UUID uuid = player.getUniqueId();
        // 锁定期间拒绝登录
        if (isLocked(player)) return false;

        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;

        if (PasswordHash.checkPassword(password, data.passwordHash())) {
            data.lastLogin(System.currentTimeMillis() / 1000);
            if (player.getAddress() != null) {
                data.ip(player.getAddress().getAddress().getHostAddress());
            }
            dataManager.save();

            loggedIn.add(uuid);
            pendingLogin.remove(uuid);
            // 登录成功，清零失败计数
            failedAttempts.remove(uuid);
            lockUntil.remove(uuid);
            return true;
        }

        // 登录失败，增加计数
        int attempts = failedAttempts.merge(uuid, 1, Integer::sum);
        if (attempts >= configManager.maxLoginAttempts()) {
            // 达到阈值，设置锁定
            lockUntil.put(uuid, System.currentTimeMillis() + configManager.lockDuration() * 1000L);
            failedAttempts.remove(uuid);
        }
        return false;
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
            String newHash = PasswordHash.hashPassword(newPassword);
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
        lockUntil.remove(uuid);
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

    // 暴力破解防护：检查是否被锁定
    public boolean isLocked(Player player) {
        Long until = lockUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    // 获取剩余锁定时间（秒）
    public long getLockRemaining(Player player) {
        Long until = lockUntil.get(player.getUniqueId());
        if (until == null) return 0;
        long remaining = until - System.currentTimeMillis();
        return remaining > 0 ? remaining / 1000 : 0;
    }
}
