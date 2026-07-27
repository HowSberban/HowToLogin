package org.HowToLogin.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.HowToLogin.plugin.HTLogin;
import org.HowToLogin.plugin.I18n;
import org.HowToLogin.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ChangePasswordCommand implements BasicCommand {

    private final HTLogin plugin;
    private final AuthManager authManager;

    public ChangePasswordCommand(HTLogin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(HTLogin.legacy(I18n.get("command.player_only")));
            return;
        }

        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.must_login", player)));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.usage", player)));
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        int minLen = plugin.getConfigManager().minPasswordLength();
        int maxLen = plugin.getConfigManager().maxPasswordLength();

        if (newPassword.length() < minLen || newPassword.length() > maxLen) {
            player.sendMessage(HTLogin.legacy(I18n.get("command.password_length", player, minLen, maxLen)));
            return;
        }

        if (authManager.changePassword(player, oldPassword, newPassword)) {
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.success", player)));
        } else {
            player.sendMessage(HTLogin.legacy(I18n.get("changepw.incorrect_old", player)));
        }
    }
}
