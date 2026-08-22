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
    // 取消回调：仅关闭窗口（afterAction(CLOSE)），无副作用，可复用
    private static final DialogActionCallback NO_OP_CANCEL = (returnValue, audience) -> {};

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
            showSetupDialog(player, secret, null);
            return Command.SINGLE_SUCCESS;
        }
        // 回退（不支持 Dialog）：合成可点击组件，密钥与 otpauth URI 均为点击即复制
        player.sendMessage(Component.empty()
                .append(msg(player, "2fa.setup_title")).appendNewline()
                .append(msg(player, "2fa.setup_hint")).appendNewline()
                .append(clickToCopy(secret, player)).appendNewline()
                .append(msg(player, "2fa.setup_link_hint")).appendNewline()
                .append(clickToCopy(otpauthUri(secret, player), player)).appendNewline()
                .append(msg(player, "2fa.setup_confirm")));
        return Command.SINGLE_SUCCESS;
    }

    /** 弹游戏内 2FA 绑定对话框，error 为上次校验失败的提示（首次为 null）；取消/ESC 仅关闭窗口 */
    private void showSetupDialog(Player player, String secret, Component error) {
        player.showDialog(dialogManager.buildSetupDialog(player.locale().toString(), secret, error,
                setupOnConfirm(player, secret), NO_OP_CANCEL));
    }

    private DialogActionCallback setupOnConfirm(Player player, String secret) {
        return (returnValue, audience) -> {
            String code = returnValue.getText("code");
            if (code == null || code.isEmpty()) {
                showSetupDialog(player, secret, msg(player, "dialog.empty_code"));
                return;
            }
            if (authManager.confirm2fa(player, code)) {
                player.sendMessage(msg(player, "2fa.confirm_success"));
            } else {
                showSetupDialog(player, secret, msg(player, "2fa.confirm_incorrect"));
            }
        };
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
                .hoverEvent(HoverEvent.showText(msg(player, "2fa.click_to_copy")))
                .clickEvent(ClickEvent.copyToClipboard(content));
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