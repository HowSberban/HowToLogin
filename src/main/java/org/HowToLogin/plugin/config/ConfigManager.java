package org.howtologin.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

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
    private boolean ipAutoLoginEnabled;
    private int ipAutoLoginExpireMinutes;
    private int loginRemindInterval;

    // 密码规则
    private int minPasswordLength;
    private int maxPasswordLength;
    private String passwordHashAlgorithm;
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
    private int protectionPosSpawnRadius;

    // 通用设置
    private boolean realUnreg;

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
        // - major.minor 变化：覆盖 config.yml + 语言文件（配置结构可能变化）
        // - patch 变化：仅覆盖语言文件，手动更新 version 字段（保留用户配置）
        FileConfiguration config = plugin.getConfig();
        String fileVersion = config.getString("version", "");
        String pluginVersion = plugin.getPluginMeta().getVersion();

        if (!pluginVersion.equals(fileVersion)) {
            boolean majorMinorChanged = !majorMinor(pluginVersion).equals(majorMinor(fileVersion));

            if (majorMinorChanged) {
                // major.minor 变化：覆盖 config + 语言文件
                plugin.saveResource("config.yml", true);
                plugin.reloadConfig();
                config = plugin.getConfig();
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
        this.mysqlHost = config.getString("database.mysql.host", "localhost");
        this.mysqlPort = config.getInt("database.mysql.port", 3306);
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
        this.poolSize = config.getInt("database.mysql.pool-size", 10);
        // 记录数据库配置指纹，用于 reload 时检测是否需要重启
        this.databaseFingerprint = databaseType + "|" + mysqlHost + "|" + mysqlPort
                + "|" + mysqlDatabase + "|" + mysqlUsername + "|" + mysqlPassword + "|" + poolSize;

        // 登录设置
        this.loginTimeout = config.getInt("login.timeout", 60);
        this.kickOnTimeout = config.getBoolean("login.kick-on-timeout", true);
        this.failProtectionEnabled = config.getBoolean("login.fail-protection.enabled", true);
        this.failMaxAttempts = config.getInt("login.fail-protection.max-attempts", 3);
        this.failKickDuration = config.getInt("login.fail-protection.kick-duration", 60);
        this.ipAutoLoginEnabled = config.getBoolean("login.ip-auto-login.enabled", true);
        this.ipAutoLoginExpireMinutes = config.getInt("login.ip-auto-login.expire-minutes", 720);
        this.loginRemindInterval = config.getInt("login.remind-interval", 5);

        // 密码规则
        this.minPasswordLength = config.getInt("password.min-length", 6);
        this.maxPasswordLength = config.getInt("password.max-length", 32);
        this.passwordHashAlgorithm = config.getString("password.hash", "bcrypt").toLowerCase(Locale.ROOT);
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
        this.maxAccountsPerIp = config.getInt("register.max-accounts-per-ip", 0);

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
        this.protectionPosSpawnRadius = config.getInt("protection.pos.spawn-radius", 10);

        // 通用设置
        this.realUnreg = config.getBoolean("settings.real-unreg", true);

        // 通用设置：默认语言（控制台日志和客户端语言无匹配文件时使用）
        // 通用设置
        String defaultLanguage = config.getString("settings.default-language", "zh_CN");
        boolean clientLanguageDetection = config.getBoolean("settings.i18n", true);
        I18n.setDefaultLocale(defaultLanguage);
        I18n.setClientLanguageDetection(clientLanguageDetection);
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
    public boolean ipAutoLoginEnabled() { return ipAutoLoginEnabled; }
    public int ipAutoLoginExpireMinutes() { return ipAutoLoginExpireMinutes; }
    public int loginRemindInterval() { return loginRemindInterval; }

    // 密码规则
    public int minPasswordLength() { return minPasswordLength; }
    public int maxPasswordLength() { return maxPasswordLength; }
    public String passwordHashAlgorithm() { return passwordHashAlgorithm; }
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
    public int protectionPosSpawnRadius() { return protectionPosSpawnRadius; }

    // 通用设置
    public boolean realUnreg() { return realUnreg; }

}