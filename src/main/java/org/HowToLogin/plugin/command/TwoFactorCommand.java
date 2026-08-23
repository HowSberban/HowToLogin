package org.howtologin.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.dialog.DialogManager;
import org.bukkit.entity.Player;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * 双因素认证命令，与 htlogin 一样使用 brigadier 原生注册，
 * 子命令作为 literal 节点，客户端输入空格后能自动提示子命令列表。
 * /2fa setup              - 生成密钥，开始绑定
 * /2fa confirm <验证码>   - 验证码确认，完成绑定
 * /2fa disable <验证码>   - 验证码确认，关闭双因素
 * /2fa <验证码>           - 登录时的双因素验证
 */
// Dialog API 标记为 @ApiStatus.Experimental，实际已稳定可用（与 DialogManager 同类情况）
@SuppressWarnings({"SameReturnValue", "UnstableApiUsage"})
public final class TwoFactorCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;
    // 游戏内绑定对话框构建器：服务端不支持 Dialog 时为 null，回退文本展示
    private final DialogManager dialogManager;
    // 取消回调：仅关闭窗口（afterAction 为 NONE，手动关闭），无副作用，可复用
    private static final DialogActionCallback CANCEL = (returnValue, audience) -> audience.closeDialog();

    public TwoFactorCommand(HTLogin plugin, AuthManager authManager, DialogManager dialogManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.dialogManager = dialogManager;
    }

    /** 构建命令树节点（由 HTLogin 注册时调用） */
    public LiteralCommandNode<CommandSourceStack> buildNode() {
        return literal("2fa")
                .requires(stack -> stack.getSender() instanceof Player)
                // /2fa — 显示用法
                .executes(this::handleUsage)
                // /2fa setup
                .then(literal("setup")
                        .executes(this::handleSetup))
                // /2fa confirm <验证码>
                .then(literal("confirm")
                        .then(argument("code", StringArgumentType.word())
                                .executes(this::handleConfirm)))
                // /2fa disable <验证码>
                .then(literal("disable")
                        .then(argument("code", StringArgumentType.word())
                                .executes(this::handleDisable)))
                // /2fa <验证码>（登录时验证；词法上不匹配 setup/confirm/disable）
                .then(argument("code", StringArgumentType.word())
                        .executes(this::handleVerify))
                .build();
    }

    private int handleUsage(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        player.sendMessage(msg(player, "2fa.usage"));
        return Command.SINGLE_SUCCESS;
    }

    /** 按玩家语言解析消息组件 */
    private static Component msg(Player player, String key) {
        return HTLogin.legacy(I18n.get(key, player));
    }

    /** 全局开关 + 登录状态前置校验：不满足时发提示并返回 true（中止本次命令） */
    private boolean blocked(Player player) {
        if (!plugin.getConfigManager().twoFactorEnabled()) {
            player.sendMessage(msg(player, "2fa.feature_disabled"));
            return true;
        }
        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(msg(player, "2fa.must_login"));
            return true;
        }
        return false;
    }

    /** /2fa setup：生成临时密钥。服务端支持 Dialog 时弹游戏内绑定窗口，否则回退文本展示 */
    private int handleSetup(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (blocked(player)) return Command.SINGLE_SUCCESS;
        if (authManager.has2fa(player.getUniqueId())) {
            player.sendMessage(msg(player, "2fa.already_enabled"));
            return Command.SINGLE_SUCCESS;
        }
        String secret = authManager.setup2fa(player);
        if (dialogManager != null) {
            // 使用对话框时不再弹聊天栏消息，避免重复刷屏
            showSetupDialog(player, secret, qrUrl(player, secret), null);
        } else {
            // 服务端不支持 Dialog（<1.21.11/未启用）：回退聊天栏展示密钥与完成指引
            player.sendMessage(buildSetupMessage(player, secret, qrUrl(player, secret)));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 合成 /2fa setup 的聊天消息：标题 + 提示 + 可复制密钥 + [扫码链接] + [过期提醒] + 完成指引 */
    private Component buildSetupMessage(Player player, String secret, String qrUrl) {
        Component message = Component.empty()
                .append(msg(player, "2fa.setup_title")).appendNewline()
                .append(msg(player, "2fa.setup_hint")).appendNewline()
                .append(clickToCopy(secret, player));
        // 二维码服务模板可用时附上可点击的扫码链接
        if (qrUrl != null) {
            message = message.appendNewline().append(clickToOpen(qrUrl, player));
        }
        // 有限时配置时追加红色过期提醒
        Component expire = expireReminder(player);
        if (expire != null) {
            message = message.appendNewline().append(expire);
        }
        return message.appendNewline().append(msg(player, "2fa.setup_confirm"));
    }

    /** 弹游戏内 2FA 绑定对话框，error 为上次校验失败的提示（首次为 null）；取消关闭窗口，复制不关闭 */
    private void showSetupDialog(Player player, String secret, String qrUrl, Component error) {
        player.showDialog(dialogManager.buildSetupDialog(player.locale().toString(), secret, qrUrl, error,
                setupOnConfirm(player, secret), CANCEL));
    }

    private DialogActionCallback setupOnConfirm(Player player, String secret) {
        return (returnValue, audience) -> {
            String code = returnValue.getText("code");
            if (code == null || code.isEmpty()) {
                showSetupDialog(player, secret, qrUrl(player, secret), msg(player, "dialog.empty_code"));
                return;
            }
            if (authManager.confirm2fa(player, code)) {
                player.sendMessage(msg(player, "2fa.confirm_success"));
                // 绑定成功即关闭窗口
                audience.closeDialog();
            } else {
                showSetupDialog(player, secret, qrUrl(player, secret), msg(player, "2fa.confirm_incorrect"));
            }
        };
    }

    /** 生成二维码服务 URL 供扫码按钮打开；模板缺失 {data} 占位符或留空时返回 null（对话框省略扫码按钮） */
    private String qrUrl(Player player, String secret) {
        String template = plugin.getConfigManager().twoFactorQrUrl();
        if (template == null || template.isEmpty() || !template.contains("{data}")) return null;
        // otpauth 资料：账号 + 密钥，交给二维码服务生成图片
        String data = "otpauth://totp/" + enc(player.getName()) + "?secret=" + enc(secret);
        return template.replace("{data}", enc(data));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 红色过期提醒组件：配置为 0（永不过期）时返回 null */
    private Component expireReminder(Player player) {
        int seconds = plugin.getConfigManager().twoFactorTempSecretExpireSeconds();
        if (seconds <= 0) return null;
        return HTLogin.legacy(I18n.get("2fa.setup_expire", player, seconds));
    }

    /** 可点击复制组件：金色突出提示可点击，内容即展示文本，点击复制到剪贴板 */
    private static Component clickToCopy(String content, Player player) {
        return Component.text(content)
                .color(DialogManager.HIGHLIGHT_COLOR)
                .hoverEvent(HoverEvent.showText(msg(player, "2fa.click_to_copy")))
                .clickEvent(ClickEvent.copyToClipboard(content));
    }

    /** 可点击打开扫码网页的组件：金色文本，点击在浏览器打开二维码页 */
    private static Component clickToOpen(String url, Player player) {
        return msg(player, "2fa.setup_scan")
                .color(DialogManager.HIGHLIGHT_COLOR)
                .hoverEvent(HoverEvent.showText(msg(player, "2fa.click_to_open")))
                .clickEvent(ClickEvent.openUrl(url));
    }

    /** /2fa confirm <验证码>：验证码通过后完成绑定 */
    private int handleConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (blocked(player)) return Command.SINGLE_SUCCESS;
        String code = StringArgumentType.getString(ctx, "code");
        if (authManager.confirm2fa(player, code)) {
            player.sendMessage(msg(player, "2fa.confirm_success"));
        } else {
            player.sendMessage(msg(player, "2fa.confirm_incorrect"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /2fa disable <验证码>：验证当前验证码后关闭双因素认证 */
    private int handleDisable(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (blocked(player)) return Command.SINGLE_SUCCESS;
        if (!authManager.has2fa(player.getUniqueId())) {
            player.sendMessage(msg(player, "2fa.not_enabled"));
            return Command.SINGLE_SUCCESS;
        }
        String code = StringArgumentType.getString(ctx, "code");
        if (authManager.disable2fa(player, code)) {
            player.sendMessage(msg(player, "2fa.disabled"));
        } else {
            player.sendMessage(msg(player, "2fa.confirm_incorrect"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /2fa <验证码>：登录时的双因素验证（密码已通过，等待 TOTP） */
    private int handleVerify(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (!plugin.getConfigManager().twoFactorEnabled()) {
            player.sendMessage(msg(player, "2fa.feature_disabled"));
            return Command.SINGLE_SUCCESS;
        }
        String code = StringArgumentType.getString(ctx, "code");
        if (!authManager.isPending2fa(player.getUniqueId())) {
            player.sendMessage(msg(player, "2fa.usage"));
            return Command.SINGLE_SUCCESS;
        }
        if (authManager.verify2fa(player, code)) {
            player.sendMessage(msg(player, "2fa.verify_success"));
            // 登录完成后传送回上次退出位置（与密码登录成功一致）
            authManager.returnToLogoutLocation(player);
        } else {
            player.sendMessage(msg(player, "2fa.confirm_incorrect"));
        }
        return Command.SINGLE_SUCCESS;
    }
}