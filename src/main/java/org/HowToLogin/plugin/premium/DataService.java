package org.howtologin.plugin.premium;

import org.howtologin.plugin.config.ConfigManager;
import org.howtologin.plugin.data.PlayerDataManager;
import org.howtologin.plugin.data.PlayerDataManager.PlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据服务（模块2）—— 纯内存仓储，包装 PlayerDataManager。
 * <p>
 * 全量加载策略：PlayerDataManager 启动时已全量加载至内存，
 * 本模块直接复用其内存缓存，运行时所有查询为 O(1) 内存操作。
 * <p>
 * 线程模型：查询操作为纯内存读取，可在任何线程（含 IO 线程）直接调用。
 * 持久化写入由 PlayerDataManager 异步落盘。
 */
public final class DataService {

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;

    // 离线确认缓存：ip + "|" + name(小写) -> 到期时间戳（毫秒）
    // 命中后跳过正版验证，直接走离线登录流程
    private final Map<String, Long> offlineConfirmed = new ConcurrentHashMap<>();

    // offlineConfirmed Map 大小阈值，超过时触发过期项清理
    private static final int CLEANUP_THRESHOLD = 1000;

    public DataService(PlayerDataManager dataManager, ConfigManager configManager) {
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    /**
     * 按玩家名查询档案（纯内存，O(1)~O(n)）。
     * 先查离线 UUID（确定性计算），再查正版玩家名匹配。
     *
     * @param name 玩家名称（不区分大小写）
     * @return ProfileResult，exists=false 表示无记录
     */
    public ProfileResult getProfile(String name) {
        // 1. 计算离线 UUID 并查询
        UUID offlineUuid = offlineUuid(name);
        PlayerData offlineData = dataManager.getPlayer(offlineUuid);
        if (offlineData != null) {
            return new ProfileResult(true, offlineData.premium(), offlineUuid, offlineData.properties());
        }

        // 2. 按名查询正版玩家（premium=1 且 name 匹配）
        PlayerData premiumData = dataManager.getByName(name);
        if (premiumData != null) {
            return new ProfileResult(true, true, premiumData.uuid(), premiumData.properties());
        }

        // 3. 无记录
        return new ProfileResult(false, false, null, null);
    }

    /**
     * 保存正版玩家数据（同时更新内存 + 异步落盘）。
     * 若已有账号则标记为 premium，否则创建新记录。
     */
    public void savePremium(UUID uuid, String name, String ip, String propertiesJson) {
        if (dataManager.hasAccount(uuid)) {
            dataManager.markPremium(uuid, name, propertiesJson);
        } else {
            dataManager.createPremiumPlayer(uuid, name, ip, propertiesJson);
        }
    }

    /**
     * 标记离线确认：该 IP + 名 在有效期内重连时跳过正版验证。
     */
    public void markOfflineConfirmed(String ip, String name) {
        // 防止 Map 无限增长：超过阈值时清理过期项
        // 攻击者可用不同 name+IP 组合高频触发此方法，懒删除无法清理未被查询的 key
        if (offlineConfirmed.size() > CLEANUP_THRESHOLD) {
            long now = System.currentTimeMillis();
            offlineConfirmed.entrySet().removeIf(e -> e.getValue() <= now);
        }
        offlineConfirmed.put(cacheKey(ip, name),
                System.currentTimeMillis() + configManager.premiumCrackerCacheSeconds() * 1000L);
    }

    /**
     * 检查离线确认标记是否有效。
     */
    public boolean isOfflineConfirmed(String ip, String name) {
        Long expire = offlineConfirmed.get(cacheKey(ip, name));
        if (expire == null) return false;
        if (System.currentTimeMillis() > expire) {
            offlineConfirmed.remove(cacheKey(ip, name));
            return false;
        }
        return true;
    }

    /** 计算离线 UUID（原版离线模式：UUID.nameUUIDFromBytes("OfflinePlayer:" + name)） */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String cacheKey(String ip, String name) {
        return ip + "|" + name.toLowerCase();
    }

    /**
     * 玩家档案查询结果（不可变）。
     *
     * @param exists     数据库中是否存在记录
     * @param premium    是否为正版账号（premium=1）
     * @param uuid       玩家 UUID（正版 UUID 或离线 UUID）
     * @param properties 皮肤 properties JSON（可能为 null）
     */
    public record ProfileResult(boolean exists, boolean premium, UUID uuid, String properties) {}
}
