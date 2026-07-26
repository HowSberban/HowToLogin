package org.HowToLogin.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.HowToLogin.plugin.HTLogin;

public final class ConfigManager {

    private final HTLogin plugin;

    // 登录设置
    private int loginTimeout;
    private boolean kickOnTimeout;
    private int maxLoginAttempts;
    private int lockDuration;

    // 密码规则
    private int minPasswordLength;
    private int maxPasswordLength;

    // 行为限制
    private boolean preventMove;
    private boolean preventLook;
    private boolean preventChat;
    private boolean preventCommand;
    private boolean preventWorldInteraction;

    public ConfigManager(HTLogin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        // 从 config.yml 加载各项配置参数

        // 登录设置
        this.loginTimeout = config.getInt("login.timeout", 60);
        this.kickOnTimeout = config.getBoolean("login.kick-on-timeout", true);
        this.maxLoginAttempts = config.getInt("login.max-attempts", 5);
        this.lockDuration = config.getInt("login.lock-duration", 300);

        // 密码规则
        this.minPasswordLength = config.getInt("password.min-length", 6);
        this.maxPasswordLength = config.getInt("password.max-length", 32);

        // 行为限制
        this.preventMove = config.getBoolean("prevent.move", true);
        this.preventLook = config.getBoolean("prevent.look", true);
        this.preventChat = config.getBoolean("prevent.chat", true);
        this.preventCommand = config.getBoolean("prevent.command", true);
        this.preventWorldInteraction = config.getBoolean("prevent.world-interaction", true);
    }

    public void reload() {
        load();
    }

    // 登录设置
    public int loginTimeout() { return loginTimeout; }
    public boolean kickOnTimeout() { return kickOnTimeout; }
    public int maxLoginAttempts() { return maxLoginAttempts; }
    public int lockDuration() { return lockDuration; }

    // 密码规则
    public int minPasswordLength() { return minPasswordLength; }
    public int maxPasswordLength() { return maxPasswordLength; }

    // 行为限制
    public boolean preventMove() { return preventMove; }
    public boolean preventLook() { return preventLook; }
    public boolean preventChat() { return preventChat; }
    public boolean preventCommand() { return preventCommand; }
    public boolean preventWorldInteraction() { return preventWorldInteraction; }
}
