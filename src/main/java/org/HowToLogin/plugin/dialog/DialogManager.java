package org.howtologin.plugin.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-join Dialog 登录窗口构建器（Paper 1.21.11+）：供 PreJoinAuthListener 在配置阶段弹出。
 * 窗口含"确认/取消"双按钮、不可 ESC 关闭，错误信息作为正文最后一行显示。
 * Dialog API 引用全部隔离在此包：服务端不支持（isSupported 为 false）时不实例化，
 * PreJoinAuthListener 不会注册，未登录玩家回退聊天栏提示（remind-method）。
 */
// Dialog API 标记为 @ApiStatus.Experimental，实际已稳定可用（与 AsyncPlayerSpawnLocationEvent 同类情况）
@SuppressWarnings("UnstableApiUsage")
public final class DialogManager {

    private final HTLogin plugin;
    // 回调选项：不限点击次数，1 小时有效期防止回调悬挂
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

    public DialogManager(HTLogin plugin) {
        this.plugin = plugin;
    }

    // ===== 窗口构建（错误行显示在正文末尾，取消行为由 onCancel 决定） =====

    /** 登录窗口（密码输入） */
    Dialog buildLoginDialog(String locale, Component error, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        DialogBase base = base(locale, "dialog.login.title", "dialog.login.body", error)
                .inputs(List.of(DialogInput.text("password", text(locale, "dialog.password_label"))
                        .maxLength(plugin.getConfigManager().maxPasswordLength())
                        .build()))
                .build();
        return confirmDialog(base, locale, onConfirm, onCancel);
    }

    /** 注册窗口（密码 + 确认输入） */
    Dialog buildRegisterDialog(String locale, Component error, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
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
        return confirmDialog(base, locale, onConfirm, onCancel);
    }

    /** 双因素验证窗口（验证码输入） */
    Dialog build2faDialog(String locale, Component error, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        DialogBase base = base(locale, "dialog.2fa.title", "dialog.2fa.body", error)
                .inputs(List.of(DialogInput.text("code", text(locale, "dialog.code_label"))
                        .maxLength(10)
                        .build()))
                .build();
        return confirmDialog(base, locale, onConfirm, onCancel);
    }

    /** 窗口骨架：标题 + 正文 + 可选错误行，不可 ESC 关闭，确认后自动关闭（失败由确认处理重弹） */
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

    /** 组装取消/确认双按钮并创建 Dialog（confirmation 类型：两个按钮呈现为左=取消、右=确认） */
    private Dialog confirmDialog(DialogBase base, String locale, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        ActionButton cancel = ActionButton.builder(text(locale, "dialog.cancel"))
                .action(DialogAction.customClick(onCancel, CALLBACK_OPTIONS))
                .build();
        ActionButton confirm = ActionButton.builder(text(locale, "dialog.confirm"))
                .action(DialogAction.customClick(onConfirm, CALLBACK_OPTIONS))
                .build();
        return Dialog.create(factory -> factory.empty().base(base).type(DialogType.confirmation(cancel, confirm)));
    }

    /** 按指定语言获取消息转 Adventure Component（配置阶段无 Player 对象，用客户端 locale） */
    static Component text(String locale, String key, Object... args) {
        return HTLogin.legacy(I18n.getForLocale(key, locale, args));
    }
}