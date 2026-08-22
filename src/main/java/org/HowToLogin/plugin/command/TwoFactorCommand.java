package org.howtologin.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.dialog.DialogManager;
import org.bukkit.entity.Player;

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
@SuppressWarnings("SameReturnValue")
public final class TwoFactorCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;
    // 游戏内绑定对话框构建器：服务端不支持 Dialog 时为 null，回退文本展示
    private final DialogManager dialogManager;

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
        player.sendMessage(HTLogin.legacy(I18n.get("2fa.usage", player)));
        return Command.SINGLE_SUCCESS;
    }

    /** 全局开关：关闭后禁止所有 2FA 子命令（已绑定玩家登录时自动跳过验证） */
    private boolean featureDisabled(Player player) {
        if (!plugin.getConfigManager().twoFactorEnabled()) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.feature_disabled", player)));
            return true;
        }
        return false;
    }

    /** /2fa setup：生成临时密钥。服务端支持 Dialog 时弹游戏内绑定窗口，否则回退文本展示 */
    private int handleSetup(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (featureDisabled(player)) return Command.SINGLE_SUCCESS;
        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.must_login", player)));
            return Command.SINGLE_SUCCESS;
        }
        if (authManager.has2fa(player.getUniqueId())) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.already_enabled", player)));
            return Command.SINGLE_SUCCESS;
        }
        String secret = authManager.setup2fa(player);
        if (dialogManager != null) {
            showSetupDialog(player, secret, null);
            return Command.SINGLE_SUCCESS;
        }
        // 回退（不支持 Dialog）：合成可点击组件，密钥与 otpauth URI 均为点击即复制
        player.sendMessage(Component.empty()
                .append(HTLogin.legacy(I18n.get("2fa.setup_title", player))).appendNewline()
                .append(HTLogin.legacy(I18n.get("2fa.setup_hint", player))).appendNewline()
                .append(clickToCopy(secret, player)).appendNewline()
                .append(HTLogin.legacy(I18n.get("2fa.setup_link_hint", player))).appendNewline()
                .append(clickToCopy(otpauthUri(secret, player), player)).appendNewline()
                .append(HTLogin.legacy(I18n.get("2fa.setup_confirm", player))));
        return Command.SINGLE_SUCCESS;
    }

    /** 弹游戏内 2FA 绑定对话框；确认后校验验证码完成绑定，失败重弹。取消/ESC 仅关闭窗口 */
    private void showSetupDialog(Player player, String secret, Component error) {
        // 取消：不执行任何操作，窗口由 afterAction(CLOSE) 自动关闭
        DialogActionCallback onCancel = (returnValue, audience) -> {};
        player.showDialog(dialogManager.buildSetupDialog(player.getLocale(), secret, error,
                setupOnConfirm(player, secret), onCancel));
    }

    private void reOpenSetup(Player player, String secret, Component error) {
        player.showDialog(dialogManager.buildSetupDialog(player.getLocale(), secret, error,
                setupOnConfirm(player, secret), (returnValue, audience) -> {}));
    }

    private DialogActionCallback setupOnConfirm(Player player, String secret) {
        return (returnValue, audience) -> {
            String code = returnValue.getText("code");
            if (code == null || code.isEmpty()) {
                reOpenSetup(player, secret, errorOf(player, "dialog.empty_code"));
                return;
            }
            if (authManager.confirm2fa(player, code)) {
                player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_success", player)));
            } else {
                reOpenSetup(player, secret, errorOf(player, "2fa.confirm_incorrect"));
            }
        };
    }

    /** 灰色错误提示组件（按玩家语言解析） */
    private static Component errorOf(Player player, String key) {
        return HTLogin.legacy(I18n.get(key, player));
    }

    /** otpauth URI：认证器扫码/点击添加时自动填充发行方与账号，免手动输入 */
    private String otpauthUri(String secret, Player player) {
        String issuer = plugin.getConfigManager().twoFactorIssuer();
        return "otpauth://totp/" + java.net.URLEncoder.encode(issuer + ":" + player.getName(), java.nio.charset.StandardCharsets.UTF_8)
                + "?secret=" + secret
                + "&issuer=" + java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 可点击复制组件：内容即展示文本，悬停提示"点击复制"，点击复制到剪贴板 */
    private static Component clickToCopy(String content, Player player) {
        return Component.text(content)
                .hoverEvent(HoverEvent.showText(HTLogin.legacy(I18n.get("2fa.click_to_copy", player))))
                .clickEvent(ClickEvent.copyToClipboard(content));
    }

    /** /2fa confirm <验证码>：验证码通过后完成绑定 */
    private int handleConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (featureDisabled(player)) return Command.SINGLE_SUCCESS;
        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.must_login", player)));
            return Command.SINGLE_SUCCESS;
        }
        String code = StringArgumentType.getString(ctx, "code");
        if (authManager.confirm2fa(player, code)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_success", player)));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_incorrect", player)));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /2fa disable <验证码>：验证当前验证码后关闭双因素认证 */
    private int handleDisable(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (featureDisabled(player)) return Command.SINGLE_SUCCESS;
        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.must_login", player)));
            return Command.SINGLE_SUCCESS;
        }
        if (!authManager.has2fa(player.getUniqueId())) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.not_enabled", player)));
            return Command.SINGLE_SUCCESS;
        }
        String code = StringArgumentType.getString(ctx, "code");
        if (authManager.disable2fa(player, code)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.disabled", player)));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_incorrect", player)));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /2fa <验证码>：登录时的双因素验证（密码已通过，等待 TOTP） */
    private int handleVerify(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (featureDisabled(player)) return Command.SINGLE_SUCCESS;
        String code = StringArgumentType.getString(ctx, "code");
        if (!authManager.isPending2fa(player.getUniqueId())) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.usage", player)));
            return Command.SINGLE_SUCCESS;
        }
        if (authManager.verify2fa(player, code)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.verify_success", player)));
            // 登录完成后传送回上次退出位置（与密码登录成功一致）
            authManager.returnToLogoutLocation(player);
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_incorrect", player)));
        }
        return Command.SINGLE_SUCCESS;
    }
}