package org.howtologin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 双因素认证命令：
 * /2fa setup              - 生成密钥，开始绑定
 * /2fa confirm <验证码>   - 验证码确认，完成绑定
 * /2fa disable <验证码>   - 验证码确认，关闭双因素
 * /2fa <验证码>           - 登录时的双因素验证
 */
public final class TwoFactorCommand implements BasicCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public TwoFactorCommand(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("command.player_only")));
            return;
        }

        // 全局开关：关闭后禁止所有 2FA 子命令（已绑定玩家登录时自动跳过验证）
        if (!plugin.getConfigManager().twoFactorEnabled()) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.feature_disabled", player)));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.usage", player)));
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "setup" -> handleSetup(player);
            case "confirm" -> handleConfirm(player, args);
            case "disable" -> handleDisable(player, args);
            default -> handleVerify(player, args[0]);
        }
    }

    /** /2fa setup：生成临时密钥供玩家添加到认证器应用 */
    private void handleSetup(Player player) {
        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.must_login", player)));
            return;
        }
        if (authManager.has2fa(player.getUniqueId())) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.already_enabled", player)));
            return;
        }
        String secret = authManager.setup2fa(player);
        // otpauth URI：认证器扫码/点击添加时自动填充发行方与账号，免手动输入
        String issuer = plugin.getConfigManager().twoFactorIssuer();
        String uri = "otpauth://totp/" + java.net.URLEncoder.encode(issuer + ":" + player.getName(), java.nio.charset.StandardCharsets.UTF_8)
                + "?secret=" + secret
                + "&issuer=" + java.net.URLEncoder.encode(issuer, java.nio.charset.StandardCharsets.UTF_8);
        player.sendMessage(HTLogin.legacy(I18n.get("2fa.setup_secret", player, secret, uri)));
    }

    /** /2fa confirm <验证码>：验证码通过后完成绑定 */
    private void handleConfirm(Player player, String[] args) {
        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.must_login", player)));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.usage", player)));
            return;
        }
        if (authManager.confirm2fa(player, args[1])) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_success", player)));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_incorrect", player)));
        }
    }

    /** /2fa disable <验证码>：验证当前验证码后关闭双因素认证 */
    private void handleDisable(Player player, String[] args) {
        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.must_login", player)));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.usage", player)));
            return;
        }
        if (!authManager.has2fa(player.getUniqueId())) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.not_enabled", player)));
            return;
        }
        if (authManager.disable2fa(player, args[1])) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.disabled", player)));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_incorrect", player)));
        }
    }

    /** /2fa <验证码>：登录时的双因素验证（密码已通过，等待 TOTP） */
    private void handleVerify(Player player, String code) {
        if (!authManager.isPending2fa(player.getUniqueId())) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.usage", player)));
            return;
        }
        if (authManager.verify2fa(player, code)) {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.verify_success", player)));
            // 登录完成后传送回上次退出位置（与密码登录成功一致）
            authManager.returnToLogoutLocation(player);
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("2fa.confirm_incorrect", player)));
        }
    }
}
