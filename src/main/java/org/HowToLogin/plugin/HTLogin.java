package org.howtologin.plugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import com.github.retrooper.packetevents.PacketEvents;
import org.howtologin.plugin.api.HTLoginApi;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.command.*;
import org.howtologin.plugin.config.ConfigManager;
import org.howtologin.plugin.data.PlayerDataManager;
import org.howtologin.plugin.dialog.DialogManager;
import org.howtologin.plugin.dialog.PreJoinAuthListener;
import org.howtologin.plugin.hook.HTLoginExpansion;
import org.howtologin.plugin.listener.PlayerListener;
import org.howtologin.plugin.packet.InventoryPacketListener;
import org.howtologin.plugin.premium.ConnectionHandler;
import org.howtologin.plugin.premium.DataService;
import org.howtologin.plugin.premium.MojangClient;
import org.howtologin.plugin.premium.PlayerInjector;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class HTLogin extends JavaPlugin {

    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private AuthManager authManager;
    private PlayerListener playerListener;
    // Pre-join Dialog（配置阶段认证）：配置事件 API 不可用时为 null，自动回退聊天栏提示
    private PreJoinAuthListener preJoinAuthListener;
    // 正版验证异步组件（线程池管理等，禁用时回收）
    private MojangClient mojangClient;
    private PlayerInjector playerInjector;
    // 数据库脏数据批量落库周期（秒）
    private static final int DB_SAVE_FLUSH_SECONDS = 5;

    @Override
    public void onEnable() {
        // I18n 必须先初始化：ConfigManager 在检测到配置版本不匹配时会调用 I18n.reload()
        I18n.init(this);
        this.configManager = new ConfigManager(this);
        this.playerDataManager = new PlayerDataManager(this);
        this.authManager = new AuthManager(this, playerDataManager, configManager);
        // Dialog 登录界面：仅服务端支持（1.21.11+）时实例化，未实例化时回退聊天栏提示
        if (DialogManager.isSupported()) {
            DialogManager dialogManager = new DialogManager(this);
            // Pre-join：配置阶段事件 API（1.21.4+）可用时启用，否则回退聊天栏提示
            if (preJoinSupported()) {
                this.preJoinAuthListener = new PreJoinAuthListener(this, authManager, dialogManager);
                getServer().getPluginManager().registerEvents(preJoinAuthListener, this);
            }
        } else if (configManager.loginDialogEnabled()) {
            getLogger().warning(I18n.get("log.dialog_unsupported"));
        }

        registerCommands();
        registerListeners();
        hookPlaceholderAPI();
        registerPacketListener();
        registerPremiumListener();
        // 清理不活跃账号（启动时执行，此时玩家尚未进入）
        if (configManager.purgeEnabled()) {
            int purged = playerDataManager.purgeInactive(configManager.purgeDays());
            if (purged > 0) {
                getLogger().info(I18n.get("log.purge_inactive", purged, configManager.purgeDays()));
            }
        }
        // 周期批量落库脏数据（合并 DB 写，降低 SQLite 锁竞争与 IO 开销）
        Bukkit.getAsyncScheduler().runAtFixedRate(this,
                task -> playerDataManager.flushDirty(), 1, DB_SAVE_FLUSH_SECONDS, TimeUnit.SECONDS);
        rePendOnlinePlayers();

        // 初始化 API 单例：必须在 AuthManager/PlayerDataManager 初始化完成后，
        // 且在 rePendOnlinePlayers 之后（后者可能触发登录流程，API 此时已可用）
        HTLoginApi.initialize(this, authManager, playerDataManager);

        getLogger().info(I18n.get("plugin.enabled", getPluginMeta().getVersion()));
    }

    @Override
    public void onDisable() {
        // 先清理 API 单例：后续将关闭 dataManager，避免第三方插件在关服过程中读到失效实例
        HTLoginApi.shutdown();
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
        // 关闭正版验证线程池（daemon 线程 JVM 会终止，但规范上应显式关闭）
        if (mojangClient != null) mojangClient.close();
        if (playerInjector != null) playerInjector.close();
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
            commands.register("2fa", "双因素认证", List.of("totp"), new TwoFactorCommand(this, authManager));
            commands.register("changepassword", "修改密码", List.of("changepw", "cp"), new ChangePasswordCommand(this, authManager));
            commands.register("logout", "退出登录", List.of(), new LogoutCommand(authManager));
            commands.register("upgrade", "将离线账号升级为正版账号", List.of(), new UpgradeAccountCommand(this, authManager));
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

    /** 注册 PacketEvents 数据包监听器（背包保护：拦截容器/装备同步包） */
    private void registerPacketListener() {
        // 始终注册监听器，是否拦截由 InventoryPacketListener 按 protection.inventory.enabled 实时判断，
        // 使配置热重载（/htlogin reload）能即时开关背包保护而不必重启
        PacketEvents.getAPI().getEventManager()
                .registerListener(new InventoryPacketListener(authManager, configManager));
    }

    /**
     * 注册正版验证监听器。
     * DataService/MojangClient/PlayerInjector 不依赖 PacketEvents，可直接实例化；
     * ConnectionHandler 依赖 PacketEvents（硬依赖），直接实例化并注册。
     * 始终注册监听器；是否拦截正版玩家由 ConnectionHandler 按数据库 premium 标记实时判断
     * （premium=1 始终验证，配置文件 premium.enabled 只决定新玩家是否验证），
     * 使配置热重载（/htlogin reload）能即时开关正版验证而不必重启。
     */
    private void registerPremiumListener() {
        DataService dataService = new DataService(playerDataManager, configManager);
        this.mojangClient = new MojangClient(this);
        this.playerInjector = new PlayerInjector(this);
        PacketEvents.getAPI().getEventManager()
                .registerListener(new ConnectionHandler(this, dataService, mojangClient, playerInjector, authManager));
        // 预热 Mojang 连接：正版验证开启或已有正版账号时（premium=1 玩家始终验证，与开关无关）
        if (configManager.premiumEnabled() || playerDataManager.hasPremiumAccount()) {
            mojangClient.warmUp();
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

    /** 配置阶段 Dialog 监听器（配置事件 API 不可用时为 null，调用方判空回退聊天栏流程） */
    public PreJoinAuthListener getPreJoinAuthListener() {
        return preJoinAuthListener;
    }

    /**
     * 检测服务端配置阶段 Dialog 是否稳定可用：事件 API 存在（1.21.4+）且服务端版本 >= 1.21.11。
     * 1.21.6–1.21.10 虽能发送配置阶段 Dialog 包，但存在未验证的兼容问题（见 Paper issue #13365/#13708），
     * AuthMe 亦将 pre-join 门槛定为 1.21.11+，故低于此版本回退聊天栏提示更稳妥。
     * 版本解析与 AuthManager#detectNewWorldStructure 同源（兼容 26.x 新命名）。
     */
    private static boolean preJoinSupported() {
        try {
            Class.forName("io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent");
        } catch (ClassNotFoundException e) {
            return false;
        }
        // 解析 Bukkit version：如 "1.21.11-R0.1-SNAPSHOT"；26.x 新命名如 "26.1.2-R0.1"（主版本 >= 26 > 1）
        String nums = Bukkit.getBukkitVersion().split("-", 2)[0];
        String[] parts = nums.split("\\.");
        int[] v = new int[3];
        try {
            for (int i = 0; i < parts.length && i < v.length; i++) {
                v[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return false;
        }
        // 阈值 1.21.11：主版本 >1（含 26.x）满足；主版本==1 需次版本>21，或 ==21 且 patch>=11
        return v[0] > 1 || (v[0] == 1 && (v[1] > 21 || (v[1] == 21 && v[2] >= 11)));
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
            playerListener.beginAuthFlow(player);
        }
    }

    /** 将旧版 '&' 颜色代码转换为 Adventure Component */
    public static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
