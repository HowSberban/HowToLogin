package org.HowToLogin.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // 行为限制
    private boolean preventMove;
    private boolean preventLook;
    private boolean preventChat;
    private boolean preventCommand;
    private List<String> commandWhitelist;
    private boolean preventWorldInteraction;

    // 登录前保护
    private boolean protectionPosEnabled;
    private int protectionPosSpawnRadius;

    // 通用设置
    private String defaultLanguage;
    private boolean clientLanguageDetection;

    public ConfigManager(HTLogin plugin) {
        this.plugin = plugin;
        load();
    }

    // 内置语言文件资源路径
    private static final String[] LANG_RESOURCES = {
            "lang/zh_CN.properties",
            "lang/en_US.properties"
    };
    // 语言文件版本记录文件（记录上次释放语言文件时的完整插件版本）
    private static final String LANG_VERSION_FILE = ".lang_version";

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        // 版本检查：
        //   - 配置文件版本只取前两位（major.minor），patch 版本仅修 bug 不触发配置覆盖
        //   - 语言文件版本用完整版本号，patch 变化时也覆盖（修 bug 可能修正消息文案）
        FileConfiguration config = plugin.getConfig();
        String fileVersion = config.getString("version", "");
        String pluginVersion = plugin.getPluginMeta().getVersion();
        String configVersion = majorMinor(pluginVersion);

        // 读取上次记录的语言文件版本
        File langVersionFile = new File(plugin.getDataFolder(), LANG_VERSION_FILE);
        String storedLangVersion = "";
        if (langVersionFile.exists()) {
            try {
                storedLangVersion = Files.readString(langVersionFile.toPath()).trim();
            } catch (IOException e) {
                plugin.getLogger().warning("无法读取语言文件版本记录：" + e.getMessage());
            }
        }

        boolean configMismatch = !configVersion.equals(fileVersion);
        boolean langMismatch = !pluginVersion.equals(storedLangVersion);

        if (configMismatch) {
            // major.minor 变化：覆盖 config + 语言文件
            plugin.saveResource("config.yml", true);
            for (String resource : LANG_RESOURCES) {
                plugin.saveResource(resource, true);
            }
            writeLangVersion(langVersionFile, pluginVersion);
            plugin.reloadConfig();
            config = plugin.getConfig();
            I18n.reload();
            plugin.getLogger().warning(I18n.get("plugin.config_version_mismatch", fileVersion, configVersion));
        } else if (langMismatch) {
            // 仅 patch 变化：覆盖语言文件
            for (String resource : LANG_RESOURCES) {
                plugin.saveResource(resource, true);
            }
            writeLangVersion(langVersionFile, pluginVersion);
            I18n.reload();
            plugin.getLogger().warning(I18n.get("plugin.lang_version_mismatch", storedLangVersion, pluginVersion));
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
        if (config.isConfigurationSection("database.mysql.params")) {
            for (String key : config.getConfigurationSection("database.mysql.params").getKeys(false)) {
                this.mysqlParams.put(key, config.getString("database.mysql.params." + key, ""));
            }
        }
        this.poolSize = config.getInt("database.mysql.pool-size", 10);

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

        // 登录前保护
        this.protectionPosEnabled = config.getBoolean("protection.pos.enabled", false);
        this.protectionPosSpawnRadius = config.getInt("protection.pos.spawn-radius", 10);

        // 通用设置：默认语言（控制台日志和客户端语言无匹配文件时使用）
        this.defaultLanguage = config.getString("settings.default-language", "zh_CN");
        this.clientLanguageDetection = config.getBoolean("settings.i18n", true);
        I18n.setDefaultLocale(defaultLanguage);
        I18n.setClientLanguageDetection(clientLanguageDetection);
    }

    public void reload() {
        load();
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

    // 行为限制
    public boolean preventMove() { return preventMove; }
    public boolean preventLook() { return preventLook; }
    public boolean preventChat() { return preventChat; }
    public boolean preventCommand() { return preventCommand; }
    public List<String> commandWhitelist() { return commandWhitelist; }
    public boolean preventWorldInteraction() { return preventWorldInteraction; }

    // 登录前保护
    public boolean protectionPosEnabled() { return protectionPosEnabled; }
    public int protectionPosSpawnRadius() { return protectionPosSpawnRadius; }

    // 通用设置
    public String defaultLanguage() { return defaultLanguage; }
    public boolean clientLanguageDetection() { return clientLanguageDetection; }

    /** 取版本号前两位（major.minor），patch 版本仅修 bug 不影响配置结构 */
    private static String majorMinor(String version) {
        if (version == null) return "";
        String base = version.split("-", 2)[0];
        String[] parts = base.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return base;
    }

    /** 写入语言文件版本记录 */
    private void writeLangVersion(File file, String version) {
        try {
            Files.writeString(file.toPath(), version);
        } catch (IOException e) {
            plugin.getLogger().warning("无法写入语言文件版本记录：" + e.getMessage());
        }
    }
}