package org.HowToLogin.plugin.auth;

import org.HowToLogin.plugin.data.PlayerDataManager;
import org.HowToLogin.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private final PlayerDataManager dataManager;
    // 线程安全集合，用于 Folia 多线程区域化调度
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingLogin = ConcurrentHashMap.newKeySet();

    public AuthManager(PlayerDataManager dataManager) {
        this.dataManager = dataManager;
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
            return true;
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
}