package org.HowToLogin.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.HowToLogin.plugin.HTLogin;

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
    private int maxLoginAttempts;
    private int lockDuration;

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

    public ConfigManager(HTLogin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
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
        this.maxLoginAttempts = config.getInt("login.max-attempts", 5);
        this.lockDuration = config.getInt("login.lock-duration", 300);

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
    public int maxLoginAttempts() { return maxLoginAttempts; }
    public int lockDuration() { return lockDuration; }

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
}