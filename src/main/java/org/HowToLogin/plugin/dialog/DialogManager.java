package org.howtologin.plugin.dialog;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.auth.PasswordValidator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog 登录界面（Paper 1.21.6+）：图形化窗口登录/注册/双因素验证，密码错误等提示直接显示在窗口内。
 * 两种弹出时机（login.dialog.mode）：
 * - pre-join：配置阶段弹出（PreJoinAuthListener），登录完成才进入世界，登录前不接收任何世界信息
 * - post-join：进入世界后弹出（本类 tryShowAuth），窗口压暗背景但不完全遮挡视野
 * 窗口不可 ESC 关闭；提交后自动关闭，失败重弹（带错误行），成功由 AuthManager.onLoginSuccess 统一收尾。
 * <p>
 * Dialog API 引用全部隔离在本包：服务端不支持时（isSupported 为 false）本类不会被实例化，
 * 调用方通过 plugin.getDialogManager() 判空回退聊天栏提示；
 * 客户端版本低于 1.21.6（无法解析 Dialog 数据包会被断连）时同样回退聊天栏。
 */
// Dialog API 标记为 @ApiStatus.Experimental，实际已稳定可用（与 AsyncPlayerSpawnLocationEvent 同类情况）
@SuppressWarnings("UnstableApiUsage")
public final class DialogManager {

    private final HTLogin plugin;
    private final AuthManager authManager;
    // 提交回调选项：不限点击次数（失败重弹后可继续提交），1 小时有效期防止回调悬挂
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofHours(1))
            .build();

    /** 检测服务端是否支持 Dialog API（1.21.6+），不支持时不实例化本类 */
    public static boolean isSupported() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public DialogManager(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    // ===== Post-join（进入世界后弹窗；旧客户端回退聊天栏提示） =====

    /**
     * 尝试以 Dialog 窗口挂起玩家：有账号弹登录窗，无账号弹注册窗。
     * @return false 表示不可用（未启用/客户端版本过低），调用方回退聊天栏提示
     */
    public boolean tryShowAuth(Player player, boolean hasAccount) {
        if (!plugin.getConfigManager().loginDialogEnabled()) return false;
        if (!clientSupported(player)) return false;
        if (hasAccount) {
            showLogin(player, null);
        } else {
            showRegister(player, null);
        }
        return true;
    }

    /** 关闭玩家当前打开的 Dialog（未打开时无效果；登录成功路径统一调用，覆盖聊天命令登录等旁路场景） */
    public void close(Player player) {
        player.closeDialog();
    }

    /** 客户端是否支持 Dialog（1.21.6 引入）：版本未知时保守视为不支持，回退聊天栏避免旧客户端断连 */
    private static boolean clientSupported(Player player) {
        ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        return version.isNewerThanOrEquals(ClientVersion.V_1_21_6);
    }

    private void showLogin(Player player, Component error) {
        player.showDialog(buildLoginDialog(player.locale().toString(), error,
                // 窗口仅发送给该玩家，直接闭包捕获目标玩家；回调切回玩家区域线程（Folia 线程安全）
                (response, audience) -> player.getScheduler().run(plugin,
                        task -> handleLoginSubmit(player, response), null)));
    }

    private void showRegister(Player player, Component error) {
        player.showDialog(buildRegisterDialog(player.locale().toString(), error,
                (response, audience) -> player.getScheduler().run(plugin,
                        task -> handleRegisterSubmit(player, response), null)));
    }

    private void show2fa(Player player, Component error) {
        player.showDialog(build2faDialog(player.locale().toString(), error,
                (response, audience) -> player.getScheduler().run(plugin,
                        task -> handle2faSubmit(player, response), null)));
    }

    // ===== Post-join 提交处理（已回到玩家区域线程） =====

    /** 登录窗口提交：复用 loginAsync（异步 bcrypt 校验），失败重弹窗口显示错误，需 2FA 时切换验证窗口 */
    private void handleLoginSubmit(Player player, DialogResponseView response) {
        String password = response.getText("password");
        if (password == null || password.isEmpty()) {
            showLogin(player, text(player, "dialog.empty_password"));
            return;
        }
        authManager.loginAsync(player, password, (result, kickSeconds) -> {
            switch (result) {
                case SUCCESS -> {
                    player.sendMessage(HTLogin.legacy(I18n.get("login.success", player)));
                    authManager.returnToLogoutLocation(player);
                }
                case NEED_2FA -> show2fa(player, null);
                case FAILED -> {
                    if (kickSeconds != null && kickSeconds > 0) {
                        player.kick(HTLogin.legacy(I18n.get("login.kicked", kickSeconds)));
                    } else {
                        showLogin(player, text(player, "login.incorrect_password", player));
                    }
                }
            }
        });
    }

    /** 注册窗口提交：校验顺序与 /register 一致，成功消息与传送逻辑复用现有方法 */
    private void handleRegisterSubmit(Player player, DialogResponseView response) {
        if (authManager.hasAccount(player)) {
            showLogin(player, text(player, "register.already_registered", player));
            return;
        }
        String password = response.getText("password");
        String confirm = response.getText("confirm");
        if (password == null || password.isEmpty()) {
            showRegister(player, text(player, "dialog.empty_password"));
            return;
        }
        if (!password.equals(confirm)) {
            showRegister(player, text(player, "register.password_mismatch", player));
            return;
        }
        String error = PasswordValidator.invalidMessage(plugin, player, password);
        if (error != null) {
            showRegister(player, HTLogin.legacy(error));
            return;
        }
        if (authManager.register(player, password)) {
            player.sendMessage(HTLogin.legacy(I18n.get("register.success", player)));
            authManager.returnToLogoutLocation(player);
        } else {
            showRegister(player, text(player, "register.failed", player));
        }
    }

    /** 双因素验证窗口提交：验证通过完成登录，失败重弹窗口 */
    private void handle2faSubmit(Player player, DialogResponseView response) {
        String code = response.getText("code");
        if (code == null || code.isEmpty()) {
            show2fa(player, text(player, "dialog.empty_code"));
            return;
        }
        if (!authManager.isPending2fa(player.getUniqueId())) {
            // 密码验证状态已失效（插件重载等），回到登录窗口重新开始
            showLogin(player, null);
            return;
        }
        if (authManager.verify2fa(player, code)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.verify_success", player)));
            authManager.returnToLogoutLocation(player);
        } else {
            show2fa(player, text(player, "2fa.confirm_incorrect", player));
        }
    }

    // ===== 窗口构建（post-join 与 pre-join 共用；locale 决定文案语言，错误行显示在正文末尾） =====

    /** 登录窗口（密码输入） */
    Dialog buildLoginDialog(String locale, Component error, DialogActionCallback onSubmit) {
        DialogBase base = base(locale, "dialog.login.title", "dialog.login.body", error)
                .inputs(List.of(DialogInput.text("password", text(locale, "dialog.password_label"))
                        .maxLength(plugin.getConfigManager().maxPasswordLength())
                        .build()))
                .build();
        return submitDialog(base, locale, onSubmit);
    }

    /** 注册窗口（密码 + 确认输入） */
    Dialog buildRegisterDialog(String locale, Component error, DialogActionCallback onSubmit) {
        int maxLength = plugin.getConfigManager().maxPasswordLength();
        DialogBase base = base(locale, "dialog.register.title", "dialog.register.body", error)
                .inputs(List.of(
                        DialogInput.text("password", text(locale, "dialog.password_label"))
                                .maxLength(maxLength)
                                .build(),
                        DialogInput.text("confirm", text(locale, "dialog.confirm_label"))
                                .maxLength(maxLength)
                                .build()))
                .build();
        return submitDialog(base, locale, onSubmit);
    }

    /** 双因素验证窗口（验证码输入） */
    Dialog build2faDialog(String locale, Component error, DialogActionCallback onSubmit) {
        DialogBase base = base(locale, "dialog.2fa.title", "dialog.2fa.body", error)
                .inputs(List.of(DialogInput.text("code", text(locale, "dialog.code_label"))
                        .maxLength(10)
                        .build()))
                .build();
        return submitDialog(base, locale, onSubmit);
    }

    /** 窗口骨架：标题 + 正文 + 可选错误行，不可 ESC 关闭，提交后自动关闭（失败由提交处理重弹） */
    private DialogBase.Builder base(String locale, String titleKey, String bodyKey, Component error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text(locale, bodyKey)));
        if (error != null) {
            body.add(DialogBody.plainMessage(error));
        }
        return DialogBase.builder(text(locale, titleKey))
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body);
    }

    /** 组装提交按钮并创建 Dialog */
    private Dialog submitDialog(DialogBase base, String locale, DialogActionCallback onSubmit) {
        ActionButton submit = ActionButton.builder(text(locale, "dialog.submit"))
                .action(DialogAction.customClick(onSubmit, CALLBACK_OPTIONS))
                .build();
        return Dialog.create(factory -> factory.empty().base(base).type(DialogType.notice(submit)));
    }

    /** 语言消息转 Adventure Component（Dialog 需要 Component 而非字符串） */
    private static Component text(Player player, String key, Object... args) {
        return HTLogin.legacy(I18n.get(key, player, args));
    }

    /** 按指定语言转换消息（无 Player 对象的场景，如配置阶段 Dialog） */
    static Component text(String locale, String key, Object... args) {
        return HTLogin.legacy(I18n.getForLocale(key, locale, args));
    }
}
