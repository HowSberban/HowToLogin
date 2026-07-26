package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class LoginCommand implements BasicCommand {

    private final AuthManager authManager;

    public LoginCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("command.player_only")));
            return;
        }

        if (authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.already_logged_in")));
            return;
        }

        if (!authManager.hasAccount(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.no_account")));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.usage")));
            return;
        }

        // 锁定检查：优先于密码验证
        if (authManager.isLocked(player)) {
            long remaining = authManager.getLockRemaining(player);
            player.sendMessage(HTLogin.legacy(I18n.get("login.locked", remaining)));
            return;
        }

        if (authManager.login(player, args[0])) {
            player.sendMessage(HTLogin.legacy(I18n.get("login.success")));
        } else {
            if (authManager.isLocked(player)) {
                // 这次失败触发了锁定
                long remaining = authManager.getLockRemaining(player);
                player.sendMessage(HTLogin.legacy(I18n.get("login.locked", remaining)));
            } else {
                player.sendMessage(HTLogin.legacy(I18n.get("login.incorrect_password")));
            }
        }
    }
}
