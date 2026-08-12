package org.howtologin.plugin.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.auth.PasswordHash;
import org.howtologin.plugin.data.PlayerDataManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * HTLogin 插件 API 入口。
 * <p>
 * 使用 {@link #getInstance()} 获取单例，插件未加载时返回 null。
 * <p>
 * 示例：
 * <pre>{@code
 * HTLoginApi api = HTLoginApi.getInstance();
 * if (api != null && api.isAuthenticated(player)) {
 *     // 玩家已登录
 * }
 * }</pre>
 */
@SuppressWarnings("unused")
public final class HTLoginApi {

    private static volatile HTLoginApi instance;

    private final HTLogin plugin;
    private final AuthManager authManager;
    private final PlayerDataManager dataManager;

    private HTLoginApi(HTLogin plugin, AuthManager authManager, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.dataManager = dataManager;
    }

    /** 插件启用时注册 API 单例（内部调用，不要直接使用） */
    public static void initialize(HTLogin plugin, AuthManager authManager, PlayerDataManager dataManager) {
        instance = new HTLoginApi(plugin, authManager, dataManager);
    }

    /** 插件禁用时清理 API 单例（内部调用，不要直接使用） */
    public static void shutdown() {
        instance = null;
    }

    /**
     * 获取 API 实例。
     * @return API 实例，插件未加载时返回 null
     */
    public static HTLoginApi getInstance() {
        return instance;
    }

    // ===== 插件信息 =====

    /** 获取插件版本号 */
    @NotNull
    public String getPluginVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    // ===== 状态查询 =====

    /** 玩家是否已登录 */
    public boolean isAuthenticated(@NotNull Player player) {
        return authManager.isLoggedIn(player);
    }

    /** 玩家是否已登录（按 UUID 查询，玩家离线时返回 false） */
    public boolean isAuthenticated(@NotNull UUID uuid) {
        return authManager.isLoggedIn(uuid);
    }

    /** 玩家是否已注册 */
    public boolean isRegistered(@NotNull UUID uuid) {
        return dataManager.hasAccount(uuid);
    }

    /** 玩家是否已注册（便捷重载） */
    public boolean isRegistered(@NotNull Player player) {
        return isRegistered(player.getUniqueId());
    }

    /** 玩家是否为正版账号 */
    public boolean isPremium(@NotNull UUID uuid) {
        return dataManager.isPremium(uuid);
    }

    // ===== 强制操作 =====

    /**
     * 强制登录在线玩家（无需密码）。
     * @return 玩家不在线或已登录时返回 false
     */
    public boolean forceLogin(@NotNull Player player) {
        if (authManager.isLoggedIn(player)) return false;
        authManager.forceLogin(player);
        return true;
    }

    /**
     * 强制登出玩家。
     * 玩家在线时会被标记为待登录状态，IP 免密登录失效。
     * @return 玩家未登录时返回 false
     */
    public boolean forceLogout(@NotNull UUID uuid) {
        return authManager.forceLogout(uuid);
    }

    /**
     * 强制注册在线玩家并自动登录。
     * @return 玩家已有账号时返回 false
     */
    public boolean forceRegister(@NotNull Player player, @NotNull String password) {
        if (!authManager.forceRegister(player.getUniqueId(), password)) return false;
        authManager.forceLogin(player);
        return true;
    }

    /**
     * 强制注册玩家（不自动登录，适用于离线玩家）。
     * @return 玩家已有账号时返回 false
     */
    public boolean forceRegister(@NotNull UUID uuid, @NotNull String password) {
        return authManager.forceRegister(uuid, password);
    }

    /**
     * 注销玩家账号（管理员操作，无需密码）。
     * 会删除数据库记录，根据配置可能同时删除原版玩家数据（player.dat）。
     * @return 玩家无账号时返回 false
     */
    public boolean unregister(@NotNull UUID uuid) {
        return authManager.unregister(uuid);
    }

    // ===== 玩家信息 =====

    /** 获取玩家上次退出位置，无记录时返回 null */
    @Nullable
    public Location getLastLocation(@NotNull UUID uuid) {
        return authManager.getLogoutLocation(uuid);
    }

    /** 获取玩家上次登录 IP，无记录时返回 null */
    @Nullable
    public String getLastIp(@NotNull UUID uuid) {
        PlayerDataManager.PlayerData data = dataManager.getPlayer(uuid);
        return data != null ? data.ip() : null;
    }

    /**
     * 获取玩家上次登录时间（epoch 秒）。
     * @return 时间戳，0 表示从未登录或登录已失效
     */
    public long getLastLoginTime(@NotNull UUID uuid) {
        PlayerDataManager.PlayerData data = dataManager.getPlayer(uuid);
        return data != null ? data.lastLogin() : 0;
    }

    /** 获取所有已注册玩家的 UUID 集合（不可变副本） */
    @NotNull
    public Set<UUID> getRegisteredUuids() {
        return new HashSet<>(dataManager.getAllUuids());
    }

    // ===== 密码操作 =====

    /**
     * 校验密码是否正确。
     * @return 密码正确返回 true，玩家无账号或密码错误返回 false
     */
    public boolean checkPassword(@NotNull UUID uuid, @NotNull String password) {
        PlayerDataManager.PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        return PasswordHash.checkPassword(password, data.passwordHash());
    }

    /**
     * 修改玩家密码（无需旧密码，管理员操作）。
     * 修改后 IP 免密登录失效，玩家下次需用新密码登录。
     * @return 玩家无账号时返回 false
     */
    public boolean changePassword(@NotNull UUID uuid, @NotNull String newPassword) {
        return authManager.forceChangePassword(uuid, newPassword);
    }
}
