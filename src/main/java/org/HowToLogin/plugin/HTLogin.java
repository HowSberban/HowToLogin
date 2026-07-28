package org.HowToLogin.plugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.HowToLogin.plugin.auth.AuthManager;
import org.HowToLogin.plugin.command.*;
import org.HowToLogin.plugin.config.ConfigManager;
import org.HowToLogin.plugin.data.PlayerDataManager;
import org.HowToLogin.plugin.hook.HTLoginExpansion;
import org.HowToLogin.plugin.listener.PlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class HTLogin extends JavaPlugin {

    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private AuthManager authManager;
    private PlayerListener playerListener;

    @Override
    public void onEnable() {
        // I18n 必须先初始化：ConfigManager 在检测到配置版本不匹配时会调用 I18n.reload()
        I18n.init(this);
        this.configManager = new ConfigManager(this);
        this.playerDataManager = new PlayerDataManager(this);
        this.authManager = new AuthManager(this, playerDataManager, configManager);

        registerCommands();
        registerListeners();
        hookPlaceholderAPI();
        rePendOnlinePlayers();

        getLogger().info(I18n.get("plugin.enabled", getPluginMeta().getVersion()));
    }

    @Override
    public void onDisable() {
        if (playerDataManager != null) {
            playerDataManager.saveSync();
            playerDataManager.close();
        }
        // Paper 1.20+ 统一调度器 API，兼容 Folia
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        getLogger().info(I18n.get("plugin.disabled"));
    }

    private void registerCommands() {
        // Paper 插件规范：通过 LifecycleEvents 程序化注册命令
        // 使用 var 接收 getLifecycleManager() 的返回值，让编译器自动推断通配符类型，
        // 避免显式声明 LifecycleEventManager<Plugin> 时与实际返回类型不匹配的警告
        var manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register("register", "注册账号", List.of("reg"), new RegisterCommand(this, authManager));
            commands.register("login", "登录账号", List.of("l"), new LoginCommand(authManager));
            commands.register("changepassword", "修改密码", List.of("changepw", "cp"), new ChangePasswordCommand(this, authManager));
            commands.register("logout", "退出登录", List.of(), new LogoutCommand(authManager));
            commands.register("unregister", "删除账号（管理员）", List.of(), new UnregisterCommand(authManager));
            commands.register("htlogin", "插件管理命令", List.of(), new HTLoginCommand(this));
        });
    }

    private void registerListeners() {
        playerListener = new PlayerListener(this, authManager);
        getServer().getPluginManager().registerEvents(playerListener, this);
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    /** PlaceholderAPI 软依赖：存在时注册变量扩展 */
    private void hookPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new HTLoginExpansion(this).register();
            getLogger().info("PlaceholderAPI 集成已启用");
        }
    }

    /**
     * 处理 /reload 后在线玩家状态丢失：
     * 插件重启后内存中的登录状态被清空，需重新挂起未登录的在线玩家。
     */
    private void rePendOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (authManager.isLoggedIn(player)) continue;
            if (authManager.hasAccount(player)) {
                authManager.addPendingLogin(player);
                player.sendMessage(HTLogin.legacy(I18n.get("listener.please_login", player)));
            } else {
                player.sendMessage(HTLogin.legacy(I18n.get("listener.please_register", player)));
            }
            playerListener.scheduleLoginTimeout(player);
            playerListener.scheduleReminder(player, authManager.hasAccount(player));
        }
    }

    /** 将旧版 '&' 颜色代码转换为 Adventure Component */
    public static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
