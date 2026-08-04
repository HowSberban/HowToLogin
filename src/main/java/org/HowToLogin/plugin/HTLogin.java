package org.howtologin.plugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.command.*;
import org.howtologin.plugin.config.ConfigManager;
import org.howtologin.plugin.data.PlayerDataManager;
import org.howtologin.plugin.hook.HTLoginExpansion;
import org.howtologin.plugin.listener.PlayerListener;
import org.howtologin.plugin.premium.DataService;
import org.howtologin.plugin.premium.MojangClient;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
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
        registerPacketListener();
        registerPremiumListener();
        rePendOnlinePlayers();

        getLogger().info(I18n.get("plugin.enabled", getPluginMeta().getVersion()));
    }

    @Override
    public void onDisable() {
        // 关服前保存所有在线已登录玩家的当前位置
        // stop 关服时 PlayerQuitEvent 可能不触发或时序不确定，显式保存确保位置不丢失
        // 用 updateLogoutLocationCache 只更新内存缓存，由后续 saveSync 统一落库
        // （插件禁用后无法注册异步保存任务）
        if (authManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (authManager.isLoggedIn(player)) {
                    authManager.updateLogoutLocationCache(player);
                }
            }
        }
        if (playerDataManager != null) {
            playerDataManager.saveSync();
            playerDataManager.close();
        }
        // Paper 1.20+ 统一调度器 API，兼容 Folia
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        // PacketEvents 监听器由其自身管理生命周期，无需手动注销
        // 先输出日志再清理 I18n 静态状态，否则 shutdown 后 bundles 被清空会导致 get 返回 key 本身
        getLogger().info(I18n.get("plugin.disabled"));
        I18n.shutdown();
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
            commands.register(new UnregisterCommand(this, authManager).buildNode(), "删除账号（管理员）", List.of());
            // htlogin 使用 brigadier 原生注册，子命令作为 literal 节点，
            // 客户端在输入空格后能自动显示子命令列表
            commands.register(new HTLoginCommand(this).buildNode(), "插件管理命令", List.of());
        });
    }

    private void registerListeners() {
        playerListener = new PlayerListener(this, authManager);
        getServer().getPluginManager().registerEvents(playerListener, this);
    }

    /** 注册 PacketEvents 数据包监听器（背包保护：拦截容器/装备同步包，需要 PacketEvents 前置） */
    private void registerPacketListener() {
        if (!configManager.protectionInventoryEnabled()) return;
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            getLogger().warning(I18n.get("log.packetevents_missing"));
            return;
        }
        try {
            // 反射加载，避免无 PacketEvents 时 Paper 类加载器解析常量池失败
            Class<?> listenerClass = Class.forName("org.howtologin.plugin.packet.InventoryPacketListener");
            Object listener = listenerClass.getConstructor(AuthManager.class, ConfigManager.class)
                    .newInstance(authManager, configManager);

            Class<?> peClass = Class.forName("com.github.retrooper.packetevents.PacketEvents");
            Object api = peClass.getMethod("getAPI").invoke(null);
            Object eventManager = api.getClass().getMethod("getEventManager").invoke(api);
            for (Method m : eventManager.getClass().getMethods()) {
                if (m.getName().equals("registerListener") && m.getParameterCount() == 1) {
                    m.invoke(eventManager, listener);
                    break;
                }
            }
        } catch (Exception e) {
            getLogger().warning(I18n.get("log.packet_listener_failed", e.getMessage()));
        }
    }

    /**
     * 注册正版验证监听器（需要 PacketEvents 前置）。
     * DataService 和 MojangClient 不依赖 PacketEvents，可直接加载；
     * PlayerInjector 和 ConnectionHandler 依赖 PacketEvents，通过反射加载。
     */
    private void registerPremiumListener() {
        if (!configManager.premiumEnabled()) return;
        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            getLogger().warning(I18n.get("log.packetevents_missing"));
            return;
        }
        try {
            // DataService / MojangClient 无 PacketEvents 依赖，直接实例化
            DataService dataService = new DataService(playerDataManager, configManager.premiumCrackerCacheSeconds());
            MojangClient mojangClient = new MojangClient(configManager.premiumTimeoutSeconds());

            // PlayerInjector / ConnectionHandler 依赖 PacketEvents，反射加载
            Class<?> injectorClass = Class.forName("org.howtologin.plugin.premium.PlayerInjector");
            Object playerInjector = injectorClass.getConstructor(HTLogin.class).newInstance(this);

            Class<?> handlerClass = Class.forName("org.howtologin.plugin.premium.ConnectionHandler");
            Object handler = handlerClass
                    .getConstructor(HTLogin.class, DataService.class, MojangClient.class, injectorClass)
                    .newInstance(this, dataService, mojangClient, playerInjector);

            // 通过反射注册到 PacketEvents
            Class<?> peClass = Class.forName("com.github.retrooper.packetevents.PacketEvents");
            Object api = peClass.getMethod("getAPI").invoke(null);
            Object eventManager = api.getClass().getMethod("getEventManager").invoke(api);
            for (Method m : eventManager.getClass().getMethods()) {
                if (m.getName().equals("registerListener") && m.getParameterCount() == 1) {
                    m.invoke(eventManager, handler);
                    break;
                }
            }
        } catch (Exception e) {
            getLogger().warning(I18n.get("log.premium_listener_failed", e.getMessage()));
        }
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

    public PlayerListener getPlayerListener() {
        return playerListener;
    }

    /** PlaceholderAPI 软依赖：存在时注册变量扩展 */
    private void hookPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new HTLoginExpansion(this).register();
            getLogger().info(I18n.get("log.placeholderapi_enabled"));
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
