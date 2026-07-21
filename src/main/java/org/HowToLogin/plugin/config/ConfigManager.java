package org.HowToLogin.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.HowToLogin.plugin.HTLogin;

public final class ConfigManager {

    private final HTLogin plugin;

    private int loginTimeout;
    private int minPasswordLength;
    private int maxPasswordLength;
    private boolean kickOnTimeout;
    private boolean preventChat;

    public ConfigManager(HTLogin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        // 从 config.yml 加载各项配置参数

        this.loginTimeout = config.getInt("login-timeout", 60);
        this.minPasswordLength = config.getInt("min-password-length", 4);
        this.maxPasswordLength = config.getInt("max-password-length", 32);
        this.kickOnTimeout = config.getBoolean("kick-on-timeout", true);
        this.preventChat = config.getBoolean("prevent-chat-before-login", true);
    }

    public void reload() {
        load();
    }

    public int loginTimeout() { return loginTimeout; }
    public int minPasswordLength() { return minPasswordLength; }
    public int maxPasswordLength() { return maxPasswordLength; }
    public boolean kickOnTimeout() { return kickOnTimeout; }
    public boolean preventChat() { return preventChat; }
}
