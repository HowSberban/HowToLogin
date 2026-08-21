package org.howtologin.plugin.dialog;

import com.destroystokyo.paper.ClientOption;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.auth.PasswordValidator;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pre-join Dialog 登录（Paper 配置阶段事件，1.21.6+）：
 * 玩家进入世界前弹窗完成登录/注册，认证成功才放行，登录前不接收任何世界信息（坐标保护天然不需要）。
 * 流程：配置阶段事件 → 弹窗阻塞等待提交 → 认证成功记录结果 → 进入世界由 onJoin 消费结果收尾。
 * 回退聊天栏提示：Dialog 未启用 / 客户端&lt;1.21.6 / 正版或 IP 免密 / 已登录；超时按 kick-on-timeout 踢出或放行收尾。
 */
@SuppressWarnings("UnstableApiUsage")
public final class PreJoinAuthListener implements Listener {

    /** 配置阶段认证结果（玩家进入世界时消费） */
    public enum AuthOutcome {
        /** 密码/双因素验证通过 */
        LOGIN,
        /** 注册完成 */
        REGISTER
    }

    private final HTLogin plugin;
    private final AuthManager authManager;
    private final DialogManager dialogManager;
    // 配置阶段认证结果：UUID → 结果（进入世界时移除）
    private final Map<UUID, AuthOutcome> outcomes = new ConcurrentHashMap<>();
    // 活跃的配置阶段会话（Dialog 提交回调查找）
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** 配置阶段会话：连接 + 完成信号（配置线程阻塞等待 Dialog 提交结果） */
    private static final class Session {
        final PlayerConfigurationConnection connection;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean success;
        volatile boolean kicked;
        // 发送窗口失败：中止 pre-join 直接放行（回退聊天栏提示）
        volatile boolean fallback;
        // 注册流程完成（写在 countDown 前，由闭锁建立 happens-before）
        boolean registered;

        Session(PlayerConfigurationConnection connection) {
            this.connection = connection;
        }
    }

    public PreJoinAuthListener(HTLogin plugin, AuthManager authManager,
                               DialogManager dialogManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.dialogManager = dialogManager;
    }

    /** 玩家进入世界时消费配置阶段认证结果（无结果返回 null，走正常登录流程） */
    public AuthOutcome consume(Player player) {
        return outcomes.remove(player.getUniqueId());
    }

    /** 配置阶段是否已完成认证（供出生点决策查询，不消费结果） */
    public boolean hasCompleted(UUID uuid) {
        return outcomes.containsKey(uuid);
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection conn = event.getConnection();
        // 清除上次连接可能残留的认证结果，避免本次连接误消费
        var profileId = conn.getProfile().getId();
        if (profileId != null) {
            outcomes.remove(profileId);
        }
        if (!plugin.getConfigManager().loginDialogEnabled()) return;
        UUID uuid = conn.getProfile().getId();
        // 已登录（reconfigure 场景）直接放行
        if (uuid == null || authManager.isLoggedIn(uuid)) return;
        // 客户端是否支持配置阶段 Dialog（<1.21.6 收到 Show Dialog 包会断连，回退聊天栏提示）
        if (!supportsDialogs(uuid)) return;
        // 正版（非回退）/ IP 免密：放行，由 onJoin 现有逻辑处理
        if (skipAutoLogin(uuid, conn)) return;

        Session session = new Session(conn);
        sessions.put(uuid, session);
        // 提为方法作用域：超时断连时仍需玩家语言
        String locale = resolveLocale(conn);
        try {
            if (authManager.hasAccount(uuid)) {
                showLogin(session, uuid, locale, null);
            } else {
                showRegister(session, uuid, locale, null);
            }
            // 阻塞配置线程直到认证完成/超时（timeout=0 无限等待；虚拟线程阻塞开销极小）
            int timeout = plugin.getConfigManager().loginTimeout();
            if (timeout <= 0) {
                session.latch.await();
            } else {
                // 认证结果由上方 session 标志判断，await 返回值无需使用
                //noinspection ResultOfMethodCallIgnored
                session.latch.await(timeout, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sessions.remove(uuid);
        }
        if (session.kicked || session.fallback) return;
        if (session.success) {
            outcomes.put(uuid, session.registered ? AuthOutcome.REGISTER : AuthOutcome.LOGIN);
            return;
        }
        // 超时未完成：kick-on-timeout 开启时踢出，否则由 onJoin 的 beginAuthFlow 接管
        if (plugin.getConfigManager().kickOnTimeout()) {
            conn.disconnect(HTLogin.legacy(I18n.getForLocale("listener.login_timeout", locale)));
        }
    }

    /** 正版（非回退）或 IP 免密：配置阶段不弹窗 */
    private boolean skipAutoLogin(UUID uuid, PlayerConfigurationConnection conn) {
        if (authManager.isPremium(uuid) && !authManager.isPremiumFallback(uuid)) return true;
        String ip = clientIp(conn);
        return ip != null && authManager.checkIpAutoLogin(uuid, ip);
    }

    /**
     * 客户端是否支持配置阶段 Dialog（协议 >= 1.21.6 / 771）。
     * 无 ViaVersion 时视为支持：本监听器仅在服务端 >= 1.21.11 时注册（见 HTLogin#preJoinSupported），
     * 能直接连上该版本服务端的客户端协议必然匹配（>= 1.21.11 > 1.21.6），一定支持 Dialog。
     * 仅在有 ViaVersion（允许旧客户端连新服务端）时，才需用 ViaAPI 按 UUID 查询客户端真实协议版本。
     */
    private static boolean supportsDialogs(UUID playerId) {
        try {
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) {
                return true;
            }
            Class<?> viaApiClass = Class.forName("com.viaversion.viaversion.api.ViaAPI");
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            int version = (int) viaApiClass.getMethod("getPlayerVersion", UUID.class).invoke(api, playerId);
            return version >= 771;
        } catch (Exception e) {
            return true; // 查询失败保守放行（不阻塞正常玩家），回退到聊天栏在 onJoin 处理
        }
    }

    /** 客户端语言（读取失败回退默认） */
    private static String resolveLocale(PlayerConfigurationConnection conn) {
        try {
            // getClientOption 保证非空，仅需判断是否空白
            String locale = conn.getClientOption(ClientOption.LOCALE);
            return locale.isBlank() ? null : locale;
        } catch (Exception e) {
            return null;
        }
    }

    private static String clientIp(PlayerConfigurationConnection conn) {
        var address = conn.getClientAddress().getAddress();
        return address != null ? address.getHostAddress() : null;
    }

    // ===== 窗口展示与提交回调（仅做线程安全操作：重弹/断连/闭锁） =====

    private void showLogin(Session session, UUID uuid, String locale, Component error) {
        showDialog(session, dialogManager.buildLoginDialog(locale, error,
                loginSubmit(session, uuid, locale), cancel(session, uuid, locale)));
    }

    private void showRegister(Session session, UUID uuid, String locale, Component error) {
        showDialog(session, dialogManager.buildRegisterDialog(locale, error,
                registerSubmit(session, uuid, locale), cancel(session, uuid, locale)));
    }

    private void show2fa(Session session, UUID uuid, String locale, Component error) {
        showDialog(session, dialogManager.build2faDialog(locale, error,
                twoFactorSubmit(session, uuid, locale), cancel(session, uuid, locale)));
    }

    /** 取消：主动放弃登录并断连（pre-join 阶段尚未进世界）；标记 kicked 使 onConfigure 不放行，并唤醒配置线程 */
    private DialogActionCallback cancel(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (sessions.get(uuid) != session) return;
            session.kicked = true;
            session.latch.countDown();
            session.connection.disconnect(HTLogin.legacy(I18n.getForLocale("dialog.cancelled", locale)));
        };
    }

    /** 发送窗口；发送失败（意外）中止 pre-join，回退聊天栏提示 */
    private void showDialog(Session session, Dialog dialog) {
        try {
            session.connection.getAudience().showDialog(dialog);
        } catch (Exception e) {
            session.fallback = true;
            session.latch.countDown();
        }
    }

    /** 登录窗口提交：bcrypt 异步校验，失败重弹，需 2FA 切换验证窗口，达阈值断连 */
    private DialogActionCallback loginSubmit(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            // 会话已结束（超时/取消后提交）：忽略过期提交
            if (sessions.get(uuid) != session) return;
            String password = response.getText("password");
            if (password == null || password.isEmpty()) {
                showLogin(session, uuid, locale, DialogManager.text(locale, "dialog.empty_password"));
                return;
            }
            authManager.loginConfigAsync(uuid, password, (result, kickSeconds) -> {
                if (sessions.get(uuid) != session) return;
                switch (result) {
                    case SUCCESS -> {
                        session.success = true;
                        session.latch.countDown();
                    }
                    case NEED_2FA -> show2fa(session, uuid, locale, null);
                    case FAILED -> {
                        if (kickSeconds != null && kickSeconds > 0) {
                            session.kicked = true;
                            session.connection.disconnect(HTLogin.legacy(
                                    I18n.getForLocale("login.kicked", locale, kickSeconds)));
                            session.latch.countDown();
                        } else {
                            showLogin(session, uuid, locale, DialogManager.text(locale, "login.incorrect_password"));
                        }
                    }
                }
            });
        };
    }

    /** 注册窗口提交：校验与 /register 一致，成功仅建号（登录收尾延迟到进入世界时） */
    private DialogActionCallback registerSubmit(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (sessions.get(uuid) != session) return;
            if (authManager.hasAccount(uuid)) {
                showLogin(session, uuid, locale, DialogManager.text(locale, "register.already_registered"));
                return;
            }
            String password = response.getText("password");
            String confirm = response.getText("confirm");
            if (password == null || password.isEmpty()) {
                showRegister(session, uuid, locale, DialogManager.text(locale, "dialog.empty_password"));
                return;
            }
            if (!password.equals(confirm)) {
                showRegister(session, uuid, locale, DialogManager.text(locale, "register.password_mismatch"));
                return;
            }
            String error = PasswordValidator.invalidMessage(plugin, locale, password);
            if (error != null) {
                showRegister(session, uuid, locale, HTLogin.legacy(error));
                return;
            }
            if (authManager.registerConfig(uuid, password, clientIp(session.connection))) {
                session.registered = true;
                session.success = true;
                session.latch.countDown();
            } else {
                showRegister(session, uuid, locale, DialogManager.text(locale, "register.failed"));
            }
        };
    }

    /** 双因素验证窗口提交：通过放行，失败重弹 */
    private DialogActionCallback twoFactorSubmit(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (sessions.get(uuid) != session) return;
            if (!authManager.isPending2fa(uuid)) {
                // 密码验证状态已失效（插件重载等），回到登录窗口重新开始
                showLogin(session, uuid, locale, null);
                return;
            }
            String code = response.getText("code");
            if (code == null || code.isEmpty()) {
                show2fa(session, uuid, locale, DialogManager.text(locale, "dialog.empty_code"));
                return;
            }
            if (authManager.verify2faConfig(uuid, code)) {
                session.success = true;
                session.latch.countDown();
            } else {
                show2fa(session, uuid, locale, DialogManager.text(locale, "2fa.confirm_incorrect"));
            }
        };
    }
}
