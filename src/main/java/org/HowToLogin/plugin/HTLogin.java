package org.HowToLogin.plugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.HowToLogin.plugin.auth.AuthManager;
import org.HowToLogin.plugin.command.*;
import org.HowToLogin.plugin.config.ConfigManager;
import org.HowToLogin.plugin.data.PlayerDataManager;
import org.HowToLogin.plugin.listener.PlayerListener;
import org.HowToLogin.plugin.util.FoliaHelper;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class HTLogin extends JavaPlugin {

    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private AuthManager authManager;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        I18n.init(this);
        this.playerDataManager = new PlayerDataManager(this);
        this.authManager = new AuthManager(playerDataManager);

        if (FoliaHelper.isFolia()) {
            getLogger().info(I18n.get("plugin.folia_detected"));
        }

        registerCommands();
        registerListeners();

        getLogger().info(I18n.get("plugin.enabled", getPluginMeta().getVersion()));
    }

    @Override
    public void onDisable() {
        if (playerDataManager != null) {
            playerDataManager.saveSync();
        }
        FoliaHelper.cancelTasks(this);
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
        getServer().getPluginManager().registerEvents(new PlayerListener(this, authManager), this);
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    /** 将旧版 '&' 颜色代码转换为 Adventure Component */
    public static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
