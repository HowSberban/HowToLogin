package org.howtologin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;
import org.howtologin.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LoginCommand implements BasicCommand {

    private final AuthManager authManager;

    public LoginCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("command.player_only")));
            return;
        }

        if (authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.already_logged_in", player)));
            return;
        }

        if (!authManager.hasAccount(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.no_account", player)));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.usage", player)));
            return;
        }

        // 踢出期检查：优先于密码验证
        if (authManager.isKicked(player)) {
            long remaining = authManager.getKickRemaining(player);
            player.kick(HTLogin.legacy(I18n.get("login.kicked", player, remaining)));
            return;
        }

        if (authManager.login(player, args[0])) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.success", player)));
            // 登录成功后传送回上次退出位置（启用坐标保护时生效）
            authManager.returnToLogoutLocation(player);
        } else {
            if (authManager.isKicked(player)) {
                // 这次失败达到上限，触发踢出
                long remaining = authManager.getKickRemaining(player);
                player.kick(HTLogin.legacy(I18n.get("login.kicked", player, remaining)));
            } else {
                player.sendMessage(HTLogin.legacy(I18n.get("login.incorrect_password", player)));
            }
        }
    }
}
