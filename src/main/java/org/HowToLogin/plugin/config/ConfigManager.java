package org.howtologin.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// preventXxx() 方法在调用方均以 ! 守卫子句形式使用（if (!preventXxx()) return;），
// IDE 误报"始终反转"，但反转方法逻辑会导致与方法名语义相反，破坏统一的 preventXxx 设计模式
@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public final class ConfigManager {

    private final HTLogin plugin;
    // 配置被钳制/回退修正标记，load() 末尾统一写回 config.yml
    private boolean configDirty;

    // 数据库设置
    private String databaseType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private Map<String, String> mysqlParams;
    private int poolSize;
    // 数据库配置指纹：用于 reload 时检测数据库配置是否变化（变化需重启而非热重载）
    private String databaseFingerprint;

    // 登录设置
    private int loginTimeout;
    private boolean kickOnTimeout;
    private boolean failProtectionEnabled;
    private int failMaxAttempts;
    private int failKickDuration;
    // 失败计数跨连接保留的过期时间（秒）：玩家最后一次失败超过此时长未再失败则清空计数，0 = 永不过期
    private int failProtectionResetSeconds;
    private boolean ipAutoLoginEnabled;
    private int ipAutoLoginExpireMinutes;
    private int loginRemindInterval;
    // 提示消息发送方式：chat / title / actionbar / bossbar
    private String loginRemindMethod;
    // IP 变动提醒：登录 IP 与上次不同时提示玩家
    private boolean ipChangeNotifyEnabled;
    // 双因素认证：全局开关（关闭后已绑定玩家跳过验证，密钥保留）
    private boolean twoFactorEnabled;
    // 双因素认证：认证器中显示的发行方名称（otpauth URI 的 issuer）
    private String twoFactorIssuer;

    // 密码规则
    private int minPasswordLength;
    private int maxPasswordLength;
    private String passwordHashAlgorithm;
    private int bcryptCost;
    private Pattern passwordPattern;

    // 注册限制
    private int maxAccountsPerIp;

    // 行为限制
    private boolean preventMove;
    private boolean preventLook;
    private boolean preventChat;
    private boolean preventCommand;
    private List<String> commandWhitelist;
    private boolean preventWorldInteraction;
    private boolean preventInventory;

    // 登录前保护
    private boolean protectionPosEnabled;
    private String protectionPosMode;
    private int protectionPosSpawnRadius;
    private double protectionPosFixedX;
    private double protectionPosFixedY;
    private double protectionPosFixedZ;
    private float protectionPosFixedYaw;
    private float protectionPosFixedPitch;
    // 未登录旁观模式：未登录期间切换为旁观，登录后恢复上次游戏模式
    private boolean protectionGamemodeEnabled;
    // 背包保护：未登录期间通过 PacketEvents 拦截物品数据包，防止 mod 窥视
    private boolean protectionInventoryEnabled;

    // 通用设置
    private boolean realUnreg;
    // 清理不活跃账号
    private boolean purgeEnabled;
    private int purgeDays;

    // 正版验证
    private boolean premiumEnabled;
    // 新玩家自动正版验证：关闭时新玩家直接离线进入，仅 /upgrade 标记的玩家验证
    private boolean premiumAutoVerify;
    private int premiumTimeoutSeconds;
    private int premiumCrackerCacheSeconds;
    // 加密握手阶段等待 EncryptionResponse 的超时（毫秒），防止恶意客户端滞留会话
    private int premiumHandshakeTimeoutMs;
    // hasJoined 首次调用前等待（毫秒），确保客户端已 join Mojang sessionserver
    private int premiumInitialDelayMs;
    // hasJoined 可重试状态码的最大重试次数（不含首次尝试）
    private int premiumMaxRetries;
    // 重试指数退避基数（毫秒），第 n 次重试等待 base * 2^n
    private long premiumRetryBackoffBaseMs;
    // Mojang hasJoined 专用线程池大小（请求含 sleep/退避重试，阻塞式任务需独立线程）
    private int premiumHttpPoolSize;
    // 离线确认/正版回退缓存硬上限（超过则逐出最早过期项，防止攻击者无限刷新导致内存膨胀）
    private int premiumCacheCap;
    // 离线账号升级为正版
    private boolean premiumUpgradeEnabled;
    // 正版验证失败时允许正版玩家以密码登录（默认关闭，不安全）
    private boolean premiumPasswordFallbackEnabled;
    // 回退标记有效期（秒），过期后重连重新尝试正版验证
    private int premiumFallbackCacheSeconds;

    public ConfigManager(HTLogin plugin) {
        this.plugin = plugin;
        load();
    }

    // 内置语言文件资源路径
    private static final String[] LANG_RESOURCES = {
            "lang/zh_CN.properties",
            "lang/en_US.properties"
    };

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        // 版本检查：config.yml 的 version 字段存储完整版本号
        // - major.minor 变化：增量合并新配置键 + 覆盖语言文件（保留用户已有的自定义值，不整体覆盖）
        // - patch 变化：仅覆盖语言文件，手动更新 version 字段（保留用户配置）
        FileConfiguration config = plugin.getConfig();
        String fileVersion = config.getString("version", "");
        String pluginVersion = plugin.getPluginMeta().getVersion();

        if (!pluginVersion.equals(fileVersion)) {
            boolean majorMinorChanged = !majorMinor(pluginVersion).equals(majorMinor(fileVersion));

            if (majorMinorChanged) {
                // major.minor 变化：增量合并，仅补入用户 config 缺失的新配置键，保留用户已有值
                mergeMissingKeys(config);
            } else {
                // 仅 patch 变化：不覆盖 config，只更新 version 字段
                config.set("version", pluginVersion);
                plugin.saveConfig();
            }

            // 任何版本变化都覆盖语言文件
            for (String resource : LANG_RESOURCES) {
                plugin.saveResource(resource, true);
            }
            I18n.reload();
            if (majorMinorChanged) {
                plugin.getLogger().warning(I18n.get("plugin.config_version_mismatch", fileVersion, pluginVersion));
            } else {
                plugin.getLogger().warning(I18n.get("plugin.lang_version_mismatch", fileVersion, pluginVersion));
            }
        }

        // 从 config.yml 加载各项配置参数

        // 数据库设置
        this.databaseType = config.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        // 数据库类型校验：仅支持 sqlite/mysql，非法值回退为 sqlite
        if (!"sqlite".equals(this.databaseType) && !"mysql".equals(this.databaseType)) {
            plugin.getLogger().warning(I18n.get("log.config_database_type_invalid", this.databaseType, "sqlite"));
            this.databaseType = "sqlite";
            config.set("database.type", "sqlite");
            configDirty = true;
        }
        this.mysqlHost = config.getString("database.mysql.host", "localhost");
        this.mysqlPort = clampRange("database.mysql.port", config.getInt("database.mysql.port", 3306), 1, 65535);
        this.mysqlDatabase = config.getString("database.mysql.database", "htlogin");
        this.mysqlUsername = config.getString("database.mysql.username", "root");
        this.mysqlPassword = config.getString("database.mysql.password", "");
        // 连接参数保持顺序，便于拼接 URL
        this.mysqlParams = new LinkedHashMap<>();
        var paramsSection = config.getConfigurationSection("database.mysql.params");
        if (paramsSection != null) {
            for (String key : paramsSection.getKeys(false)) {
                this.mysqlParams.put(key, config.getString("database.mysql.params." + key, ""));
            }
        }
        this.poolSize = clampRange("database.mysql.pool-size", config.getInt("database.mysql.pool-size", 10), 1, 128);
        // 记录数据库配置指纹，用于 reload 时检测是否需要重启
        this.databaseFingerprint = databaseType + "|" + mysqlHost + "|" + mysqlPort
                + "|" + mysqlDatabase + "|" + mysqlUsername + "|" + mysqlPassword + "|" + poolSize;

        // 登录设置
        this.loginTimeout = clampInt("login.timeout", config.getInt("login.timeout", 120), 0);
        this.kickOnTimeout = config.getBoolean("login.kick-on-timeout", true);
        this.failProtectionEnabled = config.getBoolean("login.fail-protection.enabled", true);
        this.failMaxAttempts = clampInt("login.fail-protection.max-attempts", config.getInt("login.fail-protection.max-attempts", 3), 1);
        this.failKickDuration = clampInt("login.fail-protection.kick-duration", config.getInt("login.fail-protection.kick-duration", 60), 0);
        this.failProtectionResetSeconds = clampInt("login.fail-protection.reset-seconds", config.getInt("login.fail-protection.reset-seconds", 300), 0);
        this.ipAutoLoginEnabled = config.getBoolean("login.ip-auto-login.enabled", true);
        this.ipAutoLoginExpireMinutes = clampInt("login.ip-auto-login.expire-minutes", config.getInt("login.ip-auto-login.expire-minutes", 120), 0);
        this.loginRemindInterval = clampInt("login.remind-interval", config.getInt("login.remind-interval", 5), 0);
        this.loginRemindMethod = config.getString("login.remind-method", "chat").toLowerCase(Locale.ROOT);
        // 提示方式校验：仅支持 chat/title/actionbar/bossbar，非法值回退为 chat
        if (!List.of("chat", "title", "actionbar", "bossbar").contains(this.loginRemindMethod)) {
            plugin.getLogger().warning(I18n.get("log.config_mode_invalid", "login.remind-method", this.loginRemindMethod, "chat"));
            this.loginRemindMethod = "chat";
            config.set("login.remind-method", "chat");
            configDirty = true;
        }
        this.ipChangeNotifyEnabled = config.getBoolean("login.ip-change-notify.enabled", true);
        // 双因素认证
        this.twoFactorEnabled = config.getBoolean("2fa.enabled", true);
        this.twoFactorIssuer = config.getString("2fa.issuer", "HTLogin");

        // 密码规则
        this.minPasswordLength = config.getInt("password.min-length", 6);
        this.maxPasswordLength = config.getInt("password.max-length", 32);
        // 密码长度配置校验：最小值不得小于 4
        if (this.minPasswordLength < 4) {
            plugin.getLogger().warning(I18n.get("log.password_min_length_clamped", this.minPasswordLength, 4));
            this.minPasswordLength = 4;
        }
        // 最大值不得大于 128
        if (this.maxPasswordLength > 128) {
            plugin.getLogger().warning(I18n.get("log.password_max_length_clamped", this.maxPasswordLength, 128));
            this.maxPasswordLength = 128;
        }
        // 最大值不得小于最小值
        if (this.maxPasswordLength < this.minPasswordLength) {
            plugin.getLogger().warning(I18n.get("log.password_length_range_invalid", this.maxPasswordLength, this.minPasswordLength));
            this.maxPasswordLength = this.minPasswordLength;
        }
        this.passwordHashAlgorithm = config.getString("password.hash", "bcrypt").toLowerCase(Locale.ROOT);
        // 哈希算法校验：仅支持 bcrypt/sha256，非法值回退为 bcrypt（避免静默降级为 sha256）
        if (!"bcrypt".equals(this.passwordHashAlgorithm) && !"sha256".equals(this.passwordHashAlgorithm)) {
            plugin.getLogger().warning(I18n.get("log.config_hash_invalid", this.passwordHashAlgorithm, "bcrypt"));
            this.passwordHashAlgorithm = "bcrypt";
        }
        // BCrypt work factor：钳制 4-31 有效范围，越界时回写配置文件
        this.bcryptCost = clampRange("password.hash-cost", config.getInt("password.hash-cost", 12), 4, 31);
        // 密码字符规则：正则表达式，为空表示不限制
        String patternStr = config.getString("password.pattern", "");
        if (patternStr.isBlank()) {
            this.passwordPattern = null;
        } else {
            try {
                this.passwordPattern = Pattern.compile(patternStr);
            } catch (java.util.regex.PatternSyntaxException e) {
                plugin.getLogger().warning(I18n.get("log.password_pattern_invalid", patternStr, e.getMessage()));
                this.passwordPattern = null;
            }
        }

        // 注册限制
        this.maxAccountsPerIp = clampInt("register.max-accounts-per-ip", config.getInt("register.max-accounts-per-ip", 3), 0);

        // 行为限制
        this.preventMove = config.getBoolean("prevent.move", true);
        this.preventLook = config.getBoolean("prevent.look", true);
        this.preventChat = config.getBoolean("prevent.chat", true);
        this.preventCommand = config.getBoolean("prevent.command.enabled", true);
        // 命令白名单统一转小写，匹配时大小写不敏感
        this.commandWhitelist = config.getStringList("prevent.command.whitelist")
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
        this.preventWorldInteraction = config.getBoolean("prevent.world-interaction", true);
        this.preventInventory = config.getBoolean("prevent.inventory", true);

        // 登录前保护
        this.protectionPosEnabled = config.getBoolean("protection.pos.enabled", false);
        this.protectionPosMode = config.getString("protection.pos.mode", "random");
        // 坐标模式校验：仅支持 random/fixed，非法值回退为 random
        if (!"random".equals(this.protectionPosMode) && !"fixed".equals(this.protectionPosMode)) {
            plugin.getLogger().warning(I18n.get("log.config_mode_invalid", "protection.pos.mode", this.protectionPosMode, "random"));
            this.protectionPosMode = "random";
            config.set("protection.pos.mode", "random");
            configDirty = true;
        }
        this.protectionPosSpawnRadius = clampInt("protection.pos.spawn-radius", config.getInt("protection.pos.spawn-radius", 10), 1);
        this.protectionPosFixedX = config.getDouble("protection.pos.fixed.x", 0);
        this.protectionPosFixedY = config.getDouble("protection.pos.fixed.y", 64);
        this.protectionPosFixedZ = config.getDouble("protection.pos.fixed.z", 0);
        this.protectionPosFixedYaw = (float) config.getDouble("protection.pos.fixed.yaw", 0);
        this.protectionPosFixedPitch = (float) config.getDouble("protection.pos.fixed.pitch", 0);

        // 未登录旁观模式
        this.protectionGamemodeEnabled = config.getBoolean("protection.gamemode.enabled", false);

        // 背包保护（PacketEvents 数据包拦截）
        this.protectionInventoryEnabled = config.getBoolean("protection.inventory.enabled", false);

        this.realUnreg = config.getBoolean("settings.real-unreg", true);
        this.purgeEnabled = config.getBoolean("settings.purge.enabled", false);
        this.purgeDays = clampInt("settings.purge.days", config.getInt("settings.purge.days", 90), 1);

        // 正版验证
        this.premiumEnabled = config.getBoolean("premium.enabled", false);
        this.premiumAutoVerify = config.getBoolean("premium.auto-verify", true);
        this.premiumTimeoutSeconds = clampInt("premium.timeout-seconds", config.getInt("premium.timeout-seconds", 10), 1);
        this.premiumCrackerCacheSeconds = clampInt("premium.cracker-cache-seconds", config.getInt("premium.cracker-cache-seconds", 120), 0);
        this.premiumHandshakeTimeoutMs = clampInt("premium.handshake-timeout-ms", config.getInt("premium.handshake-timeout-ms", 30000), 0);
        this.premiumInitialDelayMs = clampInt("premium.initial-delay-ms", config.getInt("premium.initial-delay-ms", 1000), 0);
        this.premiumMaxRetries = clampInt("premium.max-retries", config.getInt("premium.max-retries", 2), 0);
        this.premiumRetryBackoffBaseMs = clampInt("premium.retry-backoff-base-ms", config.getInt("premium.retry-backoff-base-ms", 5000), 0);
        this.premiumHttpPoolSize = clampRange("premium.http-pool-size", config.getInt("premium.http-pool-size", 2), 2, 64);
        this.premiumCacheCap = clampInt("premium.cache-cap", config.getInt("premium.cache-cap", 1000), 0);
        this.premiumUpgradeEnabled = config.getBoolean("premium.upgrade.enabled", false);
        this.premiumPasswordFallbackEnabled = config.getBoolean("premium.fallback.enabled", false);
        this.premiumFallbackCacheSeconds = clampInt("premium.fallback.cache-seconds", config.getInt("premium.fallback.cache-seconds", 300), 30);

        // 默认语言（控制台日志和客户端语言无匹配文件时使用）
        String defaultLanguage = config.getString("settings.default-language", "zh_CN");
        boolean clientLanguageDetection = config.getBoolean("settings.i18n", true);
        I18n.setDefaultLocale(defaultLanguage);
        I18n.setClientLanguageDetection(clientLanguageDetection);

        // 有修正时写回 config.yml，避免下次启动重复告警
        if (configDirty) {
            plugin.saveConfig();
            configDirty = false;
        }
    }

    /**
     * 增量合并默认配置：从插件内置的 config.yml 读取新配置键，仅将用户 config 中缺失的键补入，
     * 保留用户已有的自定义值（不再整体覆盖，避免升级时丢配置）。最后更新 version 字段并写回。
     */
    private void mergeMissingKeys(FileConfiguration config) {
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is != null) {
                FileConfiguration defaults =
                        YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
                for (String key : defaults.getKeys(true)) {
                    if (!config.contains(key)) {
                        config.set(key, defaults.get(key));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.get("log.config_merge_failed", e.getMessage()));
        }
        config.set("version", plugin.getPluginMeta().getVersion());
        plugin.saveConfig();
    }

    /**
     * 重新加载配置。
     * 数据库配置变化时数据源无法运行时重建，但 ConfigManager 字段仍更新为配置文件当前值，
     * 重启后 PlayerDataManager 会用新配置创建数据源。
     * @return true 表示数据库配置发生变化（调用方应提示重启以应用数据库变更）
     */
    public boolean reload() {
        String oldFingerprint = this.databaseFingerprint;
        load();
        // 字段已更新为配置文件当前值，但运行中的数据源未重建，需提示用户重启
        return !String.valueOf(oldFingerprint).equals(this.databaseFingerprint);
    }

    /** 取版本号前两位（major.minor），patch 版本仅修 bug 不影响配置结构 */
    private static String majorMinor(String version) {
        if (version == null) return "";
        String base = version.split("-", 2)[0];
        String[] parts = base.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return base;
    }

    /** 整型配置校验：低于下限时调整为下限并告警，避免非法值导致运行时异常 */
    private int clampInt(String key, int value, int min) {
        if (value < min) {
            plugin.getLogger().warning(I18n.get("log.config_num_clamped", key, value, min));
            plugin.getConfig().set(key, min);
            configDirty = true;
            return min;
        }
        return value;
    }

    /** 整型配置校验：限定区间 [min, max]，越界时钳制到边界并告警（用于端口、线程池等有硬性上限的配置） */
    private int clampRange(String key, int value, int min, int max) {
        if (value < min) {
            plugin.getLogger().warning(I18n.get("log.config_num_clamped", key, value, min));
            plugin.getConfig().set(key, min);
            configDirty = true;
            return min;
        }
        if (value > max) {
            plugin.getLogger().warning(I18n.get("log.config_num_clamped", key, value, max));
            plugin.getConfig().set(key, max);
            configDirty = true;
            return max;
        }
        return value;
    }

    // 数据库设置
    public String databaseType() { return databaseType; }
    public String mysqlHost() { return mysqlHost; }
    public int mysqlPort() { return mysqlPort; }
    public String mysqlDatabase() { return mysqlDatabase; }
    public String mysqlUsername() { return mysqlUsername; }
    public String mysqlPassword() { return mysqlPassword; }
    public Map<String, String> mysqlParams() { return mysqlParams; }
    public int poolSize() { return poolSize; }

    // 登录设置
    public int loginTimeout() { return loginTimeout; }
    public boolean kickOnTimeout() { return kickOnTimeout; }
    public boolean failProtectionEnabled() { return failProtectionEnabled; }
    public int failMaxAttempts() { return failMaxAttempts; }
    public int failKickDuration() { return failKickDuration; }
    public int failProtectionResetSeconds() { return failProtectionResetSeconds; }
    public boolean ipAutoLoginEnabled() { return ipAutoLoginEnabled; }
    public int ipAutoLoginExpireMinutes() { return ipAutoLoginExpireMinutes; }
    public int loginRemindInterval() { return loginRemindInterval; }
    public String loginRemindMethod() { return loginRemindMethod; }
    public boolean ipChangeNotifyEnabled() { return ipChangeNotifyEnabled; }
    public boolean twoFactorEnabled() { return twoFactorEnabled; }
    public String twoFactorIssuer() { return twoFactorIssuer; }

    // 密码规则
    public int minPasswordLength() { return minPasswordLength; }
    public int maxPasswordLength() { return maxPasswordLength; }
    public String passwordHashAlgorithm() { return passwordHashAlgorithm; }
    public int bcryptCost() { return bcryptCost; }
    /** 密码正则规则，null 表示不限制 */
    public Pattern passwordPattern() { return passwordPattern; }

    // 注册限制
    public int maxAccountsPerIp() { return maxAccountsPerIp; }

    // 行为限制
    public boolean preventMove() { return preventMove; }
    public boolean preventLook() { return preventLook; }
    public boolean preventChat() { return preventChat; }
    public boolean preventCommand() { return preventCommand; }
    public List<String> commandWhitelist() { return commandWhitelist; }
    public boolean preventWorldInteraction() { return preventWorldInteraction; }
    public boolean preventInventory() { return preventInventory; }

    // 登录前保护
    public boolean protectionPosEnabled() { return protectionPosEnabled; }
    public String protectionPosMode() { return protectionPosMode; }
    public int protectionPosSpawnRadius() { return protectionPosSpawnRadius; }
    public double protectionPosFixedX() { return protectionPosFixedX; }
    public double protectionPosFixedY() { return protectionPosFixedY; }
    public double protectionPosFixedZ() { return protectionPosFixedZ; }
    public float protectionPosFixedYaw() { return protectionPosFixedYaw; }
    public float protectionPosFixedPitch() { return protectionPosFixedPitch; }

    // 未登录旁观模式
    public boolean protectionGamemodeEnabled() { return protectionGamemodeEnabled; }

    // 背包保护（PacketEvents 数据包拦截）
    public boolean protectionInventoryEnabled() { return protectionInventoryEnabled; }

    // 通用设置
    public boolean realUnreg() { return realUnreg; }
    public boolean purgeEnabled() { return purgeEnabled; }
    public int purgeDays() { return purgeDays; }

    // 正版验证
    public boolean premiumEnabled() { return premiumEnabled; }
    public boolean premiumAutoVerify() { return premiumAutoVerify; }
    public int premiumTimeoutSeconds() { return premiumTimeoutSeconds; }
    public int premiumCrackerCacheSeconds() { return premiumCrackerCacheSeconds; }
    public int premiumHandshakeTimeoutMs() { return premiumHandshakeTimeoutMs; }
    public long premiumInitialDelayMs() { return premiumInitialDelayMs; }
    public int premiumMaxRetries() { return premiumMaxRetries; }
    public long premiumRetryBackoffBaseMs() { return premiumRetryBackoffBaseMs; }
    public int premiumHttpPoolSize() { return premiumHttpPoolSize; }
    public int premiumCacheCap() { return premiumCacheCap; }
    public boolean premiumUpgradeEnabled() { return premiumUpgradeEnabled; }
    public boolean premiumPasswordFallbackEnabled() { return premiumPasswordFallbackEnabled; }
    public int premiumFallbackCacheSeconds() { return premiumFallbackCacheSeconds; }

}